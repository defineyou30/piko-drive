package com.pikodrive.core.ride

import com.pikodrive.core.model.RearSeat

/** 루틴 상태머신이 차량 신호에서 판정한 이벤트. 부모 조작 없이 신호만으로 발생한다. */
sealed interface RideEvent {
    val timestampMs: Long

    /** 정차 중 뒷좌석 착석 false → true. 첫 관측값이 true면 발생하지 않음. */
    data class ChildBoarded(override val timestampMs: Long, val seat: RearSeat) : RideEvent

    /** 착석했는데 벨트 미체결이 [RideConfig.beltPromptMs] 지속 (탑승당 1회). */
    data class BeltPrompt(override val timestampMs: Long, val seat: RearSeat) : RideEvent

    /** 착석 좌석 벨트 false → true. [self] = 재촉 전에 스스로 맸음. */
    data class BeltBuckled(override val timestampMs: Long, val seat: RearSeat, val self: Boolean) : RideEvent

    /** 출발(주행 진입) 시점에 착석 좌석 중 벨트 미체결이 있음 → 운전자 음성. */
    data class DepartUnbuckled(override val timestampMs: Long, val seats: List<RearSeat>) : RideEvent

    /** 아이를 태우고 실제로 움직이기 시작 (트립당 1회). 보호자 "출발" 알림용, 대사 없음. */
    data class DepartedWithKids(override val timestampMs: Long, val seats: List<RearSeat>) : RideEvent

    /** 주행 중 벨트 풀림 ([reminder] = 계속 풀려 있어 다시 알림). */
    data class BeltReleasedWhileDriving(override val timestampMs: Long, val seat: RearSeat, val reminder: Boolean) : RideEvent

    /** 주행 중 뒷문 열림. */
    data class DoorOpenWhileMoving(override val timestampMs: Long, val seat: RearSeat) : RideEvent

    /** 아이 동승 주행 시간이 기준(영유아 60분, 그 외 120분)의 배수에 도달. */
    data class RestSuggested(override val timestampMs: Long, val minutes: Int, val infant: Boolean) : RideEvent

    /** 아이를 태운 주행 후 정차. [items] = 오늘 요일의 좌석별 준비물. */
    data class Arrived(override val timestampMs: Long, val seats: List<RearSeat>, val items: Map<RearSeat, List<String>>) : RideEvent

    /** 정차 중 아이가 있는데 차도 쪽 뒷문이 열림. */
    data class StreetSideDoorOpened(override val timestampMs: Long, val seat: RearSeat) : RideEvent

    /** 정차 중 뒷좌석 착석 true → false. [viaCurb] = 인도 쪽 문으로 내림. */
    data class ChildAlighted(override val timestampMs: Long, val seat: RearSeat, val viaCurb: Boolean) : RideEvent

    data class StickerEarned(override val timestampMs: Long, val seat: RearSeat, val reason: StickerReason) : RideEvent

    /** 스티커판 한 판 완성 ([boards] = 지금까지 완성한 판 수). 해금 단계가 오른다 (1 볼 램프, 2 정수리 램프). */
    data class BoardCompleted(override val timestampMs: Long, val seat: RearSeat, val boards: Int) : RideEvent

    /**
     * 뒷좌석 남김 보조 알림. 주행 후 정차 상태에서 운전석이 비었거나 문이 잠겼는데 뒷좌석 착석이 남아 있음.
     * [level] 1부터 [RideConfig.leftBehindRepeatMs]마다 증가 (상한 3).
     */
    data class LeftBehindAlert(override val timestampMs: Long, val seats: List<RearSeat>, val level: Int) : RideEvent

    /** 같은 상황인데 뒷좌석 착석 신호를 읽을 수 없음 → "확인 불가" (아이 부재를 보증하지 않는다). */
    data class LeftBehindUnknown(override val timestampMs: Long) : RideEvent

    data class LeftBehindCleared(override val timestampMs: Long) : RideEvent
}

enum class StickerReason(val label: String) {
    SELF_BELT("출발 전 벨트"),
    CURB_EXIT("인도 쪽 하차"),
}
