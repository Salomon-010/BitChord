package com.music.bitchord.audio.authenticity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CutoffDetectorTest {

    private fun cutoffOf(excerpt: PcmExcerpt) = CutoffDetector.find(assertNotNull(SpectrumProbe.measure(excerpt)))

    @Test
    fun `full-band noise has no cut-off`() {
        assertNull(cutoffOf(Signals.noise(Signals.pink)))
    }

    @Test
    fun `a 16 kHz low-pass is found where it is`() {
        val cutoff = assertNotNull(cutoffOf(Signals.noise(Signals.brickwall(16_000.0))))
        assertEquals(16_000.0, cutoff.hz, 150.0)
        assertTrue(cutoff.stepDb > 40, "step ${cutoff.stepDb}")
    }

    @Test
    fun `a 20 kHz low-pass is found where it is`() {
        assertEquals(20_000.0, assertNotNull(cutoffOf(Signals.noise(Signals.brickwall(20_000.0)))).hz, 150.0)
    }

    @Test
    fun `a 44_1 kHz file's own anti-alias filter is past the search`() {
        assertNull(cutoffOf(Signals.noise(Signals.brickwall(21_500.0))))
    }

    /** A dull recording: −12 dB/octave from 6 kHz on top of the pink slope, down 30 dB by 20 kHz but never a cliff. */
    @Test
    fun `a steep natural roll-off is not a cut-off`() {
        val dull: (Double) -> Double = { hz -> Signals.pink(hz) * if (hz < 6_000) 1.0 else (6_000 / hz) * (6_000 / hz) }
        assertNull(cutoffOf(Signals.noise(dull)))
    }

    @Test
    fun `a 96 kHz file cut at 22 kHz is found`() {
        val cutoff = assertNotNull(cutoffOf(Signals.noise(Signals.brickwall(22_000.0), frames = 131_072, sampleRate = 96_000, bits = 24)))
        assertEquals(22_000.0, cutoff.hz, 150.0)
    }
}
