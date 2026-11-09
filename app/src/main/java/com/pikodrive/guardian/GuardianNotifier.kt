package com.pikodrive.guardian

import android.util.Log
import com.pikodrive.EvidenceLog
import com.pikodrive.core.model.RearSeat
import com.pikodrive.core.ride.ChildProfile
import com.pikodrive.core.ride.RideEvent
import com.pikodrive.core.ride.label
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 다른 보호자에게 "출발·도착·하차·남김" 알림 (docs/CONCEPT_v3_kids_ride.md 4장, 11/8 컷라인 → 웹훅 범위로 확정).
 *
 * - 보내는 것: 이벤트 종류, 좌석, 부모가 정한 **별칭**, 시각. 위치·목적지·대사·차량 식별자는 보내지 않는다.
 * - 받는 곳: 부모가 설정한 웹훅 URL (시연은 tools/guardian_server.py + `adb reverse`).
 *   FCM 푸시·계정·보호자 초대는 서버 운영·책임 문제가 있어 로드맵.
 * - 전송 실패는 최대 [MAX_ATTEMPTS]번 다시 시도하고, 그래도 안 되면 버린다 (차 안의 동작에는 영향 없음).
 */
class GuardianNotifier(
    private val scope: CoroutineScope,
    private val url: () -> String?,
    private val profiles: () -> List<ChildProfile>,
) {
    private val queue = Channel<JSONObject>(capacity = 32)

    init {
        scope.launch {
            for (body in queue) send(body)
        }
    }

    fun onEvent(e: RideEvent) {
        val (type, seats) = when (e) {
            is RideEvent.DepartedWithKids -> "departed" to e.seats
            is RideEvent.Arrived -> "arrived" to e.seats
            is RideEvent.ChildAlighted -> "alighted" to listOf(e.seat)
            is RideEvent.LeftBehindAlert -> if (e.level == 1) "left_behind" to e.seats else return
            is RideEvent.LeftBehindCleared -> "left_behind_cleared" to emptyList()
            else -> return
        }
        if (url().isNullOrBlank()) return
        val body = JSONObject().apply {
            put("type", type)
            put("at", System.currentTimeMillis())
            put("children", JSONArray(seats.map { seat -> child(seat) }))
        }
        if (queue.trySend(body).isFailure) Log.i(EvidenceLog.TAG, "guardian queue full, dropped $type")
    }

    private fun child(seat: RearSeat) = JSONObject().apply {
        put("seat", seat.label)
        profiles().firstOrNull { it.seat == seat }?.let { put("alias", it.alias) }
    }

    private suspend fun send(body: JSONObject) {
        val target = url() ?: return
        // 차 밖으로 나가는 유일한 경로: HTTPS만 허용 (시연용 로컬 보호자 화면 127.0.0.1·localhost만 예외)
        if (!isAllowed(target)) {
            Log.i(EvidenceLog.TAG, "guardian refused non-https url")
            return
        }
        repeat(MAX_ATTEMPTS) { attempt ->
            val code = withContext(Dispatchers.IO) {
                runCatching {
                    (URL(target).openConnection() as HttpURLConnection).run {
                        requestMethod = "POST"
                        connectTimeout = 3_000
                        readTimeout = 3_000
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json; charset=utf-8")
                        outputStream.use { it.write(body.toString().toByteArray()) }
                        responseCode.also { disconnect() }
                    }
                }.getOrNull()
            }
            // 증거 로그에는 별칭을 남기지 않는다
            Log.i(EvidenceLog.TAG, "guardian ${body.getString("type")} attempt=${attempt + 1} code=$code")
            if (code != null && code in 200..299) return
            delay(RETRY_MS * (attempt + 1))
        }
    }

    companion object {
        internal fun isAllowed(url: String): Boolean = runCatching {
            val u = URL(url)
            u.protocol == "https" || (u.protocol == "http" && u.host in setOf("127.0.0.1", "localhost"))
        }.getOrDefault(false)

        private const val MAX_ATTEMPTS = 3
        private const val RETRY_MS = 2_000L
        /** 시연 기본값: 에뮬레이터 → `adb reverse tcp:8099 tcp:8099` → 호스트의 tools/guardian_server.py */
        const val DEMO_URL = "http://127.0.0.1:8099/event"
    }
}
