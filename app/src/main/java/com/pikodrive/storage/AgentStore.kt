package com.pikodrive.storage

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.pikodrive.core.agent.Memo
import com.pikodrive.core.agent.MemoTrigger
import com.pikodrive.core.agent.SafetyKind
import com.pikodrive.core.agent.SafetyRecord
import com.pikodrive.core.agent.ScheduleEntry
import java.time.DayOfWeek
import java.time.LocalTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

/**
 * v4 에이전트 저장소: 일정·메모·안전 기록. 차 안 DataStore에만 둔다 (버전 필드가 있는 JSON).
 * 안전 기록은 최근 [SAFETY_KEEP_DAYS]일만 보관한다.
 */
class AgentStore(private val context: Context) {
    val schedules: Flow<List<ScheduleEntry>> = context.pikoStore.data.map { decodeSchedules(it[SCHEDULES]) }
    val memos: Flow<List<Memo>> = context.pikoStore.data.map { decodeMemos(it[MEMOS]) }
    val safety: Flow<List<SafetyRecord>> = context.pikoStore.data.map { decodeSafety(it[SAFETY]) }

    suspend fun updateSchedules(t: (List<ScheduleEntry>) -> List<ScheduleEntry>) =
        context.pikoStore.edit { it[SCHEDULES] = encodeSchedules(t(decodeSchedules(it[SCHEDULES]))) }

    suspend fun updateMemos(t: (List<Memo>) -> List<Memo>) =
        context.pikoStore.edit { it[MEMOS] = encodeMemos(t(decodeMemos(it[MEMOS]))) }

    suspend fun addSafety(r: SafetyRecord) = context.pikoStore.edit { p ->
        val cutoff = System.currentTimeMillis() - SAFETY_KEEP_DAYS * 24 * 3600_000L
        p[SAFETY] = encodeSafety((decodeSafety(p[SAFETY]) + r).filter { it.atWallMs >= cutoff })
    }

    companion object {
        private val SCHEDULES = stringPreferencesKey("schedules_v1")
        private val MEMOS = stringPreferencesKey("memos_v1")
        private val SAFETY = stringPreferencesKey("safety_v1")
        const val SAFETY_KEEP_DAYS = 7

        private fun wrap(arr: JSONArray) = JSONObject().put("v", 1).put("items", arr).toString()
        private fun items(raw: String?): List<JSONObject> = runCatching {
            val a = JSONObject(raw ?: return emptyList()).getJSONArray("items")
            (0 until a.length()).map { a.getJSONObject(it) }
        }.getOrDefault(emptyList())

        internal fun encodeSchedules(l: List<ScheduleEntry>) = wrap(JSONArray(l.map { e ->
            JSONObject().put("id", e.id).put("child", e.childId ?: "").put("days", JSONArray(e.days.map { it.name }))
                .put("time", e.time.toString()).put("title", e.title).put("items", JSONArray(e.items)).put("lead", e.leadMinutes)
        }))

        internal fun decodeSchedules(raw: String?) = items(raw).mapNotNull { o ->
            runCatching {
                val days = o.getJSONArray("days")
                val items = o.optJSONArray("items") ?: JSONArray()
                ScheduleEntry(
                    id = o.getString("id"),
                    childId = o.optString("child").ifBlank { null },
                    days = (0 until days.length()).map { DayOfWeek.valueOf(days.getString(it)) }.toSet(),
                    time = LocalTime.parse(o.getString("time")),
                    title = o.getString("title"),
                    items = (0 until items.length()).map { items.getString(it) },
                    leadMinutes = o.optInt("lead", 15),
                )
            }.getOrNull()
        }

        internal fun encodeMemos(l: List<Memo>) = wrap(JSONArray(l.map { m ->
            JSONObject().put("id", m.id).put("text", m.text).put("trigger", m.trigger.name).put("child", m.childId ?: "")
                .put("time", m.time?.toString() ?: "").put("repeat", m.repeat).put("done", m.done)
        }))

        internal fun decodeMemos(raw: String?) = items(raw).mapNotNull { o ->
            runCatching {
                Memo(
                    id = o.getString("id"),
                    text = o.getString("text"),
                    trigger = MemoTrigger.valueOf(o.getString("trigger")),
                    childId = o.optString("child").ifBlank { null },
                    time = o.optString("time").ifBlank { null }?.let(LocalTime::parse),
                    repeat = o.optBoolean("repeat"),
                    done = o.optBoolean("done"),
                )
            }.getOrNull()
        }

        internal fun encodeSafety(l: List<SafetyRecord>) = wrap(JSONArray(l.map { r ->
            JSONObject().put("at", r.atWallMs).put("kind", r.kind.name).put("seat", r.seatLabel ?: "")
        }))

        internal fun decodeSafety(raw: String?) = items(raw).mapNotNull { o ->
            runCatching { SafetyRecord(o.getLong("at"), SafetyKind.valueOf(o.getString("kind")), o.optString("seat").ifBlank { null }) }.getOrNull()
        }

        /** 시연용 일정·메모 (데모 가족과 짝). 요일 전부라 시연 날짜와 무관하게 보인다 */
        fun demoSchedules(nowTime: LocalTime): List<ScheduleEntry> {
            val all = DayOfWeek.entries.toSet()
            val soon = nowTime.plusMinutes(12).withSecond(0).withNano(0)
            // 자정을 넘기면 '지난 일정'으로 보이므로, 넘치는 일정은 지금보다 앞 시각(이미 지난 일정)으로 둔다
            fun later(min: Long) = soon.plusMinutes(min).takeIf { it.isAfter(soon) } ?: soon.minusMinutes(240 - min)
            return listOf(
                ScheduleEntry("demo-s0", "demo-2", all, soon.minusHours(4).takeIf { it.isBefore(soon) } ?: LocalTime.of(0, 0), "등원"),
                ScheduleEntry("demo-s1", "demo-1", all, soon, "태권도", listOf("도복", "띠")),
                ScheduleEntry("demo-s2", "demo-2", all, later(50), "수영", listOf("수영 가방")),
                ScheduleEntry("demo-s3", "demo-1", all, later(180), "영어 학원", listOf("단어장")),
            )
        }

        val demoMemos = listOf(
            Memo("demo-m2", "알림장 받아 오기", MemoTrigger.ARRIVE),
            Memo("demo-m3", "지우 물통 챙겼는지", MemoTrigger.BOARD, childId = "demo-1"),
        )
    }
}
