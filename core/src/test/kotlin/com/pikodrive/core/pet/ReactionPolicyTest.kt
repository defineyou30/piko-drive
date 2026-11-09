package com.pikodrive.core.pet

import com.pikodrive.core.driving.DrivingEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReactionPolicyTest {
    private val all = DefaultLines.all.values.flatten()

    @Test
    fun `lines are short enough for about 3 seconds of TTS`() {
        // 한국어 TTS ≈ 초당 5~6음절 → 공백·문장부호 제외 14자 이하
        all.forEach {
            val syllables = it.text.count { c -> c in '가'..'힣' }
            assertTrue(syllables <= 14, "${it.lineId}: ${it.text} ($syllables)")
        }
    }

    @Test
    fun `lines never blame the driver`() {
        val banned = listOf("운전", "못", "위험", "조심", "왜", "나빠", "그만", "천천히", "제발")
        all.forEach { line -> banned.forEach { assertFalse(it in line.text, "${line.lineId} contains $it") } }
        assertEquals(all.size, all.map { it.lineId }.toSet().size, "lineId unique")
    }

    @Test
    fun `startle lines rotate and match event kind`() {
        val p = ReactionPolicy()
        val ids = (1..4).map { p.react(DrivingEvent.HardBrake(0, -12f))!!.speech!!.lineId }
        assertEquals(listOf("startle_brake_1", "startle_brake_2", "startle_brake_3", "startle_brake_1"), ids)
        assertTrue(p.react(DrivingEvent.HardAccel(0, 13f))!!.speech!!.lineId.startsWith("startle_accel"))
        assertTrue(p.react(DrivingEvent.HardBrake(0, -12f))!!.delayed)
        assertNull(p.react(DrivingEvent.Reversing(0)))
    }
}
