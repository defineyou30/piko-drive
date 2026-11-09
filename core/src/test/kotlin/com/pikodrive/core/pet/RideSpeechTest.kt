package com.pikodrive.core.pet

import com.pikodrive.core.model.DriveMode
import com.pikodrive.core.vehicle.Clock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** v3 루틴 대사 경로 (PetEngine.sayRide): 아이 대사 대기열, 운전자 알림 우선, 해제 시 취소. */
@OptIn(ExperimentalCoroutinesApi::class)
class RideSpeechTest {
    private fun TestScope.clock() = Clock { testScheduler.currentTime }
    private fun TestScope.advance(ms: Long) { advanceTimeBy(ms); runCurrent() }
    private fun kid(id: String) = SpeechRequest(id, id, SpeechCategory.RIDE_KID)
    private fun alert(id: String) = SpeechRequest(id, id, SpeechCategory.RIDE_ALERT)

    @Test
    fun `second kid line waits for the first instead of being dropped`() = runTest {
        val sink = FakeSpeechSink(clock(), speakDurationMs = 2_000)
        val e = PetEngine(backgroundScope, sink, clock())
        e.onModeChanged(DriveMode.PARKED)
        e.sayRide(kid("a"), null, urgent = false)
        e.sayRide(kid("b"), null, urgent = false)
        advance(5_000)
        assertEquals(listOf("a", "b"), sink.spoken.map { it.request.lineId })
        assertEquals(2_000L, sink.spoken[1].atMs)
    }

    @Test
    fun `stale queued kid line is dropped`() = runTest {
        val sink = FakeSpeechSink(clock(), speakDurationMs = 9_000)
        val e = PetEngine(backgroundScope, sink, clock())
        e.onModeChanged(DriveMode.PARKED)
        e.sayRide(kid("a"), null, urgent = false)
        e.sayRide(kid("b"), null, urgent = false)
        advance(20_000)
        assertEquals(listOf("a"), sink.spoken.map { it.request.lineId })
        assertEquals(SpeechOutcome.DROPPED_BUSY, e.speechLog.value.last().outcome)
    }

    @Test
    fun `driver alert cuts a kid line and ignores driving budget`() = runTest {
        val sink = FakeSpeechSink(clock(), speakDurationMs = 3_000)
        val e = PetEngine(backgroundScope, sink, clock())
        e.onModeChanged(DriveMode.DRIVING)
        e.sayRide(kid("hello"), null, urgent = false)
        advance(500)
        e.sayRide(alert("belt"), null, urgent = true)
        advance(500)
        e.sayRide(alert("belt2"), null, urgent = true) // 예산 30초 간격과 무관
        advance(5_000)
        assertEquals(listOf("hello", "belt", "belt2"), sink.attempts.map { it.request.lineId })
        assertEquals(2, sink.stopCount)
    }

    @Test
    fun `cancelSpeech stops a matching alert`() = runTest {
        val sink = FakeSpeechSink(clock(), speakDurationMs = 3_000)
        val e = PetEngine(backgroundScope, sink, clock())
        e.onModeChanged(DriveMode.PARKED)
        e.sayRide(alert("front_left_behind_2"), null, urgent = true)
        advance(500)
        e.cancelSpeech { it.lineId.startsWith("front_left_behind") }
        advance(5_000)
        assertEquals(SpeechOutcome.INTERRUPTED, e.speechLog.value.last().outcome)
    }

    @Test
    fun `v4 config silences v2 driving reactions but keeps trip records`() = runTest {
        val sink = FakeSpeechSink(clock())
        val e = PetEngine(backgroundScope, sink, clock(), PetConfig(drivingReactions = false))
        e.onModeChanged(DriveMode.DRIVING)
        e.onEvent(com.pikodrive.core.driving.DrivingEvent.Departed(0))
        e.onEvent(com.pikodrive.core.driving.DrivingEvent.HardBrake(1_000, -12f))
        e.onEvent(com.pikodrive.core.driving.DrivingEvent.SmoothStreak(2_000, 60_000))
        advance(10_000)
        e.onModeChanged(DriveMode.PARKED)
        e.onEvent(com.pikodrive.core.driving.DrivingEvent.Parked(12_000))
        advance(5_000)
        assertEquals(emptyList<FakeSpeechSink.Attempt>(), sink.attempts)
        assertEquals(1, e.tripReports.replayCache.size)
    }

    @Test
    fun `two kid lines and a memo all wait their turn`() = runTest {
        val sink = FakeSpeechSink(clock(), speakDurationMs = 2_000)
        val e = PetEngine(backgroundScope, sink, clock())
        e.onModeChanged(DriveMode.PARKED)
        e.sayRide(kid("arrive_a"), null, urgent = false)
        e.sayRide(kid("arrive_b"), null, urgent = false)
        e.sayRide(SpeechRequest("memo", "메모", SpeechCategory.AGENT), null, urgent = false)
        advance(7_000)
        assertEquals(listOf("arrive_a", "arrive_b", "memo"), sink.spoken.map { it.request.lineId })
    }

    @Test
    fun `memo cut by an urgent alert is spoken again after it`() = runTest {
        val sink = FakeSpeechSink(clock(), speakDurationMs = 2_000)
        val e = PetEngine(backgroundScope, sink, clock())
        e.onModeChanged(DriveMode.PARKED)
        e.sayRide(SpeechRequest("agent_memo_arrive", "메모예요. 알림장 받아 오기", SpeechCategory.AGENT), null, urgent = false)
        advance(500)
        e.sayRide(alert("front_street_door"), null, urgent = true)
        advance(6_000)
        assertEquals(listOf("agent_memo_arrive", "front_street_door", "agent_memo_arrive"), sink.attempts.map { it.request.lineId })
    }

    @Test
    fun `a memo is brought back only once even if alerts keep cutting it`() = runTest {
        val sink = FakeSpeechSink(clock(), speakDurationMs = 2_000)
        val e = PetEngine(backgroundScope, sink, clock())
        e.onModeChanged(DriveMode.PARKED)
        e.sayRide(SpeechRequest("agent_memo_alight", "메모예요.", SpeechCategory.AGENT), null, urgent = false)
        advance(500)
        e.sayRide(alert("front_left_behind_1"), null, urgent = true)
        advance(2_500)                                   // 알림 끝 → 메모 다시 시작
        e.sayRide(alert("front_left_behind_2"), null, urgent = true)   // 다시 끊음
        advance(8_000)
        assertEquals(2, sink.attempts.count { it.request.lineId == "agent_memo_alight" })
    }
}
