package com.pikodrive.core.agent

import java.time.DayOfWeek
import java.time.LocalTime

/**
 * 아이 일정 (v4 에이전트, docs/CONCEPT_v4_agent.md). 학원 이름 대신 부모가 정한 짧은 제목만 저장한다.
 * [leadMinutes] 전에 운전자에게 브리핑한다.
 */
data class ScheduleEntry(
    val id: String,
    val childId: String?,
    val days: Set<DayOfWeek>,
    val time: LocalTime,
    val title: String,
    val items: List<String> = emptyList(),
    val leadMinutes: Int = 15,
)

/** 메모를 언제 꺼낼지. 시각(TIME) 외에는 모두 차량 신호가 결정한다. */
enum class MemoTrigger(val label: String) {
    BOARD("탈 때"),
    DEPART("출발할 때"),
    ARRIVE("도착할 때"),
    ALIGHT("내릴 때"),
    TIME("정한 시각에"),
}

/**
 * 상황에 묶인 메모. 예) "아이가 내릴 때: 아기 쪽 문 닫힘 확인" (반복), "도착할 때: 알림장 받기" (한 번).
 * [childId]가 있으면 그 아이가 관련된 상황에서만. 한 번짜리는 꺼내면 [done]이 된다.
 */
data class Memo(
    val id: String,
    val text: String,
    val trigger: MemoTrigger,
    val childId: String? = null,
    val time: LocalTime? = null,
    val repeat: Boolean = false,
    val done: Boolean = false,
)

/** 하루 안전 기록에 남기는 종류 (보조 알림이 실제로 울린 일) */
enum class SafetyKind(val label: String) {
    DEPART_UNBUCKLED("미체결 출발"),
    BELT_RELEASED("주행 중 벨트 풀림"),
    DOOR_WHILE_MOVING("주행 중 문 열림"),
    STREET_DOOR("차도 쪽 문 열림"),
    LEFT_BEHIND("뒷좌석 남김 알림"),
}

data class SafetyRecord(val atWallMs: Long, val kind: SafetyKind, val seatLabel: String?)
