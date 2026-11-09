package com.pikodrive.core.ride

import com.pikodrive.core.model.RearSeat.LEFT
import com.pikodrive.core.model.RearSeat.RIGHT
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RideLinesTest {
    private val lines = RideLines()
    private fun kid(age: AgeGroup, piko: Boolean = age.pikoDefault) = listOf(ChildProfile("a", "지우", age, LEFT, pikoEnabled = piko))

    @Test
    fun `kid lines change by age and teens hear nothing by default`() {
        val e = RideEvent.BeltBuckled(0, LEFT, self = true)
        val kinder = lines.linesFor(e, kid(AgeGroup.KINDER)).single()
        assertEquals(Audience.REAR, kinder.audience)
        assertTrue(kinder.request.text.contains("지우"))
        assertTrue(lines.linesFor(e, kid(AgeGroup.TEEN)).isEmpty())
        assertTrue(lines.linesFor(e, kid(AgeGroup.INFANT)).isEmpty())
        // 중고등이 피코를 켜도 도착 한마디(준비물)만
        assertTrue(lines.linesFor(e, kid(AgeGroup.TEEN, piko = true)).isEmpty())
        val arrive = RideEvent.Arrived(0, listOf(LEFT), mapOf(LEFT to listOf("체육복")))
        assertEquals(1, lines.linesFor(arrive, kid(AgeGroup.TEEN, piko = true)).size)
        assertTrue(lines.linesFor(arrive, kid(AgeGroup.TEEN)).isEmpty())
    }

    @Test
    fun `quiet mode drops kid lines but keeps driver alerts`() {
        val profiles = kid(AgeGroup.KINDER)
        assertTrue(lines.linesFor(RideEvent.ChildBoarded(0, LEFT), profiles, quiet = true).isEmpty())
        val alert = lines.linesFor(RideEvent.BeltReleasedWhileDriving(0, LEFT, false), profiles, quiet = true).single()
        assertEquals(Audience.FRONT, alert.audience)
        assertTrue(alert.urgent)
        assertEquals("왼쪽 뒷좌석 지우 벨트가 풀렸어요.", alert.request.text)
    }

    @Test
    fun `teen still gets driver side checks`() {
        val profiles = kid(AgeGroup.TEEN)
        val u = lines.linesFor(RideEvent.DepartUnbuckled(0, listOf(LEFT)), profiles).single()
        assertEquals(Audience.FRONT, u.audience)
    }

    @Test
    fun `arrival names today's items and the curb side`() {
        val profiles = kid(AgeGroup.SCHOOL)
        val withItems = lines.linesFor(RideEvent.Arrived(0, listOf(LEFT), mapOf(LEFT to listOf("태권도복", "물통"))), profiles).single()
        assertEquals("지우, 오늘은 태권도복, 물통 챙겨!", withItems.request.text)
        val noItems = lines.linesFor(RideEvent.Arrived(0, listOf(LEFT), emptyMap()), profiles, streetSide = RIGHT).single()
        assertTrue(noItems.request.text.contains("왼쪽 문"))
    }

    @Test
    fun `left behind lines never claim the car is empty`() {
        val all = (1..3).map { lines.linesFor(RideEvent.LeftBehindAlert(0, listOf(LEFT), it), emptyList()).single() } +
            lines.linesFor(RideEvent.LeftBehindUnknown(0), emptyList())
        assertTrue(all.all { it.urgent && it.audience == Audience.FRONT })
        assertTrue(all.none { it.request.text.contains("없어요") && !it.request.text.contains("읽을 수 없어요") })
    }

    @Test
    fun `board completion lines follow unlocks and skip non sticker ages`() {
        val one = lines.linesFor(RideEvent.BoardCompleted(0, LEFT, 1), kid(AgeGroup.KINDER)).single()
        assertTrue(one.request.text.contains("볼"))
        val two = lines.linesFor(RideEvent.BoardCompleted(0, LEFT, 2), kid(AgeGroup.SCHOOL)).single()
        assertTrue(two.request.text.contains("귀"))
        assertEquals("kid_board_3_school", lines.linesFor(RideEvent.BoardCompleted(0, LEFT, 7), kid(AgeGroup.SCHOOL)).single().request.lineId)
        assertTrue(lines.linesFor(RideEvent.BoardCompleted(0, LEFT, 1), kid(AgeGroup.TEEN, piko = true)).isEmpty())
        assertEquals("볼 램프", unlockLabel(1)); assertEquals(null, unlockLabel(0))
    }
}
