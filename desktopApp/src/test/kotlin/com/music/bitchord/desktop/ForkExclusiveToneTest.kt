package com.music.bitchord.desktop

import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Manual, by ear, on the real device: a 1 kHz sine at −30 dBFS, 2 s, in each format Windows' default
 * output takes in exclusive mode (24-bit packed, 24-in-32, 16-bit), at 96 kHz then 48 kHz, then once
 * more a 44.1 kHz sine through the app's rate converter played at 96 kHz 24-bit (or the device's
 * best). Each tone has 0.5 s of silence either side and 3 s of nothing after, so they can be counted.
 * Skipped unless `BITCHORD_LIVE_TONE=1`; starts 5 s after launch:
 *
 *   BITCHORD_LIVE_TONE=1 ./gradlew :desktopApp:test --tests '*ForkExclusiveToneTest*' --rerun -i
 *
 * Also checks, for each format, the WAVEFORMATEXTENSIBLE the native side builds (fork_exclusive_audio.h
 * `fork_ex_format`: nBlockAlign = channels × container / 8, wValidBitsPerSample, dwChannelMask 0x3) as
 * the packer must match it, and the little-endian order of a known sample.
 */
class ForkExclusiveToneTest {

    private val level = 0.0316f // −30 dBFS
    private val toneHz = 1_000.0

    @Test
    fun `a quiet sine in every exclusive format, by ear`() {
        if (System.getenv("BITCHORD_LIVE_TONE") != "1") {
            println("exclusive tone test skipped: set BITCHORD_LIVE_TONE=1")
            return
        }
        assertTrue(ForkWasapiExclusive.available, "bitchord_window.dll did not load")
        val accepted = ForkExclusiveOutput(ForkWasapiExclusive).accepted("", 2)
        println("device takes: ${accepted.joinToString { it.label }}")

        val cases = buildList {
            for (rate in listOf(96_000, 48_000)) {
                for ((container, valid) in listOf(24 to 24, 32 to 24, 16 to 16)) {
                    val format = ForkExclusiveFormat(rate, container, valid)
                    if (format in accepted) add(format to false) else println("not accepted, skipped: ${format.label}")
                }
            }
            val best = ForkExclusiveChoice.order(44_100, accepted).firstOrNull()
            if (best != null) add(best to true)
        }
        cases.forEachIndexed { index, (format, converted) ->
            println("tone ${index + 1}: ${format.label}${if (converted) " (44.1 kHz sine through DesktopRateConverter)" else ""}")
        }
        Thread.sleep(5_000)

        cases.forEachIndexed { index, (format, converted) -> play(index + 1, format, converted) }
        println("done")
    }

    private fun play(number: Int, format: ForkExclusiveFormat, converted: Boolean) {
        // The format the native side will declare, as fork_ex_format builds it.
        val channels = 2
        val blockAlign = channels * format.containerBits / 8
        println(
            "tone $number: ${format.label}${if (converted) " via the converter" else ""} — " +
                "nChannels=2 nSamplesPerSec=${format.rate} wBitsPerSample=${format.containerBits} " +
                "wValidBitsPerSample=${format.validBits} nBlockAlign=$blockAlign " +
                "nAvgBytesPerSec=${format.rate * blockAlign} dwChannelMask=0x3",
        )
        assertEquals(blockAlign, channels * format.bytesPerSample, "the packer's frame is nBlockAlign bytes")

        // Byte order: one known frame, left 0x123456 (24-bit) / 0x1234 (16-bit), right its negative.
        val known = if (format.validBits == 16) 0x1234 / 32_768f else 0x123456 / 8_388_608f
        val probe = ByteArray(blockAlign)
        ForkExclusivePacker(format).pack(floatArrayOf(known, -known), 2, 1f, probe)
        println("  known frame bytes: ${probe.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }}")
        val expected = when {
            format.containerBits == 32 -> listOf(0x00, 0x56, 0x34, 0x12, 0x00, 0xAA, 0xCB, 0xED)
            format.containerBits == 24 -> listOf(0x56, 0x34, 0x12, 0xAA, 0xCB, 0xED)
            else -> listOf(0x34, 0x12, 0xCC, 0xED)
        }
        assertEquals(expected, probe.map { it.toInt() and 0xFF }, "little-endian, two's complement")

        val opened = ForkWasapiExclusive.open("", channels, format)
        if (opened <= 0) {
            println("  refused at open: ${ForkWasapiExclusive.lastError()}")
            return
        }
        try {
            val samples = signal(format.rate, converted)
            val packer = ForkExclusivePacker(format)
            val frames = samples.size / channels
            val bytes = ByteArray(samples.size * format.bytesPerSample)
            packer.pack(samples, samples.size, 1f, bytes)
            var at = 0
            while (at < frames) {
                val chunk = min(4_800, frames - at)
                val slice = bytes.copyOfRange(at * blockAlign, (at + chunk) * blockAlign)
                var sent = 0
                while (sent < chunk) {
                    val taken = ForkWasapiExclusive.write(slice.copyOfRange(sent * blockAlign, chunk * blockAlign), chunk - sent)
                    assertTrue(taken >= 0, "write failed: ${ForkWasapiExclusive.lastError()}")
                    sent += taken
                }
                at += chunk
            }
            ForkWasapiExclusive.drain()
            println("  wrote $frames frames, device played ${ForkWasapiExclusive.framesPlayed()}")
        } finally {
            ForkWasapiExclusive.close()
        }
        Thread.sleep(3_000)
    }

    /** 0.5 s silence, 2 s of the sine with 20 ms fades, 0.5 s silence, stereo, at [rate]. */
    private fun signal(rate: Int, converted: Boolean): FloatArray {
        val sourceRate = if (converted) 44_100 else rate
        val pad = sourceRate / 2
        val toneFrames = sourceRate * 2
        val fade = sourceRate / 50
        val frames = pad + toneFrames + pad
        val out = FloatArray(frames * 2)
        for (i in 0 until toneFrames) {
            val envelope = min(1f, min(i, toneFrames - 1 - i).toFloat() / fade)
            val value = (level * envelope * sin(2 * PI * toneHz * i / sourceRate)).toFloat()
            out[(pad + i) * 2] = value
            out[(pad + i) * 2 + 1] = value
        }
        if (!converted) return out
        val converter = DesktopRateConverter(44_100, rate, 2)
        val result = converter.process(out, out.size)
        return result.copyOf(converter.outputCount - converter.outputCount % 2)
    }
}
