package com.pikodrive.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.pikodrive.core.model.DriveMode
import com.pikodrive.core.model.Expression
import com.pikodrive.core.model.GrowthStage
import com.pikodrive.ui.component.PikoButton
import com.pikodrive.ui.component.PixelIcon
import com.pikodrive.ui.component.PixelIconView
import com.pikodrive.ui.component.PikoSays
import com.pikodrive.ui.component.Stat
import com.pikodrive.ui.piko.PikoCanvas
import com.pikodrive.ui.theme.PikoColors
import com.pikodrive.ui.theme.PikoSpace
import com.pikodrive.ui.theme.PikoText

/** S1 목업용 UI 상태. S4에서 엔진 상태로 교체한다. */
data class PetUi(
    val stage: GrowthStage = GrowthStage.FACE,
    val pixelCount: Int = 28,
    val expression: Expression = Expression.CALM,
    val moodLabel: String = "오늘 기분 좋아.",
    val minutesToNext: Int = 38,
    val progressFilled: Int = 7,
    val progressTotal: Int = 12,
    /** 완성 이후 루프: 편안한 주행 30분마다 별 1개 */
    val stars: Int = 0,
    /** v3: 이마 램프 = 아이 스티커판 (null이면 성장 픽셀로 환산) */
    val lamps: Int? = null,
    /** v3: 완성한 스티커판 수 (1 = 볼 램프, 2 = 정수리 램프) */
    val unlock: Int = 0,
)

private val expressionNames = mapOf(
    Expression.CALM to "평온",
    Expression.HAPPY to "기쁨",
    Expression.SURPRISED to "놀람",
    Expression.SLEEPY to "졸림",
    Expression.QUIET to "조용",
)

private val stageNames = mapOf(
    GrowthStage.SPARK to "점등",
    GrowthStage.EYES to "눈뜸",
    GrowthStage.FACE to "얼굴",
    GrowthStage.BODY to "몸",
    GrowthStage.COMPLETE to "완성",
)

@Composable
internal fun Cabin(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(PikoColors.Cabin900)) { content() }
}

/**
 * 정차 화면 공통 골격: 왼쪽 42%는 피코 자리(모든 화면에서 같은 위치·크기 → 화면 전환 때 캐릭터가 점프하지 않음),
 * 오른쪽 58%는 내용.
 */
@Composable
internal fun StageLayout(
    left: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
    right: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Cabin {
        Row(Modifier.fillMaxSize().padding(horizontal = PikoSpace.xl, vertical = PikoSpace.l)) {
            Column(Modifier.weight(0.42f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, content = left)
            Spacer(Modifier.width(PikoSpace.xl))
            Column(Modifier.weight(0.58f).fillMaxHeight(), verticalArrangement = Arrangement.Center, content = right)
        }
    }
}

/** 정차 화면용 피코 (공통 크기) */
@Composable
internal fun androidx.compose.foundation.layout.ColumnScope.StagePiko(
    pet: PetUi,
    expression: Expression = pet.expression,
    scatter: Float = 0f,
    dim: Float = 1f,
) {
    PikoCanvas(
        stage = pet.stage,
        pixelCount = pet.pixelCount,
        lamps = pet.lamps,
        unlock = pet.unlock,
        expression = expression,
        mode = DriveMode.PARKED,
        scatter = scatter,
        dim = dim,
        modifier = Modifier.weight(1f).fillMaxWidth(),
    )
}

// ---------- F0 첫 만남 ----------

/**
 * F0 첫 실행: 피코가 누구인지 → 아이 등록으로 바로 잇는다 (등록해야 좌석·벨트 신호를 아이별로 본다).
 * [onRegister] = 가족 설정으로, [onLater] = 등록 없이 홈으로 (홈에 등록 안내가 남는다).
 */
@Composable
fun F0FirstMeet(step: Int = 0, onNext: () -> Unit = {}, onRegister: () -> Unit = {}, onLater: () -> Unit = {}) {
    StageLayout(
        left = { StagePiko(PetUi(lamps = 0, expression = if (step == 0) Expression.HAPPY else Expression.CALM)) },
        right = {
            if (step == 0) {
                PikoSays("안녕하세요, 피코예요.")
                Spacer(Modifier.height(PikoSpace.m))
                Text("아이 일정과 뒷좌석 안전을 챙기는 비서예요.\n아이들에게는 뒷좌석 친구가 될게요.", style = PikoText.body.copy(color = PikoColors.WarmGray))
                Spacer(Modifier.height(PikoSpace.s))
                Text("운전 중에는 화면 대신 짧은 음성으로만 알려 드려요.", style = PikoText.caption)
                Spacer(Modifier.height(PikoSpace.l))
                PikoButton("시작하기", primary = true, onClick = onNext)
            } else {
                PikoSays("먼저 아이를 알려 주세요.")
                Spacer(Modifier.height(PikoSpace.m))
                Text("별칭·연령대·앉는 자리를 정하면\n벨트·문 신호를 아이별로 확인해요.", style = PikoText.body.copy(color = PikoColors.WarmGray))
                Spacer(Modifier.height(PikoSpace.s))
                Text("이름 대신 별칭만, 모든 정보는 이 차 안에만 저장돼요.", style = PikoText.caption)
                Spacer(Modifier.height(PikoSpace.l))
                Row(horizontalArrangement = Arrangement.spacedBy(PikoSpace.s)) {
                    PikoButton("아이 등록하기", primary = true, onClick = onRegister)
                    PikoButton("나중에", onClick = onLater)
                }
            }
        },
    )
}

// ---------- F3 / F3R / F4 주행 ----------

/**
 * 주행 중 운전석 화면. 텍스트·버튼 없이 피코 얼굴만 (알림은 음성으로).
 * 후진 중엔 피코를 어둡게 하고 표정을 멈춘다.
 */
@Composable
fun F3Driving(pet: PetUi = PetUi(), mode: DriveMode = DriveMode.DRIVING, dim: Float = 1f) {
    val reversing = mode == DriveMode.REVERSING
    Column(
        Modifier.fillMaxSize().background(PikoColors.Cabin950).padding(vertical = PikoSpace.l),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        PikoCanvas(
            stage = pet.stage,
            pixelCount = pet.pixelCount,
            lamps = pet.lamps,
            unlock = pet.unlock,
            expression = if (reversing) Expression.CALM else pet.expression,
            mode = mode,
            dim = if (reversing) dim * 0.5f else dim,
            modifier = Modifier.weight(1f).fillMaxWidth(0.7f),
        )
    }
}

// ---------- F8 설정 ----------

@Composable
internal fun SettingRow(title: String, desc: String, content: @Composable () -> Unit) {
    Column {
        Row(
            Modifier.fillMaxWidth().defaultMinSize(minHeight = PikoSpace.touchMin + 24.dp).padding(vertical = PikoSpace.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = PikoSpace.m)) {
                Text(title, style = PikoText.title.copy(fontSize = PikoText.body.fontSize))
                Spacer(Modifier.height(4.dp))
                Text(desc, style = PikoText.caption)
            }
            content()
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(PikoColors.Line))
    }
}

/** 세그먼트: 선택 = 웜 화이트 채움 + 어두운 글자 (앰버는 쓰지 않는다). 셀 폭 고정이라 행끼리 오른쪽 끝이 맞는다 */
@Composable
internal fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.border(1.5.dp, PikoColors.Line, RoundedCornerShape(PikoSpace.radius))) {
        options.forEachIndexed { i, o ->
            val on = i == selected
            Box(
                Modifier.height(PikoSpace.touchMin).width(140.dp)
                    .background(if (on) PikoColors.WarmWhite else Color.Transparent)
                    .clickable(role = androidx.compose.ui.semantics.Role.RadioButton) { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) {
                Text(o, style = PikoText.label.copy(color = if (on) PikoColors.Cabin950 else PikoColors.WarmGray))
            }
        }
    }
}

@Composable
internal fun BackHeader(title: String, onBack: () -> Unit) {
    Row(
        Modifier.height(PikoSpace.touchMin).clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onBack).padding(end = PikoSpace.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PixelIconView(PixelIcon.Back, PikoColors.WarmWhite)
        Spacer(Modifier.width(PikoSpace.s))
        Text(title, style = PikoText.title)
    }
}

/**
 * F8 설정. 화면에 있는 항목은 모두 실제로 동작한다 (말하기 빈도처럼 엔진에 연결되지 않는 항목은 두지 않음).
 * 왼쪽에 피코 미리보기 — 밝기 설정이 바로 보인다.
 * @param nightDim 0 자동(19~6시) · 1 항상 · 2 끄기
 */
@Composable
fun F8Settings(
    pet: PetUi = PetUi(),
    voiceOn: Boolean = true,
    onVoice: (Boolean) -> Unit = {},
    nightDim: Int = 0,
    onNightDim: (Int) -> Unit = {},
    dim: Float = 1f,
    onFamily: () -> Unit = {},
    onBack: () -> Unit = {},
) {
    StageLayout(
        left = { StagePiko(pet, expression = if (voiceOn) pet.expression else Expression.QUIET, dim = dim) },
        right = {
            BackHeader("설정", onBack)
            Spacer(Modifier.height(PikoSpace.m))
            SettingRow("피코 목소리", "끄면 말하지 않고 표정으로만 반응해요.") {
                Segmented(listOf("켜기", "끄기"), if (voiceOn) 0 else 1) { onVoice(it == 0) }
            }
            SettingRow("밤에는 은은하게", "야간에는 피코를 60% 밝기로 낮춰요.") {
                Segmented(listOf("자동", "항상", "끄기"), nightDim, onNightDim)
            }
            SettingRow("가족", "아이 별칭·연령대·자리, 차도 쪽 문, 보호자 알림.") {
                PikoButton("가족 설정", onClick = onFamily)
            }
        },
    )
}

// ---------- F9 데모 패널 ----------

@Composable
fun F9DemoPanel(
    pet: PetUi = PetUi(),
    simulated: Boolean = true,
    modeLabel: String = "주행",
    beltLeft: Boolean? = null,
    beltRight: Boolean? = null,
    onBelt: (com.pikodrive.core.model.RearSeat, Boolean?) -> Unit = { _, _ -> },
    onDemoFamily: () -> Unit = {},
    onDemoStickers: () -> Unit = {},
    onReset: () -> Unit = {},
    resetArmed: Boolean = false,
    onBack: () -> Unit = {},
) {
    StageLayout(
        left = { StagePiko(pet) },
        right = {
            BackHeader("데모 패널 (시연용)", onBack)
            Spacer(Modifier.height(PikoSpace.xs))
            Text(
                if (simulated) "시뮬레이션 데이터로 동작 중이에요. 실제 차량 연동과 구분돼요."
                else "실제 차량 신호로 동작 중이에요. 이 패널은 시연용 입력만 바꿔요.",
                style = PikoText.caption,
            )
            Spacer(Modifier.height(PikoSpace.s))
            // 에뮬레이터 Virtual Console에 벨트 주입 경로가 없어 대체 입력. 켜 두면 화면에 "시뮬레이션 입력" 배지가 붙는다
            SettingRow("벨트 · 시뮬레이션 입력", "센서 = 실제 SDK 값, 체결/미체결 = 시연용 대체 입력.") {
                Column(verticalArrangement = Arrangement.spacedBy(PikoSpace.xs)) {
                    listOf(com.pikodrive.core.model.RearSeat.LEFT to beltLeft, com.pikodrive.core.model.RearSeat.RIGHT to beltRight).forEach { (seat, v) ->
                        Segmented(
                            listOf(if (seat == com.pikodrive.core.model.RearSeat.LEFT) "왼쪽 센서" else "오른쪽 센서", "체결", "미체결"),
                            when (v) { null -> 0; true -> 1; false -> 2 },
                        ) { i -> onBelt(seat, when (i) { 1 -> true; 2 -> false; else -> null }) }
                    }
                }
            }
            Spacer(Modifier.height(PikoSpace.m))
            Row(horizontalArrangement = Arrangement.spacedBy(PikoSpace.s)) {
                PikoButton("데모 가족·일정 불러오기", modifier = Modifier.weight(1f), onClick = onDemoFamily)
                PikoButton("스티커 9장으로", modifier = Modifier.weight(1f), onClick = onDemoStickers)
                PikoButton(if (resetArmed) "한 번 더 누르면 초기화" else "처음부터 다시", modifier = Modifier.weight(1f), onClick = onReset)
            }
            Spacer(Modifier.height(PikoSpace.l))
            Row(horizontalArrangement = Arrangement.spacedBy(PikoSpace.xl)) {
                Stat(modeLabel, "현재 모드")
                Stat(if (simulated) "SIM" else "실차량", "데이터 출처")
            }
        },
    )
}

/** 상태 표시 배지 (SIM, 시뮬레이션 입력). 앰버를 쓰지 않는다 */
@Composable
fun TagBadge(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier.border(1.5.dp, PikoColors.WarmGray, RoundedCornerShape(2.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(text, style = PikoText.label.copy(color = PikoColors.WarmWhite))
    }
}

@Composable
fun SimBadge(modifier: Modifier = Modifier) = TagBadge("SIM", modifier)
