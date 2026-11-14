package com.music.bitchord.audio.authenticity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpectrumProbeTest {

    @Test
    fun `bins stay under 12 Hz at every common rate`() {
        assertEquals(4096, SpectrumProbe.fftSizeFor(44_100))
        assertEquals(4096, SpectrumProbe.fftSizeFor(48_000))
        assertEquals(8192, SpectrumProbe.fftSizeFor(96_000))
        assertEquals(16_384, SpectrumProbe.fftSizeFor(192_000))
    }

    /** The channel pair shares one transform; each channel's tone must still show, and nothing else. */
    @Test
    fun `both channels of a pair are measured`() {
        val spectrum = assertNotNull(SpectrumProbe.measure(Signals.sines(listOf(5_000.0, 9_000.0))))
        val left = (5_000.0 / spectrum.binHz).toInt()
        val right = (9_000.0 / spectrum.binHz).toInt()
        val peakLeft = (left - 2..left + 2).maxOf { spectrum.db(it) }
        val peakRight = (right - 2..right + 2).maxOf { spectrum.db(it) }
        // Within Hann's scalloping loss (up to 1.4 dB, by where each tone falls between bins).
        assertEquals(peakLeft, peakRight, 1.5)
        // Far from both tones, the windowed leakage is long gone.
        assertTrue(spectrum.db((15_000.0 / spectrum.binHz).toInt()) < peakLeft - 100)
    }

    @Test
    fun `an odd channel count is measured`() {
        val spectrum = assertNotNull(SpectrumProbe.measure(Signals.sines(listOf(3_000.0, 6_000.0, 12_000.0))))
        val tone = (12_000.0 / spectrum.binHz).toInt()
        assertTrue((tone - 2..tone + 2).maxOf { spectrum.db(it) } > spectrum.db((17_000.0 / spectrum.binHz).toInt()) + 100)
    }

    @Test
    fun `silent windows are left out`() {
        assertNull(SpectrumProbe.measure(Signals.silence()))
        val noise = Signals.noise(Signals.pink, frames = 32_768)
        val halfSilent = PcmExcerpt(noise.samples + IntArray(noise.samples.size), 2, 44_100, 16)
        val full = assertNotNull(SpectrumProbe.measure(noise)).frames
        val half = assertNotNull(SpectrumProbe.measure(halfSilent)).frames
        assertTrue(half in full..full + 1, "only the noise windows count: $half vs $full")
    }

    @Test
    fun `an excerpt shorter than one window gives nothing`() {
        assertNull(SpectrumProbe.measure(Signals.sines(listOf(1_000.0), frames = 4_000)))
    }
}
