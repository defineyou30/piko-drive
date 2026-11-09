package com.pikodrive.core.driving

import com.pikodrive.core.model.DriveMode
import com.pikodrive.core.model.Gear
import com.pikodrive.core.model.VehicleSnapshot
import com.pikodrive.core.sim.Scenario
import com.pikodrive.core.sim.scenario
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DrivingEventDetectorTest {

    private fun run(s: Scenario, config: DetectionConfig = DetectionConfig()): Pair<DrivingEventDetector, List<DrivingEvent>> {
        val d = DrivingEventDetector(config)
        return d to s.samples().flatMap(d::process)
    }

    private inline fun <reified T : DrivingEvent> List<DrivingEvent>.ofType() = filterIsInstance<T>()

    private fun cruiseThen(block: com.pikodrive.core.sim.ScenarioBuilder.() -> Unit) = scenario("fixture") {
        gear(Gear.DRIVE)
        rampTo(60f, 12_000)
        hold(5_000)
        block()
        hold(3_000)
    }

    @Test
    fun `sustained hard brake is detected once`() {
        val (_, events) = run(cruiseThen { rampTo(30f, 2_500) }) // -12 km/h/s
        val brakes = events.ofType<DrivingEvent.HardBrake>()
        assertEquals(1, brakes.size)
        assertTrue(brakes[0].kmhPerSec <= -10f)
        // 감속 시작(17s) 후 창 0.5s + 지속 0.4s 안팎에서 판정
        assertTrue(brakes[0].timestampMs in 17_800..18_200, "at ${brakes[0].timestampMs}")
    }

    @Test
    fun `gentle brake is not a hard brake`() {
        val (_, events) = run(cruiseThen { rampTo(30f, 5_000) }) // -6 km/h/s
        assertTrue(events.ofType<DrivingEvent.HardBrake>().isEmpty())
    }

    @Test
    fun `single noisy sample spike is not detected (false-positive guard)`() {
        val noisy = cruiseThen { spike(1_000, 45f); spike(3_000, 95f); hold(5_000) }
        val (_, events) = run(noisy)
        assertTrue(events.ofType<DrivingEvent.HardBrake>().isEmpty(), "$events")
        assertTrue(events.ofType<DrivingEvent.HardAccel>().isEmpty(), "$events")
        // 대조군: 디바운스를 끄면 같은 데이터가 오탐된다
        val (_, raw) = run(noisy, DetectionConfig(sustainMs = 0))
        assertTrue(raw.any { it is DrivingEvent.HardBrake }, "control: $raw")
    }

    @Test
    fun `sample gap does not create a brake across the gap`() {
        // 3초 끊김 동안 60→20 (평균 -13 km/h/s): 간격을 가로지르는 미분은 하지 않는다.
        val gappy = cruiseThen { gap(3_000, 20f); hold(3_000) }
        val (_, events) = run(gappy)
        assertTrue(events.ofType<DrivingEvent.HardBrake>().isEmpty(), "$events")
        // 대조군: 간격 허용을 늘리면 간격을 가로지른 미분이 급제동으로 오탐된다
        val (_, raw) = run(gappy, DetectionConfig(maxSampleGapMs = 10_000, sustainMs = 0))
        assertTrue(raw.any { it is DrivingEvent.HardBrake }, "control: $raw")
    }

    @Test
    fun `brake right after a gap needs a fresh window`() {
        val (_, events) = run(cruiseThen { gap(3_000, 60f); rampTo(30f, 2_500) })
        assertEquals(1, events.ofType<DrivingEvent.HardBrake>().size)
    }

    @Test
    fun `hard accel is detected`() {
        val s = scenario("accel") {
            gear(Gear.DRIVE); hold(2_000)
            rampTo(42f, 3_000) // +14 km/h/s
            hold(3_000)
        }
        val (_, events) = run(s)
        assertEquals(1, events.ofType<DrivingEvent.HardAccel>().size)
    }

    @Test
    fun `smooth streak accumulates and resets after a hard brake`() {
        val config = DetectionConfig(smoothStreakStepMs = 60_000, comfortPenaltyMs = 30_000, comfortTickMs = 10_000)
        val s = scenario("streak") {
            gear(Gear.DRIVE)
            rampTo(50f, 10_000)
            hold(140_000)                 // t=150s
            rampTo(25f, 2_000)            // 급제동
            rampTo(50f, 8_000)
            hold(60_000)                  // t=220s
        }
        val (d, events) = run(s, config)
        val streaks = events.ofType<DrivingEvent.SmoothStreak>()
        // 0.1s 시작 → 60s, 120s 에 스트릭, 급제동 후 다시 0부터 60s
        assertEquals(listOf(1, 2, 1), streaks.map { it.minutes })
        assertEquals(1, events.ofType<DrivingEvent.HardBrake>().size)
        // 편안한 시간 = 주행 220s - 5 km/h 미만 1s - 급제동 후 30s 페널티 (± 샘플 1개).
        // 트립 확정(5 km/h 이상 3초) 전 이동분도 소급 적립된다.
        val comfortSum = events.ofType<DrivingEvent.ComfortAccrued>().sumOf { it.comfortMs }
        assertTrue(d.tripComfortMs in 188_500L..190_500L, "trip comfort=${d.tripComfortMs}")
        assertTrue(d.tripComfortMs - comfortSum < 10_000, "미전송 잔여분은 1틱 미만")
    }

    @Test
    fun `depart and park transitions with park confirmation`() {
        val s = scenario("park") {
            hold(2_000)
            gear(Gear.DRIVE); rampTo(20f, 5_000); rampTo(0f, 5_000)
            gear(Gear.PARK); hold(1_000)       // 3초 미만 P
            gear(Gear.DRIVE); rampTo(10f, 3_000); rampTo(0f, 3_000)
            gear(Gear.PARK); hold(4_000)
        }
        val (d, events) = run(s)
        assertEquals(1, events.ofType<DrivingEvent.Departed>().size)
        val parked = events.ofType<DrivingEvent.Parked>()
        assertEquals(1, parked.size)
        assertEquals(19_000 + 3_000L, parked[0].timestampMs)
        assertEquals(DriveMode.PARKED, d.mode)
    }

    @Test
    fun `gear unsupported falls back to speed - zero held 5s means parked`() {
        val d = DrivingEventDetector()
        fun feed(t: Long, v: Float) = d.process(VehicleSnapshot(t, v, Gear.UNKNOWN))
        feed(0, 0f)
        assertEquals(DriveMode.BOOTING, d.mode)
        feed(4_900, 0f)
        assertEquals(DriveMode.BOOTING, d.mode, "5초 전엔 판단 보류")
        feed(5_000, 0f)
        assertEquals(DriveMode.PARKED, d.mode)
        val creep = feed(5_100, 3f)
        assertEquals(DriveMode.DRIVING, d.mode)
        assertTrue(creep.none { it is DrivingEvent.Departed }, "5 km/h 미만 서행은 아직 트립 아님")
        val departed = (1..4).flatMap { feed(5_100 + it * 1_000L, 10f) }
        assertEquals(1, departed.count { it is DrivingEvent.Departed }, "5 km/h 이상 3초 이동 → 트립")
        // 신호대기 3초 정지 → 여전히 주행
        feed(10_000, 0f); feed(13_000, 0f)
        assertEquals(DriveMode.DRIVING, d.mode)
        feed(14_000, 0f); feed(15_000, 0f)
        assertEquals(DriveMode.PARKED, d.mode)
    }

    @Test
    fun `booting holds until a valid gear arrives`() {
        val d = DrivingEventDetector()
        d.process(VehicleSnapshot(0, null, Gear.UNKNOWN))
        d.process(VehicleSnapshot(1_000, null, Gear.UNKNOWN))
        d.process(VehicleSnapshot(20_000, null, Gear.UNKNOWN))
        assertEquals(DriveMode.BOOTING, d.mode, "확인 실패 시 F3 형태 유지")
        val events = d.process(VehicleSnapshot(21_000, 0f, Gear.PARK))
        assertEquals(DriveMode.PARKED, d.mode)
        assertTrue(events.isEmpty(), "부팅→정차는 트립이 아님")
    }

    @Test
    fun `booting straight into drive starts a trip`() {
        val d = DrivingEventDetector()
        assertTrue(d.process(VehicleSnapshot(0, 40f, Gear.DRIVE)).isEmpty())
        assertEquals(DriveMode.DRIVING, d.mode)
        val events = (1..3).flatMap { d.process(VehicleSnapshot(it * 1_000L, 40f, Gear.DRIVE)) }
        assertEquals(DrivingEvent.Departed(3_000), events.single { it is DrivingEvent.Departed }, "3초 이동 후 확정")
    }

    @Test
    fun `reversing emits Reversing and never hard events`() {
        val s = scenario("reverse") {
            gear(Gear.REVERSE)
            rampTo(15f, 1_000)   // +15 km/h/s 지만 후진 중
            rampTo(0f, 1_000)
            hold(1_000)
            gear(Gear.PARK); hold(4_000)
        }
        val (_, events) = run(s)
        assertEquals(1, events.ofType<DrivingEvent.Reversing>().size)
        assertTrue(events.none { it is DrivingEvent.HardAccel || it is DrivingEvent.HardBrake })
        // 후진만 한 주차 조작은 트립이 아니다 (리포트·도착 인사 없음)
        assertTrue(events.none { it is DrivingEvent.Departed || it is DrivingEvent.Parked }, "$events")
    }

    @Test
    fun `long drive fires per step`() {
        val config = DetectionConfig(longDriveStepMs = 60_000)
        val s = scenario("long") { gear(Gear.DRIVE); rampTo(80f, 20_000); hold(110_000) }
        val (_, events) = run(s, config)
        assertEquals(listOf(1, 2), events.ofType<DrivingEvent.LongDrive>().map { it.minutes })
    }

    @Test
    fun `out of order samples are ignored`() {
        val d = DrivingEventDetector()
        d.process(VehicleSnapshot(1_000, 0f, Gear.PARK))
        assertTrue(d.process(VehicleSnapshot(900, 50f, Gear.DRIVE)).isEmpty())
        assertEquals(DriveMode.PARKED, d.mode)
    }

    // ---- Codex 리뷰 반영 ----

    @Test
    fun `ux restricted park is not PARKED until restriction lifts`() {
        val d = DrivingEventDetector(uxRestrictionSupported = true)
        var t = 0L
        fun feed(gear: Gear, v: Float, ux: Boolean?) = d.process(VehicleSnapshot(t, v, gear, ux)).also { t += 500 }
        feed(Gear.PARK, 0f, false)
        assertEquals(DriveMode.PARKED, d.mode)
        val drive = (1..8).flatMap { feed(Gear.DRIVE, 10f, true) }
        assertEquals(1, drive.count { it is DrivingEvent.Departed })
        val parkedEvents = (1..10).flatMap { feed(Gear.PARK, 0f, true) }
        assertEquals(DriveMode.DRIVING, d.mode, "P지만 UX 제한 중 → 주행 정책")
        assertTrue(parkedEvents.none { it is DrivingEvent.Parked })
        feed(Gear.PARK, 0f, false)
        assertEquals(DriveMode.PARKED, d.mode)
        val confirm = (1..6).flatMap { feed(Gear.PARK, 0f, false) }
        assertEquals(1, confirm.count { it is DrivingEvent.Parked })
        // 정차 중 UX 제한이 다시 켜져도 트립은 시작되지 않는다
        val again = feed(Gear.PARK, 0f, true)
        assertEquals(DriveMode.DRIVING, d.mode)
        assertTrue(again.none { it is DrivingEvent.Departed })
    }

    @Test
    fun `ux supported but not yet received stays BOOTING`() {
        val d = DrivingEventDetector(uxRestrictionSupported = true)
        d.process(VehicleSnapshot(0, 0f, Gear.PARK, null))
        d.process(VehicleSnapshot(1_000, 0f, Gear.PARK, null))
        assertEquals(DriveMode.BOOTING, d.mode)
        d.process(VehicleSnapshot(2_000, 0f, Gear.PARK, true))
        assertEquals(DriveMode.BOOTING, d.mode, "부팅 중 P + 제한 → 계속 BOOTING")
        val events = d.process(VehicleSnapshot(3_000, 0f, Gear.PARK, false))
        assertEquals(DriveMode.PARKED, d.mode)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `refractory resets between trips less than 5s apart`() {
        val d = DrivingEventDetector(DetectionConfig(parkConfirmMs = 500))
        val s = scenario("two trips") {
            gear(Gear.DRIVE); rampTo(30f, 6_000); hold(1_000)
            rampTo(0f, 2_000)                  // 급제동 #1 (-15 km/h/s)
            gear(Gear.PARK); hold(500)         // 트립 1 종료
            hold(100)
            gear(Gear.DRIVE)
            rampTo(20f, 1_000)
            rampTo(0f, 1_300)                  // 급제동 #2, #1과 5초 미만 간격
            hold(1_000)
        }
        val events = s.samples().flatMap(d::process)
        assertEquals(2, events.ofType<DrivingEvent.Departed>().size)
        val brakes = events.ofType<DrivingEvent.HardBrake>()
        assertEquals(2, brakes.size, "$events")
        assertTrue(brakes[1].timestampMs - brakes[0].timestampMs < 5_000)
    }

    @Test
    fun `hard brake right after a new trip starts is not swallowed by previous refractory`() {
        val d = DrivingEventDetector(DetectionConfig(parkConfirmMs = 0, hardEventRefractoryMs = 60_000))
        val first = scenario("a") {
            gear(Gear.DRIVE); rampTo(40f, 8_000); rampTo(10f, 2_500); rampTo(0f, 1_000)
            gear(Gear.PARK); hold(200)
            gear(Gear.DRIVE); rampTo(40f, 8_000); rampTo(10f, 2_500); hold(1_000)
        }
        val events = first.samples().flatMap(d::process)
        assertEquals(2, events.ofType<DrivingEvent.Departed>().size)
        assertEquals(2, events.ofType<DrivingEvent.HardBrake>().size, "60초 refractory 라도 새 트립에선 리셋")
    }

    @Test
    fun `final D segment before P or R is accrued but reverse time is not`() {
        val config = DetectionConfig(comfortTickMs = 600_000, comfortPenaltyMs = 0)
        val d = DrivingEventDetector(config)
        // 1초 간격 샘플: D 0..10s → P 11s ~ 14s
        (0..10).forEach { d.process(VehicleSnapshot(it * 1_000L, 20f, Gear.DRIVE)) }
        val toPark = (11..14).flatMap { d.process(VehicleSnapshot(it * 1_000L, 0f, Gear.PARK)) }
        assertEquals(11_000L, toPark.ofType<DrivingEvent.ComfortAccrued>().sumOf { it.comfortMs }, "10→11s 구간 포함")

        val d2 = DrivingEventDetector(config)
        (0..10).forEach { d2.process(VehicleSnapshot(it * 1_000L, 20f, Gear.DRIVE)) }
        (11..20).forEach { d2.process(VehicleSnapshot(it * 1_000L, 3f, Gear.REVERSE)) }
        val end = (21..24).flatMap { d2.process(VehicleSnapshot(it * 1_000L, 0f, Gear.PARK)) }
        assertEquals(11_000L, end.ofType<DrivingEvent.ComfortAccrued>().sumOf { it.comfortMs }, "R 구간 제외")
    }
}
