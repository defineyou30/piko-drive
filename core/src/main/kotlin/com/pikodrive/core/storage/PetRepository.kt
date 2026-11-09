package com.pikodrive.core.storage

import com.pikodrive.core.pet.PetState
import com.pikodrive.core.pet.TripReport

/** 펫 상태 + 최근 리포트 저장소. Android DataStore 구현은 :app 에서. */
interface PetRepository {
    suspend fun loadState(): PetState?
    suspend fun saveState(state: PetState)
    suspend fun addReport(report: TripReport)
    /** 최신순. */
    suspend fun recentReports(limit: Int = 7): List<TripReport>
}

class InMemoryPetRepository(private val maxReports: Int = 30) : PetRepository {
    private var state: PetState? = null
    private val reports = ArrayDeque<TripReport>()

    override suspend fun loadState(): PetState? = state

    override suspend fun saveState(state: PetState) {
        this.state = state
    }

    override suspend fun addReport(report: TripReport) {
        reports.addFirst(report)
        while (reports.size > maxReports) reports.removeLast()
    }

    override suspend fun recentReports(limit: Int): List<TripReport> = reports.take(limit)
}
