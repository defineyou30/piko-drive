package com.pikodrive

import android.app.Application
import android.util.Log
import com.pikodrive.core.model.DriveMode
import com.pikodrive.core.pet.PetEngine
import com.pikodrive.core.pet.PikoSession
import com.pikodrive.core.pet.SpeechSink
import com.pikodrive.core.pet.TripReport
import com.pikodrive.core.sim.Scenarios
import com.pikodrive.core.sim.SimulatedVehicleGateway
import com.pikodrive.core.vehicle.VehicleGateway
import com.pikodrive.core.model.RearSeat
import com.pikodrive.core.ride.ChildProfile
import com.pikodrive.core.ride.RideEvent
import com.pikodrive.core.ride.RidePhase
import com.pikodrive.guardian.GuardianNotifier
import com.pikodrive.core.agent.AgentEvent
import com.pikodrive.core.agent.Memo
import com.pikodrive.core.agent.SafetyRecord
import com.pikodrive.core.agent.ScheduleEntry
import com.pikodrive.core.agent.safetyKind
import com.pikodrive.core.ride.label
import com.pikodrive.storage.AgentStore
import com.pikodrive.storage.ChildStore
import com.pikodrive.storage.DataStorePetRepository
import com.pikodrive.storage.PikoSettings
import com.pikodrive.storage.SettingsStore
import com.pikodrive.vehicle.AmbientDimmer
import com.pikodrive.vehicle.PleosVehicleGateway
import com.pikodrive.voice.PleosSpeechSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 세션 수명 = 앱 프로세스. 화면이 다시 만들어져도 주행 상태가 끊기지 않는다.
 * 차량(Automotive)이면 Pleos 게이트웨이, 아니면 시뮬레이터(+SIM 배지).
 */
class PikoApp : Application() {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val repository by lazy { DataStorePetRepository(this) }
    private val settingsStore by lazy { SettingsStore(this) }
    val settings: StateFlow<PikoSettings?> by lazy {
        settingsStore.settings.stateIn(scope, SharingStarted.Eagerly, null)
    }

    val childStore by lazy { ChildStore(this) }
    val children: StateFlow<List<ChildProfile>> by lazy {
        childStore.children.stateIn(scope, SharingStarted.Eagerly, emptyList())
    }

    val agentStore by lazy { AgentStore(this) }
    val schedules: StateFlow<List<ScheduleEntry>> by lazy { agentStore.schedules.stateIn(scope, SharingStarted.Eagerly, emptyList()) }
    val memos: StateFlow<List<Memo>> by lazy { agentStore.memos.stateIn(scope, SharingStarted.Eagerly, emptyList()) }
    val safety: StateFlow<List<SafetyRecord>> by lazy { agentStore.safety.stateIn(scope, SharingStarted.Eagerly, emptyList()) }

    fun updateSchedules(t: (List<ScheduleEntry>) -> List<ScheduleEntry>) { scope.launch { agentStore.updateSchedules(t) } }
    fun updateMemos(t: (List<Memo>) -> List<Memo>) { scope.launch { agentStore.updateMemos(t) } }

    /** 마지막 도착 이벤트 (정차 화면의 "오늘 준비물" 카드). 다시 출발하거나 아이가 모두 내리면 지운다 */
    private val _arrival = MutableStateFlow<RideEvent.Arrived?>(null)
    val arrival: StateFlow<RideEvent.Arrived?> = _arrival.asStateFlow()

    fun updateChildren(transform: (List<ChildProfile>) -> List<ChildProfile>) {
        scope.launch {
            val before = children.value.map { it.id }.toSet()
            childStore.update(transform)
            // 아이를 지우면 그 아이의 일정·메모도 함께 지운다 (기획서 7번 개인정보 약속)
            val gone = before - childStore.children.first().map { it.id }.toSet()
            if (gone.isNotEmpty()) {
                agentStore.updateSchedules { l -> l.filterNot { it.childId in gone } }
                agentStore.updateMemos { l -> l.filterNot { it.childId in gone } }
            }
        }
    }

    private val _session = MutableStateFlow<PikoSession?>(null)
    val session: StateFlow<PikoSession?> = _session.asStateFlow()

    /** 리포트 목록 (F1 오늘 요약, 리포트 버튼). 트립이 끝날 때마다 갱신 */
    private val _reports = MutableStateFlow<List<TripReport>>(emptyList())
    val reports: StateFlow<List<TripReport>> = _reports.asStateFlow()

    /** 아직 확인하지 않은 도착 리포트. 화면 재생성(주야간 전환 등)에도 유지되고, 확인하면 다시 뜨지 않는다 */
    private val _pendingReport = MutableStateFlow<TripReport?>(null)
    val pendingReport: StateFlow<TripReport?> = _pendingReport.asStateFlow()

    fun dismissReport() {
        _pendingReport.value = null
    }

    /** 정차 중 "말로 입력" (입력 화면 공용) */
    val voice by lazy { com.pikodrive.voice.VoiceInput(this) }

    /** 뒷좌석 화면에 피코를 띄웠는지 (앞 화면 문구·배지에 사용) */
    val rearScreen = MutableStateFlow(false)

    var isSimulated: Boolean = false
        private set

    private var speechSink: PleosSpeechSink? = null
    private var dimmer: AmbientDimmer? = null
    private var sessionJobs = mutableListOf<Job>()
    /** 메인 스레드에서만 접근. 생성 중 중복 호출 방지 */
    private var starting = false

    /**
     * 권한 확인 이후 MainActivity가 호출 (메인 스레드). 중복 호출 안전.
     * CAR_SPEED가 거부돼도 시작한다: 게이트웨이가 속도 채널을 미지원으로 내보내고
     * 코어가 기어 기반으로 동작한다 (FRAMEWORK 3-3 엣지 플로우).
     */
    fun startSession(forceDemo: Boolean = false) {
        if (starting || _session.value != null) return
        starting = true
        scope.launch {
            val useCar = !forceDemo && PleosVehicleGateway.isAvailable(this@PikoApp)
            val gateway: VehicleGateway = if (useCar) PleosVehicleGateway(this@PikoApp) else
                SimulatedVehicleGateway(Scenarios.commuteCompressed, loop = true)
            isSimulated = !useCar
            // 데모 모드 = 성장 가속 + 데모 판정 기준(짧은 스트릭). 실차에서는 F9 스위치로 켠다
            val demo = !useCar || (settingsStore.settings.first().demoGrowth)
            val sink: SpeechSink = if (useCar) PleosSpeechSink(this@PikoApp).also { speechSink = it } else
                SpeechSink { r -> Log.i(EvidenceLog.TAG, "SIM-TTS ${r.lineId}"); true }
            // v4: v2 주행 반응(놀람·칭찬·배터리) 대사는 끈다 — 아이 일정·안전 비서 컨셉과 충돌
            val s = PikoSession.create(scope, gateway, sink, repository, demo = demo, drivingReactions = false)
            Log.i(EvidenceLog.TAG, "session demo=$demo")
            sessionJobs += EvidenceLog.attach(scope, s, gateway)
            _reports.value = repository.recentReports(limit = 30)
            sessionJobs += scope.launch {
                s.tripReports.collect {
                    _reports.value = listOf(it) + _reports.value
                    _pendingReport.value = it
                }
            }
            sessionJobs += scope.launch {
                // 다시 출발하면 미확인 리포트는 닫는다
                s.mode.collect { if (it != DriveMode.PARKED) _pendingReport.value = null }
            }
            sessionJobs += scope.launch { settings.collect { it?.let { st -> apply(s, st) } } }
            sessionJobs += scope.launch { children.collect { s.setProfiles(it) } }
            sessionJobs += scope.launch { schedules.collect { s.setSchedules(it) } }
            sessionJobs += scope.launch { memos.collect { s.setMemos(it) } }
            sessionJobs += scope.launch {
                // 한 번짜리 메모는 꺼내면 완료
                s.agentEvents.collect { a ->
                    if (a is AgentEvent.MemoDue && !a.memo.repeat) agentStore.updateMemos { l -> l.map { if (it.id == a.memo.id) it.copy(done = true) else it } }
                }
            }
            sessionJobs += scope.launch {
                s.rideEvents.collect { e ->
                    val kind = e.safetyKind() ?: return@collect
                    val seat = when (e) {
                        is RideEvent.BeltReleasedWhileDriving -> e.seat.label
                        is RideEvent.DoorOpenWhileMoving -> e.seat.label
                        is RideEvent.StreetSideDoorOpened -> e.seat.label
                        is RideEvent.DepartUnbuckled -> e.seats.joinToString { it.label }
                        is RideEvent.LeftBehindAlert -> e.seats.joinToString { it.label }
                        else -> null
                    }
                    agentStore.addSafety(SafetyRecord(System.currentTimeMillis(), kind, seat))
                }
            }
            val guardian = GuardianNotifier(
                scope,
                url = { settings.value?.takeIf { it.guardianOn }?.guardianUrl?.ifBlank { GuardianNotifier.DEMO_URL } },
                profiles = { children.value },
            )
            sessionJobs += scope.launch { s.rideEvents.collect(guardian::onEvent) }
            sessionJobs += scope.launch {
                s.rideEvents.collect { e ->
                    when (e) {
                        is RideEvent.StickerEarned -> childStore.addSticker(e.seat)
                        is RideEvent.Arrived -> _arrival.value = e
                        else -> Unit
                    }
                }
            }
            sessionJobs += scope.launch {
                s.rideState.collect { r ->
                    if (r.onboard.isEmpty() || r.phase == RidePhase.DRIVING) _arrival.value = null
                    // 아이가 모두 내리면 쉿 모드도 끝 (다음 탑승 때 피코가 다시 인사)
                    if (r.onboard.isEmpty() && s.quiet.value) s.setQuiet(false)
                }
            }
            if (useCar) {
                val d = AmbientDimmer(this@PikoApp).also { dimmer = it }
                sessionJobs += scope.launch { s.quiet.collect { d.set(it) } }
            }
            s.start()
            _session.value = s
        }.invokeOnCompletion { starting = false }
    }

    private fun apply(s: PikoSession, st: PikoSettings) {
        s.engine.voiceEnabled = st.voiceOn
        s.setStreetSide(if (st.streetSideLeft) RearSeat.LEFT else RearSeat.RIGHT)
    }

    /** F9 데모 스위치. 판정 기준이 바뀌므로 세션을 다시 만든다 (펫 상태는 저장소에서 이어짐). 정차 중에만 호출됨 */
    fun setDemo(on: Boolean) {
        scope.launch {
            settingsStore.update { it.copy(demoGrowth = on) }
            restartSession()
        }
    }

    fun updateSettings(transform: (PikoSettings) -> PikoSettings) {
        scope.launch { settingsStore.update(transform) }
    }

    /** F9: 피코를 처음 상태로 (PT 리허설용). 세션을 닫고 저장소를 비운 뒤 다시 시작 */
    fun resetPet() {
        scope.launch {
            closeSession()
            repository.clear()
            _reports.value = emptyList()
            PetEngine.allowFirstMeetAgain()
            settingsStore.update { it.copy(firstMeetDone = false) }
            startSession(forceDemo = isSimulated)
        }
    }

    private suspend fun restartSession() {
        closeSession()
        startSession(forceDemo = isSimulated)
    }

    private suspend fun closeSession() {
        _session.value?.let { s ->
            s.stop()
            repository.saveState(s.pet.value)
        }
        sessionJobs.forEach { it.cancel() }
        sessionJobs.clear()
        speechSink?.release()
        speechSink = null
        dimmer?.release()
        dimmer = null
        _session.value = null
    }
}
