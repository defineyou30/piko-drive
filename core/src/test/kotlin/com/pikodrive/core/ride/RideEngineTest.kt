package com.pikodrive.core.ride

import com.pikodrive.core.model.CabinSnapshot
import com.pikodrive.core.model.DriveMode
import com.pikodrive.core.model.Gear
import com.pikodrive.core.model.RearSeat
import com.pikodrive.core.model.RearSeat.LEFT
import com.pikodrive.core.model.RearSeat.RIGHT
import com.pikodrive.core.model.VehicleSnapshot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.DayOfWeek

/** v3 뒷좌석 루틴 상태머신 (docs/CONCEPT_v3_kids_ride.md 5장). 1초 간격 스냅샷으로 시나리오를 재생한다. */
class RideEngineTest {

    private var t = 0L
    // 세션 시작 워밍업(3초)은 별도 테스트에서 본다
    private val engine = RideEngine(RideConfig(warmupMs = 0)).apply {
        // 스티커는 프로필이 있는 유치원·초등만 받는다 → 기본 가족
        profiles = listOf(ChildProfile("l", "지우", AgeGroup.SCHOOL, LEFT), ChildProfile("r", "콩이", AgeGroup.KINDER, RIGHT))
    }
    private val events = mutableListOf<RideEvent>()

    /** 현재 실내 상태 (시나리오가 바꿔 가며 쓴다) */
    private var occ = mutableMapOf<RearSeat, Boolean?>(LEFT to false, RIGHT to false)
    private var belt = mutableMapOf<RearSeat, Boolean?>(LEFT to false, RIGHT to false)
    private var door = mutableMapOf<RearSeat, Boolean?>(LEFT to false, RIGHT to false)
    private var window = mutableMapOf<RearSeat, Int?>(LEFT to 0, RIGHT to 0)
    private var driver: Boolean? = true
    private var locked: Boolean? = false
    private var temp: Float? = 22f

    private fun tick(mode: DriveMode, speed: Float = if (mode == DriveMode.DRIVING) 30f else 0f, seconds: Int = 1) {
        repeat(seconds) {
            t += 1_000
            val gear = when (mode) {
                DriveMode.PARKED -> Gear.PARK
                DriveMode.DRIVING -> Gear.DRIVE
                DriveMode.REVERSING -> Gear.REVERSE
                DriveMode.BOOTING -> Gear.UNKNOWN
            }
            val snap = VehicleSnapshot(
                t, speed, gear, false,
                cabin = CabinSnapshot(occ.toMap(), belt.toMap(), door.toMap(), window.toMap(), driver, locked, temp),
            )
            events += engine.process(snap, mode, DayOfWeek.WEDNESDAY)
        }
    }

    private inline fun <reified T : RideEvent> ofType() = events.filterIsInstance<T>()
    private val P = DriveMode.PARKED
    private val D = DriveMode.DRIVING

    private fun boardLeftAndBuckle() {
        tick(P, seconds = 2)
        door[LEFT] = true; tick(P); occ[LEFT] = true; tick(P); door[LEFT] = false; tick(P)
        belt[LEFT] = true; tick(P)
    }

    @Test
    fun `boarding then self buckling before prompt earns a sticker`() {
        boardLeftAndBuckle()
        assertEquals(listOf(LEFT), ofType<RideEvent.ChildBoarded>().map { it.seat })
        assertEquals(listOf(true), ofType<RideEvent.BeltBuckled>().map { it.self })
        assertEquals(listOf(StickerReason.SELF_BELT), ofType<RideEvent.StickerEarned>().map { it.reason })
        assertTrue(ofType<RideEvent.BeltPrompt>().isEmpty())
        assertEquals(RidePhase.READY, engine.state.phase)
    }

    @Test
    fun `seated already at start does not count as boarding`() {
        occ[LEFT] = true; belt[LEFT] = true
        tick(P, seconds = 3)
        assertTrue(ofType<RideEvent.ChildBoarded>().isEmpty())
    }

    @Test
    fun `belt prompt after 8 seconds once, buckling afterwards gives no sticker`() {
        tick(P); occ[LEFT] = true; tick(P, seconds = 20)
        assertEquals(1, ofType<RideEvent.BeltPrompt>().size)
        belt[LEFT] = true; tick(P)
        assertEquals(listOf(false), ofType<RideEvent.BeltBuckled>().map { it.self })
        assertTrue(ofType<RideEvent.StickerEarned>().isEmpty())
    }

    @Test
    fun `departing with an unbuckled child warns the driver once`() {
        tick(P); occ[LEFT] = true; occ[RIGHT] = true; tick(P); belt[RIGHT] = true; tick(P)
        tick(D, seconds = 3)
        assertEquals(listOf(listOf(LEFT)), ofType<RideEvent.DepartUnbuckled>().map { it.seats })
    }

    @Test
    fun `belt released while moving alerts, then reminds at most twice`() {
        boardLeftAndBuckle()
        tick(D, seconds = 5)
        belt[LEFT] = false
        tick(D, seconds = 300)
        val alerts = ofType<RideEvent.BeltReleasedWhileDriving>()
        assertEquals(listOf(false, true, true), alerts.map { it.reminder })
        // 첫 알림 후 60초, 120초
        assertEquals(listOf(60_000L, 120_000L), alerts.drop(1).map { it.timestampMs - alerts[0].timestampMs })
    }

    @Test
    fun `belt release while stopped at a light in D does not alert until moving`() {
        boardLeftAndBuckle()
        tick(D, seconds = 3)
        belt[LEFT] = false
        tick(D, speed = 0f, seconds = 5)
        assertTrue(ofType<RideEvent.BeltReleasedWhileDriving>().isEmpty())
        tick(D, seconds = 1)
        assertEquals(1, ofType<RideEvent.BeltReleasedWhileDriving>().size)
    }

    @Test
    fun `rest suggestion at 60 minutes with infant, 120 otherwise`() {
        engine.profiles = listOf(ChildProfile("a", "아기", AgeGroup.INFANT, LEFT))
        boardLeftAndBuckle()
        tick(D, seconds = 61 * 60)
        assertEquals(listOf(60), ofType<RideEvent.RestSuggested>().map { it.minutes })
        assertTrue(ofType<RideEvent.RestSuggested>().all { it.infant })

        val e2 = RideEngine(RideConfig(warmupMs = 0)).apply { profiles = listOf(ChildProfile("b", "형", AgeGroup.SCHOOL, LEFT)) }
        var tt = 0L
        val out = mutableListOf<RideEvent>()
        fun s(mode: DriveMode, sp: Float) = VehicleSnapshot(++tt * 1000, sp, Gear.DRIVE, false,
            cabin = CabinSnapshot(mapOf(LEFT to true), mapOf(LEFT to true)))
        repeat(121 * 60) { out += e2.process(s(D, 40f), D, DayOfWeek.MONDAY) }
        assertEquals(listOf(120), out.filterIsInstance<RideEvent.RestSuggested>().map { it.minutes })
    }

    @Test
    fun `arrival lists today's items and street side door warns`() {
        engine.profiles = listOf(
            ChildProfile("a", "지우", AgeGroup.SCHOOL, LEFT, itemsByDay = mapOf(DayOfWeek.WEDNESDAY to listOf("태권도복"))),
        )
        boardLeftAndBuckle()
        tick(D, seconds = 30)
        tick(P)
        val arrived = ofType<RideEvent.Arrived>().single()
        assertEquals(mapOf(LEFT to listOf("태권도복")), arrived.items)
        assertEquals(RidePhase.ARRIVED, engine.state.phase)
        belt[LEFT] = false
        door[LEFT] = true; tick(P) // 왼쪽 = 차도 쪽
        assertEquals(listOf(LEFT), ofType<RideEvent.StreetSideDoorOpened>().map { it.seat })
    }

    @Test
    fun `curb side exit after a ride earns a sticker, street side exit does not`() {
        boardLeftAndBuckle()
        tick(D, seconds = 30); tick(P)
        belt[LEFT] = false; door[RIGHT] = true; tick(P, seconds = 2)
        occ[LEFT] = false; tick(P)
        val alighted = ofType<RideEvent.ChildAlighted>().single()
        assertTrue(alighted.viaCurb)
        assertEquals(listOf(StickerReason.SELF_BELT, StickerReason.CURB_EXIT), ofType<RideEvent.StickerEarned>().map { it.reason })
        assertEquals(RidePhase.IDLE, engine.state.phase)
    }

    @Test
    fun `no sticker for curb exit without riding`() {
        tick(P); occ[LEFT] = true; tick(P); door[RIGHT] = true; tick(P); occ[LEFT] = false; tick(P)
        assertTrue(ofType<RideEvent.StickerEarned>().none { it.reason == StickerReason.CURB_EXIT })
    }

    @Test
    fun `left behind alert when driver leaves after a ride, escalates, clears when child exits`() {
        boardLeftAndBuckle()
        tick(D, seconds = 30); tick(P, seconds = 3)
        driver = false; tick(P, seconds = 35)
        val alerts = ofType<RideEvent.LeftBehindAlert>()
        assertEquals(listOf(1, 2, 3, 3), alerts.map { it.level })
        assertEquals(LeftBehindStatus.ALERT, engine.state.leftBehind)
        assertEquals(RidePhase.LEFT_BEHIND, engine.state.phase)
        occ[LEFT] = false; tick(P)
        assertEquals(1, ofType<RideEvent.LeftBehindCleared>().size)
        assertEquals(LeftBehindStatus.NONE, engine.state.leftBehind)
    }

    @Test
    fun `locking doors with child inside triggers alert even if driver seat signal is missing`() {
        driver = null
        boardLeftAndBuckle()
        tick(D, seconds = 30); tick(P)
        locked = true; tick(P)
        assertEquals(1, ofType<RideEvent.LeftBehindAlert>().size)
    }

    @Test
    fun `parent placing child then walking around to driver door is not an alert`() {
        driver = false
        tick(P); occ[LEFT] = true; tick(P, seconds = 30)
        assertTrue(ofType<RideEvent.LeftBehindAlert>().isEmpty())
    }

    @Test
    fun `missing rear occupancy signal gives unknown instead of all clear`() {
        boardLeftAndBuckle()
        tick(D, seconds = 30); tick(P)
        occ[LEFT] = null; occ[RIGHT] = null; driver = false
        tick(P, seconds = 5)
        assertEquals(1, ofType<RideEvent.LeftBehindUnknown>().size)
        assertEquals(LeftBehindStatus.UNKNOWN, engine.state.leftBehind)
        assertTrue(ofType<RideEvent.LeftBehindAlert>().isEmpty())
    }

    @Test
    fun `acknowledge stops repeats until the situation changes`() {
        boardLeftAndBuckle()
        tick(D, seconds = 30); tick(P)
        driver = false; tick(P, seconds = 2)
        engine.acknowledgeLeftBehind()
        tick(P, seconds = 60)
        assertEquals(1, ofType<RideEvent.LeftBehindAlert>().size)
        driver = true; tick(P); driver = false; tick(P)
        assertEquals(2, ofType<RideEvent.LeftBehindAlert>().size)
    }

    @Test
    fun `door opening while moving alerts`() {
        boardLeftAndBuckle()
        tick(D, seconds = 5)
        door[RIGHT] = true; tick(D)
        assertEquals(listOf(RIGHT), ofType<RideEvent.DoorOpenWhileMoving>().map { it.seat })
        assertTrue(ofType<RideEvent.StreetSideDoorOpened>().isEmpty())
    }

    @Test
    fun `checklist reflects signals without any parent input`() {
        engine.profiles = listOf(ChildProfile("a", "아기", AgeGroup.INFANT, LEFT))
        temp = 18.6f; window[RIGHT] = 5
        tick(P); occ[LEFT] = true; tick(P)
        val byId = engine.state.checklist.associateBy { it.id }
        assertEquals(CheckStatus.ATTENTION, byId.getValue("belt_LEFT").status)
        assertEquals("왼쪽 뒷좌석 아기 벨트", byId.getValue("belt_LEFT").label)
        assertEquals(CheckStatus.OK, byId.getValue("rear_doors").status)
        assertEquals(CheckStatus.ATTENTION, byId.getValue("door_lock").status)
        assertEquals("50% 열림", byId.getValue("rear_windows").detail)
        assertEquals(CheckStatus.ATTENTION, byId.getValue("cabin_temp").status)
        assertTrue(byId.getValue("cabin_temp").detail.contains("서늘"))
        belt[LEFT] = true; locked = true; window[RIGHT] = 0; temp = 22f; tick(P)
        assertEquals(0, engine.state.attentionCount)
    }

    @Test
    fun `unknown signals show as unknown, never ok`() {
        belt[LEFT] = null; door[LEFT] = null; door[RIGHT] = null; locked = null
        window[LEFT] = null; window[RIGHT] = null
        tick(P); occ[LEFT] = true; tick(P)
        val statuses = engine.state.checklist.map { it.status }.toSet()
        assertEquals(setOf(CheckStatus.UNKNOWN), statuses)
    }

    @Test
    fun `no temperature item without an infant on board`() {
        engine.profiles = listOf(ChildProfile("a", "형", AgeGroup.TEEN, LEFT))
        tick(P); occ[LEFT] = true; tick(P)
        assertTrue(engine.state.checklist.none { it.id == "cabin_temp" })
    }

    @Test
    fun `signal flapping during warm-up does not create boarding or alighting`() {
        val e = RideEngine()
        val out = mutableListOf<RideEvent>()
        var tt = 0L
        fun s(o: Boolean?) = VehicleSnapshot(tt.also { tt += 200 }, 0f, Gear.PARK, false, cabin = CabinSnapshot(mapOf(LEFT to o)))
        listOf(false, true, false, true, false).forEach { out += e.process(s(it), P, DayOfWeek.MONDAY) }
        repeat(20) { out += e.process(s(false), P, DayOfWeek.MONDAY) }
        assertTrue(out.isEmpty())
        out += e.process(s(true), P, DayOfWeek.MONDAY)
        assertEquals(listOf(LEFT), out.filterIsInstance<RideEvent.ChildBoarded>().map { it.seat })
    }

    @Test
    fun `unbuckling after arrival is not nagged and belts leave the checklist`() {
        boardLeftAndBuckle()
        tick(D, seconds = 30); tick(P)
        belt[LEFT] = false; tick(P, seconds = 20)
        assertTrue(ofType<RideEvent.BeltPrompt>().isEmpty())
        assertTrue(engine.state.checklist.none { it.id.startsWith("belt_") || it.id == "door_lock" })
    }

    @Test
    fun `exit long after the curb door opened is not a curb exit`() {
        boardLeftAndBuckle()
        tick(D, seconds = 30); tick(P)
        door[RIGHT] = true; tick(P); door[RIGHT] = false; tick(P, seconds = 30)
        occ[LEFT] = false; tick(P)
        assertEquals(listOf(false), ofType<RideEvent.ChildAlighted>().map { it.viaCurb })
    }

    @Test
    fun `infants and teens and unknown kids earn no stickers`() {
        for (profiles in listOf(
            listOf(ChildProfile("a", "아기", AgeGroup.INFANT, LEFT)),
            listOf(ChildProfile("a", "형", AgeGroup.TEEN, LEFT)),
            emptyList(),
        )) {
            events.clear()
            engine.profiles = profiles
            occ[LEFT] = false; belt[LEFT] = false; tick(P)
            boardLeftAndBuckle()
            assertTrue(ofType<RideEvent.StickerEarned>().isEmpty(), "$profiles")
            assertEquals(1, ofType<RideEvent.BeltBuckled>().size)
        }
    }

    @Test
    fun `departure with kids fires once per trip when actually moving`() {
        boardLeftAndBuckle()
        tick(D, speed = 0f, seconds = 3)
        assertTrue(ofType<RideEvent.DepartedWithKids>().isEmpty())
        tick(D, seconds = 10); tick(P, seconds = 2); tick(D, seconds = 10)
        assertEquals(listOf(listOf(LEFT)), ofType<RideEvent.DepartedWithKids>().map { it.seats })
    }

    @Test
    fun `arriving with doors still auto-locked is not a left-behind alert`() {
        boardLeftAndBuckle()
        locked = true
        tick(D, seconds = 30); tick(P, seconds = 5)
        assertTrue(ofType<RideEvent.LeftBehindAlert>().isEmpty())
        // 내린 뒤 정차 중에 다시 잠그면 그때는 알림
        locked = false; tick(P); locked = true; tick(P)
        assertEquals(1, ofType<RideEvent.LeftBehindAlert>().size)
    }
}
