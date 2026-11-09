package com.pikodrive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.pikodrive.core.model.RearSeat
import com.pikodrive.vehicle.CabinOverrides

/** 디버그 빌드 전용: tools/vconsole.py belt → 데모 패널과 같은 벨트 대체 입력 경로 */
class DemoBeltReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_STICKERS = "com.pikodrive.DEMO_STICKERS"
        const val ACTION_GUARDIAN = "com.pikodrive.DEMO_GUARDIAN"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_GUARDIAN) {
            val on = intent.getStringExtra("on") == "true"
            (context.applicationContext as PikoApp).updateSettings { it.copy(guardianOn = on) }
            Log.i(EvidenceLog.TAG, "demo guardian=$on")
            return
        }
        if (intent.action == ACTION_STICKERS) {
            // QA: 모든 아이의 스티커 총수를 지정 (판 완성·해금 화면 확인용)
            val n = intent.getStringExtra("count")?.toIntOrNull() ?: return
            (context.applicationContext as PikoApp).updateChildren { list -> list.map { it.copy(stickers = n) } }
            Log.i(EvidenceLog.TAG, "demo stickers=$n")
            return
        }
        val seat = runCatching { RearSeat.valueOf(intent.getStringExtra("seat") ?: return) }.getOrNull() ?: return
        val value = when (intent.getStringExtra("value")) { "true" -> true; "false" -> false; else -> null }
        CabinOverrides.setBelt(seat, value)
        Log.i(EvidenceLog.TAG, "demo belt $seat=$value (simulated input)")
    }
}
