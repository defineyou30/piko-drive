package com.pikodrive.vehicle

import android.car.Car
import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.listener.EvBatteryStateOfChargeListener
import ai.pleos.playground.vehicle.listener.EvChargePortOpenListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.listener.DoorLockStateListener
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import ai.pleos.playground.vehicle.listener.HvacTemperatureListener
import ai.pleos.playground.vehicle.listener.SeatBeltBuckledStateListener
import ai.pleos.playground.vehicle.listener.WindowPositionListener
import com.pikodrive.core.model.CabinSnapshot
import com.pikodrive.core.model.RearSeat
import com.pikodrive.core.model.Gear
import com.pikodrive.core.model.VehicleSnapshot
import com.pikodrive.core.vehicle.Clock
import com.pikodrive.core.vehicle.VehicleCapabilities
import com.pikodrive.core.vehicle.VehicleGateway
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import ai.pleos.playground.vehicle.constant.control.VehicleGear as PleosGear

/**
 * 실제 차량(Pleos Connect) 데이터 소스. S0 프로브 결과(qa/S0-probe.md) 기준:
 * - 속도: AAOS PERF_VEHICLE_SPEED (m/s → km/h 변환은 여기서만)
 * - 기어: Pleos Vehicle SDK 우선, 권한·SDK 실패 시 AAOS GEAR_SELECTION 폴백
 * - UX 제한: CarUxRestrictionsManager
 * - 하트비트: 값 변화가 없어도 [HEARTBEAT_MS]마다 마지막 스냅샷 재방출 (VehicleGateway 계약)
 */
class PleosVehicleGateway(
    private val context: Context,
    private val clock: Clock = Clock.Monotonic,
) : VehicleGateway {

    private val car: Car? = runCatching { Car.createCar(context) }
        .onFailure { Log.w(TAG, "Car.createCar failed", it) }.getOrNull()
    private val props = car?.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager

    private val speedGranted =
        context.checkSelfPermission(Car.PERMISSION_SPEED) == PackageManager.PERMISSION_GRANTED

    override val capabilities = VehicleCapabilities(
        speed = speedGranted && props.hasConfig(VehiclePropertyIds.PERF_VEHICLE_SPEED),
        gear = props.hasConfig(VehiclePropertyIds.GEAR_SELECTION),
        uxRestriction = car != null,
        // Pleos SDK 신호: 등록에 실패하면 값이 null로 남고 코어가 "모름"으로 처리한다
        battery = isAvailable(context),
        passenger = isAvailable(context),
    )

    /** 현재 기어 소스 (증거 로그용) */
    @Volatile var gearSource: String = "none"
        private set

    /**
     * 채널(속도·기어·UX)별 등록 실패는 서로 격리한다: 실패한 채널은 null/UNKNOWN 으로 남고
     * 나머지 채널과 하트비트는 계속 동작한다 (코어의 미지원 폴백이 처리). 플로우 자체는 닫히지 않는다.
     */
    override val snapshots: Flow<VehicleSnapshot> = callbackFlow {
        // 콜백은 여러 바인더 스레드에서 온다 → 갱신과 스냅샷 생성을 한 락으로 묶어 혼합 스냅샷 방지
        val latest = Latest()
        fun update(change: Latest.() -> Unit) {
            val snap = synchronized(latest) {
                latest.change()
                VehicleSnapshot(
                timestampMs = clock.nowMs(),
                speedKmh = latest.speed,
                gear = latest.gear,
                uxRestricted = latest.ux,
                batteryPct = latest.battery,
                chargePortOpen = latest.chargePort,
                passengerPresent = latest.passenger,
                cabin = latest.cabin(CabinOverrides.belts.value),
            )
            }
            trySend(snap)
        }
        fun emitNow() = update {}
        fun guard(channel: String, block: () -> Unit) = runCatching(block).onFailure {
            Log.w(TAG, "$channel channel unavailable", it)
            Log.i("PIKO_EVIDENCE", "channel $channel FAILED ${it.javaClass.simpleName}")
        }

        val pm = props
        val speedCb = object : CarPropertyManager.CarPropertyEventCallback {
            override fun onChangeEvent(v: CarPropertyValue<*>) = update { speed = (v.value as? Float)?.let { it * MS_TO_KMH } }
            override fun onErrorEvent(propId: Int, zone: Int) = update { speed = null }
        }
        if (capabilities.speed && pm != null) guard("speed") {
            pm.registerCallback(speedCb, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_ONCHANGE)
        }

        // 기어: Pleos SDK → 실패 시 AAOS
        val aaosGearCb = object : CarPropertyManager.CarPropertyEventCallback {
            override fun onChangeEvent(v: CarPropertyValue<*>) = update { gear = (v.value as? Int).toGear() }
            override fun onErrorEvent(propId: Int, zone: Int) = update { gear = Gear.UNKNOWN }
        }
        val aaosGearRegistered = AtomicBoolean(false)
        fun fallbackToAaosGear() {
            // Pleos 실패 콜백이 여러 번 와도 AAOS 등록은 한 번만
            if (pm == null || !capabilities.gear || !aaosGearRegistered.compareAndSet(false, true)) return
            guard("gear-aaos") {
                pm.registerCallback(aaosGearCb, VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE)
                gearSource = "aaos"
                Log.i("PIKO_EVIDENCE", "gear source = AAOS GEAR_SELECTION (fallback)")
            }
        }
        var pleos: Vehicle? = null
        val pleosGearListener = object : GearSelectionListener {
            override fun onSelected(g: PleosGear?) = update { gear = g.toGear() }
            override fun onFailed(e: Exception) {
                Log.w(TAG, "Pleos gear failed → AAOS fallback", e)
                fallbackToAaosGear()
            }
        }
        runCatching {
            pleos = Vehicle(context).also {
                it.initialize()
                it.getGear().registerGearSelection(pleosGearListener)
            }
            gearSource = "pleos-sdk"
            Log.i("PIKO_EVIDENCE", "gear source = Pleos Vehicle SDK")
        }.onFailure {
            Log.w(TAG, "Pleos Vehicle SDK unavailable → AAOS GEAR_SELECTION", it)
            fallbackToAaosGear()
        }

        // 배터리·충전구·동승석 착석: Pleos Vehicle SDK (각각 독립 실패)
        val batteryListener = object : EvBatteryStateOfChargeListener {
            override fun onEvBatteryStateOfChargeUpdated(pct: Int?) = update { battery = pct }
            override fun onFailed(e: Exception) = update { battery = null }
        }
        val chargePortListener = object : EvChargePortOpenListener {
            override fun onEvChargePortOpenUpdated(open: Boolean?) = update { chargePort = open }
            override fun onFailed(e: Exception) = update { chargePort = null }
        }
        val passengerSeat = SeatArea(SeatValue.ROW_1_RIGHT)
        // 동승석: AAOS SEAT_OCCUPANCY 원값(0 모름·1 빈 좌석·2 착석)을 먼저 시도한다 (권한이 없으면 SecurityException)
        val seatCb = object : CarPropertyManager.CarPropertyEventCallback {
            override fun onChangeEvent(v: CarPropertyValue<*>) {
                if (v.areaId != SEAT_ROW1_RIGHT) return
                update { passenger = when (v.value as? Int) { 2 -> true; 1 -> false; else -> null } }
            }
            override fun onErrorEvent(propId: Int, zone: Int) = update { passenger = null }
        }
        var aaosSeat = false
        if (pm != null && capabilities.passenger) runCatching {
            aaosSeat = pm.registerCallback(seatCb, VehiclePropertyIds.SEAT_OCCUPANCY, CarPropertyManager.SENSOR_RATE_ONCHANGE)
            if (aaosSeat) Log.i("PIKO_EVIDENCE", "passenger source = AAOS SEAT_OCCUPANCY")
        }
        val passengerListener = object : SeatOccupancyListener {
            // Pleos Vehicle SDK 2.3.2: 차량 신호 착석(2)을 false, 빈 좌석(1)을 true로 넘긴다 (에뮬레이터 v2.1.0 실측,
            // qa/S7-report.md). AAOS 원값을 직접 못 읽을 때만 이 경로를 쓰고 값을 뒤집는다. 실차에서 재확인 필요.
            override fun onSeatOccupancyUpdated(occupied: Boolean?) = update { passenger = occupied?.not() }
            override fun onFailed(e: Exception) = update { passenger = null }
        }
        pleos?.let { v ->
            // 리스너는 "바뀔 때"만 알려주므로 등록 직후 현재값을 직접 읽어 첫 샘플을 채운다
            // (안 그러면 첫 변화가 '처음 본 값'으로 취급돼 동승자 탑승 같은 전이를 놓친다)
            guard("battery") {
                v.getEvBattery().registerEvBatteryStateOfCharge(batteryListener)
                v.getEvBattery().getEvBatteryStateOfCharge({ pct -> update { battery = pct } }, {})
            }
            guard("charge-port") {
                v.getEvBattery().registerEvChargePortOpen(chargePortListener)
                v.getEvBattery().isEvChargePortOpen({ open -> update { chargePort = open } }, {})
            }
            if (!aaosSeat) guard("passenger") {
                v.getSeat().registerSeatOccupancy(passengerSeat, passengerListener)
                v.getSeat().getCurrentSeatOccupancy(passengerSeat, { occupied -> update { passenger = occupied?.not() } }, {})
                Log.i("PIKO_EVIDENCE", "passenger source = Pleos Vehicle SDK (inverted mapping)")
            }
        }

        // v3 실내 신호: 뒷좌석 착석·벨트·뒷문·창문, 운전석 착석, 4도어 잠금, 실내 온도 (qa/v3/probe-cabin.log)
        val cabinCleanups = mutableListOf<() -> Unit>()
        pleos?.let { v -> registerCabin(v, ::update, { ch, b -> guard(ch, b) }, cabinCleanups) }
        // 데모 패널의 벨트 대체 입력(에뮬레이터에 벨트 주입 경로가 없음)이 바뀌면 바로 반영
        val overrideJob = launch { CabinOverrides.belts.collect { emitNow() } }

        var uxManager: CarUxRestrictionsManager? = null
        guard("ux") {
            val m = car?.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager ?: return@guard
            update { ux = m.currentCarUxRestrictions?.isRequiresDistractionOptimization }
            m.registerListener { r -> update { ux = r.isRequiresDistractionOptimization } }
            uxManager = m
        }

        emitNow()
        val heartbeat = launch {
            while (true) {
                delay(HEARTBEAT_MS)
                emitNow()
            }
        }

        awaitClose {
            heartbeat.cancel()
            overrideJob.cancel()
            cabinCleanups.forEach { runCatching(it) }
            runCatching { pm?.unregisterCallback(speedCb) }
            if (aaosGearRegistered.get()) runCatching { pm?.unregisterCallback(aaosGearCb) }
            runCatching { pleos?.getGear()?.unregisterGearSelection(pleosGearListener) }
            runCatching { pleos?.getEvBattery()?.unregisterEvBatteryStateOfCharge(batteryListener) }
            runCatching { pleos?.getEvBattery()?.unregisterEvChargePortOpen(chargePortListener) }
            if (aaosSeat) runCatching { pm?.unregisterCallback(seatCb) }
            else runCatching { pleos?.getSeat()?.unregisterSeatOccupancy(passengerSeat, passengerListener) }
            runCatching { pleos?.release() }
            runCatching { uxManager?.unregisterListener() }
            // 이 게이트웨이는 세션당 한 번 수집된다 → 수집이 끝나면 차량 연결도 닫는다
            runCatching { car?.disconnect() }
        }
    }

    companion object {
        private const val TAG = "PikoGateway"
        const val HEARTBEAT_MS = 1_000L
        /** VehicleAreaSeat.ROW_1_RIGHT */
        private const val SEAT_ROW1_RIGHT = 0x0004
        private const val MS_TO_KMH = 3.6f

        /** 차량 HAL이 있는 Android Automotive 기기인지 */
        fun isAvailable(context: Context): Boolean =
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)

        val requiredRuntimePermissions = arrayOf(Car.PERMISSION_SPEED)
    }
}

/** [PleosVehicleGateway.snapshots] 안에서 synchronized(latest)로만 읽고 쓴다. */
private class Latest {
    var speed: Float? = null
    var gear: Gear = Gear.UNKNOWN
    var ux: Boolean? = null
    var battery: Int? = null
    var chargePort: Boolean? = null
    var passenger: Boolean? = null
    val rearOccupied = mutableMapOf<RearSeat, Boolean?>()
    val rearBelted = mutableMapOf<RearSeat, Boolean?>()
    val rearDoorOpen = mutableMapOf<RearSeat, Boolean?>()
    val rearWindow = mutableMapOf<RearSeat, Int?>()
    var driverPresent: Boolean? = null
    var doorsLocked: Boolean? = null
    var cabinTemp: Float? = null

    /** 벨트는 데모 대체 입력이 있으면 그 값을 쓴다 (화면에 "시뮬레이션 입력" 표기) */
    fun cabin(beltOverrides: Map<RearSeat, Boolean>) = CabinSnapshot(
        rearOccupied = rearOccupied.toMap(),
        rearBelted = rearBelted.toMap() + beltOverrides,
        rearDoorOpen = rearDoorOpen.toMap(),
        rearWindowOpenTenths = rearWindow.toMap(),
        driverPresent = driverPresent,
        doorsLocked = doorsLocked,
        cabinTempC = cabinTemp,
    )
}

private val RearSeat.seatArea get() = SeatArea(if (this == RearSeat.LEFT) SeatValue.ROW_2_LEFT else SeatValue.ROW_2_RIGHT)
private val RearSeat.doorArea get() = DoorArea(if (this == RearSeat.LEFT) DoorValue.ROW_2_LEFT else DoorValue.ROW_2_RIGHT)
private val RearSeat.windowArea get() = WindowArea(if (this == RearSeat.LEFT) WindowValue.ROW_2_LEFT else WindowValue.ROW_2_RIGHT)

/**
 * 실내 신호 등록. 채널마다 독립 실패(guard), 리스너 등록 직후 현재값을 직접 읽어 첫 샘플을 채운다.
 * - 착석: Pleos SDK 2.3.2는 조수석과 같이 뒷좌석·운전석도 값이 반대(착석=false) → 뒤집는다 (에뮬레이터 v2.1.0 실측)
 * - 문 잠금: 문별 영역은 미지원, 4도어 묶음 영역만 지원 (checkDoorLockCapabilities)
 * - 창문 잠금은 읽기는 되지만 콘솔 주입이 SDK에 반영되지 않아(항상 false) 오표시 위험 → 쓰지 않는다
 * - 실내 온도: HVAC 현재 온도 ROW_1_LEFT (뒷좌석 영역은 단독 미지원)
 */
private fun registerCabin(
    v: Vehicle,
    update: (Latest.() -> Unit) -> Unit,
    guard: (String, () -> Unit) -> Unit,
    cleanups: MutableList<() -> Unit>,
) {
    val seat = v.getSeat()
    for (rs in RearSeat.entries) {
        guard("rear-seat-$rs") {
            val l = object : SeatOccupancyListener {
                override fun onSeatOccupancyUpdated(occupied: Boolean?) = update { rearOccupied[rs] = occupied?.not() }
                override fun onFailed(e: Exception) = update { rearOccupied[rs] = null }
            }
            seat.registerSeatOccupancy(rs.seatArea, l)
            seat.getCurrentSeatOccupancy(rs.seatArea, { o -> update { rearOccupied[rs] = o?.not() } }, {})
            cleanups += { seat.unregisterSeatOccupancy(rs.seatArea, l) }
        }
        guard("rear-belt-$rs") {
            val l = object : SeatBeltBuckledStateListener {
                override fun onSeatBeltBuckledStateUpdated(buckled: Boolean?) = update { rearBelted[rs] = buckled }
                override fun onFailed(e: Exception) = update { rearBelted[rs] = null }
            }
            seat.registerSeatBeltBuckledState(rs.seatArea, l)
            seat.getCurrentSeatBeltBuckledState(rs.seatArea, { b -> update { rearBelted[rs] = b } }, {})
            cleanups += { seat.unregisterSeatBeltBuckledState(rs.seatArea, l) }
        }
        guard("rear-door-$rs") {
            val l = object : DoorPositionListener {
                override fun onPositionUpdated(position: Int?) = update { rearDoorOpen[rs] = position?.let { it > 0 } }
                override fun onFailed(e: Exception) = update { rearDoorOpen[rs] = null }
            }
            v.getDoor().registerDoorPosition(rs.doorArea, l)
            v.getDoor().getCurrentDoorPosition(rs.doorArea, { p -> update { rearDoorOpen[rs] = p?.let { it > 0 } } }, {})
            cleanups += { v.getDoor().unregisterDoorPosition(rs.doorArea, l) }
        }
        guard("rear-window-$rs") {
            val l = object : WindowPositionListener {
                override fun onWindowPositionUpdated(pos: Int?) = update { rearWindow[rs] = pos }
                override fun onFailed(e: Exception) = update { rearWindow[rs] = null }
            }
            v.getWindow().registerWindowPosition(rs.windowArea, l)
            v.getWindow().getCurrentWindowPosition(rs.windowArea, { p -> update { rearWindow[rs] = p } }, {})
            cleanups += { v.getWindow().unregisterWindowPosition(rs.windowArea, l) }
        }
    }
    val driverSeat = SeatArea(SeatValue.ROW_1_LEFT)
    guard("driver-seat") {
        val l = object : SeatOccupancyListener {
            override fun onSeatOccupancyUpdated(occupied: Boolean?) = update { driverPresent = occupied?.not() }
            override fun onFailed(e: Exception) = update { driverPresent = null }
        }
        seat.registerSeatOccupancy(driverSeat, l)
        seat.getCurrentSeatOccupancy(driverSeat, { o -> update { driverPresent = o?.not() } }, {})
        cleanups += { seat.unregisterSeatOccupancy(driverSeat, l) }
    }
    val allDoors = DoorArea(DoorValue.ROW_1_LEFT, DoorValue.ROW_1_RIGHT, DoorValue.ROW_2_LEFT, DoorValue.ROW_2_RIGHT)
    guard("door-lock") {
        val l = object : DoorLockStateListener {
            override fun onDoorLockStateUpdated(locked: Boolean?) = update { doorsLocked = locked }
            override fun onFailed(e: Exception) = update { doorsLocked = null }
        }
        v.getDoor().registerDoorLockState(allDoors, l)
        v.getDoor().getDoorLockState(allDoors, { b -> update { doorsLocked = b } }, {})
        cleanups += { v.getDoor().unregisterDoorLockState(allDoors, l) }
    }
    val tempArea = SeatArea(SeatValue.ROW_1_LEFT)
    guard("cabin-temp") {
        val l = object : HvacTemperatureListener {
            override fun onHvacTemperatureUpdated(t: Float?) = update { cabinTemp = t }
            override fun onFailed(e: Exception) = update { cabinTemp = null }
        }
        v.getHVAC().registerHvacTemperatureCurrent(tempArea, l)
        v.getHVAC().getHvacTemperatureCurrent(tempArea, { t -> update { cabinTemp = t } }, {})
        cleanups += { v.getHVAC().unregisterHvacTemperatureCurrent(tempArea, l) }
    }
}

private fun CarPropertyManager?.hasConfig(id: Int): Boolean =
    this != null && runCatching { getCarPropertyConfig(id) }.getOrNull() != null

/** AAOS VehicleGear 비트값 → 모델. 수동 1~9단은 주행으로 본다. */
internal fun Int?.toGear(): Gear = when (this) {
    null -> Gear.UNKNOWN
    VehicleGear.GEAR_PARK -> Gear.PARK
    VehicleGear.GEAR_REVERSE -> Gear.REVERSE
    VehicleGear.GEAR_NEUTRAL -> Gear.NEUTRAL
    VehicleGear.GEAR_UNKNOWN -> Gear.UNKNOWN
    else -> if (this >= VehicleGear.GEAR_DRIVE) Gear.DRIVE else Gear.UNKNOWN
}

internal fun PleosGear?.toGear(): Gear = when (this) {
    PleosGear.GEAR_PARK -> Gear.PARK
    PleosGear.GEAR_REVERSE -> Gear.REVERSE
    PleosGear.GEAR_NEUTRAL -> Gear.NEUTRAL
    null, PleosGear.GEAR_UNKNOWN -> Gear.UNKNOWN
    else -> Gear.DRIVE
}
