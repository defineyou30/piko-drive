package com.pikodrive.core.driving

import com.pikodrive.core.model.DriveMode
import com.pikodrive.core.model.Gear
import com.pikodrive.core.model.VehicleSnapshot

/**
 * 스냅샷 → [DriveMode]. 기어가 유효하면 기어를 그대로 믿고,
 * UNKNOWN/NEUTRAL이면 속도로 보수적으로 추정한다. 아무 정보도 없으면 BOOTING(주행 화면 형태) 유지.
 *
 * UX Restriction 게이트 ([uxRestrictionSupported] = true 일 때):
 * - 아직 값을 한 번도 못 받았으면(null) BOOTING 유지
 * - 기어가 P라도 uxRestricted != false 이면 PARKED로 보지 않는다 → 부팅 중이면 BOOTING,
 *   아니면 DRIVING 정책(터치 비활성·쿨다운 적용)으로 처리. 이 상태는 [restrictedPark] 로 노출.
 */
internal class DriveModeResolver(
    private val config: DetectionConfig,
    private val uxRestrictionSupported: Boolean,
) {
    var mode: DriveMode = DriveMode.BOOTING
        private set

    /** 기어상 정차지만 UX 제한 때문에 PARKED로 인정하지 않은 상태. */
    var restrictedPark: Boolean = false
        private set

    private var stoppedSinceMs: Long? = null

    fun update(s: VehicleSnapshot): DriveMode {
        val speed = s.speedKmh
        stoppedSinceMs = when {
            speed == null || speed > config.stopSpeedKmh -> null
            else -> stoppedSinceMs ?: s.timestampMs
        }
        val raw = when (s.gear) {
            Gear.PARK -> DriveMode.PARKED
            Gear.REVERSE -> DriveMode.REVERSING
            Gear.DRIVE -> DriveMode.DRIVING
            Gear.NEUTRAL, Gear.UNKNOWN -> fallback(s.timestampMs, speed)
        }
        val uxUnknown = uxRestrictionSupported && s.uxRestricted == null
        val uxBlocksPark = if (uxRestrictionSupported) s.uxRestricted != false else s.uxRestricted == true
        restrictedPark = raw == DriveMode.PARKED && uxBlocksPark
        mode = when {
            mode == DriveMode.BOOTING && uxUnknown -> DriveMode.BOOTING
            restrictedPark -> if (mode == DriveMode.BOOTING) DriveMode.BOOTING else DriveMode.DRIVING
            else -> raw
        }
        return mode
    }

    private fun fallback(nowMs: Long, speed: Float?): DriveMode {
        if (speed == null) return mode
        if (speed > config.stopSpeedKmh) {
            // 후진 중 기어 정보가 끊기면 더 엄격한 R 정책을 유지한다.
            return if (mode == DriveMode.REVERSING) DriveMode.REVERSING else DriveMode.DRIVING
        }
        val since = stoppedSinceMs ?: return mode
        return if (nowMs - since >= config.fallbackParkHoldMs) DriveMode.PARKED else mode
    }
}
