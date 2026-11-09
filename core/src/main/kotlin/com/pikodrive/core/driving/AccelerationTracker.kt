package com.pikodrive.core.driving

import kotlin.math.abs

internal enum class AccelKind { BRAKE, ACCEL }

internal data class SustainedAccel(val kind: AccelKind, val kmhPerSec: Float)

/**
 * 창 평균 가속도 + 지속 시간 디바운스.
 * - 1샘플 노이즈 스파이크: 창 평균이 한 샘플 동안만 튀므로 [DetectionConfig.sustainMs] 미달 → 무시
 * - 샘플 간격(gap)·속도 미지원: 창을 비워 간격을 가로지르는 미분을 하지 않음
 * - 물리적으로 불가능한 값: 진행 중인 판정을 초기화
 */
internal class AccelerationTracker(private val config: DetectionConfig) {
    private val history = ArrayDeque<Pair<Long, Float>>()
    private var brakeRun = Run()
    private var accelRun = Run()

    private class Run(var startMs: Long? = null, var fired: Boolean = false)

    fun update(timestampMs: Long, speedKmh: Float?, gap: Boolean): SustainedAccel? {
        if (speedKmh == null || gap) reset()
        if (speedKmh == null) return null
        history.addLast(timestampMs to speedKmh)

        val accel = windowedAccel(timestampMs, speedKmh) ?: return null
        if (abs(accel) > config.implausibleKmhPerSec) {
            resetRuns()
            return null
        }
        val brake = track(brakeRun, accel <= config.hardBrakeKmhPerSec, timestampMs)
        val accelHit = track(accelRun, accel >= config.hardAccelKmhPerSec, timestampMs)
        return when {
            brake -> SustainedAccel(AccelKind.BRAKE, accel)
            accelHit -> SustainedAccel(AccelKind.ACCEL, accel)
            else -> null
        }
    }

    fun reset() {
        history.clear()
        resetRuns()
    }

    private fun resetRuns() {
        brakeRun = Run()
        accelRun = Run()
    }

    /** 조건이 연속으로 sustainMs 이상 유지된 첫 순간에만 true. */
    private fun track(run: Run, condition: Boolean, nowMs: Long): Boolean {
        if (!condition) {
            run.startMs = null
            run.fired = false
            return false
        }
        val start = run.startMs ?: nowMs.also { run.startMs = it }
        if (!run.fired && nowMs - start >= config.sustainMs) {
            run.fired = true
            return true
        }
        return false
    }

    private fun windowedAccel(nowMs: Long, speed: Float): Float? {
        val target = nowMs - config.accelWindowMs
        while (history.size >= 2 && history[1].first <= target) history.removeFirst()
        val (refMs, refSpeed) = history.first()
        if (refMs > target) return null
        return (speed - refSpeed) / ((nowMs - refMs) / 1000f)
    }
}
