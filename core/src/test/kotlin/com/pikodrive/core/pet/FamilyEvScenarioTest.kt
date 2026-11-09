package com.pikodrive.core.pet

import com.pikodrive.core.driving.DrivingEvent
import com.pikodrive.core.model.Expression
import com.pikodrive.core.sim.Scenarios
import com.pikodrive.core.sim.SimulatedVehicleGateway
import com.pikodrive.core.vehicle.Clock
import com.pikodrive.core.vehicle.VehicleCapabilities
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** S7 데모 각본(familyEv) 전체 통합 + 시뮬레이터 마스킹. */
@OptIn(ExperimentalCoroutinesApi::class)
class FamilyEvScenarioTest {

    @Test
    fun `familyEv demo end to end`() = runTest {
        val clock = Clock { testScheduler.currentTime }
        val sink = FakeSpeechSink(clock)
        val session = PikoSession.create(
            scope = backgroundScope,
            gateway = SimulatedVehicleGateway(Scenarios.familyEv, clock),
            speechSink = sink,
            clock = clock,
            demo = true,
        )
        val events = mutableListOf<DrivingEvent>()
        backgroundScope.launch { session.events.collect { events += it } }
        runCurrent()
        session.start()
        advanceTimeBy(75_000)
        runCurrent()

        assertEquals(listOf(3_000L), events.filterIsInstance<DrivingEvent.PassengerJoined>().map { it.timestampMs })
        assertEquals(listOf(41_000L), events.filterIsInstance<DrivingEvent.BatteryLow>().map { it.timestampMs })
        assertEquals(listOf(64_000L), events.filterIsInstance<DrivingEvent.ChargingStarted>().map { it.timestampMs })
        assertEquals(1, events.filterIsInstance<DrivingEvent.Parked>().size)
        assertEquals(listOf(11_000L), events.filterIsInstance<DrivingEvent.Departed>().map { it.timestampMs }, "D 진입(6s)이 아니라 이동 확정 시점")

        assertEquals(
            listOf(
                SpeechCategory.PASSENGER, SpeechCategory.GREETING, SpeechCategory.ENERGY,
                SpeechCategory.ARRIVAL, SpeechCategory.ENERGY,
            ),
            sink.spoken.map { it.request.category },
        )
        assertEquals(41_000L, sink.spoken[2].atMs, "배터리 알림은 지연 없이")
        assertTrue(sink.spoken[2].request.lineId.startsWith("energy_low"))
        assertTrue(sink.spoken[4].request.lineId.startsWith("energy_charge"))
        // 배터리 알림 직후의 칭찬(스트릭)은 전역 예산으로 생략된다
        assertTrue(session.speechLog.value.any {
            it.request.category == SpeechCategory.COMFORT && it.outcome == SpeechOutcome.DROPPED_BUDGET
        })
        assertTrue(session.pet.value.batteryLow)
        assertEquals(Expression.SLEEPY, session.pet.value.expression, "정차 + 배터리 부족 → 졸린 기본 표정")
    }

    @Test
    fun `battery and passenger are masked when capabilities are off`() = runTest {
        val clock = Clock { testScheduler.currentTime }
        val off = SimulatedVehicleGateway(Scenarios.familyEv, clock, capabilities = VehicleCapabilities.Full)
        val masked = off.snapshots.toList()
        assertTrue(masked.all { it.batteryPct == null && it.chargePortOpen == null && it.passengerPresent == null })
        val on = SimulatedVehicleGateway(Scenarios.familyEv, clock).snapshots.toList()
        assertEquals(VehicleCapabilities.All, SimulatedVehicleGateway(Scenarios.familyEv, clock).capabilities)
        assertEquals(18, on.last().batteryPct)
        assertTrue(on.last().chargePortOpen == true && on.last().passengerPresent == true)
        assertTrue(Scenarios.familyEv.durationMs in 55_000L..70_000L)
    }
}
