package com.pikodrive.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.constant.area.SeatValue
import android.content.Context
import android.util.Log
import com.pikodrive.EvidenceLog

/**
 * 쉿 모드의 실내 조명: 켜면 앰비언트 밝기를 [DIM_PCT]로 낮추고, 끄면 원래 값으로 되돌린다.
 * Pleos Light API는 5좌석 묶음 영역만 지원한다 (qa/v3/probe-cabin.log, checkAmbientLightBrightnessCapabilities).
 * 실패해도 쉿 모드(아이 대사 끄기)는 그대로 동작한다 — 조명은 덤.
 * 메인 스레드에서만 호출.
 */
class AmbientDimmer(private val context: Context) {
    private var vehicle: Vehicle? = null
    private var saved: Int? = null
    private var dimmed = false

    fun set(dim: Boolean) {
        if (dim == dimmed) return
        val v = vehicle ?: runCatching { Vehicle(context).also { it.initialize() } }
            .onFailure { Log.w(TAG, "Vehicle init failed", it) }.getOrNull()?.also { vehicle = it } ?: return
        val light = v.getLight()
        dimmed = dim
        runCatching {
            if (dim) {
                light.getAmbientLightBrightness(AREA, { cur ->
                    saved = cur
                    light.setAmbientLightBrightness(AREA, DIM_PCT,
                        { _, b -> Log.i(EvidenceLog.TAG, "ambient dim $cur→$b") },
                        { Log.i(EvidenceLog.TAG, "ambient dim FAILED ${it.javaClass.simpleName}") })
                }, { Log.i(EvidenceLog.TAG, "ambient read FAILED ${it.javaClass.simpleName}") })
            } else {
                val back = saved ?: return
                saved = null
                light.setAmbientLightBrightness(AREA, back,
                    { _, b -> Log.i(EvidenceLog.TAG, "ambient restore →$b") },
                    { Log.i(EvidenceLog.TAG, "ambient restore FAILED ${it.javaClass.simpleName}") })
            }
        }.onFailure { Log.w(TAG, "ambient control failed", it) }
    }

    fun release() {
        set(false)
        runCatching { vehicle?.release() }
        vehicle = null
    }

    companion object {
        private const val TAG = "PikoAmbient"
        const val DIM_PCT = 15
        private val AREA = SeatArea(SeatValue.ROW_1_LEFT, SeatValue.ROW_1_RIGHT, SeatValue.ROW_2_LEFT, SeatValue.ROW_2_CENTER, SeatValue.ROW_2_RIGHT)
    }
}
