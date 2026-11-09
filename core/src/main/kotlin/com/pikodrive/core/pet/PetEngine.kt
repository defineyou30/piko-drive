package com.pikodrive.core.pet

import com.pikodrive.core.driving.DrivingEvent
import com.pikodrive.core.model.DriveMode
import com.pikodrive.core.model.Expression
import com.pikodrive.core.vehicle.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 주행 이벤트 → 피코 상태·표정·발화. 채널 제한(FRAMEWORK 3-4)을 여기서 강제한다.
 * - BOOTING·REVERSING: 발화·표정 변화 없음 (트립 기록·성장 적립은 계속)
 * - DRIVING: 표정 1회/10초, 같은 카테고리 발화 2분 쿨다운, 전역 발화 예산(30초 간격·시간당 12회),
 *   급가감속 반응 2.5초 지연
 * - PARKED: 제한 없음
 * - S7 신호: BatteryLow → SLEEPY + ENERGY, ChargingStarted(정차 중만) → HAPPY + ENERGY,
 *   PassengerJoined → HAPPY + PASSENGER. 정차 중 배터리 부족이면 기본 표정이 SLEEPY.
 *
 * 스레드 안전하지 않다. [scope]는 단일 스레드 디스패처(예: Main)여야 하며 onXxx도 같은 스레드에서 호출한다.
 */
class PetEngine(
    private val scope: CoroutineScope,
    private val speechSink: SpeechSink,
    private val clock: Clock,
    private val config: PetConfig = PetConfig(),
    private val policy: ReactionPolicy = ReactionPolicy(),
    initialState: PetState = PetState.Initial,
) {
    private val _state = MutableStateFlow(
        Growth.commit(initialState.copy(expression = Expression.CALM, batteryLow = false)),
    )
    val state: StateFlow<PetState> = _state.asStateFlow()

    private val _mode = MutableStateFlow(DriveMode.BOOTING)
    val mode: StateFlow<DriveMode> = _mode.asStateFlow()

    private val _tripReports = MutableSharedFlow<TripReport>(replay = 1, extraBufferCapacity = 8)
    /** 트립 종료 시 1건. replay=1 이라 F6 화면이 늦게 구독해도 마지막 리포트를 받는다. */
    val tripReports: SharedFlow<TripReport> = _tripReports.asSharedFlow()

    private val _speechLog = MutableStateFlow<List<SpeechLogEntry>>(emptyList())
    val speechLog: StateFlow<List<SpeechLogEntry>> = _speechLog.asStateFlow()

    /** 데모 패널(F9)에서 변경. 이후 적립분부터 적용. */
    var growthMultiplier: Int = config.growthMultiplier
        set(value) {
            field = value.coerceAtLeast(1)
        }

    /** false = 권한 거부/음성 off → 발화 없음, 기본 표정 QUIET. */
    var voiceEnabled: Boolean = true
        set(value) {
            field = value
            if (!value) interruptSpeech()
            if (_mode.value != DriveMode.REVERSING) setExpression(baseline(), force = true)
        }

    private val scheduler = SpeechScheduler(speechSink, clock, config.speechRetryDelayMs, config.speechRetryWindowMs)
    private val trip = TripRecorder()
    private val lastSpokenAt = mutableMapOf<SpeechCategory, Long>()
    private var pettingCount = 0
    private var lastExpressionChangeAt: Long? = null
    private var revertJob: Job? = null
    private var speechJob: Job? = null
    private var currentRequest: SpeechRequest? = null
    private var modeGeneration = 0L
    private val pendingReactions = mutableListOf<Job>()
    private val budget = SpeechBudget(config.speechMinGapMs, config.speechMaxPerHour)
    /** 바쁠 때 대기시킨 아이 대사·에이전트 메모 (최대 [RIDE_QUEUE_MAX]줄, 오래되면 버림) */
    private val pendingRide = ArrayDeque<Pair<SpeechRequest, Long>>()
    private val requeued = ArrayDeque<SpeechRequest>()

    /** 상황이 끝난 알림(예: 남김 알림 해제)이 아직 재생 중·대기 중이면 끊는다. */
    fun cancelSpeech(match: (SpeechRequest) -> Boolean) {
        pendingRide.removeAll { match(it.first) }
        if (currentRequest?.let(match) == true) interruptSpeech()
    }

    /**
     * 모드 입력은 [PikoSession]만 한다 (UI가 임의로 안전 상태를 주입하지 못하도록 internal).
     * 모드가 바뀌면 대기 중인 지연 반응은 모두 폐기되고(세대 토큰), R단·부팅·정차→주행 전환 시
     * 진행 중인 발화와 재시도도 즉시 중단된다.
     */
    internal fun onModeChanged(newMode: DriveMode) {
        val prev = _mode.value
        if (prev == newMode) return
        _mode.value = newMode
        modeGeneration++
        // 주행·후진 후 새 정차 세션 → 쓰다듬기 횟수 초기화
        if (newMode == DriveMode.PARKED && (prev == DriveMode.DRIVING || prev == DriveMode.REVERSING)) pettingCount = 0
        pendingReactions.forEach { it.cancel() }
        pendingReactions.clear()
        val mustSilence = !newMode.allowsReaction() || (prev == DriveMode.PARKED && newMode == DriveMode.DRIVING)
        if (mustSilence) interruptSpeech()
        refreshBaseline()
    }

    /**
     * 기본 표정이 바뀌었을 수 있을 때(모드·배터리 상태 변경) 일시 표정이 없으면 새 기본 표정으로 맞춘다.
     * 주행 중이면 레이트 리밋을 따르고, 막히면 복귀 루프에 맡긴다.
     */
    private fun refreshBaseline() {
        if (!_mode.value.allowsReaction()) return
        if (revertJob?.isActive == true) return
        val target = baseline()
        if (_state.value.expression == target) return
        if (!setExpression(target)) scheduleRevert()
    }

    private fun interruptSpeech() {
        pendingRide.clear()
        val job = speechJob ?: return
        val request = currentRequest
        if (job.isActive) {
            job.cancel()
            speechSink.stop()
            if (request != null) log(clock.nowMs(), request, SpeechOutcome.INTERRUPTED)
        }
        speechJob = null
        currentRequest = null
    }

    fun onEvent(event: DrivingEvent) {
        record(event)
        if (!config.drivingReactions) return
        val modeAtEvent = _mode.value
        if (!modeAtEvent.allowsReaction()) return
        if (event is DrivingEvent.ChargingStarted && modeAtEvent != DriveMode.PARKED) return
        val reaction = policy.react(event) ?: return
        applyDeltas(reaction)
        if (!reaction.delayed) {
            express(reaction)
            return
        }
        val generation = modeGeneration
        lateinit var job: Job
        job = scope.launch {
            delay(config.reactionDelayMs)
            pendingReactions.remove(job)
            // 지연 중 모드가 한 번이라도 바뀌었으면 (D→R→D 포함) 반응을 버린다.
            if (modeGeneration == generation) express(reaction)
        }
        pendingReactions += job
    }

    /**
     * 정차 중 쓰다듬기 (F1). 주행 중에는 무시.
     * 정차 1회당 [PetConfig.pettingCapPerPark]번까지만 기분·친밀도·대사 반영, 이후엔 HAPPY 표정만.
     */
    fun petByHand() {
        if (_mode.value != DriveMode.PARKED) return
        if (pettingCount >= config.pettingCapPerPark) {
            express(Reaction(expression = Expression.HAPPY))
            return
        }
        pettingCount++
        val reaction = policy.petByHand()
        applyDeltas(reaction)
        express(reaction)
    }

    /**
     * 첫 만남 인사 "안녕, 난 피코야." + HAPPY. PARKED에서만, 프로세스당 1회 (이후 호출은 무시).
     * 주행 카테고리 쿨다운을 남기지 않으므로 곧바로 출발해도 출발 인사는 그대로 나온다.
     * @return 이번 호출로 인사했으면 true.
     */
    fun greetFirstMeet(): Boolean {
        if (_mode.value != DriveMode.PARKED) return false
        if (!firstMeetDone.compareAndSet(false, true)) return false
        val reaction = policy.firstMeet()
        reaction.expression?.let { if (setExpression(it)) scheduleRevert() }
        reaction.speech?.let { speak(it, recordCooldown = false) }
        return true
    }

    /**
     * v3 루틴 대사 (RideLines). R단·부팅 중엔 버린다.
     * [urgent] = 운전자 안전 보조 알림: 카테고리 쿨다운·발화 예산 검사를 건너뛰고, 진행 중인 발화를 끊고 말한다.
     * 빈도 제한은 RideEngine 쪽 규칙(리마인더 간격·반복 상한)이 맡는다.
     */
    fun sayRide(request: SpeechRequest, expression: Expression?, urgent: Boolean) {
        if (!_mode.value.allowsReaction()) {
            log(clock.nowMs(), request, SpeechOutcome.DROPPED_MODE)
            return
        }
        expression?.let { if (setExpression(it, priority = urgent)) scheduleRevert() }
        if (urgent && voiceEnabled) {
            // 끊긴 메모·대기 중인 말은 버리지 않고 긴급 알림 뒤로 다시 줄 세운다 (도착 메모가 차도 쪽 문 경고에 묻히던 문제)
            // 같은 메모를 두 번 이상 되살리지는 않는다 (남김 알림이 반복될 때 메모가 따라 반복되던 문제)
            val cut = currentRequest?.takeIf { it.category == SpeechCategory.AGENT && speechJob?.isActive == true }
                ?.takeIf { r -> requeued.none { it === r } }
                ?.also { requeued.addLast(it); if (requeued.size > 8) requeued.removeFirst() }
            val waiting = pendingRide.toList()
            interruptSpeech()
            val now = clock.nowMs()
            listOfNotNull(cut).forEach { pendingRide.addLast(it to now) }
            waiting.filter { (r, _) -> r.lineId != cut?.lineId }.forEach { pendingRide.addLast(it) }
            while (pendingRide.size > RIDE_QUEUE_MAX) pendingRide.removeLast()
        }
        speak(request, recordCooldown = !urgent, urgent = urgent)
    }

    private fun record(event: DrivingEvent) {
        when (event) {
            is DrivingEvent.Departed -> trip.start(event.timestampMs, _state.value)
            is DrivingEvent.HardBrake -> trip.startled(event.timestampMs)
            is DrivingEvent.HardAccel -> trip.startled(event.timestampMs)
            is DrivingEvent.ComfortAccrued -> {
                trip.addComfort(event.comfortMs)
                _state.update { it.copy(comfortMsTotal = it.comfortMsTotal + event.comfortMs * growthMultiplier) }
            }
            is DrivingEvent.Parked -> {
                _state.update(Growth::commit)
                trip.finish(event.timestampMs, _state.value)?.let { _tripReports.tryEmit(it) }
            }
            is DrivingEvent.BatteryLow -> _state.update { it.copy(batteryLow = true) }
            is DrivingEvent.BatteryRecovered -> {
                _state.update { it.copy(batteryLow = false) }
                refreshBaseline()
            }
            else -> Unit
        }
    }

    private fun applyDeltas(r: Reaction) {
        if (r.moodDelta == 0 && r.bondDelta == 0) return
        _state.update {
            it.copy(mood = (it.mood + r.moodDelta).clampStat(), bond = (it.bond + r.bondDelta).clampStat())
        }
    }

    private fun express(r: Reaction) {
        r.expression?.let { if (setExpression(it, priority = r.priority)) scheduleRevert() }
        r.speech?.let(::speak)
    }

    /** @return 표정이 실제로 바뀌었으면 true. */
    private fun setExpression(target: Expression, force: Boolean = false, priority: Boolean = false): Boolean {
        val mode = _mode.value
        if (!force && !mode.allowsReaction()) return false
        if (_state.value.expression == target) return false
        val now = clock.nowMs()
        val last = lastExpressionChangeAt
        val minInterval = if (priority) config.priorityExpressionMinIntervalMs else config.expressionMinIntervalMs
        if (!force && mode == DriveMode.DRIVING && last != null && now - last < minInterval) {
            return false
        }
        _state.update { it.copy(expression = target) }
        lastExpressionChangeAt = now
        return true
    }

    private fun scheduleRevert() {
        revertJob?.cancel()
        revertJob = scope.launch {
            delay(config.expressionHoldMs)
            while (true) {
                val target = baseline()
                if (_state.value.expression == target || setExpression(target)) return@launch
                delay(config.expressionMinIntervalMs) // R단 중이거나 레이트 리밋 → 나중에 다시
            }
        }
    }

    private fun speak(request: SpeechRequest, recordCooldown: Boolean = true, urgent: Boolean = false) {
        val mode = _mode.value
        val now = clock.nowMs()
        val last = lastSpokenAt[request.category]
        val blocked = when {
            !voiceEnabled -> SpeechOutcome.DROPPED_VOICE_OFF
            !mode.allowsReaction() -> SpeechOutcome.DROPPED_MODE
            !urgent && mode == DriveMode.DRIVING && last != null && now - last < config.speechCooldownMs ->
                SpeechOutcome.DROPPED_COOLDOWN
            speechJob?.isActive == true -> SpeechOutcome.DROPPED_BUSY
            !urgent && mode == DriveMode.DRIVING && !budget.canSpend(now, priority = request.category == SpeechCategory.STARTLE) ->
                SpeechOutcome.DROPPED_BUDGET
            else -> null
        }
        if (blocked == SpeechOutcome.DROPPED_BUSY &&
            (request.category == SpeechCategory.RIDE_KID || request.category == SpeechCategory.AGENT)
        ) {
            // 아이 대사·에이전트 메모는 다른 말과 겹치기 쉽다 (도착 때 아이 인사 + 도착 메모) →
            // 한 줄만 대기시켜 지금 발화가 끝나면 이어서
            if (pendingRide.size >= RIDE_QUEUE_MAX) log(now, pendingRide.removeFirst().first, SpeechOutcome.DROPPED_BUSY)
            if (pendingRide.none { it.first.lineId == request.lineId }) pendingRide.addLast(request to now)
            return
        }
        if (blocked != null) {
            log(now, request, blocked)
            return
        }
        // 쿨다운은 수락 시점부터 (포커스 실패로 폐기돼도 재시도 폭주 방지).
        if (recordCooldown) lastSpokenAt[request.category] = now
        if (mode == DriveMode.DRIVING) budget.spend(now)
        currentRequest = request
        speechJob = scope.launch {
            val outcome = scheduler.deliver(request) { voiceEnabled && _mode.value.allowsReaction() }
            log(clock.nowMs(), request, outcome)
            currentRequest = null
            speechJob = null
            // 오래된 줄은 버리고, 남은 첫 줄을 이어서 말한다 (speak가 다시 이 블록을 거쳐 나머지를 잇는다)
            while (pendingRide.isNotEmpty()) {
                val (next, at) = pendingRide.removeFirst()
                if (clock.nowMs() - at <= config.rideQueueMaxWaitMs) {
                    speak(next)
                    break
                }
                log(clock.nowMs(), next, SpeechOutcome.DROPPED_BUSY)
            }
        }
    }

    private fun log(atMs: Long, request: SpeechRequest, outcome: SpeechOutcome) {
        _speechLog.update { (it + SpeechLogEntry(atMs, request, outcome)).takeLast(LOG_LIMIT) }
    }

    /** 음성 off → QUIET, 정차 중 배터리 부족 → SLEEPY, 그 외 CALM. */
    private fun baseline(): Expression = when {
        !voiceEnabled -> Expression.QUIET
        _mode.value == DriveMode.PARKED && _state.value.batteryLow -> Expression.SLEEPY
        else -> Expression.CALM
    }

    private fun DriveMode.allowsReaction(): Boolean = this == DriveMode.DRIVING || this == DriveMode.PARKED

    companion object {
        internal const val LOG_LIMIT = 100
        const val RIDE_QUEUE_MAX = 3
        private val firstMeetDone = java.util.concurrent.atomic.AtomicBoolean(false)

        /**
         * 피코를 처음부터 다시 시작할 때(F9 초기화) 첫 만남 인사를 다시 허용한다.
         * 플래그가 프로세스 단위라 초기화 후 F0 화면은 떠도 인사가 안 나오던 문제 (Codex 재검토).
         */
        fun allowFirstMeetAgain() = firstMeetDone.set(false)

        /** 테스트 전용 */
        internal fun resetFirstMeetForTests() = allowFirstMeetAgain()
    }
}
