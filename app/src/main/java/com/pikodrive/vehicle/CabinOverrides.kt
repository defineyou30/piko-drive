package com.pikodrive.vehicle

import com.pikodrive.core.model.RearSeat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 데모 패널(F9)의 벨트 대체 입력. Pleos 에뮬레이터 Virtual Console에 벨트 주입 경로가 없어서
 * 시연할 때만 쓴다. 값이 있는 좌석은 SDK 값 대신 이 값을 쓰고, 화면에 "시뮬레이션 입력"을 상시 표시한다.
 * 실제 SDK 경로(registerSeatBeltBuckledState)는 그대로 등록돼 있다.
 */
object CabinOverrides {
    private val _belts = MutableStateFlow<Map<RearSeat, Boolean>>(emptyMap())
    val belts: StateFlow<Map<RearSeat, Boolean>> = _belts.asStateFlow()

    fun setBelt(seat: RearSeat, buckled: Boolean?) {
        _belts.value = if (buckled == null) _belts.value - seat else _belts.value + (seat to buckled)
    }

    fun clear() {
        _belts.value = emptyMap()
    }
}
