package com.pikodrive.storage

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class NightDim { AUTO, ALWAYS, OFF }

/** F8 설정. 화면에 있는 항목은 전부 실제로 동작해야 한다 (가짜 토글 금지). */
data class PikoSettings(
    val firstMeetDone: Boolean = false,
    val voiceOn: Boolean = true,
    val nightDim: NightDim = NightDim.AUTO,
    /** F9 데모: 성장 가속 (PT 시연용, 실차 데이터와 함께 동작) */
    val demoGrowth: Boolean = false,
    /** v3: 차도 쪽 뒷문 (한국 우측통행 길가 정차 → 왼쪽) */
    val streetSideLeft: Boolean = true,
    /** v3: 다른 보호자 알림 (기본 꺼짐). 켜면 [guardianUrl]로 출발·도착·하차·남김 + 별칭만 보낸다 */
    val guardianOn: Boolean = false,
    val guardianUrl: String = "",
)

class SettingsStore(private val context: Context) {
    val settings: Flow<PikoSettings> = context.pikoStore.data.map(::read)

    suspend fun update(transform: (PikoSettings) -> PikoSettings) {
        context.pikoStore.edit { p ->
            val n = transform(read(p))
            p[FIRST_MEET] = n.firstMeetDone
            p[VOICE] = n.voiceOn
            p[NIGHT] = n.nightDim.name
            p[DEMO_GROWTH] = n.demoGrowth
            p[STREET_LEFT] = n.streetSideLeft
            p[GUARDIAN_ON] = n.guardianOn
            p[GUARDIAN_URL] = n.guardianUrl
        }
    }

    private fun read(p: Preferences) = PikoSettings(
        firstMeetDone = p[FIRST_MEET] ?: false,
        voiceOn = p[VOICE] ?: true,
        nightDim = p[NIGHT]?.let { runCatching { NightDim.valueOf(it) }.getOrNull() } ?: NightDim.AUTO,
        demoGrowth = p[DEMO_GROWTH] ?: false,
        streetSideLeft = p[STREET_LEFT] ?: true,
        guardianOn = p[GUARDIAN_ON] ?: false,
        guardianUrl = p[GUARDIAN_URL] ?: "",
    )

    private companion object {
        val FIRST_MEET = booleanPreferencesKey("first_meet_done")
        val VOICE = booleanPreferencesKey("voice_on")
        val NIGHT = stringPreferencesKey("night_dim")
        val DEMO_GROWTH = booleanPreferencesKey("demo_growth")
        val STREET_LEFT = booleanPreferencesKey("street_side_left")
        val GUARDIAN_ON = booleanPreferencesKey("guardian_on")
        val GUARDIAN_URL = stringPreferencesKey("guardian_url")
    }
}
