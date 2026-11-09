package com.pikodrive.core.pet

/**
 * 주행 중 전역 발화 예산 (카테고리 무관). 카테고리별 쿨다운과 별개로 한 곳에서 총량을 제한한다.
 * - 발화 사이 최소 [minGapMs]. 단 [priority] 발화(놀람 반응)는 최소 간격을 건너뛴다 —
 *   출발 인사 직후 급제동처럼 핵심 반응이 덜 중요한 발화에 묻히지 않게. (S3 E2E에서 발견)
 * - 최근 [windowMs] 동안 최대 [maxPerWindow]회 (우선 발화도 포함)
 * 정차 중 발화는 예산에 포함하지 않는다.
 */
class SpeechBudget(
    private val minGapMs: Long = 30_000,
    private val maxPerWindow: Int = 12,
    private val windowMs: Long = 60 * 60_000L,
) {
    private val history = ArrayDeque<Long>()

    fun canSpend(nowMs: Long, priority: Boolean = false): Boolean {
        while (history.isNotEmpty() && nowMs - history.first() >= windowMs) history.removeFirst()
        val last = history.lastOrNull()
        if (!priority && last != null && nowMs - last < minGapMs) return false
        return history.size < maxPerWindow
    }

    fun spend(nowMs: Long) {
        history.addLast(nowMs)
    }
}
