package com.pikodrive.core.sim

import app.cash.turbine.test
import com.pikodrive.core.model.Gear
import com.pikodrive.core.vehicle.Clock
import com.pikodrive.core.vehicle.VehicleCapabilities
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SimulatedVehicleGatewayTest {
    private val ramp = scenario("ramp") {
        gear(Gear.DRIVE)
        rampTo(10f, 1_000)
        hold(500)
    }

    @Test
    fun `plays at 10Hz on virtual time with linear interpolation`() = runTest {
        val gw = SimulatedVehicleGateway(ramp, Clock { testScheduler.currentTime })
        gw.snapshots.test {
            val first = awaitItem()
            assertEquals(0L, first.timestampMs)
            assertEquals(Gear.DRIVE, first.gear)
            repeat(4) { awaitItem() }
            val fifth = awaitItem()
            assertEquals(500L, fifth.timestampMs)
            assertEquals(5f, fifth.speedKmh!!, 0.01f)
            assertEquals(500L, testScheduler.currentTime, "가상 시간으로 진행")
            repeat(10) { awaitItem() }
            awaitComplete()
        }
    }

    @Test
    fun `unsupported capabilities are masked like a real car`() = runTest {
        val gw = SimulatedVehicleGateway(
            ramp, Clock { testScheduler.currentTime },
            capabilities = VehicleCapabilities(speed = true, gear = false, uxRestriction = false),
        )
        val all = gw.snapshots.toList()
        assertTrue(all.all { it.gear == Gear.UNKNOWN && it.uxRestricted == null })
        assertEquals(16, all.size)
    }

    @Test
    fun `gaps and spikes are reproduced`() {
        val s = scenario("noise") {
            gear(Gear.DRIVE); rampTo(50f, 1_000)
            spike(500, 10f)
            hold(1_000)
            gap(1_000, 30f)
            hold(500)
        }
        assertEquals(10f, s.snapshotAt(1_500).speedKmh)
        assertEquals(50f, s.snapshotAt(1_600).speedKmh)
        val times = s.samples().map { it.timestampMs }
        assertTrue(times.none { it in 2_001..2_999 })
        assertEquals(30f, s.snapshotAt(3_000).speedKmh)
        assertNull(scenario("x") { speedUnavailable(); hold(100) }.snapshotAt(50).speedKmh)
    }

    @Test
    fun `commuteCompressed is about 90 seconds and ends parked`() {
        val s = Scenarios.commuteCompressed
        assertTrue(s.durationMs in 85_000..95_000)
        assertEquals(Gear.PARK, s.snapshotAt(s.durationMs).gear)
        assertTrue(s.keyframes.any { it.gear == Gear.REVERSE })
    }
}
