package com.pikodrive.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import com.pikodrive.R

val Pretendard = FontFamily(
    Font(R.font.pretendard_regular, FontWeight.Normal),
    Font(R.font.pretendard_semibold, FontWeight.SemiBold),
    Font(R.font.pretendard_bold, FontWeight.Bold),
)

/** 피코의 대사 전용 픽셀 서체 — 캐릭터와 같은 격자 문법을 글자에도 */
val Galmuri = FontFamily(Font(R.font.galmuri11_bold, FontWeight.Bold))

private fun style(size: TextUnit, weight: FontWeight, color: androidx.compose.ui.graphics.Color, family: FontFamily = Pretendard, tracking: TextUnit = 0.em) =
    TextStyle(fontFamily = family, fontWeight = weight, fontSize = size, color = color, lineHeight = size * 1.35f, letterSpacing = tracking)

object PikoText {
    val display = style(PikoType.display, FontWeight.Bold, PikoColors.WarmWhite, tracking = (-0.015).em)
    val stat = style(PikoType.stat, FontWeight.Bold, PikoColors.WarmWhite, tracking = (-0.015).em)
    val title = style(PikoType.title, FontWeight.SemiBold, PikoColors.WarmWhite)
    val body = style(PikoType.body, FontWeight.Normal, PikoColors.WarmWhite)
    val label = style(PikoType.label, FontWeight.SemiBold, PikoColors.WarmGray)
    val caption = style(PikoType.caption, FontWeight.Normal, PikoColors.WarmGray)

    /**
     * 피코의 대사. 따옴표 대신 픽셀 서체 + 말꼬리 픽셀([com.pikodrive.ui.component.PikoSays])로 화자를 표시한다.
     * 피코는 운전자에게 반말, 시스템 문구는 존댓말 (FRAMEWORK 2-1-2)
     */
    val pikoLine = style(PikoType.display, FontWeight.Bold, PikoColors.WarmWhite, family = Galmuri)
}

@Composable
fun PikoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = PikoColors.Cabin900,
            surface = PikoColors.Cabin800,
            primary = PikoColors.Amber,
            onPrimary = PikoColors.Cabin950,
            onBackground = PikoColors.WarmWhite,
            onSurface = PikoColors.WarmWhite,
        ),
        typography = Typography(bodyLarge = PikoText.body),
        content = content,
    )
}
