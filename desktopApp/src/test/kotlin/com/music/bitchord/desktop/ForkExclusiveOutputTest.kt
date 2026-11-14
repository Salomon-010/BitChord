package com.music.bitchord.desktop

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** WASAPI exclusive mode without a device: the choice, the bytes, the open and the fall-back. */
class ForkExclusiveOutputTest {

    private val scratch = mutableListOf<File>()

    @AfterTest
    fun cleanUp() = scratch.forEach { it.delete() }

    private fun formats(rates: List<Int>, vararg containers: Pair<Int, Int>) =
        rates.flatMap { rate -> containers.map { (c, v) -> ForkExclusiveFormat(rate, c, v) } }

    // ---- the choice -----------------------------------------------------------------------

    /** The owner's headset as they describe it: 48 and 96 kHz, 16 or 24 bits (assumed packed here). */
    private val headset = formats(listOf(48_000, 96_000), 24 to 24, 16 to 16)

    @Test
    fun `a 44_1 kHz file on a 48 and 96 kHz device goes to 96 kHz 24-bit`() {
        assertEquals(96_000, ForkExclusiveChoice.rateFor(44_100, headset))
        val order = ForkExclusiveChoice.order(44_100, headset)
        assertEquals(ForkExclusiveFormat(96_000, 24, 24), order.first())
        assertEquals(
            listOf(ForkExclusiveFormat(96_000, 24, 24), ForkExclusiveFormat(96_000, 16, 16), ForkExclusiveFormat(48_000, 24, 24), ForkExclusiveFormat(48_000, 16, 16)),
            order,
        )
    }

    @Test
    fun `a device that takes the file's own rate gets it`() {
        val dac = formats(ForkExclusiveChoice.RATES, 32 to 24, 32 to 32)
        assertEquals(44_100, ForkExclusiveChoice.rateFor(44_100, dac))
        assertEquals(ForkExclusiveFormat(44_100, 32, 24), ForkExclusiveChoice.order(44_100, dac).first())
        assertEquals(ForkExclusiveFormat(96_000, 32, 24), ForkExclusiveChoice.order(96_000, dac).first())
    }

    @Test
    fun `without its own rate, the highest whole multiple of it`() {
        val device = formats(listOf(48_000, 88_200, 96_000, 176_400), 32 to 24)
        assertEquals(176_400, ForkExclusiveChoice.rateFor(44_100, device))
        assertEquals(96_000, ForkExclusiveChoice.rateFor(48_000, formats(listOf(44_100, 96_000), 32 to 24)))
    }

    @Test
    fun `without a multiple, the highest rate the device takes`() {
        assertEquals(48_000, ForkExclusiveChoice.rateFor(96_000, formats(listOf(44_100, 48_000), 16 to 16)))
        assertEquals(96_000, ForkExclusiveChoice.rateFor(88_200, headset))
    }

    @Test
    fun `containers in order, 24-in-32 first and 16-bit last`() {
        val device = formats(listOf(48_000), 16 to 16, 24 to 24, 32 to 32, 32 to 24)
        assertEquals(
            listOf(32 to 24, 32 to 32, 24 to 24, 16 to 16),
            ForkExclusiveChoice.order(48_000, device).map { it.containerBits to it.validBits },
        )
    }

    @Test
    fun `a device that takes nothing gives nothing to try`() {
        assertNull(ForkExclusiveChoice.rateFor(44_100, emptyList()))
        assertTrue(ForkExclusiveChoice.order(44_100, emptyList()).isEmpty())
    }

    @Test
    fun `every rate and container is asked about`() {
        assertEquals(8 * 4, ForkExclusiveChoice.candidates().size)
        assertTrue(ForkExclusiveFormat(352_800, 32, 24) in ForkExclusiveChoice.candidates())
    }

    // ---- the bytes ------------------------------------------------------------------------

    private fun packed(format: ForkExclusiveFormat, vararg samples: Float): List<Int> {
        val out = ByteArray(samples.size * format.bytesPerSample)
        ForkExclusivePacker(format).pack(samples, samples.size, 1f, out)
        return out.map { it.toInt() and 0xFF }
    }

    private val step24 = 1f / 8_388_608f

    @Test
    fun `24-in-32 carries the 24 bits in the top three bytes, little-endian`() {
        assertEquals(listOf(0x00, 0x56, 0x34, 0x12), packed(ForkExclusiveFormat(48_000, 32, 24), 0x123456 * step24))
        assertEquals(listOf(0x00, 0xFF, 0xFF, 0xFF), packed(ForkExclusiveFormat(48_000, 32, 24), -step24))
        assertEquals(listOf(0x00, 0xFF, 0xFF, 0x7F), packed(ForkExclusiveFormat(48_000, 32, 24), 1f), "full scale clamped")
        assertEquals(listOf(0x00, 0x00, 0x00, 0x80), packed(ForkExclusiveFormat(48_000, 32, 24), -1f))
    }

    @Test
    fun `a 32-bit container gets the same 24 bits, left-justified`() {
        assertEquals(listOf(0x00, 0x56, 0x34, 0x12), packed(ForkExclusiveFormat(48_000, 32, 32), 0x123456 * step24))
    }

    @Test
    fun `packed 24-bit is three bytes a sample`() {
        assertEquals(listOf(0x56, 0x34, 0x12, 0xFE, 0xFF, 0xFF), packed(ForkExclusiveFormat(96_000, 24, 24), 0x123456 * step24, -2 * step24))
    }

    @Test
    fun `16-bit is two bytes a sample`() {
        assertEquals(listOf(0x34, 0x12), packed(ForkExclusiveFormat(48_000, 16, 16), 0x1234 / 32_768f))
    }

    @Test
    fun `a level finer than 24 bits survives on average`() {
        val packer = ForkExclusivePacker(ForkExclusiveFormat(96_000, 24, 24))
        val n = 100_000
        val samples = FloatArray(n) { 0.3f * step24 }
        val out = ByteArray(n * 3)
        packer.pack(samples, n, 1f, out)
        val values = IntArray(n) { (out[it * 3].toInt() and 0xFF) or ((out[it * 3 + 1].toInt() and 0xFF) shl 8) or (out[it * 3 + 2].toInt() shl 16) }
        assertTrue(values.all { it in -1..2 })
        assertEquals(0.3, values.average(), 0.01)
    }

    /** A 24-bit file decoded as the engine decodes it (FFmpeg, float), packed both ways: the file's own samples. */
    @Test
    fun `a 24-bit file goes out bit for bit in 24-in-32 and packed 24`() {
        val random = Random(5)
        val original = IntArray(48_000 * 2) { random.nextInt(-8_388_608, 8_388_608) }.also {
            it[0] = -8_388_608; it[1] = 8_388_607; it[2] = 0; it[3] = 1; it[4] = -1
        }
        val decoded = decode(wav24(original, rate = 48_000))
        assertEquals(original.size, decoded.size)

        val in32 = ByteArray(decoded.size * 4)
        ForkExclusivePacker(ForkExclusiveFormat(48_000, 32, 24)).pack(decoded, decoded.size, 1f, in32)
        val from32 = IntArray(decoded.size) { ByteBuffer.wrap(in32, it * 4, 4).order(ByteOrder.LITTLE_ENDIAN).int shr 8 }
        assertContentEquals(original, from32)
        assertTrue((0 until decoded.size).all { in32[it * 4] == 0.toByte() }, "the low byte of 24-in-32 is zero")

        val packed = ByteArray(decoded.size * 3)
        ForkExclusivePacker(ForkExclusiveFormat(48_000, 24, 24)).pack(decoded, decoded.size, 1f, packed)
        assertContentEquals(pcm24(original), packed)
    }

    // ---- the open, the writes, the fall-back -----------------------------------------------

    private class FakeDevice(val takes: List<ForkExclusiveFormat>, val refusesAtOpen: Set<ForkExclusiveFormat> = emptySet()) : ForkExclusiveBackend {
        override var available = true
        var probes = 0
        var answerProbe = true
        val opened = mutableListOf<ForkExclusiveFormat>()
        val written = mutableListOf<Pair<ByteArray, Int>>()
        var failWrites = false
        var closes = 0
        override fun probe(device: String, channels: Int, candidates: List<ForkExclusiveFormat>): BooleanArray? {
            probes++
            return if (!answerProbe) null else BooleanArray(candidates.size) { candidates[it] in takes }
        }
        override fun open(device: String, channels: Int, format: ForkExclusiveFormat): Int {
            opened += format
            return if (format in refusesAtOpen) 0 else 19_200
        }
        override fun write(bytes: ByteArray, frames: Int): Int {
            if (failWrites) return -1
            written += bytes.copyOf() to frames
            return frames
        }
        override fun framesPlayed() = 0L
        override fun pause(paused: Boolean) {}
        override fun flush() {}
        override fun drain() {}
        override fun close() { closes++ }
        override fun deviceName() = "Fake DAC"
        override fun lastError() = "refused"
    }

    @Test
    fun `the device is asked once, and its best format opened`() {
        val device = FakeDevice(headset)
        val output = ForkExclusiveOutput(device)
        assertEquals(ForkExclusiveFormat(96_000, 24, 24), output.open("dac", 44_100, 2))
        assertEquals(96_000, output.rateFor("dac", 44_100, 2))
        assertEquals(48_000, output.rateFor("dac", 48_000, 2), "48 kHz is the device's own")
        assertEquals(1, device.probes)
    }

    @Test
    fun `a format refused at open is passed over for the next`() {
        val device = FakeDevice(headset, refusesAtOpen = setOf(ForkExclusiveFormat(96_000, 24, 24)))
        val output = ForkExclusiveOutput(device)
        assertEquals(ForkExclusiveFormat(96_000, 16, 16), output.open("dac", 44_100, 2))
        assertEquals(listOf(ForkExclusiveFormat(96_000, 24, 24), ForkExclusiveFormat(96_000, 16, 16)), device.opened)
    }

    @Test
    fun `a device that refuses everything opens nothing, so shared mode takes over`() {
        val device = FakeDevice(headset, refusesAtOpen = headset.toSet())
        assertNull(ForkExclusiveOutput(device).open("dac", 44_100, 2))
        assertNull(ForkExclusiveOutput(FakeDevice(emptyList())).open("dac", 44_100, 2))
    }

    @Test
    fun `a device that can't be asked is asked again next time`() {
        val device = FakeDevice(headset).apply { answerProbe = false }
        val output = ForkExclusiveOutput(device)
        assertNull(output.open("dac", 44_100, 2))
        device.answerProbe = true
        assertNotNull(output.open("dac", 44_100, 2))
        assertEquals(2, device.probes)
    }

    @Test
    fun `what the engine writes reaches the device as whole frames of packed bytes`() {
        val device = FakeDevice(headset)
        val output = ForkExclusiveOutput(device)
        output.open("dac", 44_100, 2)
        val frames = output.write(floatArrayOf(0x123456 * step24, -step24, 0f, 0f, 0.5f), 5, 1f)
        assertEquals(2, frames, "the odd sample is not half a frame")
        val (bytes, sent) = device.written.single()
        assertEquals(2, sent)
        assertEquals(listOf(0x56, 0x34, 0x12, 0xFF, 0xFF, 0xFF, 0, 0, 0, 0, 0, 0), bytes.take(12).map { it.toInt() and 0xFF })
    }

    @Test
    fun `a failed write says so, and closing lets the device go`() {
        val device = FakeDevice(headset)
        val output = ForkExclusiveOutput(device)
        output.open("dac", 44_100, 2)
        device.failWrites = true
        assertEquals(-1, output.write(FloatArray(4), 4, 1f))
        output.close()
        assertEquals(1, device.closes)
        assertNull(output.format)
        assertEquals(-1, output.write(FloatArray(4), 4, 1f), "nothing to write to once closed")
    }

    // ---- files -------------------------------------------------------------------------------

    private fun decode(file: File): FloatArray {
        val decoder = DesktopAudioDecoder()
        decoder.open(file.absolutePath, requested = DesktopPcmFormat(48_000, 2, bytesPerSample = 4, isFloat = true)).getOrThrow()
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

    private fun pcm24(samples: IntArray) = ByteArray(samples.size * 3).also { out ->
        samples.forEachIndexed { i, v -> out[i * 3] = v.toByte(); out[i * 3 + 1] = (v shr 8).toByte(); out[i * 3 + 2] = (v shr 16).toByte() }
    }

    private fun wav24(samples: IntArray, rate: Int): File {
        val body = pcm24(samples)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + body.size); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(2)
            putInt(rate); putInt(rate * 6); putShort(6); putShort(24)
            put("data".toByteArray()); putInt(body.size)
        }
        return File.createTempFile("pcm24", ".wav").also {
            it.writeBytes(header.array() + body)
            scratch += it
        }
    }
}
