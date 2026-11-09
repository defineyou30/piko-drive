package com.pikodrive.ui.piko

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.Stroke
import com.pikodrive.core.model.DriveMode
import com.pikodrive.core.model.Expression
import com.pikodrive.core.model.GrowthStage
import com.pikodrive.ui.theme.MotionBudget
import com.pikodrive.ui.theme.PikoColors
import kotlinx.coroutines.delay
import kotlin.random.Random

/** 픽셀이 "뚝뚝" 켜지는 계단식 이징 */
fun stepEasing(steps: Int): Easing = Easing { f -> (kotlin.math.floor(f * steps) / steps).coerceAtMost(1f) }

/**
 * 피코 렌더러 (v4 캐릭터: 바이저, design/v4/agent_drafts.html 시안 B).
 *
 * 매끈한 캡슐 몸 + 어두운 바이저 + 그 안의 빛 점 눈. 차량 픽셀 램프와 같은 조명 언어로 표정을 만든다.
 * 이마의 빛 점 10개 = 아이의 스티커판 (가운데부터 한 칸씩 켜짐). 판 완성 해금: 1판 = 볼 램프, 2판 = 귀 램프.
 *
 * 모션 예산(FRAMEWORK 2-1-1)을 여기서 강제한다:
 * - PARKED: 호흡(램프 번짐), 깜빡임, 램프 점등 애니메이션
 * - DRIVING/BOOTING: 정지 화면. 표정만 즉시 교체(간격 제한). 점등은 정차 후 반영
 * - REVERSING: 완전 정지
 *
 * @param lamps 켜진 램프 수 0..[LAMPS]. null이면 v2 성장 픽셀 수로 환산 (아이 프로필이 없을 때)
 * @param unlock 완성한 스티커판 수 (해금 단계)
 * @param scatter 0..1, 놀란 순간 재생용 (리포트 화면에서만 사용)
 * @param dim 야간 모드 휘도 (0..1)
 */
@Composable
fun PikoCanvas(
    stage: GrowthStage,
    pixelCount: Int,
    expression: Expression,
    mode: DriveMode,
    modifier: Modifier = Modifier,
    scatter: Float = 0f,
    dim: Float = 1f,
    lamps: Int? = null,
    unlock: Int = 0,
) {
    val parked = mode == DriveMode.PARKED
    val targetLit = (lamps ?: (pixelCount * LAMPS / GrowthStage.COMPLETE.pixelTarget)).coerceIn(0, LAMPS)

    // 주행 중에는 점등을 동결하고, 정차하면 한 칸씩 켠다
    var shownLit by remember { mutableIntStateOf(targetLit) }
    LaunchedEffect(targetLit, parked) {
        if (!parked) return@LaunchedEffect
        while (shownLit != targetLit) {
            shownLit += if (shownLit < targetLit) 1 else -1
            delay(140)
        }
    }

    // 주행 중 표정 변화는 10초에 1번까지, 후진 중엔 동결 (엔진이 지켜도 렌더러에서 한 번 더 막는다)
    var shownExpression by remember { mutableStateOf(expression) }
    var lastExpressionChangeMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(expression, mode) {
        when (mode) {
            DriveMode.PARKED -> shownExpression = expression
            DriveMode.REVERSING -> Unit
            DriveMode.DRIVING, DriveMode.BOOTING -> {
                // 놀람·졸림은 상황을 알리는 우선 표정: 엔진과 같은 3초 간격 규칙
                val interval = if (expression == Expression.SURPRISED || expression == Expression.SLEEPY) {
                    MotionBudget.PRIORITY_EXPRESSION_INTERVAL_MS
                } else {
                    MotionBudget.DRIVING_EXPRESSION_INTERVAL_MS
                }
                val wait = lastExpressionChangeMs + interval - System.currentTimeMillis()
                if (wait > 0) delay(wait)
                if (shownExpression != expression) {
                    shownExpression = expression
                    lastExpressionChangeMs = System.currentTimeMillis()
                }
            }
        }
    }

    var blinking by remember { mutableStateOf(false) }
    LaunchedEffect(parked, shownExpression) {
        blinking = false
        if (!parked || shownExpression != Expression.CALM) return@LaunchedEffect
        while (true) {
            delay(Random.nextLong(3500, 6500))
            blinking = true
            delay(MotionBudget.BLINK_MS.toLong())
            blinking = false
        }
    }

    // 호흡: 램프 번짐 반경만 숨쉰다 (몸은 움직이지 않음)
    val breath = if (parked) {
        val t = rememberInfiniteTransition(label = "breath")
        t.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            // 계단식 6단계: 매 프레임 다시 그리지 않게 (두 화면에 동시에 떠도 에뮬레이터 부하가 낮도록)
            animationSpec = infiniteRepeatable(tween(MotionBudget.BREATH_PERIOD_MS / 2, easing = stepEasing(6)), RepeatMode.Reverse),
            label = "lamp",
        ).value
    } else {
        0.5f
    }

    Canvas(modifier) {
        val k = minOf(size.width / W, size.height / H)
        val jitter = if (scatter > 0f) scatter * 18f else 0f
        val o = Offset((size.width - W * k) / 2f + jitter, (size.height - H * k) / 2f)
        fun p(x: Float, y: Float) = o + Offset(x * k, y * k)
        fun sz(w: Float, h: Float) = Size(w * k, h * k)
        val e = if (blinking) null else shownExpression
        val lightAlpha = if (e == Expression.SLEEPY || e == Expression.QUIET) 0.55f else 1f

        // 바닥 그림자 · 몸(캡슐) · 위쪽 하이라이트
        drawOval(Ground.dimmed(dim), p(50f, 276f), sz(220f, 20f))
        drawRoundRect(
            Brush.verticalGradient(listOf(BodyTop, BodyMid, BodyBottom), startY = p(0f, 40f).y, endY = p(0f, 270f).y),
            p(40f, 40f), sz(240f, 230f), CornerRadius(96f * k), alpha = dim,
        )
        // 윗면 광택: 몸 모양 안으로 잘라 둥근 모서리 밖으로 삐져나오지 않게 (밖으로 나온 부분이 회색 타원처럼 보였다)
        val bodyShape = Path().apply {
            addRoundRect(RoundRect(Rect(p(40f, 42f), sz(280f, 228f)), CornerRadius(96f * k)))
        }
        clipPath(bodyShape) {
            drawOval(Color.White.copy(alpha = 0.35f * dim), p(92f, 56f), sz(96f, 20f))
        }

        // 이마 빛 점 10개 = 스티커판 (가운데부터)
        LAMP_ORDER.forEachIndexed { rank, j ->
            val x = LAMP_X0 + j * (LAMP + LAMP_GAP)
            val on = rank < shownLit
            if (on) glow(p(x + LAMP / 2, LAMP_Y + LAMP / 2), 14f * k * (1f + 0.3f * breath), 0.30f * dim)
            drawRoundRect((if (on) PikoColors.Amber else LampOff).dimmed(dim), p(x, LAMP_Y), sz(LAMP, LAMP), CornerRadius(2.5f * k))
        }

        // 해금 2: 귀 램프 (바이저 양옆)
        if (unlock >= 2) listOf(46f, 266f).forEach { x ->
            glow(p(x + 4f, 144f), 22f * k * (1f + 0.25f * breath), 0.35f * dim)
            drawRoundRect(PikoColors.Amber.dimmed(dim), p(x, 128f), sz(8f, 32f), CornerRadius(4f * k))
        }

        // 바이저
        drawRoundRect(
            Brush.verticalGradient(listOf(VisorTop, VisorBottom), startY = p(0f, 96f).y, endY = p(0f, 192f).y),
            p(62f, 96f), sz(196f, 96f), CornerRadius(48f * k), alpha = dim,
        )

        // 눈: 바이저 안 빛 점 (4행 x 3열, 표정마다 패턴)
        val pattern = EYES[e] ?: BLINK
        // 패턴 크기가 달라도 눈 중심은 같은 자리 (3열 x 4행 칸의 가운데 정렬)
        val dx = (3 - pattern.maxOf { it.length }) * CELL / 2f
        val dy = (4 - pattern.size) * CELL / 2f
        listOf(EYE_LX, EYE_RX).forEach { x0 ->
            pattern.forEachIndexed { r, row ->
                row.forEachIndexed { c, ch ->
                    if (ch != '#') return@forEachIndexed
                    val tl = p(x0 + dx + c * CELL, EYE_Y + dy + r * CELL)
                    glow(tl + Offset(CELL * k / 2, CELL * k / 2), 9f * k, 0.28f * dim * lightAlpha)
                    drawRoundRect(PixelLight.copy(alpha = lightAlpha).dimmed(dim), tl, sz(CELL - 2f, CELL - 2f), CornerRadius(2f * k))
                }
            }
        }
        // 입: 바이저 아래쪽 작은 빛 점
        MOUTH[e]?.forEachIndexed { r, row ->
            val x0 = 160f - row.length * CELL / 2f
            row.forEachIndexed { c, ch ->
                if (ch == '#') drawRoundRect(PikoColors.Amber.copy(alpha = 0.6f * lightAlpha).dimmed(dim),
                    p(x0 + c * CELL + 1f, 166f + r * CELL), sz(CELL - 4f, CELL - 4f), CornerRadius(2f * k))
            }
        }

        // 볼 (해금 1: 볼 램프)
        listOf(84f, 220f).forEach { x ->
            if (unlock >= 1) glow(p(x + 8f, 215f), 20f * k, 0.30f * dim)
            drawRoundRect((if (unlock >= 1) PikoColors.AmberBright else Cheek).dimmed(dim), p(x, 210f), sz(16f, 10f), CornerRadius(5f * k))
        }
    }
}

/** 이마 빛 점(스티커판) 칸 수 */
const val LAMPS = 10

// 설계 좌표계 320 x 300 (design/v4/agent_drafts.html 시안 B '바이저')
private const val W = 320f
private const val H = 300f
private const val LAMP = 8f
private const val LAMP_GAP = 3.5f
private const val LAMP_Y = 72f
private const val LAMP_X0 = 160f - (LAMPS * LAMP + (LAMPS - 1) * LAMP_GAP) / 2f
/** 가운데 두 칸부터 바깥으로 */
private val LAMP_ORDER = (0 until LAMPS).sortedBy { kotlin.math.abs(it - (LAMPS - 1) / 2f) }

private const val CELL = 12f
private const val EYE_Y = 120f
private const val EYE_LX = 97f
private const val EYE_RX = 187f
private val BLINK = listOf("##")
private val EYES = mapOf(
    Expression.CALM to listOf("##", "##", "##"),
    Expression.HAPPY to listOf(".#.", "#.#"),
    Expression.SURPRISED to listOf("###", "#.#", "#.#", "###"),
    Expression.SLEEPY to listOf("##", ".."),
    Expression.QUIET to listOf("#", "#"),
)
private val MOUTH = mapOf<Expression?, List<String>>(
    Expression.CALM to listOf("##"),
    Expression.HAPPY to listOf("#..#", ".##."),
    Expression.SURPRISED to listOf(".#.", "#.#"),
)

private val BodyTop = Color(0xFFF6F3EE)
private val BodyMid = Color(0xFFDDD8D0)
private val BodyBottom = Color(0xFFB9B3AA)
private val VisorTop = Color(0xFF232933)
private val VisorBottom = Color(0xFF0A0C0F)
private val PixelLight = Color(0xFFFFE1A8)
private val LampOff = Color(0xFFCFC9BF)
private val Cheek = Color(0xFFEF8A72)
private val Ground = Color(0xFF000000).copy(alpha = 0.45f)

private fun DrawScope.glow(center: Offset, radius: Float, alpha: Float) {
    drawCircle(
        brush = Brush.radialGradient(listOf(PikoColors.Amber.copy(alpha = alpha), Color.Transparent), center = center, radius = radius),
        radius = radius,
        center = center,
    )
}

private fun Color.dimmed(dim: Float): Color = if (dim >= 1f) this else copy(alpha = alpha * dim)

