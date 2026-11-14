package com.music.bitchord.audio.authenticity

import com.music.bitchord.audio.authenticity.AuthenticityKind.Genuine
import com.music.bitchord.audio.authenticity.AuthenticityKind.Inconclusive
import com.music.bitchord.audio.authenticity.AuthenticityKind.LossyTranscode
import com.music.bitchord.audio.authenticity.AuthenticityKind.PaddedBitDepth
import com.music.bitchord.audio.authenticity.AuthenticityKind.Upsampled
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LosslessAuthenticityTest {

    /** Three excerpts as the caller takes them, at 20 %, 50 % and 75 % of a 4 min song. */
    private fun three(shape: (Double) -> Double, sampleRate: Int = 44_100, bits: Int = 16, shift: Int = 0) =
        listOf(48.0, 120.0, 180.0).mapIndexed { i, at ->
            Signals.noise(shape, frames = if (sampleRate > 48_000) 131_072 else 65_536, sampleRate = sampleRate, bits = bits, shift = shift, seed = i + 1, startSeconds = at)
        }

    @Test
    fun `full-band 16-bit audio is genuine`() {
        val verdict = LosslessAuthenticity.analyse(three(Signals.pink))
        assertEquals(Genuine, verdict.kind)
        assertEquals(1.0, verdict.confidence)
        assertNull(verdict.cutoffHz)
        assertEquals(16, verdict.effectiveBits)
        assertEquals(listOf(48.0, 120.0, 180.0), verdict.excerpts.map { it.startSeconds })
    }

    @Test
    fun `a 16 kHz low-pass everywhere is a lossy transcode`() {
        val verdict = LosslessAuthenticity.analyse(three(Signals.brickwall(16_000.0)))
        assertEquals(LossyTranscode, verdict.kind)
        assertEquals(16_000.0, assertNotNull(verdict.cutoffHz), 150.0)
        assertTrue(verdict.confidence > 0.9, "confidence ${verdict.confidence}")
    }

    @Test
    fun `LAME's low-pass at 128 kbps gives the bitrate`() {
        val verdict = LosslessAuthenticity.analyse(three(Signals.lameLowpass(17_000.0)))
        assertEquals(LossyTranscode, verdict.kind)
        assertEquals(LossySource.Mp3, verdict.lossySource)
        assertEquals(128, verdict.estimatedKbps)
    }

    @Test
    fun `LAME's low-pass at 320 kbps gives the bitrate`() {
        val verdict = LosslessAuthenticity.analyse(three(Signals.lameLowpass(20_500.0)))
        assertEquals(LossySource.Mp3, verdict.lossySource)
        assertEquals(320, verdict.estimatedKbps)
    }

    /** AAC's cut: it zeroes everything past its bandwidth, so the drop has no shoulder. */
    @Test
    fun `an abrupt low-pass only says compressed, even at a LAME frequency`() {
        val verdict = LosslessAuthenticity.analyse(three(Signals.brickwall(16_800.0)))
        assertEquals(LossyTranscode, verdict.kind)
        assertEquals(LossySource.Compressed, verdict.lossySource)
        assertNull(verdict.estimatedKbps)
    }

    /** MP3-shaped, but at none of LAME's default frequencies: another encoder or setting, so no bitrate. */
    @Test
    fun `a rounded low-pass away from LAME's defaults only says compressed`() {
        val verdict = LosslessAuthenticity.analyse(three(Signals.lameLowpass(18_100.0)))
        assertEquals(LossyTranscode, verdict.kind)
        assertEquals(LossySource.Compressed, verdict.lossySource)
        assertNull(verdict.estimatedKbps)
    }

    @Test
    fun `only a lossy transcode names a source`() {
        assertNull(LosslessAuthenticity.analyse(three(Signals.pink)).lossySource)
        assertNull(LosslessAuthenticity.analyse(three(Signals.pink, bits = 16, shift = 8)).lossySource)
        assertNull(LosslessAuthenticity.analyse(three(Signals.brickwall(22_000.0), sampleRate = 96_000, bits = 24)).lossySource)
    }

    @Test
    fun `an anti-alias filter at 21_5 kHz is genuine`() {
        assertEquals(Genuine, LosslessAuthenticity.analyse(three(Signals.brickwall(21_500.0))).kind)
    }

    @Test
    fun `a 48 kHz file's anti-alias filter at 22_5 kHz is genuine`() {
        val excerpts = listOf(1, 2, 3).map { Signals.noise(Signals.brickwall(22_500.0), sampleRate = 48_000, seed = it) }
        val verdict = LosslessAuthenticity.analyse(excerpts)
        assertEquals(Genuine, verdict.kind)
    }

    @Test
    fun `a 24-bit file with its low byte always zero is padded`() {
        val verdict = LosslessAuthenticity.analyse(three(Signals.pink, bits = 16, shift = 8))
        assertEquals(PaddedBitDepth, verdict.kind)
        assertEquals(24, verdict.declaredBits)
        assertEquals(16, verdict.effectiveBits)
    }

    @Test
    fun `a real 24-bit file is genuine`() {
        val verdict = LosslessAuthenticity.analyse(three(Signals.pink, bits = 24))
        assertEquals(Genuine, verdict.kind)
        assertEquals(24, verdict.effectiveBits)
    }

    @Test
    fun `a padded 24-bit file made from an MP3 says both`() {
        val verdict = LosslessAuthenticity.analyse(three(Signals.lameLowpass(17_000.0), bits = 16, shift = 8))
        assertEquals(LossyTranscode, verdict.kind)
        assertEquals(16, verdict.effectiveBits)
        assertEquals(128, verdict.estimatedKbps)
    }

    @Test
    fun `a 96 kHz file with nothing past 22 kHz is upsampled`() {
        val verdict = LosslessAuthenticity.analyse(three(Signals.brickwall(22_000.0), sampleRate = 96_000, bits = 24))
        assertEquals(Upsampled, verdict.kind)
        assertNull(verdict.estimatedKbps)
    }

    @Test
    fun `a 96 kHz file with an MP3 low-pass is a lossy transcode`() {
        assertEquals(LossyTranscode, LosslessAuthenticity.analyse(three(Signals.brickwall(16_000.0), sampleRate = 96_000, bits = 24)).kind)
    }

    @Test
    fun `a full-band 96 kHz file is genuine`() {
        assertEquals(Genuine, LosslessAuthenticity.analyse(three(Signals.pink, sampleRate = 96_000, bits = 24)).kind)
    }

    /** One dull passage is not an encoder: the encoder's low-pass is everywhere. */
    @Test
    fun `a low-pass in one excerpt of three is inconclusive`() {
        val excerpts = listOf(
            Signals.noise(Signals.brickwall(16_000.0), seed = 1),
            Signals.noise(Signals.pink, seed = 2),
            Signals.noise(Signals.pink, seed = 3),
        )
        val verdict = LosslessAuthenticity.analyse(excerpts)
        assertEquals(Inconclusive, verdict.kind)
        assertEquals(16_000.0, assertNotNull(verdict.excerpts[0].cutoff).hz, 150.0)
    }

    @Test
    fun `two excerpts of three agreeing is enough`() {
        val excerpts = listOf(
            Signals.noise(Signals.brickwall(16_000.0), seed = 1),
            Signals.noise(Signals.brickwall(16_000.0), seed = 2),
            Signals.silence(),
        )
        val verdict = LosslessAuthenticity.analyse(excerpts)
        assertEquals(LossyTranscode, verdict.kind)
        assertEquals(0, verdict.excerpts[2].windows)
    }

    @Test
    fun `different low-passes do not agree`() {
        val excerpts = listOf(14_000.0, 16_500.0, 19_000.0).mapIndexed { i, hz -> Signals.noise(Signals.brickwall(hz), seed = i + 1) }
        assertEquals(Inconclusive, LosslessAuthenticity.analyse(excerpts).kind)
    }

    @Test
    fun `silence is inconclusive`() {
        val verdict = LosslessAuthenticity.analyse(listOf(Signals.silence(), Signals.silence()))
        assertEquals(Inconclusive, verdict.kind)
        assertNull(verdict.effectiveBits)
    }

    @Test
    fun `excerpts of different formats are refused`() {
        assertFailsWith<IllegalArgumentException> {
            LosslessAuthenticity.analyse(listOf(Signals.silence(sampleRate = 44_100), Signals.silence(sampleRate = 48_000)))
        }
    }
}
