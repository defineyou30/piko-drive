package com.pikodrive.core.driving

import com.pikodrive.core.model.DriveMode
import com.pikodrive.core.model.VehicleSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** 스냅샷 1개 처리 결과. */
data class DetectionResult(val mode: DriveMode, val events: List<DrivingEvent>)

/**
 * 차량 스냅샷 → [DriveMode] + [DrivingEvent]. 순수 상태 기계이며 시간은 스냅샷 timestamp만 사용한다.
 * 단일 스트림 전용 (스레드 안전하지 않음).
 */
class DrivingEventDetector(
    private val config: DetectionConfig = DetectionConfig(),
    /** true면 UX Restriction 해제(false)를 확인해야 PARKED. VehicleCapabilities.uxRestriction 을 넘길 것. */
    uxRestrictionSupported: Boolean = false,
) {
    private val modeResolver = DriveModeResolver(config, uxRestrictionSupported)
    private val accel = AccelerationTracker(config)

    val mode: DriveMode get() = modeResolver.mode

    /** 현재(또는 마지막) 트립의 편안한 주행 누적 ms. */
    var tripComfortMs: Long = 0
        private set

    private var lastTimestampMs: Long? = null
    private var inTrip = false
    private var tripStartMs = 0L
    private var parkedSinceMs: Long? = null
    private var lastHardEventMs: Long? = null
    private var lastBrakeMs: Long? = null
    private var lastAccelMs: Long? = null
    private var streakMs = 0L
    private var streakEmitted = 0
    private var longDriveEmitted = 0
    private var comfortPendingMs = 0L
    private var lastSpeedKmh: Float? = null
    private var speedSeen = false
    /** 트립 확정 전 D에서 움직인 누적 ms (후보 트립). */
    private var candidateMovingMs = 0L
    private var batteryArmed = true
    private var lastChargePortOpen: Boolean? = null
    private var lastPassengerPresent: Boolean? = null
    private var passengerJoinedEmitted = false

    fun process(s: VehicleSnapshot): List<DrivingEvent> {
        val prevTs = lastTimestampMs
        if (prevTs != null && s.timestampMs <= prevTs) return emptyList() // 역행/중복 샘플
        lastTimestampMs = s.timestampMs
        val dt = prevTs?.let { s.timestampMs - it }
        val ts = s.timestampMs
        val events = mutableListOf<DrivingEvent>()

        val prevMode = mode
        val prevWasMoving = prevMode == DriveMode.DRIVING && !modeResolver.restrictedPark
        val prevSpeed = lastSpeedKmh
        lastSpeedKmh = s.speedKmh
        if (s.speedKmh != null) speedSeen = true
        val newMode = modeResolver.update(s)
        val moving = (newMode == DriveMode.DRIVING || newMode == DriveMode.REVERSING) && !modeResolver.restrictedPark
        // 속도를 한 번도 못 받은 차량(속도 미지원)은 기존처럼 기어 기준으로 트립 시작.
        if (!speedSeen && moving && !inTrip) {
            startTrip(ts)
            events += DrivingEvent.Departed(ts)
        }
        if (newMode != prevMode) onModeChange(newMode, ts, events)

        val gap = dt != null && dt > config.maxSampleGapMs
        val signal = accel.update(ts, s.speedKmh, gap)
        if (newMode == DriveMode.DRIVING && signal != null) onSustainedAccel(signal, ts, events)
        // 직전 샘플이 D였다면 이번 샘플까지의 구간은 D로 본다 (D→P/R 전환 직전 구간 포함, R 구간은 제외).
        // 그 구간 시작 속도가 movingKmh 이상일 때만 '움직인 시간' (속도 미지원이면 기어 기준).
        if (prevWasMoving && dt != null) {
            val credit = minOf(dt, config.maxSampleGapMs)
            val movedSegment = prevSpeed == null || prevSpeed >= config.movingKmh
            if (inTrip) {
                accrue(credit, ts, events, movedSegment)
            } else if (prevSpeed != null && movedSegment) {
                candidateMovingMs += credit
                if (candidateMovingMs >= config.departConfirmMs) confirmTrip(ts, events)
            }
        }
        checkParkConfirm(ts, events)
        cabinSignals(s, newMode, events)
        return events
    }

    /**
     * S7 차량 신호: 배터리(히스테리시스), 충전구(정차 중만), 조수석(false→true, 트립당 1회).
     * null은 '모름'으로 보고 마지막으로 알던 값을 유지한다.
     */
    private fun cabinSignals(s: VehicleSnapshot, mode: DriveMode, events: MutableList<DrivingEvent>) {
        val ts = s.timestampMs
        s.batteryPct?.let { pct ->
            if (batteryArmed && pct <= config.batteryLowPct) {
                batteryArmed = false
                events += DrivingEvent.BatteryLow(ts, pct)
            } else if (!batteryArmed && pct >= config.batteryRearmPct) {
                batteryArmed = true
                events += DrivingEvent.BatteryRecovered(ts, pct)
            }
        }
        s.chargePortOpen?.let { open ->
            if (open && lastChargePortOpen != true && mode == DriveMode.PARKED) events += DrivingEvent.ChargingStarted(ts)
            lastChargePortOpen = open
        }
        s.passengerPresent?.let { present ->
            if (present && lastPassengerPresent == false && !passengerJoinedEmitted) {
                passengerJoinedEmitted = true
                events += DrivingEvent.PassengerJoined(ts)
            }
            lastPassengerPresent = present
        }
    }

    private fun onModeChange(newMode: DriveMode, ts: Long, events: MutableList<DrivingEvent>) {
        if (newMode == DriveMode.REVERSING) events += DrivingEvent.Reversing(ts)
        parkedSinceMs = if (newMode == DriveMode.PARKED) ts else null
        if (newMode == DriveMode.PARKED && !inTrip) candidateMovingMs = 0 // 움직이지 않은 후보 트립은 폐기
    }

    /** 후보 트립 → 실제 트립. 확정 전 이동 시간은 편안한 시간으로 소급 적립(스트릭은 출발 시점부터). */
    private fun confirmTrip(ts: Long, events: MutableList<DrivingEvent>) {
        val moved = candidateMovingMs
        startTrip(ts)
        events += DrivingEvent.Departed(ts)
        if (moved > 0) creditComfort(moved, ts, events)
    }

    private fun startTrip(ts: Long) {
        inTrip = true
        tripStartMs = ts
        tripComfortMs = 0
        lastHardEventMs = null
        lastBrakeMs = null
        lastAccelMs = null
        streakMs = 0
        streakEmitted = 0
        longDriveEmitted = 0
        comfortPendingMs = 0
        candidateMovingMs = 0
    }

    private fun onSustainedAccel(signal: SustainedAccel, ts: Long, events: MutableList<DrivingEvent>) {
        // 급가감속은 실제 이동의 증거 → 후보 트립을 즉시 확정 (출발 직후 급가속이 트립 밖으로 새지 않게).
        if (!inTrip) confirmTrip(ts, events)
        val last = if (signal.kind == AccelKind.BRAKE) lastBrakeMs else lastAccelMs
        if (last != null && ts - last < config.hardEventRefractoryMs) return
        events += when (signal.kind) {
            AccelKind.BRAKE -> DrivingEvent.HardBrake(ts, signal.kmhPerSec).also { lastBrakeMs = ts }
            AccelKind.ACCEL -> DrivingEvent.HardAccel(ts, signal.kmhPerSec).also { lastAccelMs = ts }
        }
        lastHardEventMs = ts
        streakMs = 0
        streakEmitted = 0
    }

    /** @param moved false = D지만 정지/서행 중 → 스트릭·편안한 시간은 쌓지 않고 장거리 경과만 본다. */
    private fun accrue(creditMs: Long, ts: Long, events: MutableList<DrivingEvent>, moved: Boolean) {
        if (moved) {
            streakMs += creditMs
            if (streakMs >= (streakEmitted + 1) * config.smoothStreakStepMs) {
                streakEmitted++
                events += DrivingEvent.SmoothStreak(ts, streakMs)
            }
        }
        val elapsed = ts - tripStartMs
        if (elapsed >= (longDriveEmitted + 1) * config.longDriveStepMs) {
            longDriveEmitted++
            events += DrivingEvent.LongDrive(ts, elapsed)
        }
        if (moved) creditComfort(creditMs, ts, events)
    }

    private fun creditComfort(creditMs: Long, ts: Long, events: MutableList<DrivingEvent>) {
        val lastHard = lastHardEventMs
        if (lastHard == null || ts - lastHard >= config.comfortPenaltyMs) {
            comfortPendingMs += creditMs
            tripComfortMs += creditMs
            if (comfortPendingMs >= config.comfortTickMs) flushComfort(ts, events)
        }
    }

    private fun flushComfort(ts: Long, events: MutableList<DrivingEvent>) {
        if (comfortPendingMs <= 0) return
        events += DrivingEvent.ComfortAccrued(ts, comfortPendingMs)
        comfortPendingMs = 0
    }

    private fun checkParkConfirm(ts: Long, events: MutableList<DrivingEvent>) {
        val since = parkedSinceMs ?: return
        if (!inTrip || mode != DriveMode.PARKED || ts - since < config.parkConfirmMs) return
        flushComfort(ts, events)
        events += DrivingEvent.Parked(ts)
        inTrip = false
        passengerJoinedEmitted = false // 다음 트립(출발 전 정차 구간 포함)에서 다시 1회
    }
}

/** Flow 어댑터. 각 스냅샷마다 현재 모드와 그 시점의 이벤트를 내보낸다. */
fun DrivingEventDetector.detect(snapshots: Flow<VehicleSnapshot>): Flow<DetectionResult> =
    snapshots.map { s -> DetectionResult(events = process(s), mode = mode) }
