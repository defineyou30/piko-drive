package com.pikodrive.core.storage

import com.pikodrive.core.model.GrowthStage
import com.pikodrive.core.pet.PetState
import com.pikodrive.core.pet.TripReport
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class InMemoryPetRepositoryTest {
    private fun report(i: Long) = TripReport(i, i + 1, 0, emptyList(), 0, GrowthStage.SPARK, GrowthStage.SPARK)

    @Test
    fun `saves state and keeps bounded recent reports newest first`() = runTest {
        val repo = InMemoryPetRepository(maxReports = 3)
        assertNull(repo.loadState())
        repo.saveState(PetState(mood = 77))
        assertEquals(77, repo.loadState()!!.mood)
        (1L..5L).forEach { repo.addReport(report(it)) }
        assertEquals(listOf(5L, 4L, 3L), repo.recentReports().map { it.startedAtMs })
        assertEquals(listOf(5L), repo.recentReports(1).map { it.startedAtMs })
    }
}
