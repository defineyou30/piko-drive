package com.pikodrive.core.sim

import com.pikodrive.core.model.Gear

/** 데모 패널(F9)에서 고를 수 있는 각본. */
object Scenarios {
    /**
     * ~90초 PT 데모: 정차 → 출발 → 부드러운 순항 → 급제동 1회 → 부드럽게 → 잠깐 후진 → 정차.
     * DetectionConfig.Demo + DEMO_GROWTH_MULTIPLIER 와 함께 쓰면 리포트에서 BODY 단계까지 성장한다.
     */
    val commuteCompressed: Scenario = scenario("commuteCompressed") {
        start(Gear.PARK, 0f, uxRestricted = false)
        hold(5_000)
        gear(Gear.DRIVE); uxRestricted(true)   // t=5s D, 7s부터 이동 → 10s 트립 확정·출발 인사
        hold(1_000)
        rampTo(40f, 8_000)          // +5 km/h/s, 부드러운 출발
        hold(10_000)
        rampTo(45f, 6_000)
        rampTo(40f, 6_000)          // t=36s 무렵 부드러운 주행 스트릭(32초) → 칭찬
        hold(14_000)
        rampTo(50f, 6_000)
        hold(10_000)                 // t=66s
        rampTo(25f, 2_000)           // -12.5 km/h/s 급제동 (칭찬과 30초 이상 간격 → 전역 예산 통과)
        hold(1_000)
        rampTo(0f, 5_000)            // -5 km/h/s, 부드러운 정지 → t=74s
        hold(1_000)
        gear(Gear.REVERSE)           // t=75s
        rampTo(5f, 2_000)
        hold(2_000)
        rampTo(0f, 2_000)            // t=81s
        gear(Gear.PARK); uxRestricted(false)
        hold(9_000)                  // t=90s, 정차 확정(3초) 이후 리포트
    }

    /**
     * ~68초 S7 데모 (패밀리·동승자 케어 + EV): 정차 중 동승자 탑승 → 출발 → 주행 중 배터리 18% →
     * 부드러운 정지·정차 → 충전구 열림. VehicleCapabilities.All(시뮬레이터 기본값)로 재생할 것.
     * 출발 확정·인사(≈11s, 5 km/h 이상 3초 이동 후)와 배터리 알림(41s)은 전역 발화 예산(30초) 간격을 지킨다.
     */
    val familyEv: Scenario = scenario("familyEv") {
        start(Gear.PARK, 0f, uxRestricted = false)
        battery(32); chargePort(false); passenger(false)
        hold(3_000)
        passenger(true)              // t=3s 동승자 탑승 → 반가움 (정차)
        hold(3_000)
        gear(Gear.DRIVE); uxRestricted(true)   // t=6s D (아직 트립 아님)
        hold(1_000)
        rampTo(40f, 8_000)           // +5 km/h/s, 8s에 5 km/h → 11s 트립 확정·출발 인사
        hold(26_000)
        battery(18)                  // t=41s 배터리 부족 → 졸린 표정 + 한마디
        hold(10_000)
        rampTo(0f, 6_000)            // -6.7 km/h/s, 부드러운 정지 → t=57s
        gear(Gear.PARK); uxRestricted(false)   // t=57s P, 3초 유지 후 정차 확정(60s) → 도착 인사
        hold(7_000)
        chargePort(true)             // t=64s 충전 시작
        hold(4_000)                  // t=68s
    }

    val all: List<Scenario> = listOf(commuteCompressed, familyEv)
}
