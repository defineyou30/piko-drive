package com.pikodrive.core.pet

import com.pikodrive.core.driving.DrivingEvent
import com.pikodrive.core.model.DriveMode
import com.pikodrive.core.model.Expression
import com.pikodrive.core.model.GrowthStage
import com.pikodrive.core.vehicle.Clock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PetEngineTest {

    private fun TestScope.clock() = Clock { testScheduler.currentTime }

    private fun TestScope.engine(
        sink: SpeechSink,
        config: PetConfig = PetConfig(),
        initial: PetState = PetState.Initial,
    ) = PetEngine(backgroundScope, sink, clock(), config, initialState = initial)

    private fun TestScope.now() = testScheduler.currentTime

    private fun TestScope.advance(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    private fun TestScope.brake() = DrivingEvent.HardBrake(now(), -12f)

    @Test
    fun `hard brake reaction is delayed 2_5s then surprised + startle line`() = runTest {
        val sink = FakeSpeechSink(clock())
        val e = engine(sink)
        e.onModeChanged(DriveMode.DRIVING)
        e.onEvent(brake())
        advance(2_400)
        assertEquals(Expression.CALM, e.state.value.expression)
        assertTrue(sink.attempts.isEmpty())
        assertTrue(e.state.value.mood < PetState.Initial.mood, "기분 변화는 즉시")
        advance(100)
        assertEquals(Expression.SURPRISED, e.state.value.expression)
        assertEquals(2_500L, sink.spoken.single().atMs)
        assertEquals(SpeechCategory.STARTLE, sink.spoken.single().request.category)
        // 일시 표정은 10초 뒤 기본으로 복귀
        advance(10_000)
        assertEquals(Expression.CALM, e.state.value.expression)
    }

    @Test
    fun `reaction pending when shifting to reverse is dropped`() = runTest {
        val sink = FakeSpeechSink(clock())
        val e = engine(sink)
        e.onModeChanged(DriveMode.DRIVING)
        e.onEvent(brake())
        advance(1_000)
        e.onModeChanged(DriveMode.REVERSING)
        advance(5_000)
        assertTrue(sink.attempts.isEmpty())
        assertEquals(Expression.CALM, e.state.value.expression)
    }

    @Test
    fun `reversing means no speech and no expression change`() = runTest {
        val sink = FakeSpeechSink(clock())
        val e = engine(sink)
        e.onModeChanged(DriveMode.REVERSING)
        e.onEvent(DrivingEvent.Departed(now()))
        e.onEvent(brake())
        e.onEvent(DrivingEvent.SmoothStreak(now(), 300_000))
        e.onEvent(DrivingEvent.LongDrive(now(), 3_600_000))
        advance(30_000)
        assertTrue(sink.attempts.isEmpty())
        assertEquals(PetState.Initial.copy(), e.state.value)
    }

    @Test
    fun `booting is silent`() = runTest {
        val sink = FakeSpeechSink(clock())
        val e = engine(sink)
        assertEquals(DriveMode.BOOTING, e.mode.value)
        e.onEvent(DrivingEvent.SmoothStreak(now(), 300_000))
        e.onEvent(brake())
        e.petByHand()
        advance(30_000)
        assertTrue(sink.attempts.isEmpty())
        assertEquals(Expression.CALM, e.state.value.expression)
    }

    @Test
    fun `same category cooldown is 2 minutes while driving, other categories pass`() = runTest {
        val sink = FakeSpeechSink(clock())
        val e = engine(sink)
        e.onModeChanged(DriveMode.DRIVING)
        e.onEvent(DrivingEvent.SmoothStreak(now(), 300_000))      // COMFORT @0
        advance(30_000)
        e.onEvent(DrivingEvent.SmoothStreak(now(), 330_000))      // COMFORT @30s → 쿨다운
        e.onEvent(DrivingEvent.LongDrive(now(), 3_600_000))       // REST @30s → 통과
        advance(90_000)                                           // @120s
        e.onEvent(DrivingEvent.SmoothStreak(now(), 600_000))      // COMFORT @120s → 통과
        advance(1)
        val categories = sink.spoken.map { it.request.category to it.atMs }
        assertEquals(
            listOf(SpeechCategory.COMFORT to 0L, SpeechCategory.REST to 30_000L, SpeechCategory.COMFORT to 120_000L),
            categories,
        )
        assertTrue(e.speechLog.value.any { it.outcome == SpeechOutcome.DROPPED_COOLDOWN })
    }

    @Test
    fun `no cooldown while parked`() = runTest {
        val sink = FakeSpeechSink(clock())
        val e = engine(sink)
        e.onModeChanged(DriveMode.PARKED)
        e.petByHand(); advance(3_000)
        e.petByHand(); advance(3_000)
        assertEquals(2, sink.spoken.size)
    }

    @Test
    fun `expression changes at most once per 10s while driving`() = runTest {
        val sink = FakeSpeechSink(clock())
        val e = engine(sink, PetConfig(expressionHoldMs = 60_000))
        e.onModeChanged(DriveMode.DRIVING)
        e.onEvent(DrivingEvent.SmoothStreak(now(), 300_000))   // HAPPY @0
        assertEquals(Expression.HAPPY, e.state.value.expression)
        advance(4_000)
        e.onEvent(DrivingEvent.LongDrive(now(), 3_600_000))    // SLEEPY @4s → 무시
        assertEquals(Expression.HAPPY, e.state.value.expression)
        advance(6_000)
        e.onEvent(DrivingEvent.LongDrive(now(), 7_200_000))    // SLEEPY @10s → 허용
        assertEquals(Expression.SLEEPY, e.state.value.expression)
    }

    @Test
    fun `startle is a priority expression - 3s gap instead of 10s`() = runTest {
        val sink = FakeSpeechSink(clock())
        val e = engine(sink, PetConfig(expressionHoldMs = 60_000, speechCooldownMs = 0))
        e.onModeChanged(DriveMode.DRIVING)
        e.onEvent(DrivingEvent.SmoothStreak(now(), 300_000))   // HAPPY @0
        advance(1_000)
        e.onEvent(DrivingEvent.HardBrake(now(), -20f))          // 2.5초 지연 → @3.5s, 우선 표정이라 3초 간격만
        advance(2_600)
        assertEquals(Expression.SURPRISED, e.state.value.expression)
        advance(1_000)
        e.onEvent(DrivingEvent.SmoothStreak(now(), 600_000))   // 일반 표정 @4.6s → 10초 제한으로 무시
        assertEquals(Expression.SURPRISED, e.state.value.expression)
    }

    @Test
    fun `priority expression still respects the 3s minimum`() = runTest {
        val e = engine(FakeSpeechSink(clock()), PetConfig(expressionHoldMs = 60_000, speechCooldownMs = 0))
        e.onModeChanged(DriveMode.DRIVING)
        e.onEvent(DrivingEvent.SmoothStreak(now(), 300_000))   // HAPPY @0
        advance(1)
        e.onEvent(DrivingEvent.BatteryLow(now(), 18))          // 즉시 반응이지만 @0 → 3초 안이라 무시
        assertEquals(Expression.HAPPY, e.state.value.expression)
    }

    @Test
    fun `expression rate limit does not apply while parked`() = runTest {
        val e = engine(FakeSpeechSink(clock()))
        e.onModeChanged(DriveMode.PARKED)
        e.voiceEnabled = false
        assertEquals(Expression.QUIET, e.state.value.expression)
        e.petByHand()
        assertEquals(Expression.HAPPY, e.state.value.expression)
    }

    @Test
    fun `audio focus failure retries once then succeeds`() = runTest {
        val sink = FakeSpeechSink(clock(), ArrayDeque(listOf(false, true)))
        val e = engine(sink)
        e.onModeChanged(DriveMode.DRIVING)
        e.onEvent(DrivingEvent.Departed(now()))
        advance(10_000)
        assertEquals(listOf(0L, 4_000L), sink.attempts.map { it.atMs })
        assertEquals(SpeechOutcome.SPOKEN_AFTER_RETRY, e.speechLog.value.single().outcome)
    }

    @Test
    fun `audio focus failure twice is dropped within 10s`() = runTest {
        val sink = FakeSpeechSink(clock(), default = false)
        val e = engine(sink)
        e.onModeChanged(DriveMode.DRIVING)
        e.onEvent(DrivingEvent.Departed(now()))
        advance(60_000)
        assertEquals(2, sink.attempts.size, "재시도는 정확히 1회")
        assertTrue(sink.attempts.last().atMs <= 10_000)
        assertEquals(SpeechOutcome.DROPPED_FOCUS, e.speechLog.value.single().outcome)
    }

    @Test
    fun `pending retry is cancelled when mode switched to reverse`() = runTest {
        val sink = FakeSpeechSink(clock(), default = false)
        val e = engine(sink)
        e.onModeChanged(DriveMode.DRIVING)
        e.onEvent(DrivingEvent.Departed(now()))
        advance(1_000)
        e.onModeChanged(DriveMode.REVERSING)
        advance(10_000)
        assertEquals(1, sink.attempts.size)
        assertEquals(SpeechOutcome.INTERRUPTED, e.speechLog.value.single().outcome)
    }

    @Test
    fun `growth commits on park and unlocks stage`() = runTest {
        val e = engine(FakeSpeechSink(clock()))
        e.onModeChanged(DriveMode.DRIVING)
        e.onEvent(DrivingEvent.Departed(now()))
        e.onEvent(DrivingEvent.ComfortAccrued(now(), 16 * 60_000L))
        // 주행 중엔 성장 연출 금지 → 단계·픽셀은 그대로
        assertEquals(GrowthStage.SPARK, e.state.value.stage)
        assertEquals(1, e.state.value.pixelCount)
        e.onModeChanged(DriveMode.PARKED)
        e.onEvent(DrivingEvent.Parked(now()))
        assertEquals(GrowthStage.EYES, e.state.value.stage)
        assertEquals(Growth.pixelsFor(16.0), e.state.value.pixelCount)
        assertTrue(e.state.value.pixelCount in 6..19)
    }

    @Test
    fun `growth curve hits every stage target at unlock`() {
        GrowthStage.entries.forEach {
            assertEquals(it, Growth.stageFor(it.unlockComfortMinutes.toDouble()))
            assertEquals(it.pixelTarget, Growth.pixelsFor(it.unlockComfortMinutes.toDouble()))
        }
        assertEquals(GrowthStage.SPARK, Growth.stageFor(14.99))
        assertEquals(96, Growth.pixelsFor(10_000.0))
    }

    @Test
    fun `demo multiplier accelerates growth`() = runTest {
        val e = engine(FakeSpeechSink(clock()), PetConfig(growthMultiplier = DEMO_GROWTH_MULTIPLIER))
        e.onModeChanged(DriveMode.DRIVING)
        e.onEvent(DrivingEvent.Departed(now()))
        e.onEvent(DrivingEvent.ComfortAccrued(now(), 30_000))   // 실제 30초
        e.onModeChanged(DriveMode.PARKED)
        e.onEvent(DrivingEvent.Parked(now()))
        assertEquals(180.0, e.state.value.comfortMinutesTotal, 0.001)
        assertEquals(GrowthStage.BODY, e.state.value.stage)
        e.growthMultiplier = 0
        assertEquals(1, e.growthMultiplier, "배수는 최소 1")
    }

    @Test
    fun `trip report contains duration, comfort, startled moments and pixels gained`() = runTest {
        val sink = FakeSpeechSink(clock())
        val e = engine(sink, PetConfig(growthMultiplier = 2))
        e.onModeChanged(DriveMode.DRIVING)
        e.onEvent(DrivingEvent.Departed(now()))
        advance(60_000)
        e.onEvent(DrivingEvent.HardBrake(now(), -11f))
        advance(60_000)
        e.onEvent(DrivingEvent.HardAccel(now(), 13f))
        e.onEvent(DrivingEvent.ComfortAccrued(now(), 10 * 60_000L))
        advance(60_000)
        e.onModeChanged(DriveMode.PARKED)
        e.onEvent(DrivingEvent.Parked(now()))
        val r = e.tripReports.replayCache.single()
        assertEquals(180_000L, r.durationMs)
        assertEquals(10, r.comfortMinutes, "리포트는 배수 미적용 실제 시간")
        assertEquals(listOf(60_000L, 120_000L), r.startledMoments)
        assertEquals(GrowthStage.EYES, r.stageAfter)
        assertEquals(Growth.pixelsFor(20.0) - 1, r.pixelsGained)
        assertTrue(r.stageUnlocked)
        // 도착 인사는 정차 중 자유 발화
        advance(1)
        assertEquals(SpeechCategory.ARRIVAL, sink.spoken.last().request.category)
        assertEquals(Expression.HAPPY, e.state.value.expression)
    }

    @Test
    fun `voice disabled never speaks and rests on QUIET`() = runTest {
        val sink = FakeSpeechSink(clock())
        val e = engine(sink)
        e.onModeChanged(DriveMode.DRIVING)
        e.voiceEnabled = false
        e.onEvent(DrivingEvent.SmoothStreak(now(), 300_000))
        advance(30_000)
        assertTrue(sink.attempts.isEmpty())
        assertEquals(Expression.QUIET, e.state.value.expression)
        assertEquals(SpeechOutcome.DROPPED_VOICE_OFF, e.speechLog.value.single().outcome)
    }

    // ---- Codex 리뷰 반영 ----

    @Test
    fun `delayed reaction is dropped after D to R to D within the delay`() = runTest {
        val sink = FakeSpeechSink(clock())
        val e = engine(sink)
        e.onModeChanged(DriveMode.DRIVING)
        e.onEvent(brake())
        advance(1_000)
        e.onModeChanged(DriveMode.REVERSING)
        advance(1_000)
        e.onModeChanged(DriveMode.DRIVING)
        advance(5_000)
        assertTrue(sink.attempts.isEmpty())
        assertEquals(Expression.CALM, e.state.value.expression)
    }

    @Test
    fun `speech in progress is stopped when entering reverse`() = runTest {
        val sink = FakeSpeechSink(clock(), speakDurationMs = 3_000)
        val e = engine(sink)
        e.onModeChanged(DriveMode.DRIVING)
        e.onEvent(DrivingEvent.Departed(now()))
        advance(1_000)
        assertEquals(1, sink.attempts.size)
        e.onModeChanged(DriveMode.REVERSING)
        advance(5_000)
        assertEquals(1, sink.stopCount)
        assertEquals(0, sink.completed, "발화가 끝까지 재생되지 않음")
        assertEquals(SpeechOutcome.INTERRUPTED, e.speechLog.value.single().outcome)
    }

    @Test
    fun `parked speech is stopped when pulling away and when booting`() = runTest {
        val sink = FakeSpeechSink(clock(), speakDurationMs = 3_000)
        val e = engine(sink)
        e.onModeChanged(DriveMode.PARKED)
        e.petByHand()
        advance(500)
        e.onModeChanged(DriveMode.DRIVING)
        assertEquals(1, sink.stopCount)
        advance(60_000)
        e.onModeChanged(DriveMode.PARKED)
        e.petByHand()
        advance(500)
        e.onModeChanged(DriveMode.BOOTING)
        assertEquals(2, sink.stopCount)
        assertEquals(0, sink.completed)
    }

    @Test
    fun `global speech budget spans categories - 30s gap and 12 per hour`() = runTest {
        val sink = FakeSpeechSink(clock())
        val e = engine(sink, PetConfig(speechCooldownMs = 0))
        e.onModeChanged(DriveMode.DRIVING)
        e.onEvent(DrivingEvent.SmoothStreak(now(), 300_000))       // COMFORT @0 → 발화
        advance(10_000)
        e.onEvent(DrivingEvent.LongDrive(now(), 3_600_000))        // REST @10s → 30초 간격 위반
        advance(20_000)
        e.onEvent(DrivingEvent.Departed(now()))                    // GREETING @30s → 통과
        advance(1)
        assertEquals(
            listOf(SpeechCategory.COMFORT to 0L, SpeechCategory.GREETING to 30_000L),
            sink.spoken.map { it.request.category to it.atMs },
        )
        assertTrue(e.speechLog.value.any { it.outcome == SpeechOutcome.DROPPED_BUDGET })

        // 이후 30초마다 카테고리를 섞어 요청 → 첫 1시간 안에는 12회까지만
        val makers = listOf<() -> DrivingEvent>(
            { DrivingEvent.SmoothStreak(now(), 1) }, { DrivingEvent.LongDrive(now(), 1) }, { DrivingEvent.Departed(now()) },
        )
        var i = 0
        while (now() < 3_590_000) {
            advance(30_000)
            e.onEvent(makers[i++ % makers.size]())
        }
        advance(1)
        assertEquals(12, sink.spoken.count { it.atMs < 3_600_000 })
        // 창이 밀리면 다시 허용
        advance(30_000)
        e.onEvent(DrivingEvent.Departed(now()))
        advance(1)
        assertTrue(sink.spoken.last().atMs >= 3_600_000)
    }

    @Test
    fun `parked speech does not consume driving budget`() = runTest {
        val sink = FakeSpeechSink(clock())
        val e = engine(sink)
        e.onModeChanged(DriveMode.PARKED)
        e.petByHand(); advance(1_000)
        e.onModeChanged(DriveMode.DRIVING)
        e.onEvent(DrivingEvent.Departed(now()))
        advance(1)
        assertEquals(2, sink.spoken.size)
    }

    @Test
    fun `startle skips the 30s min gap but still counts toward hourly cap`() = runTest {
        val sink = FakeSpeechSink(clock())
        val e = engine(sink, PetConfig(speechCooldownMs = 0))
        e.onModeChanged(DriveMode.DRIVING)
        e.onEvent(DrivingEvent.Departed(now()))                    // GREETING @0
        advance(5_000)
        e.onEvent(DrivingEvent.HardBrake(now(), -20f))             // STARTLE: 2.5초 지연 후, 최소 간격 무시
        advance(5_000)
        assertEquals(
            listOf(SpeechCategory.GREETING, SpeechCategory.STARTLE),
            sink.spoken.map { it.request.category },
        )
        advance(1_000)
        e.onEvent(DrivingEvent.LongDrive(now(), 3_600_000))        // 일반 발화는 여전히 30초 간격
        advance(1)
        assertEquals(SpeechOutcome.DROPPED_BUDGET, e.speechLog.value.last().outcome)
    }
}
