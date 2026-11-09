package com.pikodrive.core.pet

import app.cash.turbine.test
import com.pikodrive.core.model.DriveMode
import com.pikodrive.core.model.GrowthStage
import com.pikodrive.core.sim.Scenarios
import com.pikodrive.core.sim.SimulatedVehicleGateway
import com.pikodrive.core.storage.InMemoryPetRepository
import com.pikodrive.core.vehicle.Clock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import com.pikodrive.core.model.Gear
import com.pikodrive.core.sim.scenario
import com.pikodrive.core.vehicle.VehicleGateway
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 90초 데모 시나리오 전체를 엔진까지 통과시키는 통합 테스트. */
@OptIn(ExperimentalCoroutinesApi::class)
class PikoSessionTest {

    @Test
    fun `commuteCompressed demo end to end`() = runTest {
        val clock = Clock { testScheduler.currentTime }
        val sink = FakeSpeechSink(clock)
        val repo = InMemoryPetRepository()
        val session = PikoSession.create(
            scope = backgroundScope,
            gateway = SimulatedVehicleGateway(Scenarios.commuteCompressed, clock),
            speechSink = sink,
            repository = repo,
            clock = clock,
            demo = true,
        )
        val modes = mutableListOf<DriveMode>()
        session.mode.test {
            session.start()
            while (true) {
                val m = awaitItem()
                modes += m
                if (m == DriveMode.PARKED && modes.contains(DriveMode.REVERSING)) break
            }
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(
            listOf(DriveMode.BOOTING, DriveMode.PARKED, DriveMode.DRIVING, DriveMode.REVERSING, DriveMode.PARKED),
            modes,
        )
        lateinit var report: TripReport
        session.tripReports.test { report = awaitItem(); cancelAndIgnoreRemainingEvents() }
        advanceUntilIdle()

        assertEquals(1, report.startledMoments.size, "급제동 1회만")
        assertTrue(report.startledMoments.single() in 66_500L..67_500L, "${report.startledMoments}")
        // 출발 확정 = 5 km/h 이상 3초 이동(≈10s) → 정차 확정(≈84s)
        assertTrue(report.durationMs in 73_000L..75_000L, "${report.durationMs}")
        assertEquals(GrowthStage.SPARK, report.stageBefore)
        assertEquals(GrowthStage.BODY, report.stageAfter, "데모에서 0→3단계")
        assertTrue(report.pixelsGained >= 47)

        val spoken = sink.spoken.map { it.request.category }
        assertEquals(
            listOf(SpeechCategory.GREETING, SpeechCategory.COMFORT, SpeechCategory.STARTLE, SpeechCategory.ARRIVAL),
            spoken,
        )
        val startle = sink.spoken.first { it.request.category == SpeechCategory.STARTLE }
        assertTrue(startle.atMs - report.startledMoments.single() == 2_500L, "급제동 2.5초 후 발화")
        // 후진 구간(75~81s)에는 어떤 발화 시도도 없다
        assertTrue(sink.attempts.none { it.atMs in 75_000L..81_000L })

        assertEquals(GrowthStage.BODY, repo.loadState()!!.stage)
        assertEquals(report, repo.recentReports().single())
    }

    @Test
    fun `restored state survives a new session`() = runTest {
        val clock = Clock { testScheduler.currentTime }
        val repo = InMemoryPetRepository()
        repo.saveState(PetState(mood = 80, bond = 40, comfortMsTotal = 70 * 60_000L))
        val session = PikoSession.create(
            backgroundScope, SimulatedVehicleGateway(Scenarios.commuteCompressed, clock),
            FakeSpeechSink(clock), repo, clock,
        )
        assertEquals(GrowthStage.FACE, session.pet.value.stage, "로드 시 성장 재계산")
        assertEquals(40, session.pet.value.bond)
    }

    @Test
    fun `start is idempotent and stop halts collection`() = runTest {
        val clock = Clock { testScheduler.currentTime }
        var collections = 0
        val base = SimulatedVehicleGateway(Scenarios.commuteCompressed, clock)
        val gateway = object : VehicleGateway {
            override val capabilities = base.capabilities
            override val snapshots = base.snapshots.onStart { collections++ }
        }
        val session = PikoSession.create(backgroundScope, gateway, FakeSpeechSink(clock), clock = clock, demo = true)
        val a = session.start()
        val b = session.start()
        assertSame(a, b)
        advanceTimeBy(10_000); runCurrent()
        assertEquals(1, collections)
        assertEquals(DriveMode.DRIVING, session.mode.value)
        session.stop()
        assertTrue(a.isCancelled)
        val c = session.start()
        assertNotSame(a, c)
        runCurrent()
        assertEquals(2, collections)
    }

    @Test
    fun `restricted park at boot keeps BOOTING and blocks petting`() = runTest {
        val clock = Clock { testScheduler.currentTime }
        val sink = FakeSpeechSink(clock)
        val s = scenario("restricted") {
            start(Gear.PARK, 0f, uxRestricted = true)
            hold(5_000)
            uxRestricted(false)
            hold(2_000)
        }
        val session = PikoSession.create(backgroundScope, SimulatedVehicleGateway(s, clock), sink, clock = clock)
        session.start()
        advanceTimeBy(4_000); runCurrent()
        assertEquals(DriveMode.BOOTING, session.mode.value)
        session.engine.petByHand()
        advanceTimeBy(500); runCurrent()
        assertTrue(sink.attempts.isEmpty())
        advanceTimeBy(1_000); runCurrent()
        assertEquals(DriveMode.PARKED, session.mode.value)
        session.engine.petByHand()
        runCurrent()
        assertEquals(1, sink.spoken.size)
    }
}
