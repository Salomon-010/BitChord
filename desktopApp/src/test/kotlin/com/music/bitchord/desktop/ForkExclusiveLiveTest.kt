package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Manual, against the real device and the real native code: Windows' default output is asked what it
 * takes in exclusive mode, the best format for a 44.1 kHz source is opened, half a second of digital
 * silence is written and must be played, then the device is let go. Silent; while it runs (about a
 * second) no other app can play on that device. Skipped unless `BITCHORD_LIVE_EXCLUSIVE=1`:
 *
 *   BITCHORD_LIVE_EXCLUSIVE=1 ./gradlew :desktopApp:test --tests '*ForkExclusiveLiveTest*' --rerun -i
 */
class ForkExclusiveLiveTest {

    @Test
    fun `the default device in exclusive mode`() {
        if (System.getenv("BITCHORD_LIVE_EXCLUSIVE") != "1") {
            println("exclusive live test skipped: set BITCHORD_LIVE_EXCLUSIVE=1")
            return
        }
        assertTrue(ForkWasapiExclusive.available, "bitchord_window.dll did not load")
        val output = ForkExclusiveOutput(ForkWasapiExclusive)
        val accepted = output.accepted("", 2)
        println("device takes in exclusive mode: ${accepted.joinToString { it.label }.ifBlank { "nothing (${ForkWasapiExclusive.lastError()})" }}")
        for (source in listOf(44_100, 48_000, 96_000)) {
            println("  a $source Hz source would play at ${ForkExclusiveChoice.order(source, accepted).firstOrNull()?.label ?: "shared mode"}")
        }
        val opened = output.open("", 44_100, 2)
        println("opened: ${opened?.label ?: "nothing: ${ForkWasapiExclusive.lastError()}"} on ${output.deviceName()}, buffer ${output.bufferBytes} bytes")
        assertNotNull(opened)
        try {
            val frames = opened.rate / 2
            val silence = FloatArray(frames * 2)
            var written = 0
            val started = System.nanoTime()
            while (written < frames) {
                val taken = output.write(silence, (frames - written) * 2, 1f)
                assertTrue(taken >= 0, "write failed: ${ForkWasapiExclusive.lastError()}")
                written += taken
            }
            output.drain()
            val played = output.framesPlayed()
            println("wrote $written frames, device played $played, in ${(System.nanoTime() - started) / 1_000_000} ms")
            assertTrue(played >= frames * 9 / 10, "the device played $played of $frames frames")
        } finally {
            output.close()
            println("closed")
        }
    }
}
