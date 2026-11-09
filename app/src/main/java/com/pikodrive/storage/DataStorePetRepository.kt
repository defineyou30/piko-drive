package com.pikodrive.storage

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.pikodrive.core.model.GrowthStage
import com.pikodrive.core.pet.PetState
import com.pikodrive.core.pet.TripReport
import com.pikodrive.core.storage.PetRepository
import kotlinx.coroutines.flow.first

val Context.pikoStore by preferencesDataStore(name = "piko")

/**
 * 펫 상태 + 최근 트립 리포트 (로컬 전용, 서버 없음 — PLAN 3장 Won't).
 * 리포트 시각은 엔진의 단조 시계 기준이라 저장할 때 벽시계로, 읽을 때 다시 단조 시계로 바꾼다
 * (재부팅해도 "몇 시에 탔는지"가 유지되게).
 */
class DataStorePetRepository(private val context: Context, private val maxReports: Int = 30) : PetRepository {

    override suspend fun loadState(): PetState? {
        val p = context.pikoStore.data.first()
        val stage = p[STAGE]?.let { runCatching { GrowthStage.valueOf(it) }.getOrNull() } ?: return null
        return PetState(
            mood = p[MOOD] ?: PetState.Initial.mood,
            bond = p[BOND] ?: PetState.Initial.bond,
            stage = stage,
            pixelCount = p[PIXELS] ?: stage.pixelTarget,
            comfortMsTotal = p[COMFORT] ?: 0,
        )
    }

    override suspend fun saveState(state: PetState) {
        context.pikoStore.edit {
            it[MOOD] = state.mood
            it[BOND] = state.bond
            it[STAGE] = state.stage.name
            it[PIXELS] = state.pixelCount
            it[COMFORT] = state.comfortMsTotal
        }
    }

    override suspend fun addReport(report: TripReport) {
        context.pikoStore.edit {
            val lines = listOf(encode(report)) + (it[REPORTS]?.split('\n')?.filter(String::isNotBlank) ?: emptyList())
            it[REPORTS] = lines.take(maxReports).joinToString("\n")
        }
    }

    override suspend fun recentReports(limit: Int): List<TripReport> =
        context.pikoStore.data.first()[REPORTS]?.split('\n')
            ?.mapNotNull { runCatching { decode(it) }.getOrNull() }?.take(limit) ?: emptyList()

    suspend fun clear() {
        context.pikoStore.edit { p -> listOf(MOOD, BOND, PIXELS).forEach { p.remove(it) }; p.remove(STAGE); p.remove(COMFORT); p.remove(REPORTS) }
    }

    private fun toWall(mono: Long) = System.currentTimeMillis() - (monoNow() - mono)
    private fun toMono(wall: Long) = monoNow() - (System.currentTimeMillis() - wall)
    private fun monoNow() = System.nanoTime() / 1_000_000

    // 한 줄 = 한 리포트. 필드 구분 '|', 놀란 순간 구분 ','
    private fun encode(r: TripReport) = listOf(
        toWall(r.startedAtMs), toWall(r.endedAtMs), r.comfortMs,
        r.startledMoments.joinToString(",") { toWall(it).toString() },
        r.pixelsGained, r.stageBefore.name, r.stageAfter.name,
    ).joinToString("|")

    private fun decode(line: String): TripReport {
        val f = line.split('|')
        return TripReport(
            startedAtMs = toMono(f[0].toLong()),
            endedAtMs = toMono(f[1].toLong()),
            comfortMs = f[2].toLong(),
            startledMoments = f[3].split(',').filter(String::isNotBlank).map { toMono(it.toLong()) },
            pixelsGained = f[4].toInt(),
            stageBefore = GrowthStage.valueOf(f[5]),
            stageAfter = GrowthStage.valueOf(f[6]),
        )
    }

    private companion object {
        val MOOD = intPreferencesKey("mood")
        val BOND = intPreferencesKey("bond")
        val STAGE = stringPreferencesKey("stage")
        val PIXELS = intPreferencesKey("pixels")
        val COMFORT = longPreferencesKey("comfort_ms")
        val REPORTS = stringPreferencesKey("reports")
    }
}
