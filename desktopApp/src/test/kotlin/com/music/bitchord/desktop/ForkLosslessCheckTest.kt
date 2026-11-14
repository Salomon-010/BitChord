package com.music.bitchord.desktop

import com.music.bitchord.audio.authenticity.AuthenticityKind
import com.music.bitchord.audio.authenticity.AuthenticityVerdict
import com.music.bitchord.audio.authenticity.LossySource
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The excerpts come out of FFmpeg exactly as the file holds them. Each WAV's samples count the
 * frames (left = n, right = −n, shifted for a padded file), so an excerpt shows where it starts
 * and that not one sample was changed on the way.
 */
class ForkLosslessCheckTest {

    private val scratch = mutableListOf<File>()

    @AfterTest
    fun cleanUp() = scratch.forEach { it.deleteRecursively() }

    @Test
    fun `24-bit samples arrive untouched, at the file's own rate`() {
        val read = assertNotNull(ForkLosslessCheck.readExcerpts(wav(rate = 48_000, bits = 24).absolutePath))
        assertEquals(3, read.excerpts.size)
        read.excerpts.forEach { excerpt ->
            assertEquals(48_000, excerpt.sampleRate)
            assertEquals(24, excerpt.bitsPerSample)
            assertEquals(2, excerpt.channels)
            assertEquals(3 * 48_000, excerpt.frames)
            assertCounts(excerpt.samples, channels = 2, shift = 0)
        }
        assertStartsNear(read, 48_000, seconds = 12.0)
    }

    @Test
    fun `a 16-bit master stored as 24 bits keeps its zero low byte`() {
        val read = assertNotNull(ForkLosslessCheck.readExcerpts(wav(rate = 44_100, bits = 24, shift = 8).absolutePath))
        read.excerpts.forEach { assertCounts(it.samples, channels = 2, shift = 8, bits = 24) }
        val verdict = com.music.bitchord.audio.authenticity.LosslessAuthenticity.analyse(read.excerpts)
        assertEquals(24, verdict.declaredBits)
        assertEquals(16, verdict.effectiveBits)
    }

    @Test
    fun `16-bit samples arrive untouched`() {
        val read = assertNotNull(ForkLosslessCheck.readExcerpts(wav(rate = 44_100, bits = 16).absolutePath))
        read.excerpts.forEach {
            assertEquals(16, it.bitsPerSample)
            assertCounts(it.samples, channels = 2, shift = 0, bits = 16)
        }
    }

    @Test
    fun `a mono file is read as mono`() {
        val read = assertNotNull(ForkLosslessCheck.readExcerpts(wav(rate = 44_100, bits = 24, channels = 1).absolutePath))
        read.excerpts.forEach {
            assertEquals(1, it.channels)
            assertCounts(it.samples, channels = 1, shift = 0)
        }
    }

    @Test
    fun `float PCM and a file shorter than an excerpt are not read`() {
        assertNull(ForkLosslessCheck.readExcerpts(wav(rate = 44_100, bits = 32, float = true).absolutePath))
        assertNull(ForkLosslessCheck.readExcerpts(wav(rate = 44_100, bits = 24, seconds = 2.0).absolutePath))
    }

    @Test
    fun `a verdict is kept per id and file size, and read back from disk`() {
        val dir = Files.createTempDirectory("lossless-cache").toFile().also { scratch += it }
        val path = dir.toPath().resolve("nested").resolve("verdicts.json")
        val verdict = AuthenticityVerdict(
            kind = AuthenticityKind.LossyTranscode, confidence = 0.9, cutoffHz = 16_763.6, lossySource = LossySource.Mp3,
            estimatedKbps = 128, declaredBits = 24, effectiveBits = 16, sampleRate = 44_100, excerpts = emptyList(),
        )
        ForkLosslessCache(path).put("song-1", 31_000_000L, verdict)

        val again = ForkLosslessCache(path)
        assertEquals(verdict, again.get("song-1", 31_000_000L))
        assertNull(again.get("song-1", 31_000_001L), "a replaced file is measured again")
        assertNull(again.get("song-2", 31_000_000L))
    }

    @Test
    fun `a verdict from another version of the maths is not used`() {
        val dir = Files.createTempDirectory("lossless-cache").toFile().also { scratch += it }
        val file = File(dir, "verdicts.json")
        file.writeText("""{"song-1":{"sizeBytes":5,"version":-1,"kind":"Genuine","confidence":1.0,"declaredBits":16,"sampleRate":44100}}""")
        assertNull(ForkLosslessCache(file.toPath()).get("song-1", 5))
    }

    @Test
    fun `an unreadable cache file is an empty cache`() {
        val dir = Files.createTempDirectory("lossless-cache").toFile().also { scratch += it }
        val file = File(dir, "verdicts.json").apply { writeText("not json") }
        assertNull(ForkLosslessCache(file.toPath()).get("song-1", 5))
    }

    /** Left counts up by one per frame (wrapping as [wav] wraps it), right is its negative, each shifted left by [shift]. */
    private fun assertCounts(samples: IntArray, channels: Int, shift: Int, bits: Int = 24) {
        val wrap = wrapFor(bits, shift)
        val first = samples[0] shr shift
        for (frame in 0 until samples.size / channels) {
            val count = (first + frame) % wrap
            assertEquals(count shl shift, samples[frame * channels], "left at $frame")
            if (channels == 2) assertEquals(-count shl shift, samples[frame * channels + 1], "right at $frame")
        }
    }

    private fun wrapFor(bits: Int, shift: Int) = if (bits - shift <= 16) 30_000 else Int.MAX_VALUE

    /** Each excerpt starts within a few frames of its position in a [seconds] file. */
    private fun assertStartsNear(read: ForkLosslessCheck.Excerpts, rate: Int, seconds: Double) {
        read.excerpts.zip(ForkLosslessCheck.POSITIONS).forEach { (excerpt, position) ->
            val expected = (seconds * position * rate).toInt()
            val actual = excerpt.samples[0]
            assertTrue(abs(actual - expected) <= 2, "excerpt at $position starts at frame $actual, expected $expected")
        }
    }

    /** A WAV whose left sample is the frame number (wrapped for 16 bits) and right its negative. */
    private fun wav(rate: Int, bits: Int, channels: Int = 2, shift: Int = 0, seconds: Double = 12.0, float: Boolean = false): File {
        val frames = (seconds * rate).toInt()
        val width = bits / 8
        val data = ByteBuffer.allocate(frames * channels * width).order(ByteOrder.LITTLE_ENDIAN)
        val wrap = wrapFor(bits, shift)
        for (frame in 0 until frames) {
            val count = frame % wrap
            for (channel in 0 until channels) {
                val value = (if (channel == 0) count else -count) shl shift
                when {
                    float -> data.putFloat(value / 8_388_608f)
                    width == 2 -> data.putShort(value.toShort())
                    width == 3 -> data.put((value and 0xFF).toByte()).put((value shr 8 and 0xFF).toByte()).put((value shr 16).toByte())
                    else -> data.putInt(value)
                }
            }
        }
        val body = data.array()
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + body.size); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(if (float) 3 else 1); putShort(channels.toShort())
            putInt(rate); putInt(rate * channels * width); putShort((channels * width).toShort()); putShort(bits.toShort())
            put("data".toByteArray()); putInt(body.size)
        }
        return File.createTempFile("lossless", ".wav").also {
            it.writeBytes(header.array() + body)
            scratch += it
        }
    }
}
