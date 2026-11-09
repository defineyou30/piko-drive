package com.pikodrive.core.model

/**
 * S1(UI)과 S2(엔진)가 공유하는 계약. 변경 시 양쪽 모두 확인할 것.
 */

enum class Gear { PARK, REVERSE, NEUTRAL, DRIVE, UNKNOWN }

/** 차량에서 들어오는 한 시점의 상태. 미지원 값은 null. */
data class VehicleSnapshot(
    val timestampMs: Long,
    val speedKmh: Float?,
    val gear: Gear,
    val uxRestricted: Boolean? = null,
    /** EV 배터리 잔량 0..100 (S7). 미지원·미수신이면 null. */
    val batteryPct: Int? = null,
    /** 충전구 열림 여부 (S7). 미지원이면 null. */
    val chargePortOpen: Boolean? = null,
    /** 조수석 착좌 여부 (S7, 승객 감지 센서). 미지원이면 null. */
    val passengerPresent: Boolean? = null,
    /** 뒷좌석·문·잠금·창문·실내 온도 (v3 아이 라이드). 미지원 값은 각각 null. */
    val cabin: CabinSnapshot = CabinSnapshot(),
)

/** 뒷좌석 위치. 2열 좌·우만 지원 (Pleos SeatValue ROW_2_LEFT/RIGHT). */
enum class RearSeat { LEFT, RIGHT }

/**
 * 실내 신호 한 시점. 좌석별 값은 [RearSeat] 키, 미수신이면 키가 없거나 null.
 * - rearDoorOpen: 뒷문 열림 (Door position > 0)
 * - rearWindowOpenTenths: 뒷창문 열림 0..10 (Pleos Window position 단위)
 * - doorsLocked: 4도어 묶음 잠금
 * - driverPresent: 운전석 착석
 */
data class CabinSnapshot(
    val rearOccupied: Map<RearSeat, Boolean?> = emptyMap(),
    val rearBelted: Map<RearSeat, Boolean?> = emptyMap(),
    val rearDoorOpen: Map<RearSeat, Boolean?> = emptyMap(),
    val rearWindowOpenTenths: Map<RearSeat, Int?> = emptyMap(),
    val driverPresent: Boolean? = null,
    val doorsLocked: Boolean? = null,
    val cabinTempC: Float? = null,
)

/** 화면·발화 정책을 결정하는 상위 모드 (FRAMEWORK 3-4). */
enum class DriveMode {
    /** 앱 시작 직후, 기어/UX 제한 확인 전. 주행 화면 형태로 안전하게 대기. */
    BOOTING,
    PARKED,
    DRIVING,
    REVERSING,
}

/** 피코의 표정. 눈 블록 모양으로만 표현한다. */
enum class Expression {
    CALM,
    HAPPY,
    SURPRISED,
    SLEEPY,
    /** 권한 거부 등으로 말할 수 없는 상태 */
    QUIET,
}

/** 성장 단계. pixelTarget = 해당 단계 완성 시 픽셀 수, unlockComfortMinutes = 누적 편안한 주행 분. */
enum class GrowthStage(val pixelTarget: Int, val unlockComfortMinutes: Int) {
    SPARK(1, 0),
    EYES(6, 15),
    FACE(20, 60),
    BODY(48, 180),
    COMPLETE(96, 420),
}
