package com.music.bitchord.audio.authenticity

import com.music.bitchord.audio.authenticity.AuthenticityKind.Genuine
import com.music.bitchord.audio.authenticity.AuthenticityKind.LossyTranscode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The real encoders' output, made by `resources/authenticity/make-fixtures.sh`: one noise source,
 * left as it is or sent through LAME or FFmpeg's AAC, then stored as FLAC and read back as three
 * 0.5 s windows of 44.1 kHz stereo.
 */
class LosslessAuthenticityFixturesTest {

    private fun excerpts(name: String): List<PcmExcerpt> {
        val bytes = assertNotNull(javaClass.getResourceAsStream("/authenticity/$name"), name).use { it.readBytes() }
        val bits = if (name.endsWith(".s24le")) 24 else 16
        val width = bits / 8
        val samples = IntArray(bytes.size / width) { i ->
            val at = i * width
            if (width == 2) {
                (bytes[at].toInt() and 0xFF) or (bytes[at + 1].toInt() shl 8)
            } else {
                (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8) or (bytes[at + 2].toInt() shl 16)
            }
        }
        val perWindow = samples.size / 3
        return listOf(6.0, 15.0, 22.5).mapIndexed { i, at ->
            PcmExcerpt(samples.copyOfRange(i * perWindow, (i + 1) * perWindow), 2, 44_100, bits, at)
        }
    }

    private fun analyse(name: String) = LosslessAuthenticity.analyse(excerpts(name)).also { println("$name: $it") }

    @Test
    fun `the untouched source is genuine`() {
        val verdict = analyse("genuine-16.s16le")
        assertEquals(Genuine, verdict.kind)
        assertEquals(16, verdict.effectiveBits)
    }

    @Test
    fun `the untouched source at 24 bits is genuine and uses them`() {
        val verdict = analyse("genuine-24.s24le")
        assertEquals(Genuine, verdict.kind)
        assertEquals(24, verdict.effectiveBits)
    }

    @Test
    fun `a FLAC made from a 128 kbps MP3 is caught`() {
        val verdict = analyse("mp3-128.s16le")
        assertEquals(LossyTranscode, verdict.kind)
        assertEquals(LossySource.Mp3, verdict.lossySource)
        assertEquals(128, verdict.estimatedKbps)
        assertTrue(verdict.excerpts.all { it.cutoff != null }, "every excerpt shows it")
    }

    @Test
    fun `a FLAC made from a 320 kbps MP3 is caught`() {
        val verdict = analyse("mp3-320.s16le")
        assertEquals(LossyTranscode, verdict.kind)
        assertEquals(LossySource.Mp3, verdict.lossySource)
        assertEquals(320, verdict.estimatedKbps)
    }

    /** Its 17.3 kHz cut sits by LAME's 160 kbps one; only the shoulder tells them apart. */
    @Test
    fun `a FLAC made from a 128 kbps AAC is caught, as compressed with no bitrate`() {
        val verdict = analyse("aac-128.s16le")
        assertEquals(LossyTranscode, verdict.kind)
        assertEquals(LossySource.Compressed, verdict.lossySource)
        assertNull(verdict.estimatedKbps)
    }

    @Test
    fun `a 24-bit FLAC made from a 128 kbps MP3 says both`() {
        val verdict = analyse("mp3-128-24bit.s24le")
        assertEquals(LossyTranscode, verdict.kind)
        assertEquals(24, verdict.declaredBits)
        assertEquals(16, verdict.effectiveBits)
        assertEquals(LossySource.Mp3, verdict.lossySource)
        assertEquals(128, verdict.estimatedKbps)
    }
}
