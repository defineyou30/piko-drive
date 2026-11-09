package com.pikodrive

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.pikodrive.core.model.DriveMode
import com.pikodrive.core.model.Expression
import com.pikodrive.ui.live.LiveScreen
import com.pikodrive.ui.screens.F0FirstMeet
import com.pikodrive.ui.screens.F3Driving
import com.pikodrive.ui.screens.F8Settings
import com.pikodrive.ui.screens.F9DemoPanel
import com.pikodrive.ui.screens.PetUi
import com.pikodrive.ui.theme.PikoColors
import com.pikodrive.ui.theme.PikoTheme
import com.pikodrive.vehicle.PleosVehicleGateway

/**
 * 기본: 실제 세션(LiveScreen). `--es screen F1` 처럼 넘기면 S1 목업 갤러리.
 * `--ez demo true` 면 차량에서도 시뮬레이터로 실행 (PT 백업용).
 */
class MainActivity : ComponentActivity() {

    private val app get() = application as PikoApp

    // 거부돼도 시작한다 (속도 채널만 미지원 처리). 결과는 증거 로그에 남긴다.
    private val permissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            android.util.Log.i(EvidenceLog.TAG, "permissions $result")
            start()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mock = intent.getStringExtra("screen")
        setContent {
            PikoTheme {
                if (mock != null) Mock(mock) else Live()
            }
        }
        if (mock == null) {
            // 마이크 권한은 여기서 묻지 않는다: 첫 실행에 녹음 권한 창이 먼저 뜨면 세션(안전 알림) 시작이 그 답을 기다리게 된다
            // (v5 E2E에서 발견). "말로 입력"을 처음 누를 때 묻는다
            val missing = PleosVehicleGateway.requiredRuntimePermissions
                .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (missing.isEmpty() || !PleosVehicleGateway.isAvailable(this)) start() else permissionRequest.launch(missing.toTypedArray())
        }
    }

    private fun start() {
        PikoDriveService.start(this, forceDemo = intent.getBooleanExtra("demo", false))
        app.rearScreen.value = com.pikodrive.rear.RearDisplay.launch(this)
    }

    @Composable
    private fun Live() {
        val session by app.session.collectAsState()
        val s = session
        // 세션 준비 전 = 안전 우선 부팅: 텍스트·버튼 없는 주행 화면 형태
        if (s == null) F3Driving(PetUi(), DriveMode.DRIVING) else LiveScreen(app, s)
    }
}

@Composable
private fun Mock(screen: String) {
    when (screen) {
        "F0" -> F0FirstMeet(0)
        "F0b" -> F0FirstMeet(1)
        "F3" -> F3Driving()
        "F3R" -> F3Driving(mode = DriveMode.REVERSING)
        "F4" -> F3Driving(PetUi(expression = Expression.SURPRISED))
        "F8" -> F8Settings()
        "F9" -> F9DemoPanel()
        else -> Box(Modifier.fillMaxSize().background(PikoColors.Cabin900))
    }
}
