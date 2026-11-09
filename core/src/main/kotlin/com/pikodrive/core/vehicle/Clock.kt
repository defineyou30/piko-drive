package com.pikodrive.core.vehicle

/** 단조 증가 ms 시계. 테스트에서는 `Clock { testScheduler.currentTime }` 로 가상 시간을 주입한다. */
fun interface Clock {
    fun nowMs(): Long

    companion object {
        val Monotonic = Clock { System.nanoTime() / 1_000_000 }
    }
}
