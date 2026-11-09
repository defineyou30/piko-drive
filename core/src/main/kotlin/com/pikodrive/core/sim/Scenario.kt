package com.pikodrive.core.sim

import com.pikodrive.core.model.Gear
import com.pikodrive.core.model.VehicleSnapshot

/** 시나리오 기준점. 속도는 다음 키프레임까지 선형 보간, 기어·UX 제한·배터리·충전구·승객은 계단식. */
data class Keyframe(
    val atMs: Long,
    val speedKmh: Float?,
    val gear: Gear,
    val uxRestricted: Boolean? = null,
    val batteryPct: Int? = null,
    val chargePortOpen: Boolean? = null,
    val passengerPresent: Boolean? = null,
)

/**
 * 재생 가능한 주행 각본.
 * @param gaps 이 구간(포함)의 샘플은 내보내지 않는다 (센서 끊김 재현).
 * @param spikes 해당 시각 샘플의 속도만 덮어쓴다 (1샘플 노이즈 재현).
 */
data class Scenario(
    val name: String,
    val keyframes: List<Keyframe>,
    val gaps: List<LongRange> = emptyList(),
    val spikes: Map<Long, Float> = emptyMap(),
) {
    init {
        require(keyframes.isNotEmpty()) { "keyframes empty" }
        require(keyframes.zipWithNext().all { (a, b) -> a.atMs <= b.atMs }) { "keyframes must be sorted" }
    }

    val durationMs: Long get() = keyframes.last().atMs

    fun isGap(tMs: Long): Boolean = gaps.any { tMs in it }

    /** 시나리오 시각 t의 스냅샷 (timestamp = baseMs + t). */
    fun snapshotAt(tMs: Long, baseMs: Long = 0): VehicleSnapshot {
        val i = keyframes.indexOfLast { it.atMs <= tMs }.coerceAtLeast(0)
        val k = keyframes[i]
        val next = keyframes.getOrNull(i + 1)
        val speed = spikes[tMs] ?: interpolate(k, next, tMs)
        return VehicleSnapshot(
            timestampMs = baseMs + tMs,
            speedKmh = speed,
            gear = k.gear,
            uxRestricted = k.uxRestricted,
            batteryPct = k.batteryPct,
            chargePortOpen = k.chargePortOpen,
            passengerPresent = k.passengerPresent,
        )
    }

    /** 고정 간격 샘플 목록 (gap 제외). 감지기 단위 테스트용. */
    fun samples(tickMs: Long = 100, baseMs: Long = 0): List<VehicleSnapshot> =
        (0..durationMs step tickMs).filterNot(::isGap).map { snapshotAt(it, baseMs) }

    private fun interpolate(k: Keyframe, next: Keyframe?, tMs: Long): Float? {
        val from = k.speedKmh ?: return null
        val to = next?.speedKmh ?: return from
        if (next.atMs == k.atMs) return to
        val f = (tMs - k.atMs).toFloat() / (next.atMs - k.atMs)
        return from + (to - from) * f.coerceIn(0f, 1f)
    }
}

fun scenario(name: String, block: ScenarioBuilder.() -> Unit): Scenario =
    ScenarioBuilder(name).apply(block).build()

/** 커서 기반 각본 작성기. 모든 시간 인자는 ms. */
class ScenarioBuilder internal constructor(private val name: String) {
    private val keyframes = mutableListOf(Keyframe(0, 0f, Gear.PARK))
    private val gaps = mutableListOf<LongRange>()
    private val spikes = mutableMapOf<Long, Float>()
    private var cursor = 0L
    private var speed: Float? = 0f
    private var gear = Gear.PARK
    private var ux: Boolean? = null
    private var battery: Int? = null
    private var chargePort: Boolean? = null
    private var passenger: Boolean? = null

    fun start(gear: Gear, speedKmh: Float? = 0f, uxRestricted: Boolean? = null) {
        this.gear = gear; speed = speedKmh; ux = uxRestricted
        mark()
    }

    fun gear(g: Gear) {
        gear = g
        mark()
    }

    fun uxRestricted(value: Boolean?) {
        ux = value
        mark()
    }

    /** 배터리 잔량(%) 계단 변경. null = 미수신. */
    fun battery(pct: Int?) {
        battery = pct
        mark()
    }

    fun chargePort(open: Boolean?) {
        chargePort = open
        mark()
    }

    /** 조수석 착좌. */
    fun passenger(present: Boolean?) {
        passenger = present
        mark()
    }

    fun hold(ms: Long) {
        cursor += ms
        mark()
    }

    fun rampTo(kmh: Float, ms: Long) {
        cursor += ms
        speed = kmh
        mark()
    }

    fun speedUnavailable() {
        speed = null
        mark()
    }

    /** ms 동안 샘플이 끊기고, 끊김 이후엔 [thenKmh] 로 다시 들어온다. */
    fun gap(ms: Long, thenKmh: Float) {
        gaps += (cursor + 1) until (cursor + ms)
        cursor += ms
        speed = thenKmh
        // 끊김 직전까지는 이전 속도 유지 → 끊김 끝에서 계단.
        keyframes += frame(cursor - 1, keyframes.last().speedKmh)
        mark()
    }

    /** 커서 + offsetMs 시각의 샘플 1개만 [kmh] 로 튀게 한다. */
    fun spike(offsetMs: Long, kmh: Float) {
        spikes[cursor + offsetMs] = kmh
    }

    private fun mark() {
        val k = frame(cursor, speed)
        if (keyframes.last().atMs == cursor) keyframes[keyframes.lastIndex] = k else keyframes += k
    }

    private fun frame(at: Long, kmh: Float?) = Keyframe(at, kmh, gear, ux, battery, chargePort, passenger)

    internal fun build() = Scenario(name, keyframes.toList(), gaps.toList(), spikes.toMap())
}
