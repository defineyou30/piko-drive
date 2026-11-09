package com.pikodrive.core.driving

/**
 * 판정 임계값. 근거는 qa/S2-core-report.md 참고.
 * 가속도는 [accelWindowMs] 창 기준 평균(km/h/s)이며, [sustainMs] 이상 연속 유지돼야 이벤트가 된다.
 */
data class DetectionConfig(
    val hardBrakeKmhPerSec: Float = -10f,
    val hardAccelKmhPerSec: Float = 12f,
    val accelWindowMs: Long = 500,
    val sustainMs: Long = 400,
    /** 이 이상의 |가속도|는 물리적으로 불가능한 센서 노이즈로 보고 버린다 (≈1.3g). */
    val implausibleKmhPerSec: Float = 45f,
    /** 샘플 간격이 이보다 길면 가속도 계산 창을 초기화한다 (간격을 가로지르는 미분 금지). */
    val maxSampleGapMs: Long = 1_500,
    val hardEventRefractoryMs: Long = 5_000,
    val stopSpeedKmh: Float = 0.5f,
    /** 기어 미지원 시 속도 0을 이만큼 유지하면 정차로 간주 (FRAMEWORK 3-3). */
    val fallbackParkHoldMs: Long = 5_000,
    /** D→P 후 이만큼 유지돼야 트립 종료 (F6 진입 조건). */
    val parkConfirmMs: Long = 3_000,
    /** 급가감속 직후 이 시간 동안은 편안한 주행으로 적립하지 않는다. */
    val comfortPenaltyMs: Long = 30_000,
    val comfortTickMs: Long = 10_000,
    val smoothStreakStepMs: Long = 5 * 60_000L,
    val longDriveStepMs: Long = 60 * 60_000L,
    /** 이 속도 이상일 때만 '실제로 움직이는 중' — 편안한 시간·스트릭 적립과 트립 확정 기준. */
    val movingKmh: Float = 5f,
    /** D에서 [movingKmh] 이상 누적 이동이 이만큼 돼야 실제 트립(Departed). 기어만 바꾼 경우·주차 조작 제외. */
    val departConfirmMs: Long = 3_000,
    /** 이 값 이하로 내려가면 BatteryLow (S7). */
    val batteryLowPct: Int = 20,
    /** BatteryLow 후 이 값 이상 회복해야 재무장 (히스테리시스). */
    val batteryRearmPct: Int = 25,
) {
    companion object {
        /** 90초 PT 데모용: 스트릭·적립 주기를 압축. 판정 임계값은 실차와 동일. */
        val Demo = DetectionConfig(
            comfortPenaltyMs = 10_000,
            comfortTickMs = 5_000,
            smoothStreakStepMs = 32_000,
        )
    }
}
