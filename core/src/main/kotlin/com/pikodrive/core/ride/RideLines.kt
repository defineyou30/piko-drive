package com.pikodrive.core.ride

import com.pikodrive.core.model.Expression
import com.pikodrive.core.model.RearSeat
import com.pikodrive.core.pet.SpeechCategory
import com.pikodrive.core.pet.SpeechRequest

/** 누구에게 하는 말인가. 뒷자리 = 피코가 아이에게, 앞자리 = 운전자(부모)에게 보조 알림. */
enum class Audience { REAR, FRONT }

/**
 * 이벤트 하나에 대한 말 + 표정. [urgent] = 운전자 안전 보조 알림 (카테고리 쿨다운·발화 예산을 건너뛰고,
 * 진행 중인 아이 대사를 끊는다).
 */
data class RideUtterance(
    val audience: Audience,
    val request: SpeechRequest,
    val expression: Expression? = null,
    val urgent: Boolean = false,
)

/**
 * 이벤트 → 연령별 대사. 원칙:
 * - 아이에게: 1문장, 3초 이내, 칭찬·놀이 말투. 영유아는 거의 말하지 않고, 중고등(또는 피코 꺼짐)은 말하지 않는다.
 * - 운전자에게: 어느 자리·누구인지와 할 일만. 아이를 탓하거나 불안을 키우는 표현을 쓰지 않는다.
 * - 아이 별칭은 화면·음성에서만 쓰고 저장된 이름은 부모가 정한 별칭뿐이다.
 * - 센서가 확인한 사실만 말한다: 벨트가 채워졌다는 건 알지만 누가 채웠는지는 모른다 ("스스로 맸네" 금지).
 */
class RideLines {

    /** [quiet] = 쉿 모드 (아이 대사 전부 생략, 운전자 알림은 유지). [streetSide] = 차도 쪽. */
    fun linesFor(
        event: RideEvent,
        profiles: List<ChildProfile>,
        quiet: Boolean = false,
        streetSide: RearSeat = RearSeat.LEFT,
    ): List<RideUtterance> {
        val curb = if (streetSide.opposite() == RearSeat.RIGHT) "오른쪽" else "왼쪽"
        fun profile(seat: RearSeat) = profiles.firstOrNull { it.seat == seat }
        fun kid(seat: RearSeat, id: String, text: (AgeGroup, String) -> String?, expression: Expression? = Expression.HAPPY): RideUtterance? {
            if (quiet) return null
            val p = profile(seat)
            if (p != null && !p.pikoEnabled) return null
            val age = p?.age ?: AgeGroup.SCHOOL
            val name = p?.alias ?: "친구"
            val line = text(age, name) ?: return null
            return RideUtterance(Audience.REAR, SpeechRequest("kid_${id}_${age.name.lowercase()}", line, SpeechCategory.RIDE_KID), expression)
        }
        fun front(id: String, text: String, expression: Expression? = null) =
            RideUtterance(Audience.FRONT, SpeechRequest("front_$id", text, SpeechCategory.RIDE_ALERT), expression, urgent = true)
        fun who(seat: RearSeat) = profile(seat)?.alias?.let { "${seat.label} $it" } ?: seat.label

        return when (event) {
            is RideEvent.ChildBoarded -> listOfNotNull(kid(event.seat, "board", { age, name ->
                when (age) {
                    AgeGroup.INFANT -> "$name 안녕, 같이 가자."
                    AgeGroup.KINDER -> "$name 왔다! 피코가 기다렸어!"
                    AgeGroup.SCHOOL -> "$name 왔구나, 오늘도 같이 가자."
                    AgeGroup.TEEN -> null
                }
            }))
            is RideEvent.BeltPrompt -> listOfNotNull(kid(event.seat, "belt_prompt", { age, name ->
                when (age) {
                    AgeGroup.INFANT -> null // 카시트 벨트는 보호자가 채운다 → 출발 전 요약에서만
                    AgeGroup.KINDER -> "$name, 딸깍 소리 내면 피코가 깨어나!"
                    AgeGroup.SCHOOL -> "$name, 벨트 딸깍 하고 출발하자."
                    AgeGroup.TEEN -> null
                }
            }, Expression.SLEEPY))
            is RideEvent.BeltBuckled -> listOfNotNull(kid(event.seat, if (event.self) "belt_self" else "belt_done", { age, name ->
                when {
                    age == AgeGroup.INFANT || age == AgeGroup.TEEN -> null
                    event.self && age == AgeGroup.KINDER -> "딸깍! $name 벨트 됐다, 스티커 하나!"
                    event.self -> "딸깍, $name 벨트 됐네. 스티커 하나 붙였어."
                    else -> "딸깍! 이제 출발 준비 끝."
                }
            }))
            is RideEvent.StickerEarned -> emptyList() // 대사는 BeltBuckled·ChildAlighted에 포함, 화면에서만 표시
            is RideEvent.BoardCompleted -> listOfNotNull(kid(event.seat, "board_${event.boards.coerceAtMost(3)}", { age, name ->
                when {
                    !age.earnsStickers -> null
                    event.boards == 1 && age == AgeGroup.KINDER -> "스티커판 완성! 피코 볼이 반짝여!"
                    event.boards == 1 -> "$name 덕분에 스티커판 완성! 피코 볼에 불이 켜졌어."
                    event.boards == 2 && age == AgeGroup.KINDER -> "두 판째 완성! 피코 귀가 반짝여!"
                    event.boards == 2 -> "두 판째 완성. 피코 귀 램프가 켜졌어."
                    else -> "또 한 판! $name 진짜 멋지다."
                }
            }))
            is RideEvent.DepartUnbuckled -> listOf(front("depart_unbuckled",
                "${event.seats.joinToString(", ") { who(it) }} 벨트가 채워지지 않았어요."))
            is RideEvent.BeltReleasedWhileDriving -> listOf(front(if (event.reminder) "belt_reminder" else "belt_released",
                if (event.reminder) "${who(event.seat)} 벨트가 계속 풀려 있어요." else "${who(event.seat)} 벨트가 풀렸어요."))
            is RideEvent.DoorOpenWhileMoving -> listOf(front("door_moving", "${event.seat.label} 문이 열렸어요.", Expression.SURPRISED))
            is RideEvent.RestSuggested -> listOf(front("rest",
                if (event.infant) "아기와 ${event.minutes}분째예요. 쉬어 갈 곳을 찾아볼까요?"
                else "${event.minutes}분째 달리는 중이에요. 잠깐 쉬어 가요.", Expression.SLEEPY))
            is RideEvent.Arrived -> buildList {
                event.seats.forEach { seat ->
                    val items = event.items[seat]
                    kid(seat, if (items != null) "arrive_items" else "arrive", { age, name ->
                        when {
                            age == AgeGroup.INFANT -> null
                            items != null -> "$name, 오늘은 ${items.joinToString(", ")} 챙겨!"
                            age == AgeGroup.TEEN -> null
                            else -> "도착! $name, $curb 문으로 내리자."
                        }
                    })?.let(::add)
                }
            }
            is RideEvent.StreetSideDoorOpened -> listOf(front("street_door",
                "${event.seat.label} 문은 차도 쪽이에요. 반대쪽으로 내려 주세요.", Expression.SURPRISED))
            is RideEvent.ChildAlighted -> listOfNotNull(kid(event.seat, if (event.viaCurb) "bye_curb" else "bye", { age, name ->
                when {
                    age == AgeGroup.TEEN || age == AgeGroup.INFANT -> null
                    event.viaCurb -> "$name 인도 쪽으로 잘 내렸어! 잘 다녀와."
                    else -> "$name 잘 다녀와!"
                }
            }))
            is RideEvent.LeftBehindAlert -> listOf(front("left_behind_${event.level}",
                when (event.level) {
                    1 -> "${event.seats.joinToString(", ") { who(it) }}에 아직 아이가 있어요."
                    2 -> "뒷좌석을 확인해 주세요. ${event.seats.joinToString(", ") { it.label }}에 착석 신호가 있어요."
                    else -> "뒷좌석 확인이 필요해요."
                }, Expression.SURPRISED))
            is RideEvent.LeftBehindUnknown -> listOf(front("left_behind_unknown", "뒷좌석 신호를 읽을 수 없어요. 직접 확인해 주세요."))
            is RideEvent.LeftBehindCleared, is RideEvent.DepartedWithKids -> emptyList()
        }
    }
}
