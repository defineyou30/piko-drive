package com.pikodrive.core.pet

import com.pikodrive.core.vehicle.Clock
import kotlinx.coroutines.delay

/** 쿨다운은 카테고리 단위로 적용된다. */
enum class SpeechCategory {
    GREETING, STARTLE, COMFORT, REST, ARRIVAL, AFFECTION,
    /** 배터리 부족·충전 시작 (S7). lineId 태그 "low" / "charge". */
    ENERGY,
    /** 조수석 동승자 (S7). */
    PASSENGER,
    /** v3: 피코가 뒷자리 아이에게. 주행 중엔 일반 예산·쿨다운을 따른다. */
    RIDE_KID,
    /** v3: 운전자에게 안전 보조 알림. 쿨다운·예산 없음, 아이 대사를 끊고 말한다 (빈도 제한은 RideEngine이 한다). */
    RIDE_ALERT,
    /** v4: 에이전트가 운전자에게 일정·메모 브리핑. 일반 예산·쿨다운을 따른다. */
    AGENT,
}

data class SpeechRequest(val lineId: String, val text: String, val category: SpeechCategory)

/**
 * TTS 출력부.
 * - [speak]: 오디오 포커스를 얻어 발화를 끝내면 true, 포커스 거부·실패 시 false.
 *   코루틴 취소에 협조해야 한다 (취소되면 즉시 발화 중단).
 * - [stop]: 엔진이 R단·부팅·정차→주행 전환 시 호출. 진행 중 발화를 즉시 끊고 오디오 포커스를 반납할 것.
 */
fun interface SpeechSink {
    suspend fun speak(request: SpeechRequest): Boolean

    fun stop() {}
}

enum class SpeechOutcome {
    SPOKEN,
    SPOKEN_AFTER_RETRY,
    DROPPED_FOCUS,
    DROPPED_COOLDOWN,
    DROPPED_MODE,
    DROPPED_BUSY,
    DROPPED_VOICE_OFF,
    /** 전역 발화 예산(최소 간격·시간당 상한) 초과. */
    DROPPED_BUDGET,
    /** 발화 도중 R단·부팅 등으로 강제 중단. */
    INTERRUPTED,
}

/** 안전 로그 (PLAN 5장 증거 패키지용). */
data class SpeechLogEntry(val atMs: Long, val request: SpeechRequest, val outcome: SpeechOutcome)

/**
 * F4 규칙: 포커스 실패 시 [retryDelayMs] 후 1회 재시도, 최초 시도로부터 [retryWindowMs]를 넘기면 폐기.
 * 재시도 직전 [canSpeak]로 모드를 다시 확인한다 (그 사이 R단 전환 등).
 */
class SpeechScheduler(
    private val sink: SpeechSink,
    private val clock: Clock,
    private val retryDelayMs: Long = 4_000,
    private val retryWindowMs: Long = 10_000,
) {
    suspend fun deliver(request: SpeechRequest, canSpeak: () -> Boolean): SpeechOutcome {
        val startMs = clock.nowMs()
        if (!canSpeak()) return SpeechOutcome.DROPPED_MODE
        if (sink.speak(request)) return SpeechOutcome.SPOKEN
        delay(retryDelayMs)
        if (clock.nowMs() - startMs > retryWindowMs) return SpeechOutcome.DROPPED_FOCUS
        if (!canSpeak()) return SpeechOutcome.DROPPED_MODE
        return if (sink.speak(request)) SpeechOutcome.SPOKEN_AFTER_RETRY else SpeechOutcome.DROPPED_FOCUS
    }
}
