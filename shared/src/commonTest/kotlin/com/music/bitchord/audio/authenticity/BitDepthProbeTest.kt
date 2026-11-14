package com.music.bitchord.audio.authenticity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BitDepthProbeTest {

    @Test
    fun `a real 24-bit file uses all 24 bits`() {
        assertEquals(24, BitDepthProbe.effectiveBits(listOf(Signals.noise(Signals.pink, frames = 8_192, bits = 24))))
    }

    @Test
    fun `a 16-bit master stored as 24 bits uses 16`() {
        assertEquals(16, BitDepthProbe.effectiveBits(listOf(Signals.noise(Signals.pink, frames = 8_192, bits = 16, shift = 8))))
    }

    @Test
    fun `a 16-bit file uses 16`() {
        assertEquals(16, BitDepthProbe.effectiveBits(listOf(Signals.noise(Signals.pink, frames = 8_192))))
    }

    @Test
    fun `negative samples count by their own low bits`() {
        val samples = IntArray(8_192) { -(it + 1) * 256 }
        assertEquals(16, BitDepthProbe.effectiveBits(listOf(PcmExcerpt(samples, 1, 44_100, 24))))
    }

    @Test
    fun `one excerpt with its low bits used is enough`() {
        val padded = Signals.noise(Signals.pink, frames = 8_192, bits = 16, shift = 8)
        val real = Signals.noise(Signals.pink, frames = 8_192, bits = 24, seed = 2)
        assertEquals(24, BitDepthProbe.effectiveBits(listOf(padded, real)))
    }

    @Test
    fun `near silence says nothing`() {
        assertNull(BitDepthProbe.effectiveBits(listOf(Signals.silence(frames = 8_192, bits = 24))))
        val few = IntArray(8_192).also { for (i in 0 until 100) it[i * 80] = 256 }
        assertNull(BitDepthProbe.effectiveBits(listOf(PcmExcerpt(few, 1, 44_100, 24))))
    }
}
