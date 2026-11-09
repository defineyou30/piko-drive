package com.pikodrive.core.vehicle

import com.pikodrive.core.model.VehicleSnapshot
import kotlinx.coroutines.flow.Flow

/** 이 차량(또는 시뮬레이터)이 제공할 수 있는 데이터. 미지원 기능은 자동 비활성 (차종명 분기 금지). */
data class VehicleCapabilities(
    val speed: Boolean,
    val gear: Boolean,
    val uxRestriction: Boolean,
    /** EV 배터리 잔량·충전구 (S7). */
    val battery: Boolean = false,
    /** 조수석 착좌 (S7). */
    val passenger: Boolean = false,
) {
    companion object {
        /** 주행 필수 신호(속도·기어·UX 제한). 배터리·승객은 꺼져 있음. */
        val Full = VehicleCapabilities(speed = true, gear = true, uxRestriction = true)

        /** Full + 배터리·승객. 시뮬레이터 기본값. */
        val All = Full.copy(battery = true, passenger = true)
    }
}

/**
 * 차량 데이터 소스. 구현체는 값이 변하지 않아도 최소 1초마다 마지막 상태를 다시 내보내야 한다
 * (heartbeat). 기어 미지원 폴백(속도 0 & 5초 유지)과 정차 확정 판정이 샘플 도착에 의존하기 때문.
 */
interface VehicleGateway {
    val snapshots: Flow<VehicleSnapshot>
    val capabilities: VehicleCapabilities
}
