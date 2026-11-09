package com.pikodrive.core.agent

import com.pikodrive.core.model.RearSeat.LEFT
import com.pikodrive.core.model.RearSeat.RIGHT
import com.pikodrive.core.ride.AgeGroup
import com.pikodrive.core.ride.ChildProfile
import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.SATURDAY
import java.time.DayOfWeek.SUNDAY
import java.time.DayOfWeek.THURSDAY
import java.time.DayOfWeek.TUESDAY
import java.time.DayOfWeek.WEDNESDAY
import java.time.LocalTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** 입력 문장은 Pleos STT 실측 결과 형태 (qa/v4/stt-probe.md) */
class VoiceParserTest {
    private val kids = listOf(ChildProfile("j", "지우", AgeGroup.SCHOOL, LEFT), ChildProfile("k", "콩이", AgeGroup.KINDER, RIGHT))

    @Test
    fun `real stt output with misheard title still fills day time and title`() {
        val g = VoiceParser.parseSchedule("수요일 오후 3시 태국은 또", kids)
        assertEquals(setOf(WEDNESDAY), g.days)
        assertEquals(LocalTime.of(15, 0), g.time)
        assertEquals("태권도", g.title)
    }

    @Test
    fun `full schedule sentence`() {
        val g = VoiceParser.parseSchedule("월수 오후 3시 40분 지우 태권도 준비물은 도복이랑 띠 15분 전", kids)
        assertEquals(setOf(MONDAY, WEDNESDAY), g.days)
        assertEquals(LocalTime.of(15, 40), g.time)
        assertEquals("j", g.childId)
        assertEquals("태권도", g.title)
        assertEquals(listOf("도복", "띠"), g.items)
        assertEquals(15, g.leadMinutes)
    }

    @Test
    fun `day groups and time words`() {
        assertEquals((listOf(MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY)).toSet(), VoiceParser.days("평일 수영"))
        assertEquals(setOf(SATURDAY, SUNDAY), VoiceParser.days("주말 아침"))
        assertEquals(setOf(TUESDAY, THURSDAY), VoiceParser.days("화목에 피아노"))
        assertNull(VoiceParser.days("수영 가방"))                       // '수'만으로는 요일 아님
        assertEquals(LocalTime.of(16, 30), VoiceParser.time("네시 반"))   // 시간대 없으면 1~6시는 오후
        assertEquals(LocalTime.of(8, 50), VoiceParser.time("아침 8시 50분"))
        assertEquals(LocalTime.of(19, 0), VoiceParser.time("저녁 일곱시"))
        assertEquals(LocalTime.of(15, 40), VoiceParser.time("세시 사십분"))
    }

    @Test
    fun `memo sentence becomes trigger child repeat and text`() {
        val g = VoiceParser.parseMemo("콩이가 내릴 때 매번 문 닫혔는지 확인 알려줘", kids)
        assertEquals(MemoTrigger.ALIGHT, g.trigger)
        assertEquals("k", g.childId)
        assertEquals(true, g.repeat)
        assertEquals("문 닫혔는지 확인", g.text)
    }

    @Test
    fun `memo with time and arrival`() {
        val a = VoiceParser.parseMemo("도착하면 알림장 받기", kids)
        assertEquals(MemoTrigger.ARRIVE, a.trigger)
        assertEquals("알림장 받기", a.text)
        assertNull(a.childId)
        val b = VoiceParser.parseMemo("저녁 8시에 약 먹이기", kids)
        assertEquals(MemoTrigger.TIME, b.trigger)
        assertEquals(LocalTime.of(20, 0), b.time)
        assertEquals("약 먹이기", b.text)
    }

    @Test
    fun `item presets follow title and age`() {
        assertEquals(listOf("도복", "띠", "물통"), InputPresets.itemsFor("태권도", AgeGroup.SCHOOL))
        assertEquals(true, "낮잠 이불" in InputPresets.itemsFor("등원", AgeGroup.KINDER))
        assertEquals(false, "낮잠 이불" in InputPresets.itemsFor("수영", AgeGroup.KINDER))
    }

    @Test
    fun `misheard subject before the trigger does not leak into memo text`() {
        // Pleos STT 실측: "콩이가 내릴 때 문 닫혔는지 확인" → "거기가 내릴 때 문 닫혔는지 확인"
        val g = VoiceParser.parseMemo("거기가 내릴 때 문 닫혔는지 확인", kids)
        assertEquals(MemoTrigger.ALIGHT, g.trigger)
        assertNull(g.childId)
        assertEquals("문 닫혔는지 확인", g.text)
    }

    @Test
    fun `filler before the trigger is dropped and text before trigger is used when nothing follows`() {
        assertEquals("문 닫혔는지 확인", VoiceParser.parseMemo("그러니까 내릴 때 문 닫혔는지 확인", kids).text)
        assertEquals("알림장 받기", VoiceParser.parseMemo("알림장 받기 도착하면", kids).text)
    }
}
