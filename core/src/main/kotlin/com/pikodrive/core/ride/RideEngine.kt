package com.pikodrive.core.ride

import com.pikodrive.core.model.DriveMode
import com.pikodrive.core.model.RearSeat
import com.pikodrive.core.model.VehicleSnapshot
import java.time.DayOfWeek

data class RideConfig(
    /** 착석 후 벨트 미체결이 이만큼 지속되면 피코가 벨트 놀이로 재촉 */
    val beltPromptMs: Long = 8_000,
    /** 주행 중 벨트가 계속 풀려 있으면 다시 알리는 간격·횟수 */
    val beltReminderMs: Long = 60_000,
    val beltReminderMax: Int = 2,
    /** 이 속도 이상이면 "움직이는 중" (벨트 풀림·문 열림 경고, 주행 시간 적립) */
    val movingKmh: Float = 5f,
    val restInfantMs: Long = 60 * 60_000L,
    val restDefaultMs: Long = 120 * 60_000L,
    /** 정차가 이만큼 이어지면 주행 시간(휴식 기준)을 초기화 */
    val restResetParkMs: Long = 10 * 60_000L,
    val leftBehindRepeatMs: Long = 10_000,
    /** 인도 쪽 문을 연 뒤 이 시간 안에 착석이 풀리면 "인도 쪽으로 내림" */
    val exitDoorWindowMs: Long = 15_000,
    /**
     * 세션 시작 직후 이 시간 동안은 값만 받고 전이 이벤트를 내지 않는다. SDK 리스너 초기 콜백과
     * 현재값 조회가 비동기로 엇갈려 들어와 탑승·하차가 한 번씩 튀던 문제 (에뮬레이터 E2E에서 발견)
     */
    val warmupMs: Long = 3_000,
    /** 영유아 실내 적정 온도 (℃, 포함) */
    val infantTempRange: ClosedFloatingPointRange<Float> = 20f..26f,
    /** 뒷창문 열림 경고 기준 (0..10 단위, 초과 시) */
    val windowOpenTenths: Int = 2,
    /** 스냅샷 간격이 이보다 길면 주행 시간 적립을 이 값으로 자른다 (끊김 구간 과대 적립 방지) */
    val maxTickMs: Long = 2_000,
)

enum class RidePhase {
    /** 뒷좌석에 아무도 없음 (또는 신호 없음) */
    IDLE,
    /** 정차 중 아이 탑승, 벨트 미완료 */
    BOARDING,
    /** 정차 중 착석 좌석 벨트 모두 체결 */
    READY,
    DRIVING,
    /** 아이를 태운 주행 후 정차 */
    ARRIVED,
    /** 남김 보조 알림 중 */
    LEFT_BEHIND,
}

enum class CheckStatus { OK, ATTENTION, UNKNOWN }

/** 출발 전 체크 요약 한 줄. 부모가 누르지 않고 신호로만 채워진다. */
data class CheckItem(val id: String, val label: String, val status: CheckStatus, val detail: String)

data class SeatView(
    val seat: RearSeat,
    val occupied: Boolean?,
    val belted: Boolean?,
    val doorOpen: Boolean?,
    val profile: ChildProfile?,
)

enum class LeftBehindStatus { NONE, ALERT, UNKNOWN }

data class RideState(
    val phase: RidePhase = RidePhase.IDLE,
    val seats: List<SeatView> = RearSeat.entries.map { SeatView(it, null, null, null, null) },
    val checklist: List<CheckItem> = emptyList(),
    val leftBehind: LeftBehindStatus = LeftBehindStatus.NONE,
    /** 아이 동승 주행 누적 (휴식 기준) */
    val kidDriveMs: Long = 0,
) {
    val onboard: List<SeatView> get() = seats.filter { it.occupied == true }
    val attentionCount: Int get() = checklist.count { it.status == CheckStatus.ATTENTION }
}

/**
 * 뒷좌석 루틴 상태머신 (docs/CONCEPT_v3_kids_ride.md 5장). 스냅샷 하나 + 그 시점 [DriveMode]를 받아 이벤트를 판정한다.
 * 순수 로직 (시간은 스냅샷 timestampMs만 사용) → 단위 테스트로 전 구간 검증.
 *
 * 안전 관련 판정은 모두 "보조"다. 착석 신호가 null이면 아이가 없다고 가정하지 않고 UNKNOWN으로 남긴다.
 * 스레드 안전하지 않다 (PikoSession 수집 코루틴에서만 호출).
 */
class RideEngine(private val config: RideConfig = RideConfig()) {

    var profiles: List<ChildProfile> = emptyList()
    /** 차도 쪽. 한국(우측통행)에서 길가 정차 시 왼쪽 문이 차도 쪽 */
    var streetSide: RearSeat = RearSeat.LEFT
    /**
     * 도착 때 말할 아이별 준비물. v4부터 준비물은 일정에서만 입력 → 세션이 "그 아이의 지금 무렵 일정 준비물"로 바꿔 끼운다.
     * 기본값은 예전 프로필의 요일별 준비물 (저장된 데이터 호환).
     */
    var arrivalItems: (ChildProfile, DayOfWeek) -> List<String> = { p, d -> p.itemsByDay[d].orEmpty() }

    var state: RideState = RideState()
        private set

    private inner class SeatTrack(val seat: RearSeat) {
        var occupied: Boolean? = null
        var belted: Boolean? = null
        var doorOpen: Boolean? = null
        var unbeltedSince: Long? = null
        var prompted = false
        var selfBeltAwarded = false
        var releasedAt: Long? = null
        var reminders = 0
        var pendingRelease = false
        var lastDoorOpenAt: Long? = null
        var lastDoorWasCurb = false
        /** 이번 탑승 이후 함께 주행했는지 (하차·스티커·남김 판정용) */
        var rodeThisBoarding = false

        fun profile() = profiles.firstOrNull { it.seat == seat }
        /** 스티커는 프로필이 있고 연령대가 대상일 때만 (저장할 곳이 없거나 영유아·중고등이면 없음) */
        fun earnsStickers() = profile()?.age?.earnsStickers == true
        fun resetBoarding() {
            unbeltedSince = null; prompted = false; selfBeltAwarded = false
            releasedAt = null; reminders = 0; pendingRelease = false; rodeThisBoarding = false
        }
    }

    private val tracks = RearSeat.entries.associateWith { SeatTrack(it) }
    private var prevMode: DriveMode = DriveMode.BOOTING
    private var lastTs: Long? = null
    private var kidDriveMs = 0L
    private var restNotified = 0
    private var parkedSince: Long? = null
    private var tripWithKids = false
    private var driverPresent: Boolean? = null
    private var doorsLocked: Boolean? = null
    private var leftBehindSince: Long? = null
    private var leftBehindLevel = 0
    private var leftBehindUnknownSent = false
    private var acknowledged = false
    private var firstTs: Long? = null
    private var lockedWhileParked = false

    fun process(s: VehicleSnapshot, mode: DriveMode, today: DayOfWeek): List<RideEvent> {
        val out = mutableListOf<RideEvent>()
        val now = s.timestampMs
        val dt = lastTs?.let { (now - it).coerceIn(0, config.maxTickMs) } ?: 0L
        lastTs = now
        val moving = (s.speedKmh ?: 0f) >= config.movingKmh
        val c = s.cabin
        val start = firstTs ?: now.also { firstTs = it }
        if (now - start < config.warmupMs) {
            seed(c)
            prevMode = mode
            state = buildState(mode, c)
            return emptyList()
        }

        // --- 좌석별 전이 ---
        for (t in tracks.values) {
            val occ = c.rearOccupied[t.seat]
            val belt = c.rearBelted[t.seat]
            val door = c.rearDoorOpen[t.seat]
            val parked = mode == DriveMode.PARKED

            // 문
            if (door == true && t.doorOpen == false) {
                t.lastDoorOpenAt = now
                t.lastDoorWasCurb = t.seat != streetSide
                if (mode == DriveMode.DRIVING && moving) out += RideEvent.DoorOpenWhileMoving(now, t.seat)
                if (parked && t.seat == streetSide && anyOccupiedOrUnknown(c.rearOccupied)) {
                    out += RideEvent.StreetSideDoorOpened(now, t.seat)
                }
            }
            if (door != null) t.doorOpen = door

            // 착석
            val prevOcc = t.occupied
            if (occ == true && prevOcc == false && mode != DriveMode.DRIVING) {
                t.resetBoarding()
                out += RideEvent.ChildBoarded(now, t.seat)
            }
            if (occ == false && prevOcc == true && parked) {
                val viaCurb = recentCurbExit(now)
                out += RideEvent.ChildAlighted(now, t.seat, viaCurb)
                if (viaCurb && t.rodeThisBoarding && t.earnsStickers()) out += RideEvent.StickerEarned(now, t.seat, StickerReason.CURB_EXIT)
                t.resetBoarding()
            }
            if (occ != null) t.occupied = occ

            // 벨트
            val prevBelt = t.belted
            if (belt != null) t.belted = belt
            if (t.occupied != true) {
                t.unbeltedSince = null
                continue
            }
            if (belt == true && prevBelt == false) {
                val self = !t.prompted && mode != DriveMode.DRIVING
                out += RideEvent.BeltBuckled(now, t.seat, self)
                if (self && !t.selfBeltAwarded && t.earnsStickers()) {
                    t.selfBeltAwarded = true
                    out += RideEvent.StickerEarned(now, t.seat, StickerReason.SELF_BELT)
                }
                t.unbeltedSince = null; t.releasedAt = null; t.reminders = 0; t.pendingRelease = false
            }
            if (t.belted == false) {
                if (t.unbeltedSince == null) t.unbeltedSince = now
                // 도착 후(함께 달린 뒤 정차) 벨트를 푸는 건 내리려는 것 → 재촉하지 않는다
                if (parked && !t.prompted && !t.rodeThisBoarding && now - t.unbeltedSince!! >= config.beltPromptMs) {
                    t.prompted = true
                    out += RideEvent.BeltPrompt(now, t.seat)
                }
                // 신호 대기(D, 정지) 중에 풀어도 다시 움직이는 순간 알린다
                if (mode == DriveMode.DRIVING && prevBelt == true) t.pendingRelease = true
                if (mode == DriveMode.DRIVING && moving) {
                    if (t.pendingRelease) {
                        t.pendingRelease = false
                        t.releasedAt = now; t.reminders = 0
                        out += RideEvent.BeltReleasedWhileDriving(now, t.seat, reminder = false)
                    } else {
                        val since = t.releasedAt ?: now.also { t.releasedAt = it }
                        if (t.reminders < config.beltReminderMax && now - since >= config.beltReminderMs * (t.reminders + 1)) {
                            t.reminders++
                            out += RideEvent.BeltReleasedWhileDriving(now, t.seat, reminder = true)
                        }
                    }
                }
            }
        }

        val occupiedSeats = tracks.values.filter { it.occupied == true }.map { it.seat }

        // --- 모드 전이: 출발·도착 ---
        if (mode == DriveMode.DRIVING && prevMode != DriveMode.DRIVING && prevMode != DriveMode.REVERSING) {
            val unbelted = tracks.values.filter { it.occupied == true && it.belted == false }.map { it.seat }
            if (unbelted.isNotEmpty()) {
                out += RideEvent.DepartUnbuckled(now, unbelted)
                // 출발 경고를 이미 했으니 주행 중 리마인더는 지금부터 센다
                unbelted.forEach { tracks.getValue(it).releasedAt = now }
            }
        }
        if (mode == DriveMode.DRIVING && moving && occupiedSeats.isNotEmpty()) {
            if (!tripWithKids) out += RideEvent.DepartedWithKids(now, occupiedSeats)
            tripWithKids = true
            occupiedSeats.forEach { tracks.getValue(it).rodeThisBoarding = true }
        }
        if (mode == DriveMode.PARKED && prevMode == DriveMode.DRIVING && tripWithKids) {
            val seats = occupiedSeats
            if (seats.isNotEmpty()) {
                val items = seats.mapNotNull { seat ->
                    tracks.getValue(seat).profile()?.let { arrivalItems(it, today) }?.takeIf { it.isNotEmpty() }?.let { seat to it }
                }.toMap()
                out += RideEvent.Arrived(now, seats, items)
            }
        }

        // --- 휴식 (아이 동승 주행 시간) ---
        if (mode == DriveMode.PARKED) {
            val since = parkedSince ?: now.also { parkedSince = it }
            if (now - since >= config.restResetParkMs) { kidDriveMs = 0; restNotified = 0 }
        } else {
            parkedSince = null
        }
        if (mode == DriveMode.DRIVING && occupiedSeats.isNotEmpty()) {
            kidDriveMs += dt
            val infant = occupiedSeats.any { tracks.getValue(it).profile()?.age == AgeGroup.INFANT }
            val step = if (infant) config.restInfantMs else config.restDefaultMs
            val due = (kidDriveMs / step).toInt()
            if (due > restNotified) {
                restNotified = due
                out += RideEvent.RestSuggested(now, (kidDriveMs / 60_000).toInt(), infant)
            }
        }

        // --- 남김 보조 알림 ---
        c.driverPresent?.let { driverPresent = it }
        val lockedNow = c.doorsLocked
        // 잠금 트리거는 "정차 중에 잠갔다"(false→true)만 본다. 주행 중 자동 잠금이 남은 채 P에 들어오는 건
        // 도착의 평범한 상태라 남김이 아니다 (E2E 보호자 화면에서 도착마다 오경보가 나던 문제)
        if (lockedNow == false) lockedWhileParked = false
        if (lockedNow == true && doorsLocked == false && mode == DriveMode.PARKED) lockedWhileParked = true
        if (lockedNow != null) doorsLocked = lockedNow
        out += leftBehind(now, mode, c.rearOccupied)

        if (mode == DriveMode.PARKED && occupiedSeats.isEmpty() && leftBehindSince == null) tripWithKids = false
        prevMode = mode
        state = buildState(mode, c)
        return out
    }

    private fun seed(c: com.pikodrive.core.model.CabinSnapshot) {
        for (t in tracks.values) {
            c.rearOccupied[t.seat]?.let { t.occupied = it }
            c.rearBelted[t.seat]?.let { t.belted = it }
            c.rearDoorOpen[t.seat]?.let { t.doorOpen = it }
        }
        c.driverPresent?.let { driverPresent = it }
        c.doorsLocked?.let { doorsLocked = it }
    }

    /** 부모가 남김 알림 화면에서 "확인했어요"를 누름 → 상황이 바뀔 때까지(운전자 복귀·하차) 반복 중지. */
    fun acknowledgeLeftBehind() {
        acknowledged = true
    }

    private fun leftBehind(now: Long, mode: DriveMode, rearOccupied: Map<RearSeat, Boolean?>): List<RideEvent> {
        val riders = tracks.values.filter { it.rodeThisBoarding }
        val occupied = riders.filter { it.occupied == true }.map { it.seat }
        val unknownSignal = riders.isNotEmpty() && riders.all { rearOccupied[it.seat] == null }
        val trigger = mode == DriveMode.PARKED && (driverPresent == false || lockedWhileParked)
        val out = mutableListOf<RideEvent>()
        when {
            trigger && unknownSignal -> {
                if (!leftBehindUnknownSent) {
                    leftBehindUnknownSent = true
                    out += RideEvent.LeftBehindUnknown(now)
                }
            }
            trigger && occupied.isNotEmpty() -> {
                val since = leftBehindSince
                if (since == null) {
                    leftBehindSince = now; leftBehindLevel = 1; acknowledged = false
                    out += RideEvent.LeftBehindAlert(now, occupied, 1)
                } else if (!acknowledged && now - since >= config.leftBehindRepeatMs * leftBehindLevel) {
                    leftBehindLevel = (leftBehindLevel + 1).coerceAtMost(3)
                    out += RideEvent.LeftBehindAlert(now, occupied, leftBehindLevel)
                    // 상한 도달 후에도 같은 간격으로 반복
                    if (leftBehindLevel == 3) leftBehindSince = now - config.leftBehindRepeatMs * 2
                }
            }
            else -> {
                if (leftBehindSince != null || leftBehindUnknownSent) out += RideEvent.LeftBehindCleared(now)
                leftBehindSince = null; leftBehindLevel = 0; leftBehindUnknownSent = false; acknowledged = false
            }
        }
        return out
    }

    private fun recentCurbExit(now: Long): Boolean {
        val last = tracks.values.filter { it.lastDoorOpenAt != null }.maxByOrNull { it.lastDoorOpenAt!! } ?: return false
        return last.lastDoorWasCurb && now - last.lastDoorOpenAt!! <= config.exitDoorWindowMs
    }

    private fun anyOccupiedOrUnknown(occ: Map<RearSeat, Boolean?>) =
        RearSeat.entries.any { occ[it] != false }

    private fun buildState(mode: DriveMode, c: com.pikodrive.core.model.CabinSnapshot): RideState {
        val seats = RearSeat.entries.map { seat ->
            val t = tracks.getValue(seat)
            SeatView(seat, t.occupied, t.belted.takeIf { t.occupied == true }, t.doorOpen, t.profile())
        }
        val onboard = seats.filter { it.occupied == true }
        val lb = when {
            leftBehindSince != null -> LeftBehindStatus.ALERT
            leftBehindUnknownSent -> LeftBehindStatus.UNKNOWN
            else -> LeftBehindStatus.NONE
        }
        val phase = when {
            lb != LeftBehindStatus.NONE -> RidePhase.LEFT_BEHIND
            onboard.isEmpty() -> RidePhase.IDLE
            mode == DriveMode.DRIVING || mode == DriveMode.REVERSING -> RidePhase.DRIVING
            tripWithKids && tracks.values.any { it.rodeThisBoarding && it.occupied == true } -> RidePhase.ARRIVED
            onboard.all { it.belted == true } -> RidePhase.READY
            else -> RidePhase.BOARDING
        }
        // 도착 후엔 벨트를 풀고 잠금을 여는 게 정상 → 그 줄은 빼고 문·창문만 본다
        val alighting = phase == RidePhase.ARRIVED || phase == RidePhase.LEFT_BEHIND
        val items = checklist(if (alighting) emptyList() else onboard, onboard, c)
            .filterNot { alighting && it.id == "door_lock" } // 내리려면 잠금이 풀리는 게 정상
        return RideState(phase, seats, items, lb, kidDriveMs)
    }

    private fun checklist(beltRows: List<SeatView>, onboard: List<SeatView>, c: com.pikodrive.core.model.CabinSnapshot): List<CheckItem> {
        val items = mutableListOf<CheckItem>()
        beltRows.forEach { v ->
            val who = v.profile?.alias?.let { "${v.seat.label} $it" } ?: v.seat.label
            items += when (v.belted) {
                true -> CheckItem("belt_${v.seat}", "$who 벨트", CheckStatus.OK, "체결")
                false -> CheckItem("belt_${v.seat}", "$who 벨트", CheckStatus.ATTENTION, "미체결")
                null -> CheckItem("belt_${v.seat}", "$who 벨트", CheckStatus.UNKNOWN, "확인 불가")
            }
        }
        val doors = RearSeat.entries.map { c.rearDoorOpen[it] }
        items += when {
            doors.all { it == null } -> CheckItem("rear_doors", "뒷문", CheckStatus.UNKNOWN, "확인 불가")
            doors.any { it == true } -> CheckItem("rear_doors", "뒷문", CheckStatus.ATTENTION,
                RearSeat.entries.filter { c.rearDoorOpen[it] == true }.joinToString { it.label.removeSuffix("뒷좌석") + "열림" })
            else -> CheckItem("rear_doors", "뒷문", CheckStatus.OK, "닫힘")
        }
        items += when (c.doorsLocked) {
            true -> CheckItem("door_lock", "문 잠금", CheckStatus.OK, "잠김")
            false -> CheckItem("door_lock", "문 잠금", CheckStatus.ATTENTION, "열림")
            null -> CheckItem("door_lock", "문 잠금", CheckStatus.UNKNOWN, "확인 불가")
        }
        val windows = RearSeat.entries.map { c.rearWindowOpenTenths[it] }
        items += when {
            windows.all { it == null } -> CheckItem("rear_windows", "뒷창문", CheckStatus.UNKNOWN, "확인 불가")
            windows.any { (it ?: 0) > config.windowOpenTenths } -> CheckItem("rear_windows", "뒷창문", CheckStatus.ATTENTION,
                "${(windows.maxOf { it ?: 0 }) * 10}% 열림")
            else -> CheckItem("rear_windows", "뒷창문", CheckStatus.OK, "닫힘")
        }
        if (onboard.any { it.profile?.age == AgeGroup.INFANT }) {
            val temp = c.cabinTempC
            items += when {
                temp == null -> CheckItem("cabin_temp", "실내 온도", CheckStatus.UNKNOWN, "확인 불가")
                temp in config.infantTempRange -> CheckItem("cabin_temp", "실내 온도", CheckStatus.OK, "%.0f℃".format(temp))
                temp < config.infantTempRange.start -> CheckItem("cabin_temp", "실내 온도", CheckStatus.ATTENTION, "%.0f℃ · 조금 서늘해요".format(temp))
                else -> CheckItem("cabin_temp", "실내 온도", CheckStatus.ATTENTION, "%.0f℃ · 조금 더워요".format(temp))
            }
        }
        return items
    }
}
