package com.music.bitchord.desktop

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The 16-bit output path: a 16-bit file goes out untouched, anything finer is rounded with TPDF dither. */
class ForkPcm16Test {

    private val scratch = mutableListOf<File>()

    @AfterTest
    fun cleanUp() = scratch.forEach { it.delete() }

    /** Full-scale noise and the edges: both extremes, zero, ±1 step. */
    private val original: ShortArray by lazy {
        val random = Random(7)
        val edges = shortArrayOf(Short.MIN_VALUE, Short.MAX_VALUE, 0, 1, -1, 2, -2, 16_384, -16_384)
        ShortArray(44_100 * 2) { if (it < edges.size) edges[it] else random.nextInt(-32_768, 32_768).toShort() }
    }

    /** The file decoded the way the engine decodes it: FFmpeg, float, the deck's stereo. */
    private fun decodedAsTheEngineDoes(): FloatArray {
        val wav = wav16(original, channels = 2, rate = 44_100)
        val decoder = DesktopAudioDecoder()
        decoder.open(wav.absolutePath, requested = DesktopPcmFormat(44_100, 2, bytesPerSample = 4, isFloat = true)).getOrThrow()
        try {
            val out = ArrayList<Float>()
            while (true) {
                val block = decoder.readSamples() ?: break
                for (i in 0 until decoder.sampleCount) out += block[i]
            }
            return out.toFloatArray()
        } finally {
            decoder.close()
        }
    }

    @Test
    fun `a 16-bit file through the 16-bit output comes out sample for sample`() {
        val decoded = decodedAsTheEngineDoes()
        assertEquals(original.size, decoded.size)

        // Java Sound's line: the bytes written.
        val bytes = ByteArray(decoded.size * 2)
        ForkPcm16Quantizer().toBytes(decoded, decoded.size, 1f, bytes)
        val written = ShortArray(decoded.size) { ((bytes[it * 2].toInt() and 0xFF) or (bytes[it * 2 + 1].toInt() shl 8)).toShort() }
        assertContentEquals(original, written)

        // WASAPI's line: the floats handed to the native writer, which scales by 32768 and rounds.
        val grid = FloatArray(decoded.size)
        ForkPcm16Quantizer().toGrid(decoded, decoded.size, 1f, grid)
        val native = ShortArray(grid.size) { (grid[it] * 32_768f).toDouble().roundToLong().coerceIn(-32_768, 32_767).toShort() }
        assertContentEquals(original, native)
    }

    @Test
    fun `upstream's truncation moved every non-zero sample of a 16-bit file`() {
        // What was there before, kept as a reminder of why: (x * 32767) truncated.
        val decoded = decodedAsTheEngineDoes()
        val changed = decoded.indices.count { (decoded[it] * 32_767f).toInt() != original[it].toInt() }
        assertTrue(changed > original.size * 0.99, "$changed of ${original.size} changed")
    }

    /** A level of 0.3 step, constant: truncation writes 0 forever, dither averages back to 0.3. */
    @Test
    fun `a level finer than one step survives on average instead of vanishing`() {
        val quantizer = ForkPcm16Quantizer()
        val level = 0.3f / 32_768f
        val values = IntArray(200_000) { quantizer.quantize(level, 1f) }
        assertTrue(values.all { it in -1..2 }, "TPDF stays within ±1 step of the rounding: ${values.distinct()}")
        assertEquals(0.3, values.average(), 0.01)
    }

    @Test
    fun `the dither's error is white and of TPDF's size, whatever the signal`() {
        val quantizer = ForkPcm16Quantizer(seed = 3)
        val random = Random(11)
        var sum = 0.0
        var squares = 0.0
        var lagged = 0.0
        var previous = 0.0
        val n = 200_000
        repeat(n) {
            val exact = random.nextDouble(-20_000.0, 20_000.0)
            val error = quantizer.quantize((exact / 32_768).toFloat(), 1f) - (exact / 32_768).toFloat().toDouble() * 32_768
            assertTrue(abs(error) < 1.5, "error $error")
            sum += error
            squares += error * error
            lagged += error * previous
            previous = error
        }
        assertEquals(0.0, sum / n, 0.01, "no bias")
        // Rounding (1/12) plus TPDF (1/6): a quarter of a step squared.
        assertEquals(0.25, squares / n, 0.01)
        assertEquals(0.0, lagged / n, 0.01, "no correlation from one sample to the next")
    }

    @Test
    fun `the volume is rounded with dither, an even sample halved stays exact`() {
        val quantizer = ForkPcm16Quantizer()
        assertEquals(1_000, quantizer.quantize(2_000 / 32_768f, 0.5f))
        val odd = IntArray(10_000) { quantizer.quantize(2_001 / 32_768f, 0.5f) }
        assertTrue(odd.all { it in 999..1_002 })
        assertEquals(1_000.5, odd.average(), 0.05)
    }

    @Test
    fun `full scale is clamped and nothing breaks the output`() {
        val quantizer = ForkPcm16Quantizer()
        assertEquals(32_767, quantizer.quantize(1f, 1f))
        assertEquals(-32_768, quantizer.quantize(-1f, 1f))
        assertEquals(32_767, quantizer.quantize(3f, 1f))
        assertEquals(-32_768, quantizer.quantize(-3f, 1f))
        assertEquals(0, quantizer.quantize(Float.NaN, 1f))
        assertEquals(0, quantizer.quantize(0.5f, 0f))
    }

    @Test
    fun `the desktop starts on 32-bit float output`() {
        assertEquals("FLOAT_32", ForkAudioDefaults.OUTPUT_PRECISION)
    }

    private fun wav16(samples: ShortArray, channels: Int, rate: Int): File {
        val body = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply { samples.forEach(::putShort) }.array()
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + body.size); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(channels.toShort())
            putInt(rate); putInt(rate * channels * 2); putShort((channels * 2).toShort()); putShort(16)
            put("data".toByteArray()); putInt(body.size)
        }
        return File.createTempFile("pcm16", ".wav").also {
            it.writeBytes(header.array() + body)
            scratch += it
        }
    }
}
