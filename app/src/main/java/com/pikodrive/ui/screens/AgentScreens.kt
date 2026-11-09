package com.pikodrive.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.pikodrive.core.agent.AgentBriefing
import com.pikodrive.core.agent.AgentLines
import com.pikodrive.core.agent.Memo
import com.pikodrive.core.agent.MemoTrigger
import com.pikodrive.core.agent.SafetyKind
import com.pikodrive.core.agent.SafetyRecord
import com.pikodrive.core.agent.ScheduleEntry
import com.pikodrive.core.model.DriveMode
import com.pikodrive.core.ride.CheckStatus
import com.pikodrive.core.ride.ChildProfile
import com.pikodrive.core.ride.RideEvent
import com.pikodrive.core.ride.RidePhase
import com.pikodrive.core.ride.RideState
import com.pikodrive.core.ride.label
import com.pikodrive.ui.component.PikoButton
import com.pikodrive.ui.component.PixelIcon
import com.pikodrive.ui.piko.PikoCanvas
import com.pikodrive.ui.theme.PikoColors
import com.pikodrive.ui.theme.PikoSpace
import com.pikodrive.ui.theme.PikoText
import java.time.DayOfWeek
import java.time.LocalTime

// ---------- F1A 앞 화면: 아이 일정·안전 비서 ----------

/**
 * 정차 중 운전석 화면 = 부모용 브리핑. 아이용 화면은 뒷좌석([com.pikodrive.rear.RearActivity]).
 * 3초 안에 봐야 할 순서대로 (reviews/codex-v4-copy-flow.md 3장):
 * 맨 위 한 줄 = 지금 할 일 → 왼쪽 뒷좌석 상태 → 가운데 일정 → 오른쪽 메모·오늘 기록·스티커.
 */
@Composable
fun F1Agent(
    pet: PetUi,
    briefing: AgentBriefing,
    ride: RideState,
    arrival: RideEvent.Arrived?,
    children: List<ChildProfile>,
    safetyToday: List<SafetyRecord>,
    rearScreen: Boolean,
    quiet: Boolean,
    onQuiet: (Boolean) -> Unit,
    onSchedule: () -> Unit,
    onMemo: () -> Unit,
    onFamily: () -> Unit,
    onSettings: () -> Unit,
    onAgentTap: () -> Unit,
    dim: Float = 1f,
) {
    Cabin {
        Column(Modifier.fillMaxSize().padding(horizontal = PikoSpace.l, vertical = PikoSpace.m)) {
            NowStrip(pet, briefing, ride, arrival, children, rearScreen, onFamily, onAgentTap, dim)
            Spacer(Modifier.height(PikoSpace.m))
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(PikoSpace.l)) {
                Column(Modifier.weight(0.38f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(PikoSpace.xs)) {
                    RearPanel(ride, arrival, children, onFamily)
                }
                Column(Modifier.weight(0.34f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(PikoSpace.s)) {
                    NextScheduleCard(briefing, children)
                    SectionTitle("오늘 일정")
                    if (briefing.today.isEmpty()) Text("오늘 등록된 일정이 없어요", style = PikoText.caption)
                    briefing.today.forEach { v ->
                        ListRow(
                            lead = AgentLines.hm(v.entry.time),
                            text = "${alias(v.entry.childId, children)}${v.entry.title}",
                            sub = v.entry.items.joinToString(", "),
                            dim = v.past,
                        )
                    }
                }
                Column(Modifier.weight(0.28f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(PikoSpace.s)) {
                    SectionTitle("메모 ${briefing.pendingMemos.size}")
                    if (briefing.pendingMemos.isEmpty()) Text("정한 순간에 꺼내 줄 메모를 남겨 보세요", style = PikoText.caption)
                    briefing.pendingMemos.forEach { m ->
                        ListRow(lead = memoWhen(m), text = m.text, sub = (if (m.repeat) "매번" else "한 번") + alias(m.childId, children).let { if (it.isBlank()) "" else " · ${it.trim()}" })
                    }
                    SafetyCard(safetyToday)
                    children.filter { it.age.earnsStickers }.forEach { StickerRow(it) }
                }
            }
            Spacer(Modifier.height(PikoSpace.s))
            Row(horizontalArrangement = Arrangement.spacedBy(PikoSpace.s)) {
                PikoButton("일정", modifier = Modifier.weight(1f), onClick = onSchedule)
                PikoButton("메모", modifier = Modifier.weight(1f), onClick = onMemo)
                PikoButton("가족", modifier = Modifier.weight(1f), onClick = onFamily)
                if (ride.onboard.isNotEmpty()) {
                    PikoButton(if (quiet) "쉿 모드 끄기" else "쉿 모드", icon = PixelIcon.Pet, primary = quiet,
                        modifier = Modifier.weight(1f), onClick = { onQuiet(!quiet) })
                }
                PikoButton("설정", icon = PixelIcon.Settings, modifier = Modifier.weight(1f), onClick = onSettings)
            }
        }
    }
}

/** 맨 위 한 줄: 지금 부모가 할 일 하나. 우선순위 = 아이 미등록 > 확인 필요 > 도착 준비물 > 곧 일정 > 정상 > 다음 일정 */
@Composable
private fun NowStrip(
    pet: PetUi,
    b: AgentBriefing,
    ride: RideState,
    arrival: RideEvent.Arrived?,
    children: List<ChildProfile>,
    rearScreen: Boolean,
    onFamily: () -> Unit,
    onAgentTap: () -> Unit,
    dim: Float,
) {
    val attention = ride.checklist.filter { it.status == CheckStatus.ATTENTION }
    val unknown = ride.checklist.count { it.status == CheckStatus.UNKNOWN }
    val next = b.next
    val left = b.minutesToNext
    val (headline, sub, warn) = when {
        children.isEmpty() -> Triple("아이를 등록해 주세요", "등록하면 뒷좌석 착석·벨트를 아이별로 확인해요", false)
        ride.onboard.isNotEmpty() && ride.phase != RidePhase.ARRIVED && attention.isNotEmpty() ->
            Triple("확인 필요 · ${attention.first().label} ${attention.first().detail}",
                if (attention.size > 1) "그 밖에 ${attention.drop(1).joinToString { it.label }}" else "차량 신호 기준이에요", true)
        arrival != null && arrival.items.isNotEmpty() ->
            Triple("도착 · 오늘 챙길 것", arrival.items.entries.joinToString(" / ") { (seat, items) ->
                "${ride.seats.firstOrNull { it.seat == seat }?.profile?.alias ?: seat.label} ${items.joinToString(", ")}" }, false)
        next != null && left != null && left <= next.leadMinutes * 2 ->
            Triple("${left}분 뒤 ${AgentLines.hm(next.time)} ${alias(next.childId, children)}${next.title}",
                next.items.takeIf { it.isNotEmpty() }?.let { "준비물 ${it.joinToString(", ")}" } ?: "준비물 없음", false)
        ride.onboard.isNotEmpty() ->
            Triple("확인된 항목은 모두 정상이에요", if (unknown > 0) "확인 불가 ${unknown}개 · 센서 값이 없는 항목은 직접 봐 주세요" else "차량 신호 기준이에요", false)
        next != null -> Triple("다음 일정 ${AgentLines.hm(next.time)} ${alias(next.childId, children)}${next.title}", "", false)
        else -> Triple("오늘 남은 일정이 없어요", "", false)
    }
    Row(
        Modifier.fillMaxWidth().background(if (warn) PikoColors.AlertDeep else PikoColors.Cabin800, RoundedCornerShape(12.dp))
            .padding(horizontal = PikoSpace.m, vertical = PikoSpace.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PikoCanvas(
            stage = pet.stage, pixelCount = pet.pixelCount, expression = pet.expression, mode = DriveMode.PARKED,
            lamps = pet.lamps, unlock = pet.unlock, dim = dim,
            modifier = Modifier.size(120.dp).clickable(onClick = onAgentTap),
        )
        Spacer(Modifier.width(PikoSpace.m))
        Column(Modifier.weight(1f)) {
            // 이름 5번 탭 = 숨은 데모 패널 (시연용)
            Text(
                "피코 · 아이 일정·안전 비서" + if (rearScreen) " · 아이들은 뒷좌석 화면에서 함께해요" else " · 뒷좌석에는 음성으로 함께해요",
                style = PikoText.caption, modifier = Modifier.clickable(onClick = onAgentTap),
            )
            Text(headline, style = PikoText.title.copy(color = if (warn) PikoColors.Alert else PikoColors.WarmWhite))
            if (sub.isNotBlank()) Text(sub, style = PikoText.body.copy(color = PikoColors.WarmGray))
        }
        if (children.isEmpty()) PikoButton("아이 등록", primary = true, onClick = onFamily)
    }
}

private fun alias(childId: String?, children: List<ChildProfile>) =
    childId?.let { id -> children.firstOrNull { it.id == id }?.alias?.let { "$it " } } ?: ""

/** 메모 시점 표시는 편집 화면과 같은 말(MemoTrigger.label)을 쓴다. 시각만 실제 시각으로 */
private fun memoWhen(m: Memo) = if (m.trigger == MemoTrigger.TIME) m.time?.let(AgentLines::hm) ?: m.trigger.label else m.trigger.label

@Composable
private fun SectionTitle(text: String) {
    Spacer(Modifier.height(PikoSpace.xs))
    Text(text, style = PikoText.label)
}

@Composable
private fun NextScheduleCard(b: AgentBriefing, children: List<ChildProfile>) {
    val next = b.next
    Column(
        Modifier.fillMaxWidth().background(PikoColors.Cabin800, RoundedCornerShape(12.dp)).padding(PikoSpace.m),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("다음 일정", style = PikoText.label)
        if (next == null) {
            Text("오늘 남은 일정이 없어요", style = PikoText.title)
        } else {
            Text("${AgentLines.hm(next.time)}  ${alias(next.childId, children)}${next.title}", style = PikoText.display.copy(fontSize = PikoText.stat.fontSize))
            val left = b.minutesToNext ?: 0
            val leftText = if (left >= 60) "${left / 60}시간 ${left % 60}분 뒤" else "${left}분 뒤"
            Text(leftText + (next.items.takeIf { it.isNotEmpty() }?.let { " · 준비물 ${it.joinToString(", ")}" } ?: ""),
                style = PikoText.body.copy(color = if (left <= next.leadMinutes) PikoColors.Amber else PikoColors.WarmGray))
        }
    }
}

@Composable
private fun ListRow(lead: String, text: String, sub: String, dim: Boolean = false) {
    val c = if (dim) PikoColors.Mute else PikoColors.WarmWhite
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        Text(lead, style = PikoText.label.copy(color = if (dim) PikoColors.Mute else PikoColors.Amber), modifier = Modifier.width(120.dp))
        Column(Modifier.weight(1f)) {
            Text(text, style = PikoText.body.copy(color = c))
            if (sub.isNotBlank()) Text(sub, style = PikoText.caption.copy(color = if (dim) PikoColors.Mute else PikoColors.WarmGray))
        }
    }
}

@Composable
private fun ColumnScope.RearPanel(ride: RideState, arrival: RideEvent.Arrived?, children: List<ChildProfile>, onFamily: () -> Unit) {
    Text("뒷좌석 · 차량 신호로 확인", style = PikoText.label)
    if (children.isEmpty()) {
        Text("등록된 아이가 없어요", style = PikoText.title)
        Text("아이별 별칭·연령대·자리를 정하면 착석·벨트를 아이별로 확인해요", style = PikoText.caption)
        Spacer(Modifier.height(PikoSpace.xs))
        PikoButton("아이 등록", onClick = onFamily)
        return
    }
    if (ride.onboard.isEmpty()) {
        Text("비어 있어요", style = PikoText.title)
        Text("아이가 타면 벨트·문·잠금·창문을 차량 신호로 확인해요", style = PikoText.caption)
        return
    }
    val attention = ride.attentionCount
    val unknown = ride.checklist.count { it.status == CheckStatus.UNKNOWN }
    // "출발 준비 완료"는 센서가 못 읽은 항목까지 괜찮다는 보증처럼 들려서 쓰지 않는다
    val title = when {
        ride.phase == RidePhase.ARRIVED -> "도착 · 내리기 전에 문 쪽을 봐 주세요"
        attention > 0 -> "확인 필요 ${attention}개" + if (unknown > 0) " · 확인 불가 ${unknown}개" else ""
        unknown > 0 -> "확인된 항목은 정상 · 확인 불가 ${unknown}개"
        else -> "확인된 항목은 모두 정상이에요"
    }
    val warn = attention > 0 && ride.phase != RidePhase.ARRIVED
    Text(title, style = PikoText.title.copy(color = if (warn) PikoColors.Alert else PikoColors.WarmWhite))
    Spacer(Modifier.height(PikoSpace.xs))
    if (arrival != null && arrival.items.isNotEmpty()) {
        ItemsCard(arrival, ride)
        Spacer(Modifier.height(PikoSpace.xs))
    }
    ride.checklist.forEach { CheckRow(it) }
}

@Composable
private fun SafetyCard(records: List<SafetyRecord>) {
    Column(
        Modifier.fillMaxWidth().border(1.5.dp, PikoColors.Line, RoundedCornerShape(PikoSpace.radius)).padding(PikoSpace.s),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("오늘 안전 기록", style = PikoText.label)
        if (records.isEmpty()) {
            Text("보조 알림이 울린 일이 없어요", style = PikoText.body)
        } else {
            records.groupingBy { it.kind }.eachCount().toSortedMap(compareBy { it.ordinal }).forEach { (k, n) ->
                Row(Modifier.fillMaxWidth()) {
                    Text(k.label, style = PikoText.body, modifier = Modifier.weight(1f))
                    Text("${n}회", style = PikoText.body.copy(color = if (k == SafetyKind.LEFT_BEHIND) PikoColors.Alert else PikoColors.WarmGray))
                }
            }
        }
    }
}
