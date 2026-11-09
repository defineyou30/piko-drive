package com.pikodrive.core.pet

import com.pikodrive.core.model.CabinSnapshot
import com.pikodrive.core.model.Gear
import com.pikodrive.core.model.RearSeat.LEFT
import com.pikodrive.core.model.VehicleSnapshot
import com.pikodrive.core.ride.AgeGroup
import com.pikodrive.core.ride.ChildProfile
import com.pikodrive.core.ride.RideEvent
import com.pikodrive.core.vehicle.Clock
import com.pikodrive.core.vehicle.VehicleCapabilities
import com.pikodrive.core.vehicle.VehicleGateway
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** 9장 가진 아이가 스스로 벨트 → 10장째에 판 완성 이벤트와 축하 대사 (PikoSession 배선). */
@OptIn(ExperimentalCoroutinesApi::class)
class BoardCompletionTest {
    @Test
    fun `tenth sticker completes the board and piko celebrates`() = runTest {
        val clock = Clock { testScheduler.currentTime }
        // P, 4초 빈 좌석 → 착석 → 2초 뒤 벨트
        val gateway = object : VehicleGateway {
            override val capabilities = VehicleCapabilities.Full
            override val snapshots = flow {
                for (sec in 0..10) {
                    val occ = sec >= 4
                    val belt = sec >= 6
                    emit(VehicleSnapshot(clock.nowMs(), 0f, Gear.PARK, false,
                        cabin = CabinSnapshot(mapOf(LEFT to occ), mapOf(LEFT to belt))))
                    delay(1_000)
                }
            }
        }
        val sink = FakeSpeechSink(clock)
        val session = PikoSession.create(backgroundScope, gateway, sink, clock = clock)
        session.setProfiles(listOf(ChildProfile("a", "지우", AgeGroup.SCHOOL, LEFT, stickers = 9)))
        val events = mutableListOf<RideEvent>()
        backgroundScope.launch { session.rideEvents.collect { events += it } }
        session.start()
        advanceTimeBy(15_000); runCurrent()
        assertEquals(listOf(1), events.filterIsInstance<RideEvent.BoardCompleted>().map { it.boards })
        assertEquals(true, sink.attempts.any { it.request.lineId == "kid_board_1_school" })
    }
}
