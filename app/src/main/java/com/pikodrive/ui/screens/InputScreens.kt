package com.pikodrive.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.pikodrive.core.agent.AgentLines
import com.pikodrive.core.agent.InputPresets
import com.pikodrive.core.agent.Memo
import com.pikodrive.core.agent.MemoTrigger
import com.pikodrive.core.agent.ScheduleEntry
import com.pikodrive.core.agent.VoiceParser
import com.pikodrive.core.ride.ChildProfile
import com.pikodrive.ui.component.PikoButton
import com.pikodrive.ui.theme.PikoColors
import com.pikodrive.ui.theme.PikoSpace
import com.pikodrive.ui.theme.PikoText
import com.pikodrive.voice.VoiceInput
import com.pikodrive.voice.VoiceState
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/*
 * 입력 화면 v5 (design/v5/input_redesign.png): 고르면 끝나는 입력.
 * - 맨 위 문장 요약 = 저장될 내용 그대로
 * - 자주 쓰는 값은 칩, 키보드는 "+ 직접 입력"일 때만
 * - 정차 중 "말로 입력": 짧게 말하면 알아들은 칸만 채우고 나머지는 칩으로 (Pleos STT, qa/v4/stt-probe.md)
 * - 초안: 저장을 눌러야 목록에 들어간다, 취소하면 사라진다
 */

private val dayOrder = DayOfWeek.entries.toList()
private val dayLabel = listOf("월", "화", "수", "목", "금", "토", "일")
private val weekdays = dayOrder.take(5).toSet()
private val weekend = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)

private fun daysText(d: Set<DayOfWeek>) = when {
    d.size == 7 -> "매일"
    d == weekdays -> "평일"
    d == weekend -> "주말"
    else -> dayOrder.filter { it in d }.joinToString("·") { dayLabel[dayOrder.indexOf(it)] }
}

/** 받침이 있으면 [withBatchim], 없으면 [without] (지우가 / 콩이가 → 별칭 끝 글자 기준) */
private fun josa(word: String, withBatchim: String, without: String): String {
    val c = word.lastOrNull() ?: return without
    return if (c in '가'..'힣' && (c - '가') % 28 != 0) withBatchim else without
}

// ---------- F12 일정 ----------

@Composable
fun F12Schedule(
    schedules: List<ScheduleEntry>,
    children: List<ChildProfile>,
    voice: VoiceInput,
    onChange: (List<ScheduleEntry>) -> Unit,
    onBack: () -> Unit,
) {
    var draft by remember { mutableStateOf<ScheduleEntry?>(null) }
    var isNew by remember { mutableStateOf(false) }
    var heard by remember { mutableStateOf<String?>(null) }
    fun newDraft() = ScheduleEntry(
        "s${System.currentTimeMillis()}", children.firstOrNull()?.id, setOf(LocalDate.now().dayOfWeek), LocalTime.of(15, 0), "",
    )
    val vs by voice.state.collectAsState()
    LaunchedEffect(vs) {
        val v = vs
        if (v is VoiceState.Heard) {
            val g = VoiceParser.parseSchedule(v.text, children)
            val base = draft ?: newDraft().also { isNew = true }
            val title = g.title ?: base.title
            draft = base.copy(
                days = g.days ?: base.days, time = g.time ?: base.time, childId = g.childId ?: base.childId,
                title = title, items = g.items.ifEmpty { base.items }, leadMinutes = g.leadMinutes ?: base.leadMinutes,
            )
            heard = "\"${v.text}\" → ${g.filledCount}칸 채웠어요. 틀린 칸은 아래에서 골라 고쳐 주세요."
            voice.reset()
        }
    }
    EditorShell(
        title = "일정",
        onBack = { voice.cancel(); onBack() },
        list = {
            // 새로 만들기는 목록 맨 위에 고정 (저장으로 목록이 늘어도 자리가 바뀌지 않게)
            PikoButton("+ 새 일정", modifier = Modifier.fillMaxWidth(), onClick = { draft = newDraft(); isNew = true; heard = null })
            schedules.sortedBy { it.time }.forEach { e ->
                SavedRow(
                    title = "${daysText(e.days)} ${AgentLines.hm(e.time)}  ${children.firstOrNull { it.id == e.childId }?.alias?.plus(" ") ?: ""}${e.title}",
                    sub = e.items.joinToString(", ").ifBlank { "준비물 없음" },
                    selected = draft?.id == e.id,
                ) { draft = e; isNew = false; heard = null }
            }
            if (schedules.isEmpty()) Text("아직 일정이 없어요", style = PikoText.caption)
        },
        footer = {
            val e = draft ?: return@EditorShell
            SaveRow(
                canSave = e.title.isNotBlank(),
                isNew = isNew,
                onSave = {
                    onChange(if (isNew) schedules + e else schedules.map { if (it.id == e.id) e else it })
                    draft = null; heard = null
                },
                onCancel = { draft = null; heard = null },
                onDelete = { onChange(schedules.filterNot { it.id == e.id }); draft = null },
            )
        },
        editor = {
            VoiceBar(vs, heard, hint = "예) \"수요일 오후 3시 40분 지우 태권도\"", onStart = { voice.start() }, onCancel = { voice.cancel() })
            val e = draft
            if (e == null) {
                Text("왼쪽에서 일정을 고르거나 새로 만들어요. 말로 하면 새 일정으로 채워 드려요.", style = PikoText.body.copy(color = PikoColors.WarmGray))
                return@EditorShell
            }
            val child = children.firstOrNull { it.id == e.childId }
            Summary(
                buildAnnotatedString {
                    if (e.days.size < 7) append("매주 ")
                    amber(daysText(e.days)); append(" "); amber(AgentLines.hm(e.time)); append(", ")
                    amber((child?.alias?.plus(" ") ?: "") + e.title.ifBlank { "무엇인지 골라 주세요" })
                    append("\n")
                    if (e.items.isNotEmpty()) { append("준비물 "); amber(e.items.joinToString(", ")); append(" · ") }
                    amber("${e.leadMinutes}분 전"); append("에 알려 드려요")
                },
            )
            Field("누구") {
                KidCards(children, e.childId, allowAll = true, allLabel = "둘 다") { draft = e.copy(childId = it) }
            }
            Field("무엇") {
                ChipFlow(InputPresets.titles, selected = setOf(e.title), custom = true) { t ->
                    // 제목을 바꾸면 준비물 후보도 바뀐다: 이전 제목의 기본 준비물을 그대로 두지 않음
                    draft = e.copy(title = t, items = if (e.items.isEmpty()) emptyList() else e.items.filter { it in InputPresets.itemsFor(t, child?.age) })
                }
            }
            Field("요일") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("평일", e.days == weekdays) { draft = e.copy(days = weekdays) }
                    Chip("주말", e.days == weekend) { draft = e.copy(days = weekend) }
                    Chip("매일", e.days.size == 7) { draft = e.copy(days = dayOrder.toSet()) }
                    Spacer(Modifier.width(PikoSpace.s))
                    dayOrder.forEachIndexed { i, d ->
                        Chip(dayLabel[i], d in e.days, small = true) {
                            draft = e.copy(days = if (d in e.days && e.days.size > 1) e.days - d else e.days + d)
                        }
                    }
                }
            }
            Field("시각 — 시 한 번, 분 한 번") { TimeChips(e.time) { draft = e.copy(time = it) } }
            Field("준비물") {
                val options = (InputPresets.itemsFor(e.title, child?.age) + e.items).distinct()
                ChipFlow(options, selected = e.items.toSet(), custom = true) { it ->
                    draft = e.copy(items = if (it in e.items) e.items - it else (e.items + it).take(4))
                }
            }
            Field("미리 알림") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(10, 15, 30).forEach { m -> Chip("${m}분 전", e.leadMinutes == m) { draft = e.copy(leadMinutes = m) } }
                }
            }
        },
    )
}

// ---------- F13 메모 ----------

@Composable
fun F13Memo(
    memos: List<Memo>,
    children: List<ChildProfile>,
    voice: VoiceInput,
    onChange: (List<Memo>) -> Unit,
    onBack: () -> Unit,
) {
    var draft by remember { mutableStateOf<Memo?>(null) }
    var isNew by remember { mutableStateOf(false) }
    var heard by remember { mutableStateOf<String?>(null) }
    fun newDraft() = Memo("m${System.currentTimeMillis()}", "", MemoTrigger.ALIGHT, repeat = true)
    val vs by voice.state.collectAsState()
    LaunchedEffect(vs) {
        val v = vs
        if (v is VoiceState.Heard) {
            val g = VoiceParser.parseMemo(v.text, children)
            val base = draft ?: newDraft().also { isNew = true }
            draft = base.copy(
                trigger = g.trigger ?: base.trigger, time = g.time ?: base.time, childId = g.childId ?: base.childId,
                repeat = g.repeat ?: base.repeat, text = g.text ?: base.text,
            )
            heard = "\"${v.text}\" → 알아들은 칸을 채웠어요. 틀린 칸은 아래에서 골라 고쳐 주세요."
            voice.reset()
        }
    }
    EditorShell(
        title = "메모",
        onBack = { voice.cancel(); onBack() },
        list = {
            // 새로 만들기는 목록 맨 위에 고정 (저장으로 목록이 늘어도 자리가 바뀌지 않게)
            PikoButton("+ 새 메모", modifier = Modifier.fillMaxWidth(), onClick = { draft = newDraft(); isNew = true; heard = null })
            memos.forEach { m ->
                SavedRow(
                    title = m.text,
                    sub = whenText(m, children) + (if (m.repeat) " · 매번" else " · 한 번") + if (m.done) " · 지난 메모" else "",
                    selected = draft?.id == m.id,
                    dim = m.done,
                ) { draft = m; isNew = false; heard = null }
            }
            if (memos.isEmpty()) Text("아직 메모가 없어요", style = PikoText.caption)
        },
        footer = {
            val m = draft ?: return@EditorShell
            SaveRow(
                canSave = m.text.isNotBlank(),
                isNew = isNew,
                onSave = {
                    val saved = m.copy(done = false)
                    onChange(if (isNew) memos + saved else memos.map { if (it.id == m.id) saved else it })
                    draft = null; heard = null
                },
                onCancel = { draft = null; heard = null },
                onDelete = { onChange(memos.filterNot { it.id == m.id }); draft = null },
            )
        },
        editor = {
            VoiceBar(vs, heard, hint = "예) \"콩이가 내릴 때 매번 문 닫혔는지 확인\"", onStart = { voice.start() }, onCancel = { voice.cancel() })
            val m = draft
            if (m == null) {
                Text("왼쪽에서 메모를 고르거나 새로 만들어요.", style = PikoText.body.copy(color = PikoColors.WarmGray))
                return@EditorShell
            }
            Summary(
                buildAnnotatedString {
                    amber(whenText(m, children) + if (m.repeat) "마다" else " 한 번")
                    append("\n\""); amber(m.text.ifBlank { "무엇을 알려 드릴지 골라 주세요" }); append("\"라고 말해 드려요")
                },
                sub = "운전 중엔 음성으로만, 정차 중엔 화면에도 보여요",
            )
            Field("언제") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MemoTrigger.entries.forEach { t ->
                        BigChoice(t.label, m.trigger == t, Modifier.weight(1f)) {
                            draft = m.copy(trigger = t, time = if (t == MemoTrigger.TIME) m.time ?: LocalTime.of(8, 0) else null)
                        }
                    }
                }
            }
            if (m.trigger == MemoTrigger.TIME) Field("시각") { TimeChips(m.time ?: LocalTime.of(8, 0)) { draft = m.copy(time = it) } }
            Field("누구") { KidCards(children, m.childId, allowAll = true, allLabel = "누구든") { draft = m.copy(childId = it) } }
            Field("무엇") {
                val options = (InputPresets.memoTexts[m.trigger].orEmpty() + listOfNotNull(m.text.ifBlank { null })).distinct()
                ChipFlow(options, selected = setOf(m.text), custom = true) { draft = m.copy(text = it) }
            }
            Field("얼마나") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("이번 한 번만", !m.repeat) { draft = m.copy(repeat = false) }
                    Chip("매번", m.repeat) { draft = m.copy(repeat = true) }
                }
            }
        },
    )
}

private fun whenText(m: Memo, children: List<ChildProfile>): String {
    val who = children.firstOrNull { it.id == m.childId }?.alias
    val subject = who?.let { "$it${josa(it, "이가", "가")} " } ?: ""
    return when (m.trigger) {
        MemoTrigger.BOARD -> "${subject}탈 때"
        MemoTrigger.DEPART -> "출발할 때"
        MemoTrigger.ARRIVE -> "도착할 때"
        MemoTrigger.ALIGHT -> "${subject}내릴 때"
        MemoTrigger.TIME -> "${m.time?.let(AgentLines::hm) ?: "정한 시각"}에"
    }
}

// ---------- 공통 조각 ----------

private fun androidx.compose.ui.text.AnnotatedString.Builder.amber(text: String) =
    withStyle(SpanStyle(color = PikoColors.Amber)) { append(text) }

@Composable
private fun EditorShell(
    title: String,
    onBack: () -> Unit,
    list: @Composable ColumnScope.() -> Unit,
    footer: @Composable () -> Unit,
    editor: @Composable ColumnScope.() -> Unit,
) {
    Cabin {
        Column(Modifier.fillMaxSize().padding(horizontal = PikoSpace.l, vertical = PikoSpace.m)) {
            BackHeader(title, onBack)
            Spacer(Modifier.height(PikoSpace.s))
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(PikoSpace.l)) {
                Column(Modifier.weight(0.3f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(PikoSpace.xs), content = list)
                // 저장·취소는 스크롤 밖에 고정: 내용이 길어도 항상 보인다
                Column(Modifier.weight(0.7f)) {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(PikoSpace.s), content = editor)
                    footer()
                }
            }
        }
    }
}

@Composable
private fun SavedRow(title: String, sub: String, selected: Boolean, dim: Boolean = false, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .background(if (selected) PikoColors.Cabin700 else Color.Transparent, RoundedCornerShape(10.dp))
            .border(1.5.dp, if (selected) PikoColors.Amber else PikoColors.Line, RoundedCornerShape(10.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(PikoSpace.s),
    ) {
        Text(title, style = PikoText.body.copy(color = if (dim) PikoColors.Mute else PikoColors.WarmWhite))
        Text(sub, style = PikoText.caption)
    }
}

@Composable
private fun Summary(text: androidx.compose.ui.text.AnnotatedString, sub: String = "아래에서 고른 내용이 이 문장으로 바로 바뀌어요") {
    Column(Modifier.fillMaxWidth().background(PikoColors.Cabin800, RoundedCornerShape(14.dp)).padding(PikoSpace.m)) {
        Text(text, style = PikoText.title)
        Spacer(Modifier.height(6.dp))
        Text(sub, style = PikoText.caption)
    }
}

/** 말로 입력: 정차 중에만 열리는 화면이라 버튼으로 시작 (주행 중 음성 입력은 범위 밖) */
@Composable
private fun VoiceBar(state: VoiceState, heard: String?, hint: String, onStart: () -> Unit, onCancel: () -> Unit) {
    // 마이크 권한은 처음 누를 때 묻고, 허락하면 바로 듣기 시작
    val context = androidx.compose.ui.platform.LocalContext.current
    val askMic = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { onStart() } // 거부하면 VoiceInput이 "마이크 권한이 필요해요"를 띄운다
    val start = {
        val granted = context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val qaInject = java.io.File(context.filesDir, "voice_inject.pcm").exists()
        if (granted || qaInject) onStart() else askMic.launch(android.Manifest.permission.RECORD_AUDIO)
    }
    val listening = state is VoiceState.Listening
    // 줄 전체가 버튼 (누를 곳이 넓게): 듣는 중이면 다시 눌러 멈춤
    Row(
        Modifier.fillMaxWidth().border(1.5.dp, if (listening) PikoColors.Amber else PikoColors.Line, RoundedCornerShape(14.dp))
            .clickable(role = Role.Button) { if (listening) onCancel() else start() }
            .padding(PikoSpace.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(PikoSpace.touchMin).background(if (listening) PikoColors.Amber else PikoColors.Cabin700, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            // 마이크 아이콘: 둥근 캡슐 + 받침
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(width = 14.dp, height = 22.dp).background(if (listening) PikoColors.Cabin950 else PikoColors.WarmWhite, RoundedCornerShape(7.dp)))
                Spacer(Modifier.height(3.dp))
                Box(Modifier.size(width = 20.dp, height = 3.dp).background(if (listening) PikoColors.Cabin950 else PikoColors.WarmWhite))
            }
        }
        Spacer(Modifier.width(PikoSpace.s))
        Column(Modifier.weight(1f)) {
            when (state) {
                is VoiceState.Listening -> {
                    Text("듣고 있어요… 짧게 말해 주세요", style = PikoText.body.copy(color = PikoColors.Amber))
                    Text(state.partial.ifBlank { hint }, style = PikoText.caption)
                }
                is VoiceState.Failed -> {
                    Text(state.reason, style = PikoText.body.copy(color = PikoColors.Alert))
                    Text(hint, style = PikoText.caption)
                }
                else -> {
                    Text(if (heard != null) "들은 말 · 다시 말하려면 누르세요" else "말로 입력 · 눌러서 말하기", style = PikoText.body)
                    Text(heard ?: hint, style = PikoText.caption)
                }
            }
        }
    }
}

@Composable
private fun Field(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = PikoText.label)
        content()
    }
}

@Composable
private fun Chip(text: String, on: Boolean, small: Boolean = false, dashed: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.height(60.dp).then(if (small) Modifier.width(68.dp) else Modifier)
            .background(if (on) PikoColors.WarmWhite else Color.Transparent, RoundedCornerShape(30.dp))
            .border(1.5.dp, if (on) PikoColors.WarmWhite else if (dashed) PikoColors.Mute else PikoColors.Line, RoundedCornerShape(30.dp))
            .clickable(role = Role.Checkbox, onClick = onClick)
            .padding(horizontal = if (small) 0.dp else 22.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = PikoText.label.copy(color = if (on) PikoColors.Cabin950 else if (dashed) PikoColors.WarmGray else PikoColors.WarmWhite))
    }
}

/** 칩 목록 + "+ 직접 입력" (누르면 그 자리에 입력칸) */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipFlow(options: List<String>, selected: Set<String>, custom: Boolean, onPick: (String) -> Unit) {
    var typing by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { o -> Chip(o, o in selected) { onPick(o) } }
        if (custom && !typing) Chip("+ 직접 입력", false, dashed = true) { typing = true }
    }
    if (typing) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PikoTextField(text, { text = it.take(16) }, Modifier.weight(1f))
            PikoButton("추가", primary = true, onClick = {
                if (text.isNotBlank()) onPick(text.trim())
                text = ""; typing = false
            })
            PikoButton("닫기", onClick = { text = ""; typing = false })
        }
    }
}

@Composable
private fun KidCards(children: List<ChildProfile>, selectedId: String?, allowAll: Boolean, allLabel: String, onPick: (String?) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        children.forEach { c ->
            KidCard("${c.alias} · ${c.age.label}", c.alias.take(1), c.id == selectedId, Modifier.weight(1f)) { onPick(c.id) }
        }
        if (allowAll) KidCard(allLabel, null, selectedId == null, Modifier.weight(1f)) { onPick(null) }
    }
}

@Composable
private fun KidCard(label: String, initial: String?, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier.height(72.dp)
            .background(if (on) Color(0xFF2A2216) else Color.Transparent, RoundedCornerShape(12.dp))
            .border(1.5.dp, if (on) PikoColors.Amber else PikoColors.Line, RoundedCornerShape(12.dp))
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = PikoSpace.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (initial != null) {
            Box(Modifier.size(36.dp).background(PikoColors.WarmWhite, CircleShape), contentAlignment = Alignment.Center) {
                Text(initial, style = PikoText.label.copy(color = PikoColors.Cabin950))
            }
            Spacer(Modifier.width(12.dp))
        }
        Text(label, style = PikoText.body)
    }
}

@Composable
private fun BigChoice(label: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.height(84.dp)
            .background(if (on) Color(0xFF2A2216) else Color.Transparent, RoundedCornerShape(12.dp))
            .border(1.5.dp, if (on) PikoColors.Amber else PikoColors.Line, RoundedCornerShape(12.dp))
            .clickable(role = Role.RadioButton, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = PikoText.body) }
}

/** 시 한 번(7~22시), 분 한 번(10분 단위). 고른 시각이 목록 밖이면 그 칩을 하나 더 보여 준다 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TimeChips(t: LocalTime, onChange: (LocalTime) -> Unit) {
    val hours = ((7..22).toList() + t.hour).distinct().sorted()
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), maxItemsInEachRow = 8) {
        hours.forEach { h -> Chip("${h}시", t.hour == h, small = true) { onChange(t.withHour(h)) } }
    }
    Spacer(Modifier.height(4.dp))
    val mins = ((0..50 step 10).toList() + t.minute).distinct().sorted()
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        mins.forEach { m -> Chip(":%02d".format(m), t.minute == m, small = true) { onChange(t.withMinute(m)) } }
    }
}

@Composable
private fun SaveRow(canSave: Boolean, isNew: Boolean, onSave: () -> Unit, onCancel: () -> Unit, onDelete: () -> Unit) {
    var armed by remember { mutableStateOf(false) }
    Spacer(Modifier.height(PikoSpace.s))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(PikoSpace.s)) {
        PikoButton(if (canSave) "저장" else "칸을 채우면 저장할 수 있어요", primary = canSave, enabled = canSave, modifier = Modifier.weight(1f), onClick = onSave)
        PikoButton("취소", onClick = onCancel)
        if (!isNew) PikoButton(if (armed) "한 번 더 누르면 삭제" else "삭제", onClick = { if (armed) onDelete() else armed = true })
    }
}
