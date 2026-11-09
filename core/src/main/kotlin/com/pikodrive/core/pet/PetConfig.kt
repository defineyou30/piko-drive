package com.pikodrive.core.pet

/** FRAMEWORK 2-1-1 · F4 규칙 수치. */
data class PetConfig(
    /** 급가감속 후 반응까지 지연 (2~3초 중앙값). */
    val reactionDelayMs: Long = 2_500,
    /**
     * v2 주행 반응(급제동 놀람·편안함 칭찬·배터리·충전·조수석 인사)을 말·표정으로 낼지.
     * v4(아이 일정·안전 비서)에서는 끈다 — 운전 평가처럼 들리고 컨셉과 충돌 (reviews/codex-v4-copy-flow.md).
     * 꺼도 트립 기록·모드 전환은 그대로.
     */
    val drivingReactions: Boolean = true,
    /** 주행 중 같은 카테고리 발화 최소 간격. */
    val speechCooldownMs: Long = 120_000,
    /** 주행 중 표정 변화 최소 간격. */
    val expressionMinIntervalMs: Long = 10_000,
    /** 우선 표정(놀람·배터리 졸림)의 최소 간격 */
    val priorityExpressionMinIntervalMs: Long = 3_000,
    /** 일시 표정(놀람·기쁨 등) 유지 후 기본 표정으로 복귀까지. */
    val expressionHoldMs: Long = 10_000,
    val speechRetryDelayMs: Long = 4_000,
    val speechRetryWindowMs: Long = 10_000,
    val growthMultiplier: Int = 1,
    /** 주행 중 전역 발화 최소 간격 (카테고리 무관). */
    val speechMinGapMs: Long = 30_000,
    /** 주행 중 시간당 최대 발화 수. */
    val speechMaxPerHour: Int = 12,
    /** 정차 1회당 쓰다듬기 반영(기분·친밀도·대사) 최대 횟수. 이후엔 HAPPY 표정만 (FRAMEWORK F1). */
    val pettingCapPerPark: Int = 3,
    /** v3: 바쁠 때 대기시킨 아이 대사를 이 시간 안에만 이어서 말한다 (늦으면 버림). */
    val rideQueueMaxWaitMs: Long = 8_000,
)
