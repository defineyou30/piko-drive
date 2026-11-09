package com.pikodrive.core.agent

import com.pikodrive.core.model.DriveMode
import com.pikodrive.core.model.RearSeat.LEFT
import com.pikodrive.core.model.RearSeat.RIGHT
import com.pikodrive.core.pet.SpeechCategory
import com.pikodrive.core.ride.AgeGroup
import com.pikodrive.core.ride.ChildProfile
import com.pikodrive.core.ride.RideEvent
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AgentEngineTest {
    private val jiwoo = ChildProfile("j", "지우", AgeGroup.SCHOOL, LEFT)
    private val kong = ChildProfile("k", "콩이", AgeGroup.KINDER, RIGHT)
    private val profiles = listOf(jiwoo, kong)
    // 2026-10-26 = 월요일
    private fun at(h: Int, m: Int) = LocalDateTime.of(2026, 10, 26, h, m)
    private val P = DriveMode.PARKED
    private val D = DriveMode.DRIVING
    private val taekwondo = ScheduleEntry("t", "j", setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY), LocalTime.of(15, 40), "태권도", listOf("도복"))

    @Test
    fun `schedule briefs once inside lead window on its days only`() {
        val a = AgentEngine().apply { schedules = listOf(taekwondo, taekwondo.copy(id = "x", days = setOf(DayOfWeek.FRIDAY))) }
        val out = mutableListOf<AgentEvent>()
        for (min in 0..60) out += a.process(at(15, 0).plusMinutes(min.toLong()), P, emptyList(), profiles, emptySet())
        val soon = out.filterIsInstance<AgentEvent.ScheduleSoon>()
        assertEquals(listOf("t"), soon.map { it.entry.id })
        assertEquals(15, soon.single().minutesLeft)
        val line = AgentLines.lineFor(soon.single(), profiles)
        assertEquals("15분 뒤 15:40, 지우 태권도예요. 준비물은 도복.", line.text)
        assertEquals(SpeechCategory.AGENT, line.category)
    }

    @Test
    fun `copula follows the final consonant`() {
        assertEquals("예요", AgentLines.copula("태권도"))
        assertEquals("이에요", AgentLines.copula("수영"))
        assertEquals("이에요", AgentLines.copula("ABC"))
    }

    @Test
    fun `memos fire on their vehicle situation`() {
        val a = AgentEngine().apply {
            memos = listOf(
                Memo("1", "알림장 받기", MemoTrigger.ARRIVE),
                Memo("2", "아기 쪽 문 닫힘 확인", MemoTrigger.ALIGHT, childId = "k", repeat = true),
                Memo("3", "우산 챙기기", MemoTrigger.DEPART),
                Memo("4", "물통 넣기", MemoTrigger.BOARD, childId = "j"),
            )
        }
        val fired = mutableListOf<String>()
        fun step(mode: DriveMode, events: List<RideEvent> = emptyList(), onboard: Set<String> = setOf("j", "k")) =
            a.process(at(9, 0), mode, events, profiles, onboard).filterIsInstance<AgentEvent.MemoDue>().forEach { fired += it.memo.id }
        step(P)
        step(P, listOf(RideEvent.ChildBoarded(0, RIGHT)))       // 콩이 → 4번은 지우 전용
        step(P, listOf(RideEvent.ChildBoarded(0, LEFT)))        // 지우 → 4
        step(D)                                                  // 출발 → 3
        step(D)
        step(P)                                                  // 도착 → 1
        step(P, listOf(RideEvent.ChildAlighted(0, LEFT, true)))  // 지우 하차 → 2번은 콩이 전용
        step(P, listOf(RideEvent.ChildAlighted(0, RIGHT, true))) // 콩이 → 2
        assertEquals(listOf("4", "3", "1", "2"), fired)
    }

    @Test
    fun `first mode after app start is not a departure`() {
        val a = AgentEngine().apply { memos = listOf(Memo("3", "우산", MemoTrigger.DEPART)) }
        val out = a.process(at(9, 0), DriveMode.BOOTING, emptyList(), profiles, emptySet()) +
            a.process(at(9, 0), D, emptyList(), profiles, emptySet())
        assertTrue(out.isEmpty())
    }

    @Test
    fun `done memos stay quiet and child specific depart memo needs that child onboard`() {
        val a = AgentEngine().apply {
            memos = listOf(Memo("1", "끝난 메모", MemoTrigger.DEPART, done = true), Memo("2", "지우 수영복", MemoTrigger.DEPART, childId = "j"))
        }
        a.process(at(9, 0), P, emptyList(), profiles, setOf("k"))
        val out = a.process(at(9, 0), D, emptyList(), profiles, setOf("k"))
        assertTrue(out.isEmpty())
    }

    @Test
    fun `time memo fires once at its time but not when the app starts long after`() {
        val a = AgentEngine().apply { memos = listOf(Memo("t", "약 먹이기", MemoTrigger.TIME, time = LocalTime.of(8, 30), repeat = true)) }
        val early = a.process(at(8, 29), P, emptyList(), profiles, emptySet())
        val due = a.process(at(8, 30), P, emptyList(), profiles, emptySet()) + a.process(at(8, 35), P, emptyList(), profiles, emptySet())
        assertTrue(early.isEmpty())
        assertEquals(1, due.size)
        val late = AgentEngine().apply { memos = a.memos }.process(at(9, 0), P, emptyList(), profiles, emptySet())
        assertTrue(late.isEmpty())
    }

    @Test
    fun `briefing lists today's schedule with next and pending memos`() {
        val a = AgentEngine().apply {
            schedules = listOf(taekwondo, ScheduleEntry("s", "k", setOf(DayOfWeek.MONDAY), LocalTime.of(9, 0), "등원"))
            memos = listOf(Memo("1", "알림장", MemoTrigger.ARRIVE), Memo("2", "끝", MemoTrigger.ARRIVE, done = true))
        }
        val b = a.briefing(at(12, 0))
        assertEquals(listOf("s", "t"), b.today.map { it.entry.id })
        assertEquals(listOf(true, false), b.today.map { it.past })
        assertEquals("t", b.next?.id)
        assertEquals(220, b.minutesToNext)
        assertEquals(listOf("1"), b.pendingMemos.map { it.id })
    }

    @Test
    fun `safety record keeps only the first alert of a situation`() {
        assertEquals(SafetyKind.BELT_RELEASED, RideEvent.BeltReleasedWhileDriving(0, LEFT, false).safetyKind())
        assertEquals(null, RideEvent.BeltReleasedWhileDriving(0, LEFT, true).safetyKind())
        assertEquals(SafetyKind.LEFT_BEHIND, RideEvent.LeftBehindAlert(0, listOf(LEFT), 1).safetyKind())
        assertEquals(null, RideEvent.LeftBehindAlert(0, listOf(LEFT), 2).safetyKind())
        assertEquals(null, RideEvent.ChildBoarded(0, LEFT).safetyKind())
    }

    @Test
    fun `arrival items come from the child's schedule near now`() {
        val a = AgentEngine().apply {
            schedules = listOf(
                taekwondo,                                                                     // 지우 15:40 도복
                ScheduleEntry("p", "j", setOf(DayOfWeek.MONDAY), LocalTime.of(19, 0), "피아노", listOf("악보")),
                ScheduleEntry("c", null, setOf(DayOfWeek.MONDAY), LocalTime.of(15, 50), "공통", listOf("물통")),
            )
        }
        assertEquals(listOf("도복"), a.itemsNear(at(15, 30), "j"))        // 10분 뒤 태권도가 가장 가깝다
        assertEquals(listOf("물통"), a.itemsNear(at(15, 30), "k"))        // 콩이는 공통 일정만
        assertEquals(listOf("악보"), a.itemsNear(at(18, 0), "j"))
        assertEquals(emptyList<String>(), a.itemsNear(at(9, 0), "j"))     // 가까운 일정 없음
    }
}
