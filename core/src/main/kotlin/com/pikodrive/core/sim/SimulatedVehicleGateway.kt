package com.pikodrive.core.sim

import com.pikodrive.core.model.Gear
import com.pikodrive.core.model.VehicleSnapshot
import com.pikodrive.core.vehicle.Clock
import com.pikodrive.core.vehicle.VehicleCapabilities
import com.pikodrive.core.vehicle.VehicleGateway
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * [Scenario]를 [tickMs] 간격(기본 10Hz)으로 재생. 시간 진행은 `delay`라서 runTest 가상 시간에서 그대로 동작한다.
 * capabilities에서 꺼진 값은 실제 미지원 차량처럼 null/UNKNOWN 으로 내보낸다.
 * 데모 화면은 반드시 `SIM` 배지를 표시할 것 (PLAN 3장 Must 6).
 */
class SimulatedVehicleGateway(
    private val scenario: Scenario,
    private val clock: Clock = Clock.Monotonic,
    override val capabilities: VehicleCapabilities = VehicleCapabilities.All,
    private val tickMs: Long = 100,
    private val loop: Boolean = false,
) : VehicleGateway {
    val isSimulated: Boolean get() = true

    override val snapshots: Flow<VehicleSnapshot> = flow {
        do {
            val baseMs = clock.nowMs()
            var t = 0L
            while (t <= scenario.durationMs) {
                if (!scenario.isGap(t)) emit(mask(scenario.snapshotAt(t, baseMs)))
                delay(tickMs)
                t += tickMs
            }
        } while (loop)
    }

    private fun mask(s: VehicleSnapshot) = s.copy(
        speedKmh = s.speedKmh.takeIf { capabilities.speed },
        gear = if (capabilities.gear) s.gear else Gear.UNKNOWN,
        uxRestricted = s.uxRestricted.takeIf { capabilities.uxRestriction },
        batteryPct = s.batteryPct.takeIf { capabilities.battery },
        chargePortOpen = s.chargePortOpen.takeIf { capabilities.battery },
        passengerPresent = s.passengerPresent.takeIf { capabilities.passenger },
    )
}
