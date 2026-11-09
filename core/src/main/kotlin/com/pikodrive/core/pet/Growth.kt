package com.pikodrive.core.pet

import com.pikodrive.core.model.GrowthStage
import kotlin.math.floor

/** 90초 데모에서 0→3단계(BODY)까지 보이도록 한 성장 가속 배수. */
const val DEMO_GROWTH_MULTIPLIER = 360

/**
 * 편안한 주행 누적 분 → 단계·픽셀 수.
 * 단계는 unlockComfortMinutes 이상 중 가장 높은 것, 픽셀은 현재 단계 pixelTarget에서
 * 다음 단계 pixelTarget까지 진행률만큼 선형으로 늘어난다.
 */
object Growth {
    fun stageFor(comfortMinutes: Double): GrowthStage =
        GrowthStage.entries.last { it.unlockComfortMinutes <= comfortMinutes }

    fun pixelsFor(comfortMinutes: Double): Int {
        val stage = stageFor(comfortMinutes)
        val next = GrowthStage.entries.getOrNull(stage.ordinal + 1) ?: return stage.pixelTarget
        val progress = (comfortMinutes - stage.unlockComfortMinutes) /
            (next.unlockComfortMinutes - stage.unlockComfortMinutes)
        return stage.pixelTarget + floor((next.pixelTarget - stage.pixelTarget) * progress).toInt()
    }

    /** comfortMsTotal 기준으로 stage·pixelCount를 확정. */
    fun commit(state: PetState): PetState {
        val minutes = state.comfortMinutesTotal
        return state.copy(stage = stageFor(minutes), pixelCount = pixelsFor(minutes))
    }
}
