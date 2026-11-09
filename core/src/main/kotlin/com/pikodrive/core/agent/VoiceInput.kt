package com.pikodrive.core.agent

import com.pikodrive.core.ride.AgeGroup
import com.pikodrive.core.ride.ChildProfile
import java.time.DayOfWeek
import java.time.LocalTime

/**
 * 입력 화면의 "자주 쓰는 값" 칩. 키보드는 여기 없는 값만 (design/v5/input_redesign.png).
 */
object InputPresets {
    val titles = listOf("등원", "하원", "학교", "태권도", "수영", "피아노", "영어", "수학", "미술", "축구", "발레", "줄넘기")

    private val itemsByTitle = mapOf(
        "태권도" to listOf("도복", "띠", "물통"),
        "수영" to listOf("수영 가방", "수모", "수경", "수건"),
        "피아노" to listOf("악보", "교재"),
        "영어" to listOf("단어장", "교재"),
        "수학" to listOf("문제집", "필통"),
        "미술" to listOf("물감", "앞치마"),
        "축구" to listOf("축구화", "물통", "정강이 보호대"),
        "발레" to listOf("발레복", "발레 슈즈"),
        "줄넘기" to listOf("줄넘기", "물통"),
        "등원" to listOf("실내화", "물통", "알림장"),
        "학교" to listOf("실내화", "알림장", "숙제"),
        "하원" to listOf("알림장"),
    )
    private val defaultItems = listOf("물통", "수건", "실내화")

    /** 일정 제목·연령대에 맞춘 준비물 후보 (영유아·유치원의 등원·하원·학교에는 여벌 옷·낮잠 이불 추가) */
    fun itemsFor(title: String, age: AgeGroup?): List<String> {
        val base = itemsByTitle[title] ?: defaultItems
        val little = age == AgeGroup.INFANT || age == AgeGroup.KINDER
        return if (little && title in setOf("등원", "하원", "학교")) (base + listOf("여벌 옷", "낮잠 이불")).distinct() else base
    }

    val memoTexts = mapOf(
        MemoTrigger.BOARD to listOf("물통 챙겼는지", "가방 챙겼는지", "벨트 확인"),
        MemoTrigger.DEPART to listOf("우산", "간식", "숙제 챙겼는지"),
        MemoTrigger.ARRIVE to listOf("알림장 받기", "하원 시간 확인", "선생님께 인사"),
        MemoTrigger.ALIGHT to listOf("문 닫혔는지 확인", "가방 챙겼는지", "우산"),
        MemoTrigger.TIME to listOf("약 먹이기", "픽업 출발", "간식"),
    )

    val allItems: List<String> get() = (itemsByTitle.values.flatten() + defaultItems + listOf("여벌 옷", "낮잠 이불", "우산")).distinct()
}

/** 음성 한마디에서 알아낸 일정 칸. 못 알아낸 칸은 null (화면에서 칩으로 채운다) */
data class ScheduleGuess(
    val days: Set<DayOfWeek>? = null,
    val time: LocalTime? = null,
    val childId: String? = null,
    val title: String? = null,
    val items: List<String> = emptyList(),
    val leadMinutes: Int? = null,
) {
    val filledCount: Int get() = listOfNotNull(days, time, childId, title, leadMinutes).size + if (items.isNotEmpty()) 1 else 0
}

data class MemoGuess(
    val trigger: MemoTrigger? = null,
    val time: LocalTime? = null,
    val childId: String? = null,
    val repeat: Boolean? = null,
    val text: String? = null,
)

/**
 * 짧은 한국어 음성 인식 결과 → 입력 칸. 규칙 기반 (네트워크·LLM 없음, 테스트 가능).
 * Pleos STT 실측(qa/v4/stt-probe.md): 숫자는 "3시"처럼 정규화돼 오지만 고유어는 틀린다("태권도"→"태국은")
 * → 제목은 첫 음절 + 둘째 음절 초성이 같으면 후보로 본다. 결과는 언제나 화면 요약 문장으로 부모가 확인한다.
 */
object VoiceParser {

    fun parseSchedule(text: String, children: List<ChildProfile>): ScheduleGuess {
        val t = text.replace(",", " ").trim()
        val title = matchTitle(t)
        val items = itemsIn(t, exclude = listOfNotNull(title))
        return ScheduleGuess(
            days = days(t),
            time = time(t),
            childId = child(t, children),
            title = title,
            items = items,
            leadMinutes = Regex("""(\d{1,2})\s*분\s*전""").find(t)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in listOf(10, 15, 30) },
        )
    }

    fun parseMemo(text: String, children: List<ChildProfile>): MemoGuess {
        var t = text.replace(",", " ").trim()
        val trigger = MEMO_TRIGGERS.firstOrNull { (re, _) -> re.containsMatchIn(t) }
        val time = time(t)
        val trig = trigger?.second ?: if (time != null) MemoTrigger.TIME else null
        val repeat = when {
            Regex("매번|항상|맨날|늘").containsMatchIn(t) -> true
            Regex("한 ?번만?|이번만|오늘만").containsMatchIn(t) -> false
            else -> null
        }
        val childId = child(t, children)
        // 메모 본문 = 상황·아이·반복·부탁 말투를 걷어낸 나머지.
        // 한국어 메모는 "(누가) 언제 + 무엇" 순서: 상황 말 뒤가 본문, 앞은 주어·군말이다
        // (실측: "콩이가 내릴 때 …" → "거기가 내릴 때 …", "그러니까 내릴 때 …"). 뒤가 비었을 때만 앞을 본문으로
        trigger?.first?.let { re ->
            re.find(t)?.let { m ->
                val after = t.substring(m.range.last + 1).trim()
                t = if (after.isNotBlank()) after else t.substring(0, m.range.first)
            }
        }
        TIME_RE.find(t)?.let { t = t.replace(it.value, " ").replace(Regex("^\\s*에\\s+"), " ") }
        children.firstOrNull { it.id == childId }?.let { c -> t = Regex("${Regex.escape(c.alias)}(이가|가|이|는|은|랑|한테|에게)?").replace(t, " ") }
        t = Regex("매번|항상|맨날|한 ?번만?|이번만|오늘만|마다|메모|(이)?라고|알려 ?줘|말해 ?줘|해 ?줘|해 ?주세요|알려 ?주세요")
            .replace(t, " ")
        t = t.replace(Regex("\\s+"), " ").trim().trim('.', '!', '?', ' ')
        return MemoGuess(trig, if (trig == MemoTrigger.TIME) time else null, childId, repeat, t.ifBlank { null })
    }

    // ---- 공통 ----

    private val DAY_CHARS = mapOf(
        '월' to DayOfWeek.MONDAY, '화' to DayOfWeek.TUESDAY, '수' to DayOfWeek.WEDNESDAY, '목' to DayOfWeek.THURSDAY,
        '금' to DayOfWeek.FRIDAY, '토' to DayOfWeek.SATURDAY, '일' to DayOfWeek.SUNDAY,
    )

    fun days(t: String): Set<DayOfWeek>? {
        val out = mutableSetOf<DayOfWeek>()
        if ("매일" in t) return DayOfWeek.entries.toSet()
        if ("평일" in t) out += DayOfWeek.entries.take(5)
        if ("주말" in t) out += listOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
        Regex("([월화수목금토일])요일").findAll(t).forEach { out += DAY_CHARS.getValue(it.groupValues[1][0]) }
        // "월수금", "화목에" 같은 붙여 말하기 (두 글자 이상, 앞뒤가 한글이 아니거나 조사)
        Regex("""(?<![가-힣])([월화수목금토일]{2,})(?=$|[^가-힣]|에|마다|요일)""").findAll(t).forEach { m ->
            m.groupValues[1].forEach { ch -> out += DAY_CHARS.getValue(ch) }
        }
        return out.takeIf { it.isNotEmpty() }
    }

    private val HOUR_WORDS = mapOf(
        "한" to 1, "두" to 2, "세" to 3, "네" to 4, "다섯" to 5, "여섯" to 6, "일곱" to 7, "여덟" to 8,
        "아홉" to 9, "열" to 10, "열한" to 11, "열두" to 12,
    )
    private val MIN_WORDS = mapOf("십" to 10, "이십" to 20, "삼십" to 30, "사십" to 40, "오십" to 50)
    private val TIME_RE = Regex(
        """(오전|오후|아침|저녁|밤|낮)?\s*(\d{1,2}|열두|열한|다섯|여섯|일곱|여덟|아홉|한|두|세|네|열)\s*시\s*(반|(\d{1,2})\s*분|(오십|사십|삼십|이십|십)\s*분)?""",
    )

    fun time(t: String): LocalTime? {
        val m = TIME_RE.find(t) ?: return null
        val period = m.groupValues[1]
        var h = m.groupValues[2].toIntOrNull() ?: HOUR_WORDS[m.groupValues[2]] ?: return null
        val min = when {
            m.groupValues[3] == "반" -> 30
            m.groupValues[4].isNotEmpty() -> m.groupValues[4].toInt()
            m.groupValues[5].isNotEmpty() -> MIN_WORDS.getValue(m.groupValues[5])
            else -> 0
        }
        if (h > 23 || min > 59) return null
        h = when {
            period in setOf("오후", "저녁", "밤") && h < 12 -> h + 12
            period in setOf("오전", "아침") && h == 12 -> 0
            // 시간대를 말하지 않으면 아이 일정 기준: 1~6시는 오후
            period.isEmpty() && h in 1..6 -> h + 12
            else -> h
        }
        return LocalTime.of(h, min)
    }

    fun child(t: String, children: List<ChildProfile>): String? =
        children.filter { it.alias.isNotBlank() && it.alias in t }.maxByOrNull { it.alias.length }?.id

    /** 일정 제목: 정확히 들어 있으면 그것, 아니면 첫 음절 + 둘째 음절 초성이 같은 낱말 ("태국은" → 태권도) */
    fun matchTitle(t: String): String? {
        InputPresets.titles.sortedByDescending { it.length }.firstOrNull { it in t }?.let { return it }
        val words = t.split(' ').filter { it.isNotBlank() }
        return InputPresets.titles.filter { it.length >= 3 }.firstOrNull { title ->
            words.any { w -> w.length >= 2 && w[0] == title[0] && chosung(w[1]) == chosung(title[1]) }
        }
    }

    private fun itemsIn(t: String, exclude: List<String>): List<String> {
        val found = InputPresets.allItems.filter { it in t && it !in exclude }.toMutableList()
        // "준비물은 A랑 B" → 프리셋에 없는 것도 받는다
        Regex("준비물(은|는|:)?\\s*(.+)").find(t)?.groupValues?.get(2)?.let { raw ->
            // 꼬리에 붙은 "15분 전"·시각·요일은 준비물이 아니다
            val tail = raw.replace(Regex("\\d{1,2}\\s*분\\s*전"), " ").replace(TIME_RE, " ")
            tail.split(Regex("\\s*(이랑|랑|하고|그리고|와|과|,|\\s)\\s*"))
                .map { it.trim().removeSuffix("이요").removeSuffix("요") }
                .filter { it.length in 1..8 && it.none(Char::isDigit) && it != "전" && it !in found && found.none { f -> it in f } }
                .forEach { found += it }
        }
        return found.distinct().take(4)
    }

    private val MEMO_TRIGGERS = listOf(
        Regex("(아이가\\s*)?(내릴\\s*때|내리면|하차)") to MemoTrigger.ALIGHT,
        Regex("(아이가\\s*)?(탈\\s*때|타면|탑승)") to MemoTrigger.BOARD,
        Regex("출발(할\\s*때|하면|\\s*전)?") to MemoTrigger.DEPART,
        Regex("도착(할\\s*때|하면)?") to MemoTrigger.ARRIVE,
    )

    private fun chosung(c: Char): Int? = if (c in '가'..'힣') (c - '가') / 588 else null
}
