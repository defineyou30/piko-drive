package com.pikodrive.core.pet

import com.pikodrive.core.vehicle.Clock

/** 발화 기록 + 시도별 성공/실패 각본. results 소진 후엔 [default]. */
class FakeSpeechSink(
    private val clock: Clock,
    private val results: ArrayDeque<Boolean> = ArrayDeque(),
    private val default: Boolean = true,
    private val speakDurationMs: Long = 0,
) : SpeechSink {
    var stopCount = 0
        private set
    var completed = 0
        private set
    data class Attempt(val atMs: Long, val request: SpeechRequest, val success: Boolean)

    val attempts = mutableListOf<Attempt>()
    val spoken: List<Attempt> get() = attempts.filter { it.success }

    override suspend fun speak(request: SpeechRequest): Boolean {
        val ok = results.removeFirstOrNull() ?: default
        attempts += Attempt(clock.nowMs(), request, ok)
        if (ok && speakDurationMs > 0) kotlinx.coroutines.delay(speakDurationMs)
        if (ok) completed++
        return ok
    }

    override fun stop() {
        stopCount++
    }
}
