package com.pikodrive.ui.live

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.pikodrive.PikoApp
import com.pikodrive.core.model.DriveMode
import com.pikodrive.core.pet.PikoSession
import com.pikodrive.storage.NightDim
import com.pikodrive.storage.PikoSettings
import com.pikodrive.core.model.RearSeat
import com.pikodrive.core.ride.LeftBehindStatus
import com.pikodrive.core.ride.ChildProfile
import com.pikodrive.storage.ChildStore
import com.pikodrive.storage.AgentStore
import com.pikodrive.ui.screens.F12Schedule
import com.pikodrive.ui.screens.F13Memo
import com.pikodrive.ui.screens.F1Agent
import com.pikodrive.ui.screens.F10LeftBehind
import com.pikodrive.ui.screens.F11Family
import com.pikodrive.vehicle.CabinOverrides
import com.pikodrive.ui.screens.F0FirstMeet
import com.pikodrive.ui.screens.F3Driving
import com.pikodrive.ui.screens.F8Settings
import com.pikodrive.ui.screens.F9DemoPanel
import com.pikodrive.ui.screens.PetUi
import com.pikodrive.ui.screens.SimBadge
import com.pikodrive.ui.screens.TagBadge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import com.pikodrive.ui.theme.MotionBudget
import com.pikodrive.ui.theme.PikoSpace
import java.util.Calendar

/** 정차 중에만 열리는 하위 화면 */
private enum class Parked { HOME, SETTINGS, DEMO, FAMILY, SCHEDULE, MEMO }

/**
 * 차량 상태가 화면을 결정한다 (FRAMEWORK 3-1).
 * - BOOTING·DRIVING → F3, REVERSING → F3R (텍스트·버튼 없음)
 * - PARKED → 남김 알림(F10)이 최우선, 첫 실행이면 F0(→ 가족 등록), 아니면 부모용 브리핑 홈(F1A)
 *   + 일정·메모·가족·설정·데모 하위 화면
 * - 출발하면 열려 있던 하위 화면은 모두 닫힌다 (주행 중 터치 UI 금지)
 * - 화면 전환은 ≤300ms 크로스페이드 (F2)
 */
@Composable
fun LiveScreen(app: PikoApp, session: PikoSession) {
    val mode by session.mode.collectAsState()
    val pet by session.pet.collectAsState()
    val settings by app.settings.collectAsState()
    var parked by rememberSaveable { mutableStateOf(Parked.HOME) }
    var secretTaps by remember { mutableIntStateOf(0) }
    var lastTapAt by remember { mutableLongStateOf(0L) }
    var firstMeetStep by rememberSaveable { mutableIntStateOf(0) }
    var resetArmed by remember { mutableStateOf(false) }
    val ride by session.rideState.collectAsState()
    val quiet by session.quiet.collectAsState()
    val arrival by app.arrival.collectAsState()
    val children by app.children.collectAsState()
    val beltOverrides by CabinOverrides.belts.collectAsState()
    val briefing by session.briefing.collectAsState()
    val schedules by app.schedules.collectAsState()
    val memos by app.memos.collectAsState()
    val safety by app.safety.collectAsState()
    val rearScreen by app.rearScreen.collectAsState()

    LaunchedEffect(mode) {
        if (mode != DriveMode.PARKED) {
            parked = Parked.HOME
            app.voice.cancel() // 말로 입력은 정차 중에만
        }
    }
    // F0 첫 만남: 피코가 직접 인사 (엔진이 같은 프로세스에서 한 번만 말한다)
    val firstMeet = mode == DriveMode.PARKED && settings?.firstMeetDone == false
    LaunchedEffect(firstMeet) { if (firstMeet) session.engine.greetFirstMeet() }
    // 크로스페이드로 사라지는 중인 정차 화면의 탭은 무시한다: 실제 차량 모드가 P일 때만 반응 (eval D5)
    fun parkedOnly(action: () -> Unit) {
        if (session.mode.value == DriveMode.PARKED) action()
    }

    val st = settings ?: PikoSettings()
    val dim = nightDim(st.nightDim)
    // 램프 = 지금 타 있는 아이(없으면 첫 프로필)의 스티커판
    val focus = ride.onboard.firstNotNullOfOrNull { it.profile }?.let { p -> children.firstOrNull { it.id == p.id } }
        ?: children.firstOrNull()
    // v4: 피코의 빛은 아이 스티커판만 나타낸다 (아이가 없으면 꺼진 채). v2 운전 성장 진행은 쓰지 않는다
    val ui = PetUi(expression = pet.expression, lamps = focus?.stickersOnBoard ?: 0, unlock = focus?.boardsCompleted ?: 0)

    // 화면 키: 같은 키면 재구성만, 다른 키면 크로스페이드
    val screenKey = when (mode) {
        DriveMode.BOOTING, DriveMode.DRIVING -> "drive"
        DriveMode.REVERSING -> "reverse"
        DriveMode.PARKED -> when {
            settings == null -> "drive" // 설정 로딩 전엔 보수 화면
            // 남김 보조 알림은 어떤 화면보다 먼저
            ride.leftBehind != LeftBehindStatus.NONE -> "left-behind"
            !st.firstMeetDone -> "first"
            // v4: 정차 홈 = 부모용 에이전트 브리핑 (일정·메모·뒷좌석 체크·안전 기록). v2 트립 리포트는 쓰지 않는다
            parked == Parked.HOME -> "agent-home"
            else -> "parked-${parked.name}"
        }
    }

    Box(Modifier.fillMaxSize()) {
        Crossfade(screenKey, animationSpec = tween(MotionBudget.DRIVING_TRANSITION_MS), label = "screen") { key ->
            when (key) {
                "left-behind" -> F10LeftBehind(ride, onAck = { session.acknowledgeLeftBehind() })
                "agent-home" -> F1Agent(
                    pet = ui,
                    briefing = briefing,
                    ride = ride,
                    arrival = arrival,
                    children = children,
                    safetyToday = safety.filter { isToday(it.atWallMs) },
                    rearScreen = rearScreen,
                    quiet = quiet,
                    onQuiet = { on -> parkedOnly { session.setQuiet(on) } },
                    onSchedule = { parkedOnly { parked = Parked.SCHEDULE } },
                    onMemo = { parkedOnly { parked = Parked.MEMO } },
                    onFamily = { parkedOnly { parked = Parked.FAMILY } },
                    onSettings = { parkedOnly { parked = Parked.SETTINGS } },
                    onAgentTap = {
                        val now = System.currentTimeMillis()
                        secretTaps = if (now - lastTapAt < 800) secretTaps + 1 else 1
                        lastTapAt = now
                        if (secretTaps >= 5) {
                            secretTaps = 0
                            parkedOnly { parked = Parked.DEMO }
                        }
                    },
                    dim = dim,
                )
                "parked-SCHEDULE" -> F12Schedule(
                    schedules = schedules,
                    children = children,
                    voice = app.voice,
                    onChange = { list -> parkedOnly { app.updateSchedules { list } } },
                    onBack = { parkedOnly { parked = Parked.HOME } },
                )
                "parked-MEMO" -> F13Memo(
                    memos = memos,
                    children = children,
                    voice = app.voice,
                    onChange = { list -> parkedOnly { app.updateMemos { list } } },
                    onBack = { parkedOnly { parked = Parked.HOME } },
                )
                "parked-FAMILY" -> F11Family(
                    children = children,
                    streetSideLeft = st.streetSideLeft,
                    onStreetSide = { left -> parkedOnly { app.updateSettings { it.copy(streetSideLeft = left) } } },
                    onChange = { list -> parkedOnly { app.updateChildren { list } } },
                    onBack = { parkedOnly { parked = Parked.HOME } },
                    guardianOn = st.guardianOn,
                    onGuardian = { on -> parkedOnly { app.updateSettings { it.copy(guardianOn = on) } } },
                )
                "drive" -> F3Driving(ui, DriveMode.DRIVING, dim)
                "reverse" -> F3Driving(ui, DriveMode.REVERSING, dim)
                "first" -> F0FirstMeet(
                    step = firstMeetStep,
                    onNext = { parkedOnly { firstMeetStep = 1 } },
                    onRegister = {
                        parkedOnly {
                            app.updateSettings { it.copy(firstMeetDone = true) }
                            parked = Parked.FAMILY
                        }
                    },
                    onLater = { parkedOnly { app.updateSettings { it.copy(firstMeetDone = true) } } },
                )
                "parked-SETTINGS" -> F8Settings(
                    pet = ui,
                    dim = dim,
                    voiceOn = st.voiceOn,
                    onVoice = { on -> parkedOnly { app.updateSettings { it.copy(voiceOn = on) } } },
                    nightDim = st.nightDim.ordinal,
                    onNightDim = { i -> parkedOnly { app.updateSettings { it.copy(nightDim = NightDim.entries[i]) } } },
                    onFamily = { parkedOnly { parked = Parked.FAMILY } },
                    onBack = { parkedOnly { parked = Parked.HOME } },
                )
                "parked-DEMO" -> F9DemoPanel(
                    pet = ui,
                    simulated = app.isSimulated,
                    modeLabel = "정차",
                    beltLeft = beltOverrides[RearSeat.LEFT],
                    beltRight = beltOverrides[RearSeat.RIGHT],
                    onBelt = { seat, v -> CabinOverrides.setBelt(seat, v) },
                    onDemoFamily = {
                        parkedOnly {
                            app.updateChildren { ChildStore.demoFamily }
                            app.updateSchedules { AgentStore.demoSchedules(java.time.LocalTime.now()) }
                            app.updateMemos { AgentStore.demoMemos }
                        }
                    },
                    // 판 완성 장면 시연: 이번 판을 9장으로 (다음 스티커 1장이면 완성)
                    onDemoStickers = {
                        parkedOnly {
                            app.updateChildren { list ->
                                list.map { it.copy(stickers = it.boardsCompleted * ChildProfile.STICKERS_PER_BOARD + 9) }
                            }
                        }
                    },
                    resetArmed = resetArmed,
                    // 두 번 눌러야 초기화 (eval D9)
                    onReset = {
                        parkedOnly {
                            if (resetArmed) {
                                resetArmed = false
                                app.resetPet()
                            } else {
                                resetArmed = true
                            }
                        }
                    },
                    onBack = { parkedOnly { parked = Parked.HOME } },
                )
                else -> LaunchedEffect(Unit) { parked = Parked.HOME }
            }
        }
        // 시뮬레이터·대체 입력은 화면 어디서든 표시 (영상·PT에서 실제 신호로 오해하지 않게)
        Column(Modifier.align(Alignment.TopEnd).padding(PikoSpace.m), horizontalAlignment = Alignment.End) {
            if (app.isSimulated) SimBadge()
            if (beltOverrides.isNotEmpty()) {
                Spacer(Modifier.height(PikoSpace.xs))
                TagBadge("시뮬레이션 입력 · 벨트")
            }
        }
    }
}

private fun nightDim(mode: NightDim): Float = when (mode) {
    NightDim.OFF -> 1f
    NightDim.ALWAYS -> NIGHT_DIM
    NightDim.AUTO -> {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        if (h >= 19 || h < 6) NIGHT_DIM else 1f
    }
}

/** FRAMEWORK 2-1-1: 야간 피코 휘도 60% */
private const val NIGHT_DIM = 0.6f

private fun isToday(wallMs: Long): Boolean {
    val a = Calendar.getInstance().apply { timeInMillis = wallMs }
    val b = Calendar.getInstance()
    return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
}
