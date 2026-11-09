package com.pikodrive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * 피코 세션을 화면과 무관하게 살려 두는 포그라운드 서비스.
 *
 * 내비 같은 다른 앱이 앞에 오면 액티비티만 있는 프로세스는 약 10초 뒤 동결되어
 * 급제동 감지·발화·트립 기록이 멈췄다 (reviews/eval-function.md D1).
 * "대시보드에 사는 동승자"는 화면이 가려져도 운전을 느껴야 한다.
 */
class PikoDriveService : Service() {

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "피코와 함께 달리는 중", NotificationManager.IMPORTANCE_MIN),
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.star_on)
            .setContentTitle("피코가 함께 타고 있어요")
            .setOngoing(true)
            .build()
        // 시스템이 프로세스를 죽인 뒤 백그라운드에서 서비스를 되살리면 포그라운드 시작이 거부된다
        // (ForegroundServiceStartNotAllowedException → 앱 크래시 반복, v4 E2E에서 발견). 그때는 조용히 멈추고
        // 사용자가 앱을 다시 열 때 MainActivity가 정상 경로로 시작한다
        foregroundOk = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(ID, notification)
            }
        }.onFailure { android.util.Log.w(EvidenceLog.TAG, "foreground start refused: ${it.javaClass.simpleName}") }.isSuccess
    }

    private var foregroundOk = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!foregroundOk) {
            stopSelf()
            return START_NOT_STICKY
        }
        (application as PikoApp).startSession(forceDemo = intent?.getBooleanExtra(EXTRA_DEMO, false) ?: false)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "piko_drive"
        private const val ID = 1
        const val EXTRA_DEMO = "demo"

        fun start(context: Context, forceDemo: Boolean) {
            context.startForegroundService(Intent(context, PikoDriveService::class.java).putExtra(EXTRA_DEMO, forceDemo))
        }
    }
}
