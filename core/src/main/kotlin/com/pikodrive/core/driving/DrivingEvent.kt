package com.pikodrive.core.driving

/** 차량 스냅샷 스트림에서 판정한 주행 이벤트. timestampMs = 판정 시점의 스냅샷 시각. */
sealed interface DrivingEvent {
    val timestampMs: Long

    data class HardAccel(override val timestampMs: Long, val kmhPerSec: Float) : DrivingEvent
    data class HardBrake(override val timestampMs: Long, val kmhPerSec: Float) : DrivingEvent

    /** 급가감속 없이 이어진 주행 시간이 [DetectionConfig.smoothStreakStepMs] 배수에 도달. */
    data class SmoothStreak(override val timestampMs: Long, val streakMs: Long) : DrivingEvent {
        val minutes: Int get() = (streakMs / 60_000).toInt()
    }

    /** 트립 종료 (P 진입 후 [DetectionConfig.parkConfirmMs] 유지). */
    data class Parked(override val timestampMs: Long) : DrivingEvent

    /**
     * 트립 시작. D에서 [DetectionConfig.movingKmh] 이상으로 누적 [DetectionConfig.departConfirmMs] 이동한 시점
     * (또는 그 전에 급가감속이 감지된 시점). 후진만으로는 시작하지 않는다.
     * 속도 미지원 차량은 기존처럼 D/R 진입 시점.
     */
    data class Departed(override val timestampMs: Long) : DrivingEvent

    data class Reversing(override val timestampMs: Long) : DrivingEvent

    /** 트립 경과 시간이 [DetectionConfig.longDriveStepMs] 배수에 도달. */
    data class LongDrive(override val timestampMs: Long, val elapsedMs: Long) : DrivingEvent {
        val minutes: Int get() = (elapsedMs / 60_000).toInt()
    }

    /** 편안한 주행 시간 적립분 (성장 재료). [DetectionConfig.comfortTickMs] 단위 + 트립 종료 시 잔여분. */
    data class ComfortAccrued(override val timestampMs: Long, val comfortMs: Long) : DrivingEvent

    /** 배터리 잔량이 [DetectionConfig.batteryLowPct] 이하로 내려감. 1회만, [DetectionConfig.batteryRearmPct] 이상 회복해야 재무장. */
    data class BatteryLow(override val timestampMs: Long, val pct: Int) : DrivingEvent

    /** BatteryLow 이후 [DetectionConfig.batteryRearmPct] 이상으로 회복 (반응 없음, 상태 해제용). */
    data class BatteryRecovered(override val timestampMs: Long, val pct: Int) : DrivingEvent

    /** 정차(PARKED) 중 충전구가 닫힘/미수신 → 열림. */
    data class ChargingStarted(override val timestampMs: Long) : DrivingEvent

    /** 조수석 착좌 false → true. 첫 관측값이 true면 발생하지 않음. 트립당(트립 종료까지) 1회. */
    data class PassengerJoined(override val timestampMs: Long) : DrivingEvent
}
