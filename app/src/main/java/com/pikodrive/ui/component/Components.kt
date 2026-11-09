package com.pikodrive.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pikodrive.ui.theme.PikoColors
import com.pikodrive.ui.theme.PikoSpace
import com.pikodrive.ui.theme.PikoText
import com.pikodrive.ui.theme.PikoType

/**
 * 이모지 대신 쓰는 5×5 픽셀 아이콘. 셀 사이 간격 없이 꽉 찬 블록이라 작은 크기에서도 점선처럼 깨지지 않는다.
 */
enum class PixelIcon(val rows: List<String>) {
    Pet(listOf(".#.#.", "#####", "#####", ".###.", "..#..")),
    Report(listOf("....#", "..#.#", "..#.#", "#.#.#", "#.#.#")),
    Settings(listOf(".#...", "#####", ".....", "...#.", "#####")),
    Back(listOf("...#.", "..#..", ".#...", "..#..", "...#.")),
}

@Composable
fun PixelIconView(icon: PixelIcon, color: Color, modifier: Modifier = Modifier, size: Dp = 30.dp) {
    Canvas(modifier.size(size)) {
        val cell = this.size.width / 5f
        icon.rows.forEachIndexed { r, line ->
            line.forEachIndexed { c, ch ->
                // +0.5px로 이웃 셀과 이어 붙여 안티앨리어싱 틈을 없앤다
                if (ch == '#') drawRect(color, Offset(c * cell, r * cell), Size(cell + 0.5f, cell + 0.5f))
            }
        }
    }
}

/**
 * 차량용 큰 버튼. 76dp 이상, 보조(테두리) / 주요(앰버 채움).
 * 눌림 상태를 밝기로 보여주고, 접근성 역할은 Button.
 */
@Composable
fun PikoButton(
    label: String,
    modifier: Modifier = Modifier,
    icon: PixelIcon? = null,
    primary: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit = {},
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = RoundedCornerShape(PikoSpace.radius)
    val bg = when {
        primary && pressed -> PikoColors.AmberBright
        primary -> PikoColors.Amber
        pressed -> PikoColors.Line
        else -> PikoColors.Cabin700
    }
    val fg = when {
        !enabled -> PikoColors.Mute
        primary -> PikoColors.Cabin950
        else -> PikoColors.WarmWhite
    }
    Row(
        modifier
            .defaultMinSize(minHeight = PikoSpace.touchMin, minWidth = 180.dp)
            .background(bg, shape)
            .border(1.5.dp, if (primary) bg else PikoColors.Line, shape)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = PikoSpace.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            PixelIconView(icon, fg)
            Box(Modifier.size(PikoSpace.s))
        }
        Text(label, style = PikoText.title.copy(color = fg, fontSize = PikoType.body))
    }
}

/**
 * 피코의 대사. 따옴표 대신 왼쪽에 말꼬리 픽셀(앰버 코어 한 칸)을 두고 픽셀 서체로 쓴다.
 */
@Composable
fun PikoSays(text: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.Top) {
        Box(
            Modifier.padding(top = 18.dp).size(16.dp).background(PikoColors.Amber),
        )
        Spacer(Modifier.width(PikoSpace.s))
        Text(text, style = PikoText.pikoLine)
    }
}

/**
 * 세그먼트 픽셀 진행 바. 다음 성장 단계까지의 진행을 "켜진 칸"으로 보여준다.
 */
@Composable
fun PixelProgress(filled: Int, total: Int, modifier: Modifier = Modifier, cellHeight: Dp = 16.dp) {
    Row(modifier.fillMaxWidth().height(cellHeight), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(total) { i ->
            Box(
                Modifier
                    .weight(1f)
                    .height(cellHeight)
                    .background(if (i < filled) PikoColors.PixelLit else PikoColors.Line),
            )
        }
    }
}

/** 큰 숫자 + 라벨. accent = 성장(앰버) */
@Composable
fun Stat(value: String, label: String, modifier: Modifier = Modifier, accent: Boolean = false) {
    Column(modifier) {
        Text(value, style = PikoText.stat.copy(color = if (accent) PikoColors.Amber else PikoColors.WarmWhite))
        Spacer(Modifier.height(2.dp))
        Text(label, style = PikoText.label)
    }
}
