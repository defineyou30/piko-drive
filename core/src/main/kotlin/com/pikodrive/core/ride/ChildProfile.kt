package com.pikodrive.core.ride

import com.pikodrive.core.model.RearSeat
import java.time.DayOfWeek

/**
 * 연령대. 피코의 말투·존재감과 앞자리 체크 항목이 함께 바뀐다 (docs/CONCEPT_v3_kids_ride.md 3장).
 * [pikoDefault] = 프로필을 새로 만들 때 피코 켜짐 기본값. 중고등은 캐릭터 거부감을 고려해 끈다.
 */
enum class AgeGroup(val label: String, val pikoDefault: Boolean, val earnsStickers: Boolean) {
    /** 카시트 벨트는 보호자가 채운다 → 스티커 없음 */
    INFANT("영유아", true, false),
    KINDER("유치원", true, true),
    SCHOOL("초등", true, true),
    /** 캐릭터 보상 대상이 아님 */
    TEEN("중고등", false, false),
}

/** 완성한 판 수 → 해금 이름 (화면 표시용) */
fun unlockLabel(boards: Int): String? = when {
    boards >= 2 -> "귀 램프"
    boards == 1 -> "볼 램프"
    else -> null
}

/**
 * 아이 프로필. 실명 대신 [alias](별칭), 학원명 없이 준비물 항목명만 저장한다.
 * [stickers] = 스티커판 누적 장수 (판 크기 [STICKERS_PER_BOARD]).
 */
data class ChildProfile(
    val id: String,
    val alias: String,
    val age: AgeGroup,
    val seat: RearSeat,
    val pikoEnabled: Boolean = age.pikoDefault,
    val itemsByDay: Map<DayOfWeek, List<String>> = emptyMap(),
    val stickers: Int = 0,
) {
    val boardsCompleted: Int get() = stickers / STICKERS_PER_BOARD
    val stickersOnBoard: Int get() = stickers % STICKERS_PER_BOARD

    companion object {
        const val STICKERS_PER_BOARD = 10
        const val MAX_CHILDREN = 2
    }
}

/** 좌석 이름 (운전자 음성·화면 공통). */
val RearSeat.label: String
    get() = when (this) {
        RearSeat.LEFT -> "왼쪽 뒷좌석"
        RearSeat.RIGHT -> "오른쪽 뒷좌석"
    }

fun RearSeat.opposite(): RearSeat = if (this == RearSeat.LEFT) RearSeat.RIGHT else RearSeat.LEFT
