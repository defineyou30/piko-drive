package com.pikodrive.rear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pikodrive.PikoApp
import com.pikodrive.core.model.DriveMode
import com.pikodrive.core.model.Expression
import com.pikodrive.core.ride.ChildProfile
import com.pikodrive.ui.piko.PikoCanvas
import com.pikodrive.ui.theme.PikoColors
import com.pikodrive.ui.theme.PikoText
import com.pikodrive.ui.theme.PikoTheme
import kotlinx.coroutines.delay

/**
 * 뒷좌석 화면 (아이용). 운전자 화면이 아니라 주행 중에도 피코가 움직이고 말풍선을 보여준다.
 * 부모용 정보(일정·메모·체크)는 여기 없다. 쉿 모드면 피코가 잠든다.
 */
class RearActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as PikoApp
        setContent { PikoTheme { RearScreen(app) } }
    }
}

@Composable
private fun RearScreen(app: PikoApp) {
    val session by app.session.collectAsState()
    val children by app.children.collectAsState()
    val s = session
    Box(Modifier.fillMaxSize().background(PikoColors.Cabin950)) {
        if (s == null) return@Box
        val pet by s.pet.collectAsState()
        val ride by s.rideState.collectAsState()
        val quiet by s.quiet.collectAsState()
        val line by s.kidLine.collectAsState()
        val onboard = ride.onboard.mapNotNull { v -> children.firstOrNull { it.id == v.profile?.id } }
        val focus = onboard.firstOrNull() ?: children.firstOrNull()
        // 말풍선은 8초 보여주고 지운다
        var bubble by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(line) {
            bubble = line?.text
            delay(8_000)
            bubble = null
        }
        Row(Modifier.fillMaxSize().padding(40.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(0.55f), horizontalAlignment = Alignment.CenterHorizontally) {
                PikoCanvas(
                    stage = pet.stage,
                    pixelCount = pet.pixelCount,
                    expression = if (quiet) Expression.SLEEPY else pet.expression,
                    // 아이 화면은 운전 제한 대상이 아니다 → 늘 정차 모션(호흡·깜빡임·점등)
                    mode = DriveMode.PARKED,
                    lamps = focus?.stickersOnBoard,
                    unlock = focus?.boardsCompleted ?: 0,
                    modifier = Modifier.fillMaxWidth().height(520.dp),
                )
            }
            Spacer(Modifier.width(32.dp))
            Column(Modifier.weight(0.45f), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                val text = when {
                    quiet -> "조용히 가는 시간이야. 피코는 눈빛으로 함께할게."
                    bubble != null -> bubble!!
                    onboard.isEmpty() -> "피코가 기다리고 있어."
                    else -> "${onboard.joinToString(", ") { it.alias }} 안녕! 같이 가자."
                }
                Box(
                    Modifier.background(PikoColors.Cabin800, RoundedCornerShape(28.dp)).padding(horizontal = 32.dp, vertical = 28.dp),
                ) {
                    Text(text, style = PikoText.pikoLine.copy(fontSize = 40.sp, lineHeight = 54.sp))
                }
                onboard.filter { it.age.earnsStickers }.forEach { StickerBoard(it) }
            }
        }
    }
}

@Composable
private fun StickerBoard(c: ChildProfile) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "${c.alias}의 스티커 ${c.stickersOnBoard}/${ChildProfile.STICKERS_PER_BOARD}" + when {
                c.boardsCompleted >= 2 -> " · 피코 귀가 반짝여!"
                c.boardsCompleted == 1 -> " · 피코 볼이 반짝여!"
                else -> ""
            },
            style = PikoText.title.copy(fontSize = 30.sp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            repeat(ChildProfile.STICKERS_PER_BOARD) { i ->
                Box(
                    Modifier.weight(1f).height(36.dp).background(
                        if (i < c.stickersOnBoard) PikoColors.Amber else PikoColors.Cabin700, RoundedCornerShape(8.dp),
                    ),
                )
            }
        }
    }
}
