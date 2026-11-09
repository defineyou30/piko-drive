package com.pikodrive.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 디자인 토큰 (FRAMEWORK 2-1).
 * 시그니처 액센트는 램프 앰버 하나. 나머지는 따뜻한 무채색.
 */
object PikoColors {
    // 캐빈 (중성에 가까운 블랙 — v1의 갈색 기운이 Pleos 시스템 UI 옆에서 탁해 보였다)
    val Cabin950 = Color(0xFF0C0C0C)
    val Cabin900 = Color(0xFF121212)
    val Cabin800 = Color(0xFF1C1C1C)
    val Cabin700 = Color(0xFF2A2A2A)
    val Line = Color(0xFF3D3D3D)

    val WarmWhite = Color(0xFFF4EFE6)
    val WarmGray = Color(0xFFB4ACA2)
    val Mute = Color(0xFF6F675F)

    /** 시그니처 램프 앰버. 의미는 "피코의 빛·성장"과 주요 행동(CTA)에만 쓴다 */
    val Amber = Color(0xFFFFB547)
    val AmberBright = Color(0xFFFFD48A)
    val AmberDim = Color(0xFF8A5E22)

    /** v3 안전 보조 알림 전용 (벨트 미체결·문 열림·남김). 앰버(피코의 빛)와 의미를 섞지 않는다 */
    val Alert = Color(0xFFFF7A59)
    val AlertDeep = Color(0xFF3A1A12)

    // 피코 픽셀
    val PixelEye = Color(0xFFFFFBF2)
    val PixelLit = Color(0xFFD9CFC1)
    val PixelHint = WarmWhite.copy(alpha = 0.06f)
    /** 바이저(눈 줄) 띠 */
    val Visor = Color(0xFF1A1714)
}

object PikoSpace {
    val xs = 8.dp
    val s = 16.dp
    val m = 24.dp
    val l = 40.dp
    val xl = 64.dp

    /** 차량 HMI 최소 터치 타깃 */
    val touchMin = 76.dp
    val radius = 4.dp
}

/** 차량 HMI 기준 타입 스케일 (160dpi에서 22sp ≈ 3.5mm, 70cm 시거리) */
object PikoType {
    val display = 56.sp
    val stat = 48.sp
    val title = 32.sp
    val body = 26.sp
    val label = 22.sp
    val caption = 22.sp
}

/** 주행 중 모션 예산 (FRAMEWORK 2-1-1) */
object MotionBudget {
    const val DRIVING_TRANSITION_MS = 300
    const val BREATH_PERIOD_MS = 4000
    const val BLINK_MS = 120
    const val DRIVING_EXPRESSION_INTERVAL_MS = 10_000L
    const val PRIORITY_EXPRESSION_INTERVAL_MS = 3_000L
}
