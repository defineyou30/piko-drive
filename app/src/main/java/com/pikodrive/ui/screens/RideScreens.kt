package com.pikodrive.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import com.pikodrive.core.model.RearSeat
import com.pikodrive.core.ride.AgeGroup
import com.pikodrive.core.ride.CheckItem
import com.pikodrive.core.ride.CheckStatus
import com.pikodrive.core.ride.ChildProfile
import com.pikodrive.core.ride.LeftBehindStatus
import com.pikodrive.core.ride.RideEvent
import com.pikodrive.core.ride.RideState
import com.pikodrive.core.ride.label
import com.pikodrive.core.ride.unlockLabel
import com.pikodrive.ui.component.PikoButton
import com.pikodrive.ui.theme.PikoColors
import com.pikodrive.ui.theme.PikoSpace
import com.pikodrive.ui.theme.PikoText

@Composable
internal fun StickerRow(p: ChildProfile, modifier: Modifier = Modifier) {
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${p.alias} · ${p.age.label}", style = PikoText.label.copy(color = PikoColors.WarmWhite))
            Text("스티커 ${p.stickersOnBoard}/${ChildProfile.STICKERS_PER_BOARD}" + (unlockLabel(p.boardsCompleted)?.let { " · $it" } ?: ""),
                style = PikoText.label)
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(ChildProfile.STICKERS_PER_BOARD) { i ->
                Box(Modifier.weight(1f).height(16.dp).background(if (i < p.stickersOnBoard) PikoColors.Amber else PikoColors.Cabin700))
            }
        }
    }
}

@Composable
internal fun ItemsCard(arrival: RideEvent.Arrived, ride: RideState) {
    Column(
        Modifier.fillMaxWidth().border(1.5.dp, PikoColors.Line, RoundedCornerShape(PikoSpace.radius)).padding(PikoSpace.s),
        verticalArrangement = Arrangement.spacedBy(PikoSpace.xs),
    ) {
        Text("오늘 챙길 것", style = PikoText.label)
        arrival.items.forEach { (seat, items) ->
            val who = ride.seats.firstOrNull { it.seat == seat }?.profile?.alias ?: seat.label
            Text("$who · ${items.joinToString(", ")}", style = PikoText.title)
        }
    }
}

@Composable
internal fun CheckRow(item: CheckItem) {
    Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
        val mark = Modifier.size(20.dp)
        when (item.status) {
            CheckStatus.OK -> Box(mark.background(PikoColors.WarmGray))
            CheckStatus.ATTENTION -> Box(mark.background(PikoColors.Alert))
            CheckStatus.UNKNOWN -> Box(mark.border(2.dp, PikoColors.Mute))
        }
        Spacer(Modifier.width(PikoSpace.s))
        Text(item.label, style = PikoText.body, modifier = Modifier.weight(1f))
        Text(
            item.detail,
            style = PikoText.body.copy(
                color = when (item.status) {
                    CheckStatus.OK -> PikoColors.WarmGray
                    CheckStatus.ATTENTION -> PikoColors.Alert
                    CheckStatus.UNKNOWN -> PikoColors.Mute
                },
            ),
        )
    }
}

// ---------- F10 뒷좌석 남김 보조 알림 ----------

/**
 * 주행 후 정차에서 운전석이 비었거나 문이 잠겼는데 뒷좌석 착석 신호가 남아 있을 때.
 * 보조 알림임을 화면에 항상 밝히고, 신호를 못 읽으면 "확인 불가"로 말한다 (아이 부재를 보증하지 않음).
 */
@Composable
fun F10LeftBehind(ride: RideState, onAck: () -> Unit) {
    val unknown = ride.leftBehind == LeftBehindStatus.UNKNOWN
    Box(Modifier.fillMaxSize().background(PikoColors.AlertDeep).padding(PikoSpace.xl)) {
        Column(Modifier.align(Alignment.CenterStart).fillMaxWidth(0.7f)) {
            // 맨 위에 '보조 알림'을 먼저: 차량 기본 경고나 안전 보증으로 읽히지 않게
            Text("보조 알림 · 차량 기본 경고를 대신하지 않아요", style = PikoText.title.copy(color = PikoColors.WarmGray))
            Spacer(Modifier.height(PikoSpace.s))
            Text(if (unknown) "뒷좌석 신호를 읽을 수 없어요" else "뒷좌석을 확인해 주세요",
                style = PikoText.display.copy(color = PikoColors.Alert, fontSize = PikoText.display.fontSize * 1.3f))
            Spacer(Modifier.height(PikoSpace.m))
            if (unknown) {
                Text("착석 센서 값이 들어오지 않아요. 직접 뒷좌석을 봐 주세요.", style = PikoText.title)
            } else {
                ride.onboard.forEach { v ->
                    Text("${v.seat.label}${v.profile?.alias?.let { " · $it" } ?: ""} — 착석 신호 있음", style = PikoText.title)
                }
            }
            Spacer(Modifier.height(PikoSpace.l))
            Text("차에서 멀어지기 전에 뒷좌석을 직접 확인해 주세요.", style = PikoText.body)
            Spacer(Modifier.height(PikoSpace.m))
            // "확인했어요"는 실제로 봤다는 뜻으로 읽혀서, 하는 일 그대로 "알림 닫기" (reviews/codex-v4-copy-flow.md)
            PikoButton("알림 닫기", primary = true, modifier = Modifier.width(420.dp), onClick = onAck)
            Spacer(Modifier.height(PikoSpace.l))
            Text("보조 알림이에요. 차량 센서 신호로만 판단하며 아이의 존재나 부재를 보증하지 않아요.\n차량 기본 경고를 대신하지 않아요.",
                style = PikoText.caption)
        }
    }
}

// ---------- F11 가족 (아이 프로필) ----------

/**
 * 아이 프로필 최대 2명 + 차도 쪽 방향. 정차 중에만 열린다.
 * 실명 대신 별칭, 학원명 대신 준비물 항목명만 받는다 (화면에 명시).
 */
@Composable
fun F11Family(
    children: List<ChildProfile>,
    streetSideLeft: Boolean,
    onStreetSide: (Boolean) -> Unit,
    onChange: (List<ChildProfile>) -> Unit,
    onBack: () -> Unit,
    guardianOn: Boolean = false,
    onGuardian: (Boolean) -> Unit = {},
) {
    var selected by remember { mutableIntStateOf(0) }
    Cabin {
        Column(Modifier.fillMaxSize().padding(horizontal = PikoSpace.xl, vertical = PikoSpace.l).verticalScroll(rememberScrollState())) {
            BackHeader("가족", onBack)
            Text("이름 대신 별칭만 저장해요. 모든 정보는 이 차 안에만 있어요.", style = PikoText.caption)
            Spacer(Modifier.height(PikoSpace.m))
            if (children.isEmpty()) {
                Text("아직 등록된 아이가 없어요. '+ 아이 추가'로 시작해요.", style = PikoText.title)
                Spacer(Modifier.height(PikoSpace.s))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(PikoSpace.s), verticalAlignment = Alignment.CenterVertically) {
                children.forEachIndexed { i, c ->
                    PikoButton("${c.alias} · ${c.seat.label}", primary = i == selected, onClick = { selected = i })
                }
                if (children.size < ChildProfile.MAX_CHILDREN) {
                    PikoButton("+ 아이 추가", onClick = {
                        val seat = if (children.any { it.seat == RearSeat.LEFT }) RearSeat.RIGHT else RearSeat.LEFT
                        onChange(children + ChildProfile("c${System.currentTimeMillis()}", "아이${children.size + 1}", AgeGroup.SCHOOL, seat))
                        selected = children.size
                    })
                }
            }
            Spacer(Modifier.height(PikoSpace.m))
            children.getOrNull(selected)?.let { c ->
                ChildEditor(c, onChange = { n -> onChange(children.map { if (it.id == c.id) n else it }) },
                    onDelete = { onChange(children.filterNot { it.id == c.id }); selected = 0 })
            }
            Spacer(Modifier.height(PikoSpace.m))
            SettingRow("차도 쪽 문", "정차 중 이쪽 뒷문이 열리면 반대쪽으로 내리도록 알려요. 길가 정차 기준 한국은 왼쪽.") {
                Segmented(listOf("왼쪽", "오른쪽"), if (streetSideLeft) 0 else 1) { onStreetSide(it == 0) }
            }
            SettingRow("다른 보호자에게 알림", "출발·도착·하차·뒷좌석 남김을 알려요. 보내는 건 별칭과 좌석, 시각뿐이에요. 위치는 보내지 않아요.") {
                Segmented(listOf("켜기", "끄기"), if (guardianOn) 0 else 1) { onGuardian(it == 0) }
            }
        }
    }
}

@Composable
private fun ChildEditor(c: ChildProfile, onChange: (ChildProfile) -> Unit, onDelete: () -> Unit) {
    var armed by remember(c.id) { mutableStateOf(false) }
    SettingRow("별칭", "피코와 알림이 이 이름으로 불러요.") {
        PikoTextField(c.alias, { onChange(c.copy(alias = it.take(8))) }, Modifier.width(420.dp))
    }
    SettingRow("연령대", "피코의 말투와 앞자리 체크 항목이 바뀌어요. 중고등은 피코가 기본으로 꺼져요.") {
        Segmented(AgeGroup.entries.map { it.label }, c.age.ordinal) { i ->
            val age = AgeGroup.entries[i]
            onChange(c.copy(age = age, pikoEnabled = age.pikoDefault))
        }
    }
    SettingRow("뒷좌석 자리", "이 자리의 착석·벨트 신호를 이 아이로 봐요.") {
        Segmented(listOf("왼쪽", "오른쪽"), c.seat.ordinal) { onChange(c.copy(seat = RearSeat.entries[it])) }
    }
    SettingRow("피코", "끄면 이 아이에게는 말하지 않아요. 앞자리 체크는 그대로예요.") {
        Segmented(listOf("켜기", "끄기"), if (c.pikoEnabled) 0 else 1) { onChange(c.copy(pikoEnabled = it == 0)) }
    }
    Text("준비물은 일정에서 정해요. 도착하면 그 무렵 일정의 준비물을 알려 드려요.", style = PikoText.caption)
    Spacer(Modifier.height(PikoSpace.s))
    PikoButton(if (armed) "한 번 더 누르면 삭제" else "이 아이 삭제", onClick = { if (armed) onDelete() else armed = true })
}

@Composable
internal fun PikoTextField(value: String, onValue: (String) -> Unit, modifier: Modifier = Modifier) {
    // 쉼표 입력 중 공백이 사라지지 않도록 화면 값은 따로 들고 있는다
    var text by remember(value.isEmpty()) { mutableStateOf(value) }
    BasicTextField(
        value = text,
        onValueChange = { text = it; onValue(it) },
        singleLine = true,
        textStyle = PikoText.title,
        cursorBrush = SolidColor(PikoColors.Amber),
        modifier = modifier.height(PikoSpace.touchMin).border(1.5.dp, PikoColors.Line, RoundedCornerShape(PikoSpace.radius))
            .padding(horizontal = PikoSpace.s, vertical = PikoSpace.s),
    )
}
