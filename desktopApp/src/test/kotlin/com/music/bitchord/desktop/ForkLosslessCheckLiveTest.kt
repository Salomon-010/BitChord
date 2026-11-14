package com.music.bitchord.desktop

import com.music.bitchord.data.navidrome.NavidromeRepository
import com.music.bitchord.data.navidrome.NavidromeSettings
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import java.nio.file.Files
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Manual: the first song of every album on the Navidrome server the PC app is logged in to,
 * checked the way [ForkLosslessCheck] checks it, with the verdict, cut-off, bits and time printed.
 * Skipped unless `BITCHORD_LIVE_NAVIDROME=1`:
 *
 *   BITCHORD_LIVE_NAVIDROME=1 ./gradlew :desktopApp:test --tests '*ForkLosslessCheckLiveTest*' -i
 *
 * Verdicts go to a temporary cache, not the app's; each song is checked twice to show the second
 * comes from it. Prints no address (they carry the login).
 */
class ForkLosslessCheckLiveTest {

    @Test
    fun `one song of each album on the saved server`() {
        if (System.getenv("BITCHORD_LIVE_NAVIDROME") != "1") {
            println("lossless live check skipped: set BITCHORD_LIVE_NAVIDROME=1")
            return
        }
        ForkNavidrome.installLogin()
        assertTrue(NavidromeSettings.isConfigured(), "no Navidrome login saved in the PC app")
        val client = NavidromeRepository.client()
        val cacheDir = Files.createTempDirectory("lossless-live")
        val store = ForkLosslessCache(cacheDir.resolve("verdicts.json"))
        try {
            val albums = runBlocking { client.albums("alphabeticalByName", 50) }
            println("albums on the server: ${albums.size}")
            for (album in albums) {
                val song = runBlocking { client.album(album.id) }.second.firstOrNull() ?: continue
                println()
                println("${album.artist} - ${album.name} / ${song.track ?: "?"}. ${song.title}")
                println("  server: ${song.suffix}, ${song.bitDepth ?: "?"} bits, ${song.samplingRate ?: "?"} Hz, ${song.durationSec ?: "?"} s, ${song.sizeBytes?.let { "%.1f MB".format(Locale.ROOT, it / 1e6) } ?: "?"}")
                println("  partial reads: ${rangeAnswer(client.streamUrl(song.id))}")
                val wall = System.nanoTime()
                val outcome = ForkLosslessCheck.check(song.id, song.sizeBytes, store)
                val wallMs = (System.nanoTime() - wall) / 1_000_000
                if (outcome == null) {
                    println("  not checked (not a lossless file, or unreadable)")
                    continue
                }
                val v = outcome.verdict
                println("  verdict: ${v.kind}  confidence ${"%.2f".format(Locale.ROOT, v.confidence)}")
                println("  cut-off: ${v.cutoffHz?.let { "%.2f kHz".format(Locale.ROOT, it / 1000) } ?: "none"}  source: ${v.lossySource ?: "-"}  estimated: ${v.estimatedKbps?.let { "$it kbps" } ?: "-"}")
                println("  bits: ${v.effectiveBits ?: "?"} used of ${v.declaredBits}  at ${v.sampleRate} Hz")
                v.excerpts.forEach { e ->
                    println("    excerpt at ${"%.1f".format(Locale.ROOT, e.startSeconds)} s: ${e.windows} windows, cut-off ${e.cutoff?.let { "%.2f kHz, step %.0f dB, shoulder %.0f Hz".format(Locale.ROOT, it.hz / 1000, it.stepDb, it.shoulderHz) } ?: "none"}")
                }
                println("  time: ${wallMs} ms in all (open + fetch + decode ${outcome.decodeMs} ms, analysis ${outcome.analyseMs} ms)")
                val again = System.nanoTime()
                val second = ForkLosslessCheck.check(song.id, song.sizeBytes, store)
                println("  again: from cache = ${second?.cached}, ${(System.nanoTime() - again) / 1_000} µs")
            }
        } finally {
            cacheDir.toFile().deleteRecursively()
        }
    }

    /** Whether the server answers a byte range of the original file with 206, as a seek needs. */
    private fun rangeAnswer(url: String): String = runCatching {
        val request = Request.Builder().url(NavidromeRepository.withLogin(url)).header("Range", "bytes=0-1023").build()
        OkHttpClient().newCall(request).execute().use { response ->
            "${response.code}${if (response.code == 206) " (yes)" else " (no: the whole file would be read)"}, ${response.header("Content-Range") ?: "no Content-Range"}"
        }
    }.getOrElse { "could not ask (${it.javaClass.simpleName})" }
}
