package com.pikodrive.core.driving

import com.pikodrive.core.model.Gear
import com.pikodrive.core.model.VehicleSnapshot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** S7 신호 확장: 배터리·충전구·조수석. */
class VehicleSignalDetectorTest {

    private var t = 0L

    private fun snap(
        gear: Gear = Gear.DRIVE,
        speed: Float = if (gear == Gear.PARK) 0f else 30f,
        battery: Int? = null,
        port: Boolean? = null,
        passenger: Boolean? = null,
    ): VehicleSnapshot {
        t += 1_000
        return VehicleSnapshot(t, speed, gear, null, battery, port, passenger)
    }

    private inline fun <reified T : DrivingEvent> List<DrivingEvent>.ofType() = filterIsInstance<T>()

    @Test
    fun `battery low fires once on crossing and re-arms only at 25 or above`() {
        val d = DrivingEventDetector()
        val pcts = listOf(40, 30, 21, 20, 19, 22, 24, 18, 23, 25, 22, 20, 15)
        val events = pcts.flatMap { d.process(snap(battery = it)) }
        val lows = events.ofType<DrivingEvent.BatteryLow>()
        // 20 도달 1회, 24까지 올라도 재무장 안 됨(18에서 미발생), 25 회복 후 20에서 다시 1회
        assertEquals(listOf(20, 20), lows.map { it.pct })
        assertEquals(listOf(25), events.ofType<DrivingEvent.BatteryRecovered>().map { it.pct })
    }

    @Test
    fun `battery staying low does not repeat and null samples keep state`() {
        val d = DrivingEventDetector()
        val events = (listOf(18, null, 17, null, 16) + List(20) { 15 }).flatMap { d.process(snap(battery = it)) }
        assertEquals(1, events.ofType<DrivingEvent.BatteryLow>().size)
        assertTrue(events.ofType<DrivingEvent.BatteryRecovered>().isEmpty())
    }

    @Test
    fun `charging started only when parked`() {
        val d = DrivingEventDetector()
        // 주행 중 열림 → 없음
        val driving = listOf(snap(port = false), snap(port = true), snap(port = false)).flatMap(d::process)
        assertTrue(driving.ofType<DrivingEvent.ChargingStarted>().isEmpty())
        // 정차 중 false → true → 1회, 열린 채 유지하면 반복 없음
        val parked = listOf(
            snap(Gear.PARK, port = false), snap(Gear.PARK, port = true), snap(Gear.PARK, port = true),
            snap(Gear.PARK, port = null), snap(Gear.PARK, port = true),
        ).flatMap(d::process)
        assertEquals(1, parked.ofType<DrivingEvent.ChargingStarted>().size)
        // 미수신(null) → true 도 정차 중이면 시작으로 본다
        val fresh = DrivingEventDetector()
        val fromNull = listOf(snap(Gear.PARK), snap(Gear.PARK, port = true)).flatMap(fresh::process)
        assertEquals(1, fromNull.ofType<DrivingEvent.ChargingStarted>().size)
    }

    @Test
    fun `passenger first observed true does not fire, false to true does`() {
        val d = DrivingEventDetector()
        val first = listOf(snap(Gear.PARK, passenger = true), snap(Gear.PARK, passenger = true)).flatMap(d::process)
        assertTrue(first.ofType<DrivingEvent.PassengerJoined>().isEmpty(), "첫 관측 true는 무시")

        val d2 = DrivingEventDetector()
        val joined = listOf(
            snap(Gear.PARK, passenger = null), snap(Gear.PARK, passenger = false),
            snap(Gear.PARK, passenger = null), snap(Gear.PARK, passenger = true),
        ).flatMap(d2::process)
        assertEquals(1, joined.ofType<DrivingEvent.PassengerJoined>().size, "null은 모름 → 마지막 false 기준")
    }

    @Test
    fun `passenger joined fires at most once per trip and again after the trip ends`() {
        val d = DrivingEventDetector()
        val trip1 = buildList {
            add(snap(Gear.PARK, passenger = false))
            add(snap(Gear.PARK, passenger = true))      // 출발 전 탑승 → 1회
            repeat(3) { add(snap(passenger = true)) }   // 출발
            add(snap(passenger = false))
            add(snap(passenger = true))                 // 같은 트립 재탑승 → 무시
            repeat(4) { add(snap(Gear.PARK, passenger = true)) } // 정차 확정(3초)
        }.flatMap(d::process)
        assertEquals(1, trip1.ofType<DrivingEvent.PassengerJoined>().size)
        assertEquals(1, trip1.ofType<DrivingEvent.Parked>().size)

        val trip2 = listOf(
            snap(Gear.PARK, passenger = false), snap(Gear.PARK, passenger = true), snap(passenger = true),
        ).flatMap(d::process)
        assertEquals(1, trip2.ofType<DrivingEvent.PassengerJoined>().size, "트립 종료 후 다시 1회")
    }
}
