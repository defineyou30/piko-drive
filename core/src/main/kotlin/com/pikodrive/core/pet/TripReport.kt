package com.pikodrive.core.pet

import com.pikodrive.core.model.GrowthStage

/** 트립(Departed→Parked) 1회 요약. F6 도착 리포트 카드의 데이터. */
data class TripReport(
    val startedAtMs: Long,
    val endedAtMs: Long,
    /** 실제 편안한 주행 시간 (성장 배수 미적용). */
    val comfortMs: Long,
    val startledMoments: List<Long>,
    val pixelsGained: Int,
    val stageBefore: GrowthStage,
    val stageAfter: GrowthStage,
) {
    val durationMs: Long get() = endedAtMs - startedAtMs
    val comfortMinutes: Int get() = (comfortMs / 60_000).toInt()
    val stageUnlocked: Boolean get() = stageAfter != stageBefore
}

internal class TripRecorder {
    private var startedAtMs: Long? = null
    private var pixelsAtStart = 0
    private var stageAtStart = GrowthStage.SPARK
    private var comfortMs = 0L
    private val startled = mutableListOf<Long>()

    val active: Boolean get() = startedAtMs != null

    fun start(atMs: Long, state: PetState) {
        startedAtMs = atMs
        pixelsAtStart = state.pixelCount
        stageAtStart = state.stage
        comfortMs = 0
        startled.clear()
    }

    fun startled(atMs: Long) {
        if (active) startled += atMs
    }

    fun addComfort(ms: Long) {
        if (active) comfortMs += ms
    }

    fun finish(atMs: Long, committed: PetState): TripReport? {
        val start = startedAtMs ?: return null
        startedAtMs = null
        return TripReport(
            startedAtMs = start,
            endedAtMs = atMs,
            comfortMs = comfortMs,
            startledMoments = startled.toList(),
            pixelsGained = committed.pixelCount - pixelsAtStart,
            stageBefore = stageAtStart,
            stageAfter = committed.stage,
        )
    }
}
