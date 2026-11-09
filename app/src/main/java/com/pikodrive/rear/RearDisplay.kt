package com.pikodrive.rear

import android.app.ActivityOptions
import android.car.Car
import android.car.CarOccupantZoneManager
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.util.Log
import android.view.Display
import com.pikodrive.EvidenceLog

/**
 * 뒷좌석 화면 찾기 + 피코(아이용) 띄우기.
 * 1) 실차: CarOccupantZoneManager의 뒷좌석 탑승자 구역 메인 디스플레이
 * 2) 없으면: 기본 화면이 아닌 보조 디스플레이 (에뮬레이터 `adb emu multidisplay add`로 시연)
 * 3) 그것도 없으면 null → 뒷좌석 피코는 음성 중심 (앞 화면은 그대로 에이전트)
 * 실제 Pleos 차량의 뒷좌석 화면 유무는 확인 전 (docs/CONCEPT_v4_agent.md).
 */
object RearDisplay {
    fun find(context: Context): Display? {
        val dm = context.getSystemService(DisplayManager::class.java)
        runCatching {
            val car = Car.createCar(context) ?: return@runCatching null
            val zm = car.getCarManager(Car.CAR_OCCUPANT_ZONE_SERVICE) as? CarOccupantZoneManager
            val display = zm?.allOccupantZones
                ?.firstOrNull { it.occupantType == CarOccupantZoneManager.OCCUPANT_TYPE_REAR_PASSENGER }
                ?.let { zm.getDisplayForOccupant(it, CarOccupantZoneManager.DISPLAY_TYPE_MAIN) }
            car.disconnect()
            display
        }.getOrNull()?.let {
            Log.i(EvidenceLog.TAG, "rear display = occupant zone ${it.displayId}")
            return it
        }
        return dm.displays.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY && it.flags and Display.FLAG_PRIVATE == 0 }
            ?.also { Log.i(EvidenceLog.TAG, "rear display = secondary ${it.displayId} ${it.name}") }
    }

    /** 뒷좌석 화면이 있으면 그 화면에 [RearActivity]를 띄운다. @return 띄웠으면 true */
    fun launch(context: Context): Boolean {
        val d = find(context) ?: return false
        return runCatching {
            val opts = ActivityOptions.makeBasic().setLaunchDisplayId(d.displayId)
            context.startActivity(
                Intent(context, RearActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK),
                opts.toBundle(),
            )
            true
        }.onFailure { Log.w(EvidenceLog.TAG, "rear launch failed", it) }.getOrDefault(false)
    }
}
