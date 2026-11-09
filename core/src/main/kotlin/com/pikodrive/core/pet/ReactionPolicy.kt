package com.pikodrive.core.pet

import com.pikodrive.core.driving.DrivingEvent
import com.pikodrive.core.model.Expression

/** 이벤트 하나에 대한 반응. delayed = 급가감속 후 2~3초 지연 대상. */
data class Reaction(
    val moodDelta: Int = 0,
    val bondDelta: Int = 0,
    val expression: Expression? = null,
    val speech: SpeechRequest? = null,
    val delayed: Boolean = false,
    /**
     * 운전 상황을 알리는 핵심 표정(놀람, 배터리 졸림). 주행 중 10초 표정 제한 대신
     * [PetConfig.priorityExpressionMinIntervalMs] 간격만 지킨다 — 칭찬 뒤 원래 표정 복귀 같은
     * 사소한 변화가 예산을 써서 놀란 표정이 안 보이던 문제 (영상 녹화 QA에서 발견)
     */
    val priority: Boolean = false,
)

/**
 * 이벤트 → 상태 변화 + 대사. 대사 원칙: 1문장 ≤ 3초, 피코는 "놀랐다"고만 말하고
 * 운전자를 평가·비난하지 않는다. 같은 카테고리 안에서는 순서대로 돌려 쓴다.
 */
class ReactionPolicy(private val lines: Map<SpeechCategory, List<SpeechRequest>> = DefaultLines.all) {
    private val cursor = mutableMapOf<SpeechCategory, Int>()

    fun react(event: DrivingEvent): Reaction? = when (event) {
        is DrivingEvent.HardBrake -> Reaction(
            moodDelta = -6, expression = Expression.SURPRISED,
            speech = next(SpeechCategory.STARTLE, "brake"), delayed = true, priority = true,
        )
        is DrivingEvent.HardAccel -> Reaction(
            moodDelta = -4, expression = Expression.SURPRISED,
            speech = next(SpeechCategory.STARTLE, "accel"), delayed = true, priority = true,
        )
        is DrivingEvent.SmoothStreak -> Reaction(
            moodDelta = 5, bondDelta = 1, expression = Expression.HAPPY,
            speech = next(SpeechCategory.COMFORT),
        )
        is DrivingEvent.Departed -> Reaction(bondDelta = 1, speech = next(SpeechCategory.GREETING, "greet"))
        is DrivingEvent.Parked -> Reaction(
            moodDelta = 3, bondDelta = 2, expression = Expression.HAPPY,
            speech = next(SpeechCategory.ARRIVAL),
        )
        is DrivingEvent.LongDrive -> Reaction(expression = Expression.SLEEPY, speech = next(SpeechCategory.REST))
        is DrivingEvent.BatteryLow -> Reaction(
            expression = Expression.SLEEPY, speech = next(SpeechCategory.ENERGY, "low"), priority = true,
        )
        is DrivingEvent.ChargingStarted -> Reaction(
            moodDelta = 3, expression = Expression.HAPPY,
            speech = next(SpeechCategory.ENERGY, "charge"),
        )
        is DrivingEvent.PassengerJoined -> Reaction(
            moodDelta = 2, bondDelta = 1, expression = Expression.HAPPY,
            speech = next(SpeechCategory.PASSENGER),
        )
        is DrivingEvent.Reversing, is DrivingEvent.ComfortAccrued, is DrivingEvent.BatteryRecovered -> null
    }

    /** 첫 만남 인사 (정차, 프로세스당 1회). */
    fun firstMeet(): Reaction = Reaction(expression = Expression.HAPPY, speech = next(SpeechCategory.GREETING, "hello"))

    /** 정차 중 쓰다듬기. */
    fun petByHand(): Reaction = Reaction(
        moodDelta = 2, bondDelta = 2, expression = Expression.HAPPY,
        speech = next(SpeechCategory.AFFECTION),
    )

    private fun next(category: SpeechCategory, idTag: String? = null): SpeechRequest? {
        val pool = lines[category].orEmpty().filter { idTag == null || idTag in it.lineId }
        if (pool.isEmpty()) return null
        val key = cursor[category] ?: 0
        cursor[category] = key + 1
        return pool[key % pool.size]
    }
}

object DefaultLines {
    private fun line(id: String, text: String, c: SpeechCategory) = SpeechRequest(id, text, c)

    val all: Map<SpeechCategory, List<SpeechRequest>> = listOf(
        line("startle_brake_1", "앗, 깜짝 놀랐어!", SpeechCategory.STARTLE),
        line("startle_brake_2", "우와, 몸이 앞으로 쏠렸어.", SpeechCategory.STARTLE),
        line("startle_brake_3", "괜찮아, 나 꽉 붙잡았어!", SpeechCategory.STARTLE),
        line("startle_accel_1", "우왓, 몸이 뒤로 붙었어!", SpeechCategory.STARTLE),
        line("startle_accel_2", "슝! 깜짝이야.", SpeechCategory.STARTLE),
        line("comfort_1", "흔들림 없어서 포근해.", SpeechCategory.COMFORT),
        line("comfort_2", "부드러워서 기분 좋아.", SpeechCategory.COMFORT),
        line("comfort_3", "같이 가니까 편안해.", SpeechCategory.COMFORT),
        line("greet_1", "출발! 같이 가자.", SpeechCategory.GREETING),
        line("greet_2", "오늘도 잘 부탁해.", SpeechCategory.GREETING),
        line("hello_1", "안녕하세요, 피코예요.", SpeechCategory.GREETING),
        line("rest_1", "우리 조금 쉬어갈까?", SpeechCategory.REST),
        line("rest_2", "나 기지개 켜고 싶어.", SpeechCategory.REST),
        line("arrive_1", "도착! 함께해서 좋았어.", SpeechCategory.ARRIVAL),
        line("arrive_2", "오늘도 고마웠어.", SpeechCategory.ARRIVAL),
        line("pet_1", "헤헤, 간지러워.", SpeechCategory.AFFECTION),
        line("pet_2", "나 쓰다듬어 줘서 좋아.", SpeechCategory.AFFECTION),
        line("energy_low_1", "배터리가 졸려. 쉬어갈까?", SpeechCategory.ENERGY),
        line("energy_low_2", "나 살짝 졸려, 충전하러 갈까?", SpeechCategory.ENERGY),
        line("energy_charge_1", "충전 중이야, 든든해.", SpeechCategory.ENERGY),
        line("energy_charge_2", "냠냠, 전기 맛있어!", SpeechCategory.ENERGY),
        line("passenger_1", "옆자리 친구도 안녕!", SpeechCategory.PASSENGER),
        line("passenger_2", "같이 타니까 더 신나!", SpeechCategory.PASSENGER),
    ).groupBy { it.category }
}
