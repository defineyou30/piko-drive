package com.pikodrive.storage

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.pikodrive.core.model.RearSeat
import com.pikodrive.core.ride.AgeGroup
import com.pikodrive.core.ride.ChildProfile
import java.time.DayOfWeek
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

/**
 * 아이 프로필 (최대 2명). 차 안 DataStore에만 저장한다: 별칭·연령대·좌석·피코 켜짐·요일별 준비물 항목명·스티커 수.
 * 실명·학원명·탑승 이력은 저장하지 않는다 (docs/CONCEPT_v3_kids_ride.md 7장).
 * 저장 형식은 버전 필드가 있는 JSON (v2의 구분자 문자열 저장 지적 반영).
 */
class ChildStore(private val context: Context) {
    val children: Flow<List<ChildProfile>> = context.pikoStore.data.map { decode(it[KEY]) }

    suspend fun update(transform: (List<ChildProfile>) -> List<ChildProfile>) {
        context.pikoStore.edit { p ->
            p[KEY] = encode(transform(decode(p[KEY])).take(ChildProfile.MAX_CHILDREN))
        }
    }

    suspend fun addSticker(seat: RearSeat) = update { list ->
        list.map { if (it.seat == seat) it.copy(stickers = it.stickers + 1) else it }
    }

    companion object {
        private val KEY = stringPreferencesKey("children_v1")
        private const val VERSION = 1

        internal fun encode(list: List<ChildProfile>): String = JSONObject().apply {
            put("v", VERSION)
            put("children", JSONArray(list.map { c ->
                JSONObject().apply {
                    put("id", c.id); put("alias", c.alias); put("age", c.age.name); put("seat", c.seat.name)
                    put("piko", c.pikoEnabled); put("stickers", c.stickers)
                    put("items", JSONObject().apply { c.itemsByDay.forEach { (d, items) -> put(d.name, JSONArray(items)) } })
                }
            }))
        }.toString()

        internal fun decode(raw: String?): List<ChildProfile> {
            if (raw.isNullOrBlank()) return emptyList()
            return runCatching {
                val arr = JSONObject(raw).getJSONArray("children")
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    val items = o.optJSONObject("items")
                    ChildProfile(
                        id = o.getString("id"),
                        alias = o.getString("alias"),
                        age = AgeGroup.valueOf(o.getString("age")),
                        seat = RearSeat.valueOf(o.getString("seat")),
                        pikoEnabled = o.optBoolean("piko", true),
                        stickers = o.optInt("stickers", 0),
                        itemsByDay = items?.keys()?.asSequence()?.mapNotNull { k ->
                            val d = runCatching { DayOfWeek.valueOf(k) }.getOrNull() ?: return@mapNotNull null
                            val a = items.getJSONArray(k)
                            d to (0 until a.length()).map { a.getString(it) }
                        }?.toMap().orEmpty(),
                    )
                }
            }.getOrDefault(emptyList())
        }

        /** 시연용 가족 (F9). 별칭은 예시. 준비물은 일정(AgentStore.demoSchedules)에 있다 */
        val demoFamily = listOf(
            ChildProfile("demo-1", "지우", AgeGroup.SCHOOL, RearSeat.LEFT),
            ChildProfile("demo-2", "콩이", AgeGroup.KINDER, RearSeat.RIGHT),
        )
    }
}
