package com.pikodrive.voice

import android.content.Context
import android.util.Log
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import com.pikodrive.core.pet.SpeechRequest
import com.pikodrive.core.pet.SpeechSink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Pleos TTS(ON_DEVICE) 출력부. S0/S3 측정: speak→onStart 43~229ms, stop→무음 ~205ms.
 *
 * - 리스너는 initialize 성공 후 등록 (먼저 등록하면 SDK 내부 예외)
 * - `speak(text, isFinal = true)`: false면 스트리밍으로 보고 onDone을 보내지 않는다
 * - 발화는 [speakLock]으로 하나씩만. 콜백은 "현재 발화가 onStart를 받은 뒤"에만 완료로 인정해서
 *   이전 발화의 늦은 onDone/onStop이 다음 발화를 끝내 버리지 않게 한다
 * - SDK 예외는 false (엔진이 재시도·폐기), 취소는 즉시 stop() 후 재전파
 */
class PleosSpeechSink(private val context: Context) : SpeechSink {

    private val initLock = Mutex()
    private val speakLock = Mutex()
    @Volatile private var tts: TextToSpeech? = null
    @Volatile private var current: Utterance? = null

    private class Utterance {
        val done = CompletableDeferred<Boolean>()
        @Volatile var started = false
    }

    private val events = object : EventListener {
        override fun onStart() { current?.started = true }
        override fun onReady() = Unit
        override fun onDone() { finish(true) }
        override fun onError(error: ErrorType) {
            Log.w(TAG, "tts error $error")
            finish(false)
        }
        override fun onStop() { finish(false) }
        override fun onUpdatedRms(rms: Double) = Unit
    }

    private fun finish(ok: Boolean) {
        val u = current ?: return
        if (u.started) u.done.complete(ok)
    }

    private suspend fun ready(): TextToSpeech? = initLock.withLock {
        tts?.let { return@withLock it }
        val t = runCatching { TextToSpeech(context, Mode.ON_DEVICE) }
            .onFailure { Log.w(TAG, "tts create failed", it) }.getOrNull() ?: return@withLock null
        val ok = withTimeoutOrNull(INIT_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                runCatching {
                    t.initialize(object : OnInitializeListener {
                        override fun onSuccess() { if (cont.isActive) cont.resume(true) }
                        override fun onFailure(error: ErrorType) {
                            Log.w(TAG, "tts init failed $error")
                            if (cont.isActive) cont.resume(false)
                        }
                    })
                }.onFailure { if (cont.isActive) cont.resume(false) }
            }
        } ?: false
        if (!ok || runCatching { t.addEventListener(events) }.isFailure) {
            runCatching { t.release() }
            return@withLock null
        }
        tts = t
        t
    }

    override suspend fun speak(request: SpeechRequest): Boolean = speakLock.withLock {
        val t = ready() ?: return@withLock false
        val u = Utterance()
        current = u
        try {
            t.speak(request.text, true)
            withTimeoutOrNull(SPEAK_TIMEOUT_MS) { u.done.await() } ?: false
        } catch (e: CancellationException) {
            runCatching { t.stop() }
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "tts speak failed", e)
            false
        } finally {
            if (current === u) current = null
        }
    }

    /** 엔진이 R단·부팅·출발 시 호출. 진행 중 발화를 즉시 끊는다. */
    override fun stop() {
        current?.done?.complete(false)
        runCatching { tts?.stop() }
    }

    /** stop → 대기 중 발화 해제 → release 순서 보장 */
    fun release() {
        stop()
        runCatching { tts?.release() }
        tts = null
    }

    private companion object {
        const val TAG = "PikoTts"
        const val INIT_TIMEOUT_MS = 5_000L
        /** 대사는 3초 이내. 여유 포함 상한 */
        const val SPEAK_TIMEOUT_MS = 8_000L
    }
}
