package com.pikodrive.core.pet

import com.pikodrive.core.driving.DrivingEvent
import com.pikodrive.core.model.DriveMode
import com.pikodrive.core.model.Expression
import com.pikodrive.core.vehicle.Clock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** S7 신호 확장: 배터리·충전·동승자 반응 규칙. */
@OptIn(ExperimentalCoroutinesApi::class)
class VehicleSignalEngineTest {

    private fun TestScope.clock() = Clock { testScheduler.currentTime }
    private fun TestScope.engine(sink: SpeechSink) = PetEngine(backgroundScope, sink, clock(), PetConfig())
    private fun TestScope.now() = testScheduler.currentTime
    private fun TestScope.advance(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    @Test
    fun `battery low while parked - sleepy plus energy line, sleepy stays as baseline`() = runTest {
        val sink = FakeSpeechSink(clock())
        val e = engine(sink)
        e.onModeChanged(DriveMode.PARKED)
        e.onEvent(DrivingEvent.BatteryLow(now(), 19))
        runCurrent()
        assertTrue(e.state.value.batteryLow)
        assertEquals(Expression.SLEEPY, e.state.value.expression)
        val line = sink.spoken.single().request
        assertEquals(SpeechCategory.ENERGY, line.category)
        assertTrue(line.lineId.startsWith("energy_low"))
        // 쓰다듬기 HAPPY → 유지 시간 후 CALM이 아니라 SLEEPY로 복귀
        e.petByHand()
        assertEquals(Expression.HAPPY, e.state.value.expression)
        advance(10_000)
        assertEquals(Expression.SLEEPY, e.state.value.expression)
        // 음성 off면 QUIET 우선
        e.voiceEnabled = false
        assertEquals(Expression.QUIET, e.state.value.expression)
        e.voiceEnabled = true
        assertEquals(Expression.SLEEPY, e.state.value.expression)
        // 25% 회복 → 기본 표정 CALM
        e.onEvent(DrivingEvent.BatteryRecovered(now(), 25))
        assertFalse(e.state.value.batteryLow)
        assertEquals(Expression.CALM, e.state.value.expression)
    }

    @Test
    fun `sleepy baseline only while parked`() = runTest {
        val sink = FakeSpeechSink(clock())
        val e = engine(sink)
        e.onModeChanged(DriveMode.DRIVING)
        e.onEvent(DrivingEvent.BatteryLow(now(), 20))
        runCurrent()
        assertEquals(Expression.SLEEPY, e.state.value.expression)
        assertEquals(SpeechCategory.ENERGY, sink.spoken.single().request.category, "주행 중에도 예산 안이면 발화")
        advance(10_000)
        assertEquals(Expression.CALM, e.state.value.expression, "주행 중 기본 표정은 CALM")
        e.onModeChanged(DriveMode.PARKED)
        assertEquals(Expression.SLEEPY, e.state.value.expression, "정차 + 배터리 부족 → SLEEPY")
        advance(20_000)
        e.onModeChanged(DriveMode.DRIVING)
        assertEquals(Expression.CALM, e.state.value.expression, "다시 출발하면 CALM")
    }

    @Test
    fun `charging started reacts only when parked`() = runTest {
        val sink = FakeSpeechSink(clock())
        val e = engine(sink)
        e.onModeChanged(DriveMode.DRIVING)
        e.onEvent(DrivingEvent.ChargingStarted(now()))
        runCurrent()
        assertTrue(sink.attempts.isEmpty())
        assertEquals(Expression.CALM, e.state.value.expression)
        e.onModeChanged(DriveMode.PARKED)
        e.onEvent(DrivingEvent.ChargingStarted(now()))
        runCurrent()
        assertEquals(Expression.HAPPY, e.state.value.expression)
        val line = sink.spoken.single().request
        assertEquals(SpeechCategory.ENERGY, line.category)
        assertTrue(line.lineId.startsWith("energy_charge"))
    }

    @Test
    fun `passenger joined while parked - happy and passenger line`() = runTest {
        val sink = FakeSpeechSink(clock())
        val e = engine(sink)
        e.onModeChanged(DriveMode.PARKED)
        val bondBefore = e.state.value.bond
        e.onEvent(DrivingEvent.PassengerJoined(now()))
        runCurrent()
        assertEquals(Expression.HAPPY, e.state.value.expression)
        assertEquals(SpeechCategory.PASSENGER, sink.spoken.single().request.category)
        assertTrue(e.state.value.bond > bondBefore)
    }

    @Test
    fun `new signals are silent while reversing or booting`() = runTest {
        val sink = FakeSpeechSink(clock())
        val e = engine(sink)
        val events = { listOf(
            DrivingEvent.BatteryLow(now(), 15), DrivingEvent.ChargingStarted(now()), DrivingEvent.PassengerJoined(now()),
        ) }
        events().forEach(e::onEvent) // BOOTING
        e.onModeChanged(DriveMode.REVERSING)
        advance(1_000)
        events().forEach(e::onEvent)
        advance(30_000)
        assertTrue(sink.attempts.isEmpty())
        assertEquals(Expression.CALM, e.state.value.expression)
        assertTrue(e.speechLog.value.isEmpty())
        // 상태 기록은 계속: 이후 정차하면 배터리 부족 기본 표정
        assertTrue(e.state.value.batteryLow)
        e.onModeChanged(DriveMode.PARKED)
        assertEquals(Expression.SLEEPY, e.state.value.expression)
    }

    @Test
    fun `driving - new signals obey global budget and category cooldown`() = runTest {
        val sink = FakeSpeechSink(clock())
        val e = engine(sink)
        e.onModeChanged(DriveMode.DRIVING)
        e.onEvent(DrivingEvent.Departed(now()))            // GREETING @0
        advance(10_000)
        e.onEvent(DrivingEvent.PassengerJoined(now()))      // 10초 뒤 → 전역 30초 간격 위반
        advance(25_000)
        e.onEvent(DrivingEvent.BatteryLow(now(), 20))       // 35초 → 허용
        advance(35_000)
        e.onEvent(DrivingEvent.BatteryLow(now(), 19))       // 70초: 예산은 통과, ENERGY 쿨다운 2분 위반
        runCurrent()
        val outcomes = e.speechLog.value.map { it.request.category to it.outcome }
        assertEquals(
            listOf(
                SpeechCategory.GREETING to SpeechOutcome.SPOKEN,
                SpeechCategory.PASSENGER to SpeechOutcome.DROPPED_BUDGET,
                SpeechCategory.ENERGY to SpeechOutcome.SPOKEN,
                SpeechCategory.ENERGY to SpeechOutcome.DROPPED_COOLDOWN,
            ),
            outcomes,
        )
    }

    @Test
    fun `engine start clears a stale persisted battery flag`() = runTest {
        val e = PetEngine(backgroundScope, FakeSpeechSink(clock()), clock(), initialState = PetState(batteryLow = true))
        assertFalse(e.state.value.batteryLow, "배터리 상태는 세션마다 차량 값으로 다시 판정")
    }

    @Test
    fun `energy and passenger lines exist with two variants each`() {
        val lines = DefaultLines.all
        assertEquals(2, lines.getValue(SpeechCategory.ENERGY).count { "low" in it.lineId })
        assertEquals(2, lines.getValue(SpeechCategory.ENERGY).count { "charge" in it.lineId })
        assertEquals(2, lines.getValue(SpeechCategory.PASSENGER).size)
        val p = ReactionPolicy()
        val ids = (1..3).map { p.react(DrivingEvent.PassengerJoined(0))!!.speech!!.lineId }
        assertEquals(listOf("passenger_1", "passenger_2", "passenger_1"), ids)
        assertFalse(p.react(DrivingEvent.BatteryLow(0, 20))!!.delayed)
    }
}
