package com.pikodrive.voice

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType
import ai.pleos.playground.stt.constant.Mode
import ai.pleos.playground.stt.listener.OnInitializeListener
import ai.pleos.playground.stt.listener.ResultListener
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.pikodrive.EvidenceLog
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 말로 입력 상태 (입력 화면이 그린다) */
sealed interface VoiceState {
    data object Idle : VoiceState
    data class Listening(val partial: String) : VoiceState
    data class Heard(val text: String) : VoiceState
    data class Failed(val reason: String) : VoiceState
}

/**
 * 정차 중 "말로 입력" — Pleos SpeechToText 3.3.1, 기기 내 인식(ON_DEVICE, 실측: qa/v4/stt-probe.md).
 * 한 번 누르면 한마디 듣고 끝난다. 최종 결과([VoiceState.Heard])를 화면이 VoiceParser로 칸에 나눈다.
 *
 * QA: 에뮬레이터엔 마이크가 없다 → 앱 내부 files/voice_inject.pcm(16kHz·16bit·mono)이 있으면 마이크 대신 그 소리를
 * sendAudio로 흘려 넣는다 (adb run-as로만 넣을 수 있는 파일, tools/v5_voice.py).
 * 메인 스레드에서만 호출.
 */
class VoiceInput(private val context: Context) {
    private val _state = MutableStateFlow<VoiceState>(VoiceState.Idle)
    val state: StateFlow<VoiceState> = _state.asStateFlow()

    private var stt: SpeechToText? = null
    private val main = Handler(Looper.getMainLooper())
    /** 최종 결과 없이 시간이 다 되면 마지막 부분 결과라도 쓴다 (실측: 부분 "수요일 오후 3시"까지 오고 최종이 안 오는 경우) */
    private val timeout = Runnable {
        val partial = (_state.value as? VoiceState.Listening)?.partial.orEmpty()
        Log.i(EvidenceLog.TAG, "voice timeout partial=${partial.isNotBlank()}")
        finish(if (partial.isNotBlank()) VoiceState.Heard(partial) else VoiceState.Failed("잘 못 들었어요. 다시 말해 주세요."))
    }

    /** 이번 듣기에서 엔진 오류로 다시 시도했는지 (실측: 첫 사용 때 ON_DEVICE_ENGINE_ERROR가 한 번 난 뒤 정상) */
    private var retried = false

    fun start() {
        if (_state.value is VoiceState.Listening) return
        retried = false
        begin()
    }

    private fun begin() {
        val inject = File(context.filesDir, "voice_inject.pcm").takeIf { it.exists() }
        if (inject == null && context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            _state.value = VoiceState.Failed("마이크 권한이 필요해요.")
            return
        }
        _state.value = VoiceState.Listening("")
        main.removeCallbacks(timeout)
        main.postDelayed(timeout, TIMEOUT_MS)
        runCatching {
            val s = SpeechToText(context, Mode.ON_DEVICE).also { stt = it }
            s.addListener(object : ResultListener {
                override fun onUpdated(text: String, isFinal: Boolean) = main.post {
                    if (isFinal) {
                        Log.i(EvidenceLog.TAG, "voice heard len=${text.length}")
                        finish(if (text.isBlank()) VoiceState.Failed("잘 못 들었어요. 다시 말해 주세요.") else VoiceState.Heard(text))
                    } else if (_state.value is VoiceState.Listening) {
                        _state.value = VoiceState.Listening(text)
                    }
                }.let { }
                override fun onUpdatedEpdData(on: Long, off: Long) = Unit
                override fun onStartedRecognition() = Unit
                override fun onEndedRecognition() = Unit
                override fun onError(e: ErrorType) = main.post {
                    if (e == ErrorType.ON_DEVICE_ENGINE_ERROR && !retried) {
                        retried = true
                        Log.i(EvidenceLog.TAG, "voice engine error → retry once")
                        stt?.let { runCatching { it.release() } }
                        stt = null
                        main.removeCallbacks(timeout)
                        _state.value = VoiceState.Idle
                        main.postDelayed({ begin() }, 600)
                    } else {
                        finish(VoiceState.Failed("음성 인식이 안 됐어요. 다시 눌러 말해 주세요."))
                    }
                }.let { }
                override fun onReady() = Unit
                override fun onUpdatedRms(rms: Double) = Unit
            })
            s.initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    runCatching { s.setLocale(java.util.Locale.KOREA) }
                    s.request()
                    if (inject != null) feed(s, inject)
                }
                override fun onFailure(e: ErrorType) = main.post { finish(VoiceState.Failed("음성 인식을 쓸 수 없어요 ($e).")) }.let { }
            })
        }.onFailure { finish(VoiceState.Failed("음성 인식을 쓸 수 없어요.")) }
    }

    /** QA 전용: 파일 소리를 실시간 속도로 100ms씩 */
    private fun feed(s: SpeechToText, f: File) = Thread {
        val bytes = f.readBytes()
        Log.i(EvidenceLog.TAG, "voice inject ${bytes.size} bytes (QA)")
        Thread.sleep(400)
        var i = 0
        while (i < bytes.size && stt === s) {
            val end = minOf(i + 3200, bytes.size)
            s.sendAudio(bytes.copyOfRange(i, end))
            i = end
            Thread.sleep(100)
        }
        if (stt === s) s.completeAudioSend()
    }.start()

    fun cancel() = finish(VoiceState.Idle)

    /** 결과를 화면이 가져간 뒤 */
    fun reset() {
        if (_state.value !is VoiceState.Listening) _state.value = VoiceState.Idle
    }

    private fun finish(next: VoiceState) {
        main.removeCallbacks(timeout)
        stt?.let { runCatching { it.stop() }; runCatching { it.release() } }
        stt = null
        _state.value = next
    }

    companion object {
        private const val TIMEOUT_MS = 12_000L
    }
}
