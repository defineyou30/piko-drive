package com.pikodrive.core.agent

import com.pikodrive.core.model.DriveMode
import com.pikodrive.core.pet.SpeechCategory
import com.pikodrive.core.pet.SpeechRequest
import com.pikodrive.core.ride.ChildProfile
import com.pikodrive.core.ride.RideEvent
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

sealed interface AgentEvent {
    /** 일정 [ScheduleEntry.leadMinutes] 전 브리핑 (하루 한 번) */
    data class ScheduleSoon(val entry: ScheduleEntry, val minutesLeft: Int) : AgentEvent

    /** 메모를 꺼낼 때 */
    data class MemoDue(val memo: Memo) : AgentEvent
}

data class ScheduleView(val entry: ScheduleEntry, val past: Boolean)

/** 부모 화면 브리핑 카드 */
data class AgentBriefing(
    val today: List<ScheduleView> = emptyList(),
    val next: ScheduleEntry? = null,
    val minutesToNext: Int? = null,
    val pendingMemos: List<Memo> = emptyList(),
)

/**
 * 스케줄·메모 에이전트 (부모 쪽). 차량 모드 전이와 뒷좌석 루틴 이벤트, 벽시계만으로 판단하는 순수 로직.
 * - 일정: 오늘 요일에 해당하는 일정이 [ScheduleEntry.leadMinutes] 안으로 들어오면 한 번 브리핑
 * - 메모: 트리거 상황(탈 때·출발·도착·내릴 때·시각)에서 꺼낸다. 한 번짜리는 [onMemoFired]로 완료 처리 요청
 * - 출발·도착은 정차 ↔ 주행 전이 (앱 시작 직후의 첫 모드는 전이로 보지 않음)
 */
class AgentEngine {
    var schedules: List<ScheduleEntry> = emptyList()
    var memos: List<Memo> = emptyList()

    private var prevMode: DriveMode? = null
    private val firedToday = mutableMapOf<String, LocalDate>()

    fun process(
        now: LocalDateTime,
        mode: DriveMode,
        rideEvents: List<RideEvent>,
        profiles: List<ChildProfile>,
        onboardChildIds: Set<String>,
    ): List<AgentEvent> {
        val out = mutableListOf<AgentEvent>()
        val today = now.toLocalDate()

        // 일정 브리핑
        for (e in schedules) {
            if (today.dayOfWeek !in e.days) continue
            val at = today.atTime(e.time)
            val left = Duration.between(now, at).toMinutes()
            val key = "s:${e.id}"
            if (now.isBefore(at) && left < e.leadMinutes && firedToday[key] != today) {
                firedToday[key] = today
                out += AgentEvent.ScheduleSoon(e, (left + 1).toInt().coerceAtMost(e.leadMinutes))
            }
        }

        // 상황 트리거
        val prev = prevMode
        val departed = prev == DriveMode.PARKED && mode == DriveMode.DRIVING
        val arrived = prev == DriveMode.DRIVING && mode == DriveMode.PARKED
        if (mode != DriveMode.BOOTING) prevMode = mode
        fun seatChild(e: RideEvent): String? = when (e) {
            is RideEvent.ChildBoarded -> profiles.firstOrNull { it.seat == e.seat }?.id
            is RideEvent.ChildAlighted -> profiles.firstOrNull { it.seat == e.seat }?.id
            else -> null
        }
        val pending = memos.filter { !it.done }
        fun fire(m: Memo) {
            if (out.any { it is AgentEvent.MemoDue && it.memo.id == m.id }) return
            out += AgentEvent.MemoDue(m)
        }
        for (m in pending) {
            when (m.trigger) {
                MemoTrigger.DEPART -> if (departed && m.matchesOnboard(onboardChildIds)) fire(m)
                MemoTrigger.ARRIVE -> if (arrived && m.matchesOnboard(onboardChildIds)) fire(m)
                MemoTrigger.BOARD -> rideEvents.filterIsInstance<RideEvent.ChildBoarded>()
                    .firstOrNull { m.childId == null || seatChild(it) == m.childId }?.let { fire(m) }
                MemoTrigger.ALIGHT -> rideEvents.filterIsInstance<RideEvent.ChildAlighted>()
                    .firstOrNull { m.childId == null || seatChild(it) == m.childId }?.let { fire(m) }
                MemoTrigger.TIME -> {
                    val t = m.time ?: continue
                    val key = "m:${m.id}"
                    val late = Duration.between(today.atTime(t), now).toMinutes()
                    // 앱을 늦게 켰을 때 지난 메모가 몰려 나오지 않게 10분 안쪽만
                    if (late in 0 until TIME_GRACE_MIN && firedToday[key] != today) {
                        firedToday[key] = today
                        fire(m)
                    }
                }
            }
        }
        return out
    }

    /**
     * 도착 시 이 아이에게 말할 준비물: 오늘 이 아이(또는 공통) 일정 중 지금 시각에 가장 가까운 것
     * (30분 전에 시작했거나 2시간 안에 시작할 일정)의 준비물.
     */
    fun itemsNear(now: LocalDateTime, childId: String): List<String> {
        val today = now.toLocalDate()
        return schedules
            .filter { today.dayOfWeek in it.days && (it.childId == null || it.childId == childId) && it.items.isNotEmpty() }
            .map { it to Duration.between(now, today.atTime(it.time)).toMinutes() }
            .filter { (_, m) -> m in -30..120 }
            .minByOrNull { (_, m) -> kotlin.math.abs(m) }?.first?.items.orEmpty()
    }

    fun briefing(now: LocalDateTime): AgentBriefing {
        val today = now.toLocalDate()
        val list = schedules.filter { today.dayOfWeek in it.days }.sortedBy { it.time }
            .map { ScheduleView(it, !today.atTime(it.time).isAfter(now)) }
        val next = list.firstOrNull { !it.past }?.entry
        return AgentBriefing(
            today = list,
            next = next,
            minutesToNext = next?.let { Duration.between(now, today.atTime(it.time)).toMinutes().toInt() },
            pendingMemos = memos.filter { !it.done },
        )
    }

    private fun Memo.matchesOnboard(onboard: Set<String>) = childId == null || childId in onboard

    companion object {
        const val TIME_GRACE_MIN = 10L
    }
}

/** 에이전트 대사. 운전자에게 하는 말이라 존댓말, 한두 문장. 아이 이름은 별칭만. */
object AgentLines {
    fun lineFor(e: AgentEvent, profiles: List<ChildProfile>): SpeechRequest = when (e) {
        is AgentEvent.ScheduleSoon -> {
            val who = e.entry.childId?.let { id -> profiles.firstOrNull { it.id == id }?.alias }
            val items = e.entry.items.takeIf { it.isNotEmpty() }?.let { " 준비물은 ${it.joinToString(", ")}." } ?: ""
            SpeechRequest(
                "agent_schedule",
                "${e.minutesLeft}분 뒤 ${hm(e.entry.time)}, ${who?.let { "$it " } ?: ""}${e.entry.title}${copula(e.entry.title)}.$items",
                SpeechCategory.AGENT,
            )
        }
        is AgentEvent.MemoDue -> SpeechRequest("agent_memo_${e.memo.trigger.name.lowercase()}", "메모예요. ${e.memo.text}", SpeechCategory.AGENT)
    }

    fun hm(t: LocalTime) = "%02d:%02d".format(t.hour, t.minute)

    /** 받침 있으면 "이에요", 없으면 "예요" (태권도예요 / 수영이에요). 한글이 아니면 "이에요" */
    fun copula(word: String): String {
        val c = word.trimEnd().lastOrNull() ?: return "이에요"
        if (c !in '가'..'힣') return "이에요"
        return if ((c - '가') % 28 == 0) "예요" else "이에요"
    }
}

/** 보조 알림이 실제로 울린 이벤트 → 안전 기록 종류. 같은 상황의 반복 알림은 첫 번째만 */
fun RideEvent.safetyKind(): SafetyKind? = when (this) {
    is RideEvent.DepartUnbuckled -> SafetyKind.DEPART_UNBUCKLED
    is RideEvent.BeltReleasedWhileDriving -> if (reminder) null else SafetyKind.BELT_RELEASED
    is RideEvent.DoorOpenWhileMoving -> SafetyKind.DOOR_WHILE_MOVING
    is RideEvent.StreetSideDoorOpened -> SafetyKind.STREET_DOOR
    is RideEvent.LeftBehindAlert -> if (level == 1) SafetyKind.LEFT_BEHIND else null
    else -> null
}
