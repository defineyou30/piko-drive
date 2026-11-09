package com.pikodrive.core.driving

import com.pikodrive.core.model.Gear
import com.pikodrive.core.model.VehicleSnapshot
import com.pikodrive.core.sim.scenario
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 기능 평가 D2·D3: 트립·편안한 시간은 실제 이동(≥ 5 km/h) 기준. */
class MovingTripDetectorTest {

    private fun runAll(build: com.pikodrive.core.sim.ScenarioBuilder.() -> Unit, config: DetectionConfig = DetectionConfig.Demo) =
        DrivingEventDetector(config).let { d -> d to scenario("t", build).samples().flatMap(d::process) }

    @Test
    fun `D held at 0 kmh for 45s is not a trip and earns nothing`() {
        val (d, events) = runAll({ hold(1_000); gear(Gear.DRIVE); hold(45_000); gear(Gear.PARK); hold(5_000) })
        assertTrue(events.isEmpty(), "$events")
        assertEquals(0L, d.tripComfortMs)
    }

    @Test
    fun `stationary time in D during a trip earns no comfort and no streak`() {
        val (d, events) = runAll({
            gear(Gear.DRIVE); rampTo(30f, 3_000); hold(10_000)   // 이동
            rampTo(0f, 5_000); hold(45_000)                     // 신호대기 45초 (D, 0 km/h)
            rampTo(30f, 3_000); hold(10_000)
            rampTo(0f, 5_000); gear(Gear.PARK); hold(4_000)
        })
        assertEquals(1, events.count { it is DrivingEvent.Departed })
        assertEquals(1, events.count { it is DrivingEvent.Parked })
        // 이동 시간 ≈ 3+10+5+3+10+5 = 36s 에서 5 km/h 미만 구간(≈0.5s×4) 제외. 정지 45초는 0.
        assertTrue(d.tripComfortMs in 33_000L..35_000L, "comfort=${d.tripComfortMs}")
        assertTrue(events.none { it is DrivingEvent.SmoothStreak }, "Demo 스트릭 32초 미만 → 정지 시간이 스트릭에 안 들어감")
    }

    @Test
    fun `gear toggles and reverse-only parking maneuvers do not create trips`() {
        val (_, toggle) = runAll({ hold(1_000); gear(Gear.DRIVE); hold(1_000); gear(Gear.PARK); hold(5_000) })
        assertTrue(toggle.none { it is DrivingEvent.Departed || it is DrivingEvent.Parked })
        val (_, reverse) = runAll({
            hold(1_000); gear(Gear.REVERSE); rampTo(5f, 1_000); hold(6_000); rampTo(0f, 1_000)
            gear(Gear.PARK); hold(5_000)
        })
        assertTrue(reverse.none { it is DrivingEvent.Departed || it is DrivingEvent.Parked }, "$reverse")
        // 짧은 이동(2초) → P → 다시 2초: 정차하면 후보 누적이 초기화되어 트립 아님
        val (_, inch) = runAll({
            gear(Gear.DRIVE); hold(100); rampTo(8f, 200); hold(2_000); rampTo(0f, 300); gear(Gear.PARK); hold(1_000)
            gear(Gear.DRIVE); rampTo(8f, 200); hold(2_000); rampTo(0f, 300); gear(Gear.PARK); hold(4_000)
        })
        assertTrue(inch.none { it is DrivingEvent.Departed }, "$inch")
    }

    @Test
    fun `departed fires at confirmed movement and hard launch confirms early`() {
        val (_, events) = runAll({ hold(1_000); gear(Gear.DRIVE); hold(2_000); rampTo(20f, 4_000); hold(5_000) })
        // D 진입 1s, 5 km/h 도달 4s → 3초 후 ≈7s
        val dep = events.filterIsInstance<DrivingEvent.Departed>().single()
        assertTrue(dep.timestampMs in 6_900L..7_200L, "${dep.timestampMs}")

        val (_, launch) = runAll({ gear(Gear.DRIVE); hold(500); rampTo(45f, 3_000); hold(3_000) }) // +15 km/h/s
        val iDep = launch.indexOfFirst { it is DrivingEvent.Departed }
        val iAcc = launch.indexOfFirst { it is DrivingEvent.HardAccel }
        assertTrue(iDep in 0 until iAcc, "급출발은 트립 안에서 기록: $launch")
        assertEquals(launch[iDep].timestampMs, launch[iAcc].timestampMs)
    }

    @Test
    fun `speed unsupported keeps gear based trips and comfort`() {
        val d = DrivingEventDetector(DetectionConfig(comfortTickMs = 1_000, comfortPenaltyMs = 0))
        val start = d.process(VehicleSnapshot(0, null, Gear.DRIVE))
        assertTrue(start.any { it is DrivingEvent.Departed })
        val rest = (1..5).flatMap { d.process(VehicleSnapshot(it * 1_000L, null, Gear.DRIVE)) } +
            (6..9).flatMap { d.process(VehicleSnapshot(it * 1_000L, null, Gear.PARK)) }
        assertEquals(6_000L, d.tripComfortMs)
        assertEquals(1, rest.count { it is DrivingEvent.Parked })
    }
}
