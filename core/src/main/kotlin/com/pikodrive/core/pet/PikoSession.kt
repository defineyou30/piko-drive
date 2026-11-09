package com.pikodrive.core.pet

import com.pikodrive.core.driving.DetectionConfig
import com.pikodrive.core.driving.DrivingEventDetector
import com.pikodrive.core.model.DriveMode
import com.pikodrive.core.storage.PetRepository
import com.pikodrive.core.vehicle.Clock
import com.pikodrive.core.vehicle.VehicleCapabilities
import com.pikodrive.core.vehicle.VehicleGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import com.pikodrive.core.driving.DrivingEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.pikodrive.core.model.RearSeat
import com.pikodrive.core.ride.Audience
import com.pikodrive.core.ride.ChildProfile
import com.pikodrive.core.ride.RideEngine
import com.pikodrive.core.ride.RideEvent
import com.pikodrive.core.ride.RideLines
import com.pikodrive.core.ride.RideState
import com.pikodrive.core.agent.AgentBriefing
import com.pikodrive.core.agent.AgentEngine
import com.pikodrive.core.agent.AgentEvent
import com.pikodrive.core.agent.AgentLines
import com.pikodrive.core.agent.Memo
import com.pikodrive.core.agent.ScheduleEntry
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalDate

/**
 * UI가 붙는 진입점: Gateway → Detector → Engine 배선 + 저장.
 * `val session = PikoSession.create(scope, gateway, sink, repo); session.start()`
 */
class PikoSession(
    private val scope: CoroutineScope,
    private val gateway: VehicleGateway,
    val engine: PetEngine,
    private val detector: DrivingEventDetector =
        DrivingEventDetector(uxRestrictionSupported = gateway.capabilities.uxRestriction),
    private val repository: PetRepository? = null,
    private val today: () -> DayOfWeek = { LocalDate.now().dayOfWeek },
    private val wallNow: () -> LocalDateTime = { LocalDateTime.now() },
) {
    /** v4 스케줄·메모 에이전트 (부모 쪽). [setSchedules]·[setMemos]로만 바꾼다. */
    private val agent = AgentEngine()
    private val _briefing = MutableStateFlow(AgentBriefing())
    val briefing: StateFlow<AgentBriefing> = _briefing.asStateFlow()
    private val _agentEvents = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 32)
    /** 일정 브리핑·메모 꺼냄 (한 번짜리 메모 완료 저장이 구독) */
    val agentEvents: SharedFlow<AgentEvent> = _agentEvents.asSharedFlow()

    fun setSchedules(list: List<ScheduleEntry>) { agent.schedules = list; _briefing.value = agent.briefing(wallNow()) }
    fun setMemos(list: List<Memo>) { agent.memos = list; _briefing.value = agent.briefing(wallNow()) }

    /** v3 뒷좌석 루틴. 프로필·차도 방향은 [setProfiles]·[setStreetSide]로만 바꾼다. */
    private val ride = RideEngine()
    private val rideLines = RideLines()
    private val _rideState = MutableStateFlow(RideState())
    val rideState: StateFlow<RideState> = _rideState.asStateFlow()
    private val _rideEvents = MutableSharedFlow<RideEvent>(extraBufferCapacity = 64)
    /** 루틴 이벤트 (스티커 저장·증거 로그·보호자 알림이 구독). */
    val rideEvents: SharedFlow<RideEvent> = _rideEvents.asSharedFlow()
    private val _quiet = MutableStateFlow(false)
    /** 쉿 모드: 아이에게 하는 피코 대사를 모두 끈다 (운전자 알림은 유지). */
    val quiet: StateFlow<Boolean> = _quiet.asStateFlow()

    private val _kidLine = MutableStateFlow<KidLine?>(null)
    /** 뒷좌석 화면 말풍선용: 피코가 아이에게 마지막으로 건넨 말 (쉿 모드·피코 꺼짐이면 없음) */
    val kidLine: StateFlow<KidLine?> = _kidLine.asStateFlow()

    fun setProfiles(profiles: List<ChildProfile>) { ride.profiles = profiles }

    init {
        // 준비물은 일정에서만: 일정에 준비물이 있으면 그것, 없으면 예전 프로필 요일별 준비물
        ride.arrivalItems = { p, d -> agent.itemsNear(wallNow(), p.id).ifEmpty { p.itemsByDay[d].orEmpty() } }
    }
    fun setStreetSide(side: RearSeat) { ride.streetSide = side }
    fun setQuiet(on: Boolean) { _quiet.value = on }
    fun acknowledgeLeftBehind() = ride.acknowledgeLeftBehind()

    val mode: StateFlow<DriveMode> get() = engine.mode
    val pet: StateFlow<PetState> get() = engine.state
    val tripReports: SharedFlow<TripReport> get() = engine.tripReports
    val speechLog: StateFlow<List<SpeechLogEntry>> get() = engine.speechLog
    val capabilities: VehicleCapabilities get() = gateway.capabilities

    private val _events = MutableSharedFlow<DrivingEvent>(extraBufferCapacity = 64)
    /** 감지된 주행 이벤트 (증거 로그·디버그용). 놓쳐도 엔진 동작엔 영향 없음. */
    val events: SharedFlow<DrivingEvent> = _events.asSharedFlow()

    private var job: Job? = null

    /** 수집 시작. 이미 실행 중이면 기존 Job을 그대로 돌려준다 (중복 수집 방지). */
    fun start(): Job {
        job?.takeIf { it.isActive }?.let { return it }
        return launchCollection().also { job = it }
    }

    /** 수집 중단. 이후 [start]로 다시 시작할 수 있다 (감지기 상태는 유지). */
    fun stop() {
        job?.cancel()
        job = null
    }

    /**
     * 스티커 이벤트 뒤에 판 완성 이벤트를 붙인다. 저장소 반영(앱)은 비동기라 여기서 프로필 장수를 먼저 올려 둔다
     * (같은 수집 루프 안에서 스티커가 연달아 와도 판 완성을 놓치지 않게).
     */
    private fun withBoards(events: List<RideEvent>): List<RideEvent> = events.flatMap { e ->
        if (e !is RideEvent.StickerEarned) return@flatMap listOf(e)
        val p = ride.profiles.firstOrNull { it.seat == e.seat } ?: return@flatMap listOf(e)
        val next = p.copy(stickers = p.stickers + 1)
        ride.profiles = ride.profiles.map { if (it.id == p.id) next else it }
        if (next.stickers % ChildProfile.STICKERS_PER_BOARD == 0) {
            listOf(e, RideEvent.BoardCompleted(e.timestampMs, e.seat, next.boardsCompleted))
        } else {
            listOf(e)
        }
    }

    private fun launchCollection(): Job = scope.launch {
        repository?.let { repo ->
            launch { engine.tripReports.collect { repo.addReport(it) } }
            launch {
                // 표정만 바뀐 경우는 저장하지 않는다.
                engine.state.map { it.copy(expression = PetState.Initial.expression) }
                    .distinctUntilChanged().drop(1)
                    .collect { repo.saveState(it) }
            }
        }
        gateway.snapshots.collect { snapshot ->
            val events = detector.process(snapshot)
            if (detector.mode != engine.mode.value) engine.onModeChanged(detector.mode)
            events.forEach {
                _events.tryEmit(it)
                engine.onEvent(it)
            }
            val rideEvents = ride.process(snapshot, detector.mode, today()).let(::withBoards)
            rideEvents.forEach { e ->
                _rideEvents.tryEmit(e)
                // 남김 상황이 풀리면 아직 재생·대기 중인 남김 알림은 끊는다 (해제 뒤에 "아이가 있어요"가 들리지 않게)
                if (e is RideEvent.LeftBehindCleared) engine.cancelSpeech { it.lineId.startsWith("front_left_behind") }
                rideLines.linesFor(e, ride.profiles, _quiet.value, ride.streetSide).forEach { u ->
                    if (u.audience == Audience.REAR) _kidLine.value = KidLine(u.request.text, snapshot.timestampMs)
                    engine.sayRide(u.request, u.expression, u.urgent)
                }
            }
            _rideState.value = ride.state
            val onboardIds = ride.state.onboard.mapNotNull { it.profile?.id }.toSet()
            val now = wallNow()
            agent.process(now, detector.mode, rideEvents, ride.profiles, onboardIds).forEach { a ->
                _agentEvents.tryEmit(a)
                engine.sayRide(AgentLines.lineFor(a, ride.profiles), null, urgent = false)
            }
            _briefing.value = agent.briefing(now)
        }
    }

    companion object {
        suspend fun create(
            scope: CoroutineScope,
            gateway: VehicleGateway,
            speechSink: SpeechSink,
            repository: PetRepository? = null,
            clock: Clock = Clock.Monotonic,
            demo: Boolean = false,
            /** v4 앱은 false (v2 주행 반응 끔). 기본값 true는 v2 테스트·시뮬레이터 호환 */
            drivingReactions: Boolean = true,
        ): PikoSession {
            val initial = repository?.loadState() ?: PetState.Initial
            val engine = PetEngine(
                scope = scope,
                speechSink = speechSink,
                clock = clock,
                config = PetConfig(growthMultiplier = if (demo) DEMO_GROWTH_MULTIPLIER else 1, drivingReactions = drivingReactions),
                initialState = initial,
            )
            val detector = DrivingEventDetector(
                config = if (demo) DetectionConfig.Demo else DetectionConfig(),
                uxRestrictionSupported = gateway.capabilities.uxRestriction,
            )
            return PikoSession(scope, gateway, engine, detector, repository)
        }
    }
}

/** 뒷좌석 화면에 띄울 피코의 말. [atMs] = 차량 스냅샷 시각 (화면이 몇 초 뒤 지운다) */
data class KidLine(val text: String, val atMs: Long)
