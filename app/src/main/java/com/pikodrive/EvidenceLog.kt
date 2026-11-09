package com.pikodrive

import android.util.Log
import com.pikodrive.core.driving.DrivingEvent
import com.pikodrive.core.pet.PikoSession
import com.pikodrive.core.vehicle.VehicleGateway
import com.pikodrive.vehicle.PleosVehicleGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch

/**
 * 안전·기술 증거 로그 (FRAMEWORK 5장 증거 패키지). `adb logcat -s PIKO_EVIDENCE` 로 수집.
 * 모드 전환, 발화 결과, 표정·성장 변화, 트립 리포트를 한 줄씩 남긴다.
 */
object EvidenceLog {
    const val TAG = "PIKO_EVIDENCE"

    /** @return 세션을 교체할 때 취소할 수집 작업들 */
    fun attach(scope: CoroutineScope, s: PikoSession, gateway: VehicleGateway): List<Job> = buildList {
        Log.i(TAG, "session start caps=${s.capabilities} source=${if (gateway is PleosVehicleGateway) "pleos" else "sim"}")
        add(scope.launch { s.mode.collect { Log.i(TAG, "mode $it") } })
        add(scope.launch {
            s.events.collect { e ->
                // 주기 이벤트(편안함 적립)는 너무 잦아서 제외
                if (e !is DrivingEvent.ComfortAccrued) Log.i(TAG, "event $e")
            }
        })
        add(scope.launch {
            var lastSeen: Any? = null
            s.speechLog.collect { log ->
                // 대사 전문은 남기지 않는다 (lineId로 충분, 로그에 사용자 콘텐츠·아이 별칭이 섞이지 않게).
                // 로그는 최근 100건만 유지되므로 개수가 아니라 마지막으로 본 항목 기준으로 새 항목을 고른다
                val from = log.indexOfLast { it === lastSeen } + 1
                log.drop(from).forEach { e -> Log.i(TAG, "speech ${e.outcome} ${e.request.category} ${e.request.lineId} at=${e.atMs}") }
                lastSeen = log.lastOrNull()
            }
        })
        add(scope.launch {
            s.pet.distinctUntilChangedBy { it.expression }.collect { Log.i(TAG, "expression ${it.expression}") }
        })
        add(scope.launch {
            s.pet.distinctUntilChangedBy { it.stage to it.pixelCount }.collect { Log.i(TAG, "growth ${it.stage} px=${it.pixelCount}") }
        })
        add(scope.launch { s.rideEvents.collect { Log.i(TAG, "ride $it") } })
        add(scope.launch {
            s.rideState.distinctUntilChangedBy { Triple(it.phase, it.leftBehind, it.checklist.map { c -> c.id to c.status }) }
                .collect { r -> Log.i(TAG, "ridestate ${r.phase} lb=${r.leftBehind} onboard=${r.onboard.map { it.seat }} check=${r.checklist.joinToString { "${it.id}:${it.status}" }}") }
        })
        add(scope.launch {
            s.tripReports.collect { r ->
                Log.i(TAG, "trip ${r.durationMs / 1000}s comfort=${r.comfortMs / 1000}s startled=${r.startledMoments.size} +px=${r.pixelsGained} stage=${r.stageBefore}→${r.stageAfter}")
            }
        })
    }
}
