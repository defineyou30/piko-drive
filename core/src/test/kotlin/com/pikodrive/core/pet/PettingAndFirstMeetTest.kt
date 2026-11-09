package com.pikodrive.core.pet

import com.pikodrive.core.driving.DrivingEvent
import com.pikodrive.core.model.DriveMode
import com.pikodrive.core.model.Expression
import com.pikodrive.core.model.Gear
import com.pikodrive.core.sim.SimulatedVehicleGateway
import com.pikodrive.core.sim.scenario
import com.pikodrive.core.vehicle.Clock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** 기능 평가 D3(세션 수준)·D6·D7. */
@OptIn(ExperimentalCoroutinesApi::class)
class PettingAndFirstMeetTest {

    @BeforeEach
    fun reset() = PetEngine.resetFirstMeetForTests()

    private fun TestScope.clock() = Clock { testScheduler.currentTime }
    private fun TestScope.engine(sink: SpeechSink) = PetEngine(backgroundScope, sink, clock(), PetConfig())
    private fun TestScope.advance(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    @Test
    fun `petting counts at most 3 times per parked session then only smiles`() = runTest {
        val sink = FakeSpeechSink(clock())
        val e = engine(sink)
        e.onModeChanged(DriveMode.PARKED)
        repeat(3) { e.petByHand(); runCurrent() }
        val afterThree = e.state.value
        assertEquals(3, sink.spoken.count { it.request.category == SpeechCategory.AFFECTION })
        advance(10_000)
        assertEquals(Expression.CALM, e.state.value.expression)
        e.petByHand(); runCurrent()
        assertEquals(Expression.HAPPY, e.state.value.expression, "4번째는 표정만")
        assertEquals(afterThree.mood, e.state.value.mood)
        assertEquals(afterThree.bond, e.state.value.bond)
        assertEquals(3, sink.attempts.size, "4번째는 대사 없음")
        // 주행 후 새 정차 → 다시 3회
        e.onModeChanged(DriveMode.DRIVING)
        e.onModeChanged(DriveMode.PARKED)
        e.petByHand(); runCurrent()
        assertTrue(e.state.value.bond > afterThree.bond)
        assertEquals(4, sink.spoken.count { it.request.category == SpeechCategory.AFFECTION })
    }

    @Test
    fun `first meet greeting only when parked and only once`() = runTest {
        val sink = FakeSpeechSink(clock())
        val e = engine(sink)
        assertFalse(e.greetFirstMeet(), "BOOTING에선 안 함 (소모되지도 않음)")
        e.onModeChanged(DriveMode.PARKED)
        assertTrue(e.greetFirstMeet())
        runCurrent()
        assertEquals(Expression.HAPPY, e.state.value.expression)
        val hello = sink.spoken.single().request
        assertEquals("hello_1", hello.lineId)
        assertEquals("안녕하세요, 피코예요.", hello.text)
        assertEquals(SpeechCategory.GREETING, hello.category)
        assertFalse(e.greetFirstMeet(), "두 번째는 무시")
        assertFalse(engine(FakeSpeechSink(clock())).also { it.onModeChanged(DriveMode.PARKED) }.greetFirstMeet(), "같은 프로세스 새 엔진도 무시")
        // 곧바로 출발해도 출발 인사는 쿨다운에 막히지 않고, 첫 만남 대사가 섞이지 않는다
        advance(5_000)
        e.onModeChanged(DriveMode.DRIVING)
        e.onEvent(DrivingEvent.Departed(testScheduler.currentTime))
        runCurrent()
        assertTrue(sink.spoken.last().request.lineId.startsWith("greet_"), "${sink.spoken.last()}")
    }

    @Test
    fun `gear toggle without movement gives no trip report and no arrival speech`() = runTest {
        val clock = clock()
        val sink = FakeSpeechSink(clock)
        val gw = SimulatedVehicleGateway(
            scenario("toggle") {
                start(Gear.PARK, 0f, uxRestricted = false); hold(2_000)
                gear(Gear.DRIVE); uxRestricted(true); hold(1_000)
                gear(Gear.PARK); uxRestricted(false); hold(2_000)
                gear(Gear.REVERSE); uxRestricted(true); rampTo(5f, 1_000); hold(3_000); rampTo(0f, 1_000)
                gear(Gear.PARK); uxRestricted(false); hold(6_000)
            },
            clock,
        )
        val session = PikoSession.create(backgroundScope, gw, sink, clock = clock, demo = true)
        session.start()
        advance(20_000)
        assertTrue(session.tripReports.replayCache.isEmpty())
        assertTrue(sink.attempts.none { it.request.category == SpeechCategory.ARRIVAL || it.request.category == SpeechCategory.GREETING })
        assertEquals(DriveMode.PARKED, session.mode.value)
    }
}
