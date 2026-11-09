package com.pikodrive.core.pet

import com.pikodrive.core.model.Expression
import com.pikodrive.core.model.GrowthStage

/**
 * 피코의 영속 상태. mood·bond는 0..100.
 * stage·pixelCount는 정차 시점에만 갱신된다 (주행 중 성장 연출 금지, FRAMEWORK 2-1-1).
 */
data class PetState(
    val mood: Int = 60,
    val bond: Int = 10,
    val expression: Expression = Expression.CALM,
    val stage: GrowthStage = GrowthStage.SPARK,
    val pixelCount: Int = GrowthStage.SPARK.pixelTarget,
    /** 편안한 주행 누적 ms (growthMultiplier 적용 후). */
    val comfortMsTotal: Long = 0,
    /**
     * 배터리 부족(≤20%, 25% 이상 회복 전까지) 여부 (S7). 세션마다 차량 값으로 다시 판정하며
     * 엔진 시작 시 false로 초기화된다. true이고 정차 중이면 기본 표정이 SLEEPY.
     */
    val batteryLow: Boolean = false,
) {
    val comfortMinutesTotal: Double get() = comfortMsTotal / 60_000.0

    companion object {
        val Initial = PetState()
    }
}

internal fun Int.clampStat(): Int = coerceIn(0, 100)
