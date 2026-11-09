package com.pikodrive.probe

import android.app.Activity
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import ai.pleos.playground.vehicle.listener.SeatBeltBuckledStateListener
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.stt.constant.ErrorType as SttErrorType
import ai.pleos.playground.stt.listener.OnInitializeListener as SttInitListener
import ai.pleos.playground.stt.listener.ResultListener as SttResultListener
import ai.pleos.playground.vehicle.listener.DoorLockStateListener
import ai.pleos.playground.vehicle.listener.WindowLockStateListener
import ai.pleos.playground.vehicle.listener.WindowPositionListener
import ai.pleos.playground.vehicle.listener.HvacTemperatureListener
import ai.pleos.playground.vehicle.listener.AmbientLightBrightnessListener
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.DoorValue

/**
 * S0 프로브. 각 데이터 소스에서 값이 들어오는지 PROBE 태그로 로그를 남긴다.
 * 결과는 `adb logcat -s PIKO_PROBE` 로 수집해 qa/S0-probe.md 에 정리한다.
 */
class ProbeActivity : Activity() {

    private lateinit var out: TextView
    private val t0 = SystemClock.elapsedRealtime()
    private var car: Car? = null
    private var vehicle: Vehicle? = null
    private var tts: TextToSpeech? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        out = TextView(this).apply { setTextColor(Color.WHITE); textSize = 18f; setPadding(32, 32, 32, 32) }
        setContentView(ScrollView(this).apply { setBackgroundColor(Color.BLACK); addView(out) })

        log("start", "sdk=${android.os.Build.VERSION.SDK_INT}")
        // 음성 인식만 시험: am start ... --es stt file|mic [--es sttmode ON_DEVICE|HYBRID|SERVER]
        intent.getStringExtra("stt")?.let { src ->
            probeStt(src, runCatching { SttMode.valueOf(intent.getStringExtra("sttmode") ?: "ON_DEVICE") }.getOrDefault(SttMode.ON_DEVICE))
            return
        }
        probeCarApi()
        probePleosVehicle()
        probePleosTts()
    }

    // ---- AAOS 표준 Car API: 속도·기어·UX 제한 ----
    private fun probeCarApi() {
        val c = runCatching { Car.createCar(this) }.onFailure { log("car", "createCar FAIL ${it}") }.getOrNull() ?: return
        car = c
        val pm = c.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
        val props = mapOf(
            "PERF_VEHICLE_SPEED" to VehiclePropertyIds.PERF_VEHICLE_SPEED,
            "GEAR_SELECTION" to VehiclePropertyIds.GEAR_SELECTION,
            "CURRENT_GEAR" to VehiclePropertyIds.CURRENT_GEAR,
            "VENDOR_GEAR_SELECTION" to 0x21400400,
        )
        props.forEach { (name, id) ->
            val cfg = runCatching { pm.getCarPropertyConfig(id) }.getOrNull()
            log("prop", "$name 0x${id.toString(16)} config=${if (cfg == null) "NONE" else "OK access=${cfg.access}"}")
            if (cfg == null) return@forEach
            runCatching { pm.getProperty<Any>(id, 0) }
                .onSuccess { log("prop", "$name initial=${it.value}") }
                .onFailure { log("prop", "$name get FAIL ${it.javaClass.simpleName}: ${it.message}") }
            val ok = runCatching {
                pm.registerCallback(object : CarPropertyManager.CarPropertyEventCallback {
                    override fun onChangeEvent(v: CarPropertyValue<*>) {
                        log("prop", "$name event=${v.value} status=${v.status}")
                        if (reverseTest && id == VehiclePropertyIds.GEAR_SELECTION && v.value == GEAR_REVERSE) {
                            stopAt = SystemClock.elapsedRealtime()
                            log("tts", "REVERSE detected → stop()")
                            tts?.stop()
                        }
                    }
                    override fun onErrorEvent(propId: Int, zone: Int) = log("prop", "$name error zone=$zone")
                }, id, CarPropertyManager.SENSOR_RATE_ONCHANGE)
            }
            log("prop", "$name subscribe=${ok.getOrNull() ?: ok.exceptionOrNull()}")
        }

        runCatching {
            val ux = c.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
            log("ux", "initial required=${ux.currentCarUxRestrictions.isRequiresDistractionOptimization} active=${ux.currentCarUxRestrictions.activeRestrictions}")
            ux.registerListener { r -> log("ux", "changed required=${r.isRequiresDistractionOptimization} active=${r.activeRestrictions}") }
        }.onFailure { log("ux", "FAIL $it") }
    }

    // ---- Pleos Vehicle SDK: 기어 ----
    private fun probePleosVehicle() {
        runCatching {
            val v = Vehicle(this).also { vehicle = it }
            v.initialize()
            log("pleos", "Vehicle initialize OK")
            v.getGear().getCurrentGearSelectionState(
                { g: VehicleGear? -> log("pleos", "gear current=$g") },
                { e: Exception -> log("pleos", "gear current FAIL $e") },
            )
            v.getGear().registerGearSelection(object : GearSelectionListener {
                override fun onSelected(gear: VehicleGear?) = log("pleos", "gear event=$gear")
                override fun onFailed(e: Exception) = log("pleos", "gear listener FAIL $e")
            })
            probeRear(v)
            probeCabin(v)
        }.onFailure { log("pleos", "Vehicle FAIL $it") }
    }

    // ---- 뒷좌석 착석·벨트·문 (아이 라이드 컨셉 검증) ----
    private fun probeRear(v: Vehicle) {
        listOf(SeatValue.ROW_2_LEFT, SeatValue.ROW_2_RIGHT).forEach { sv ->
            val a = SeatArea(sv)
            runCatching {
                v.getSeat().getCurrentSeatOccupancy(a, { log("rear", "$sv occupied current=$it") }, { log("rear", "$sv occupied current FAIL $it") })
                v.getSeat().registerSeatOccupancy(a, object : SeatOccupancyListener {
                    override fun onSeatOccupancyUpdated(occupied: Boolean?) = log("rear", "$sv occupied event=$occupied")
                    override fun onFailed(e: Exception) = log("rear", "$sv occupied listener FAIL $e")
                })
            }.onFailure { log("rear", "$sv occupancy FAIL $it") }
            runCatching {
                v.getSeat().getCurrentSeatBeltBuckledState(a, { log("rear", "$sv belt current=$it") }, { log("rear", "$sv belt current FAIL $it") })
                v.getSeat().registerSeatBeltBuckledState(a, object : SeatBeltBuckledStateListener {
                    override fun onSeatBeltBuckledStateUpdated(buckled: Boolean?) = log("rear", "$sv belt event=$buckled")
                    override fun onFailed(e: Exception) = log("rear", "$sv belt listener FAIL $e")
                })
            }.onFailure { log("rear", "$sv belt FAIL $it") }
        }
        listOf(DoorValue.ROW_2_LEFT, DoorValue.ROW_2_RIGHT).forEach { dv ->
            val a = DoorArea(dv)
            runCatching {
                v.getDoor().getCurrentDoorPosition(a, { log("rear", "door $dv pos current=$it") }, { log("rear", "door $dv current FAIL $it") })
                v.getDoor().registerDoorPosition(a, object : DoorPositionListener {
                    override fun onPositionUpdated(position: Int?) = log("rear", "door $dv pos event=$position")
                    override fun onFailed(e: Exception) = log("rear", "door $dv listener FAIL $e")
                })
            }.onFailure { log("rear", "door $dv FAIL $it") }
        }
    }

    // ---- 부모 체크 후보: 문 잠금·창문 잠금/위치·실내 온도·앰비언트 ----
    private fun probeCabin(v: Vehicle) {
        runCatching { v.getDoor().checkDoorLockCapabilities({ c, areas -> log("cabin", "doorlock caps $c areas=${areas.map { it.values.toList() }}") }, { log("cabin", "doorlock caps FAIL $it") }) }
        runCatching { v.getLight().checkAmbientLightBrightnessCapabilities({ c, m -> log("cabin", "ambient caps $c areas=${m.keys.map { it.values.toList() }}") }, { log("cabin", "ambient caps FAIL $it") }) }
        runCatching { v.getHVAC().checkHvacTemperatureCurrentCapabilities({ c, l -> log("cabin", "tempcur caps $c areas=${l.map { it.values.toList() }}") }, { log("cabin", "tempcur caps FAIL $it") }) }
        runCatching { v.getWindow().checkWindowLockCapabilities({ c, l -> log("cabin", "winlock caps $c areas=${l.map { it.values.toList() }}") }, { log("cabin", "winlock caps FAIL $it") }) }
        listOf(DoorArea(DoorValue.ROW_1_LEFT, DoorValue.ROW_1_RIGHT, DoorValue.ROW_2_LEFT, DoorValue.ROW_2_RIGHT)).forEach { a ->
            val dv = "ALL4"
            runCatching {
                v.getDoor().getDoorLockState(a, { log("cabin", "doorlock $dv current=$it") }, { log("cabin", "doorlock $dv current FAIL $it") })
                v.getDoor().registerDoorLockState(a, object : DoorLockStateListener {
                    override fun onDoorLockStateUpdated(locked: Boolean?) = log("cabin", "doorlock $dv event=$locked")
                    override fun onFailed(e: Exception) = log("cabin", "doorlock $dv listener FAIL $e")
                })
            }.onFailure { log("cabin", "doorlock $dv FAIL $it") }
        }
        listOf(WindowValue.ROW_2_LEFT, WindowValue.ROW_2_RIGHT).forEach { wv ->
            val a = WindowArea(wv)
            runCatching {
                v.getWindow().getCurrentWindowLockState(a, { log("cabin", "winlock $wv current=$it") }, { log("cabin", "winlock $wv current FAIL $it") })
                v.getWindow().registerWindowLockState(a, object : WindowLockStateListener {
                    override fun onWindowLockStateUpdated(locked: Boolean?) = log("cabin", "winlock $wv event=$locked")
                    override fun onFailed(e: Exception) = log("cabin", "winlock $wv listener FAIL $e")
                })
            }.onFailure { log("cabin", "winlock $wv FAIL $it") }
            runCatching {
                v.getWindow().getCurrentWindowPosition(a, { log("cabin", "winpos $wv current=$it") }, { log("cabin", "winpos $wv current FAIL $it") })
                v.getWindow().registerWindowPosition(a, object : WindowPositionListener {
                    override fun onWindowPositionUpdated(pos: Int?) = log("cabin", "winpos $wv event=$pos")
                    override fun onFailed(e: Exception) = log("cabin", "winpos $wv listener FAIL $e")
                })
            }.onFailure { log("cabin", "winpos $wv FAIL $it") }
        }
        listOf(SeatValue.ROW_1_LEFT, SeatValue.ROW_2_LEFT).forEach { sv ->
            val a = SeatArea(sv)
            runCatching {
                v.getHVAC().getHvacTemperatureCurrent(a, { log("cabin", "temp $sv current=$it") }, { log("cabin", "temp $sv current FAIL $it") })
                v.getHVAC().registerHvacTemperatureCurrent(a, object : HvacTemperatureListener {
                    override fun onHvacTemperatureUpdated(t: Float?) = log("cabin", "temp $sv event=$t")
                    override fun onFailed(e: Exception) = log("cabin", "temp $sv listener FAIL $e")
                })
            }.onFailure { log("cabin", "temp $sv FAIL $it") }
            if (sv == SeatValue.ROW_1_LEFT) runCatching {
                val a = SeatArea(SeatValue.ROW_1_LEFT, SeatValue.ROW_1_RIGHT, SeatValue.ROW_2_LEFT, SeatValue.ROW_2_CENTER, SeatValue.ROW_2_RIGHT)
                v.getLight().getAmbientLightBrightness(a, { log("cabin", "ambient $sv current=$it") }, { log("cabin", "ambient $sv current FAIL $it") })
                v.getLight().registerAmbientLightBrightnessListener(a, object : AmbientLightBrightnessListener {
                    override fun onBrightnessUpdated(b: Int?) = log("cabin", "ambient $sv event=$b")
                    override fun onFailed(e: Exception) = log("cabin", "ambient $sv listener FAIL $e")
                })
            }.onFailure { log("cabin", "ambient $sv FAIL $it") }
        }
    }

    // ---- Pleos SpeechToText SDK (부모 음성 입력 후보) ----
    // file: 앱 외부 파일 폴더의 stt.pcm(16kHz·16bit·mono)을 sendAudio로 흘려 넣는다 (에뮬레이터엔 실제 마이크가 없음)
    // mic: request()로 차량 마이크 인식
    private var stt: SpeechToText? = null
    private fun probeStt(source: String, mode: SttMode) {
        runCatching {
            val s = SpeechToText(this, mode).also { stt = it }
            s.addListener(object : SttResultListener {
                override fun onUpdated(text: String, isFinal: Boolean) = log("stt", "[$mode] ${if (isFinal) "FINAL" else "partial"}: $text")
                override fun onUpdatedEpdData(on: Long, off: Long) = log("stt", "[$mode] epd $on..$off")
                override fun onStartedRecognition() = log("stt", "[$mode] started")
                override fun onEndedRecognition() = log("stt", "[$mode] ended")
                override fun onError(e: SttErrorType) = log("stt", "[$mode] ERROR $e")
                override fun onReady() = log("stt", "[$mode] ready")
                override fun onUpdatedRms(rms: Double) = Unit
            })
            s.initialize(object : SttInitListener {
                override fun onSuccess() {
                    log("stt", "[$mode] initialize OK")
                    runCatching { s.setLocale(java.util.Locale.KOREA) }
                    s.request()
                    log("stt", "[$mode] request() called, source=$source")
                    if (source == "file") Thread {
                        val f = java.io.File(filesDir, "stt.pcm").takeIf { it.exists() } ?: java.io.File(getExternalFilesDir(null), "stt.pcm")
                        if (!f.exists()) { log("stt", "no file ${f.path}"); return@Thread }
                        val bytes = f.readBytes()
                        Thread.sleep(500)
                        // 실시간 속도로 100ms(3200바이트)씩
                        var i = 0
                        while (i < bytes.size) {
                            val end = minOf(i + 3200, bytes.size)
                            s.sendAudio(bytes.copyOfRange(i, end))
                            i = end
                            Thread.sleep(100)
                        }
                        s.completeAudioSend()
                        log("stt", "[$mode] sent ${bytes.size} bytes, completeAudioSend")
                    }.start()
                }
                override fun onFailure(e: SttErrorType) = log("stt", "[$mode] initialize FAIL $e")
            })
        }.onFailure { log("stt", "[$mode] FAIL $it") }
    }

    // ---- Pleos TTS SDK ----
    // 리스너는 initialize 성공 후에 붙인다 (먼저 붙이면 SDK 내부 lateinit 예외)
    private fun probePleosTts(mode: Mode = Mode.ON_DEVICE) {
        runCatching {
            val t = TextToSpeech(this, mode).also { tts = it }
            t.initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    log("tts", "[$mode] initialize OK")
                    var startAt = 0L
                    t.addEventListener(object : EventListener {
                        override fun onStart() = log("tts", "onStart (speak→start ${SystemClock.elapsedRealtime() - startAt}ms)")
                        override fun onReady() = log("tts", "onReady")
                        override fun onDone() = log("tts", "onDone")
                        override fun onError(error: ErrorType) = log("tts", "onError $error")
                        override fun onStop() = log("tts", "onStop (stop→stopped ${SystemClock.elapsedRealtime() - stopAt}ms)")
                        override fun onUpdatedRms(rms: Double) = Unit
                    })
                    startAt = SystemClock.elapsedRealtime()
                    if (reverseTest) {
                        t.speak("후진 중단 테스트. 이 문장은 길게 이어집니다. 기어를 R로 바꾸면 바로 멈춰야 해요. 하나 둘 셋 넷 다섯 여섯.", false)
                        log("tts", "speak called (reverse test, waiting for R)")
                    } else {
                        t.speak("안녕, 난 피코야. 오늘도 같이 가자.", false)
                        log("tts", "speak called")
                        out.postDelayed({ stopAt = SystemClock.elapsedRealtime(); log("tts", "stop() at +1200ms"); t.stop() }, 1200)
                    }
                }
                override fun onFailure(error: ErrorType) {
                    log("tts", "[$mode] initialize FAIL $error")
                    if (mode == Mode.ON_DEVICE) probePleosTts(Mode.HYBRID)
                }
            })
        }.onFailure { log("tts", "[$mode] TTS FAIL $it") }
    }

    private var stopAt = 0L
    private val reverseTest by lazy { intent.getBooleanExtra("reverseTest", false) }

    private fun log(area: String, msg: String) {
        val line = "[+${SystemClock.elapsedRealtime() - t0}ms] $area: $msg"
        Log.i(TAG, line)
        runOnUiThread { out.append(line + "\n") }
    }

    override fun onDestroy() {
        runCatching { tts?.release() }
        runCatching { vehicle?.release() }
        runCatching { car?.disconnect() }
        super.onDestroy()
    }

    private companion object {
        const val TAG = "PIKO_PROBE"
        const val GEAR_REVERSE = 2
    }
}
