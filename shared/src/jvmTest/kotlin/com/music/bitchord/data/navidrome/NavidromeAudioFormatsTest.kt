package com.music.bitchord.data.navidrome

import com.music.bitchord.data.model.AudioFormat
import com.music.bitchord.data.model.AudioQuality
import com.music.bitchord.data.model.AudioQualityTier
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NavidromeAudioFormatsTest {
    @AfterTest
    fun forget() = NavidromeAudioFormats.clearForTest()

    private fun songResponse(fields: String) =
        """{"subsonic-response":{"status":"ok","version":"1.16.1","type":"navidrome","openSubsonic":true,
           "song":{"id":"s1","title":"RÉEL","album":"IN INFINITUM",$fields}}}"""

    private fun read(fields: String): NavidromeClient.SongRow? {
        val server = MockWebServer()
        try {
            server.enqueue(MockResponse().setBody(songResponse(fields)))
            server.start()
            val client = NavidromeClient(server.url("/").toString(), "me", "not-a-real-password", "abc")
            return runBlocking { client.song("s1") }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun getSongReadsSuffixDepthRateAndBitrate() {
        val row = read(""""suffix":"flac","bitDepth":24,"samplingRate":96000,"bitRate":2834""")!!
        assertEquals("flac", row.suffix)
        assertEquals(24, row.bitDepth)
        assertEquals(96_000, row.samplingRate)
        assertEquals(2_834, row.bitRate)
    }

    @Test
    fun aRowWithoutABitrateStillReads() {
        val row = read(""""suffix":"mp3"""")!!
        assertNull(row.bitRate)
        assertNull(row.bitDepth)
    }

    @Test
    fun aRememberedRowGivesItsSongABadge() {
        val row = read(""""suffix":"flac","bitDepth":16,"samplingRate":44100,"bitRate":950""")!!
        NavidromeAudioFormats.remember(row)
        val song = Song(videoId = NavidromeSettings.ID_PREFIX + "s1", title = "RÉEL", artist = "SDM", thumbnailUrl = null)

        assertEquals(AudioFormat("flac", 16, 44_100, 950), NavidromeAudioFormats.cached(song))
        val quality = NavidromeAudioFormats.quality(song)!!
        assertEquals(AudioQualityTier.LOSSLESS, quality.tier)
        assertEquals("FLAC · 16 bits · 44.1 kHz", AudioQuality.detail(quality))
    }

    @Test
    fun songsThatAreNotNavidromesHaveNoBadge() {
        val row = read(""""suffix":"flac","bitDepth":24,"samplingRate":96000""")!!
        NavidromeAudioFormats.remember(row)
        // The same id, but a YouTube song: never mistaken for the server's file.
        assertNull(NavidromeAudioFormats.quality(Song(videoId = "s1", title = "x", artist = "y", thumbnailUrl = null)))
        assertNull(NavidromeAudioFormats.quality(Song(videoId = NavidromeSettings.ID_PREFIX + "other", title = "x", artist = "y", thumbnailUrl = null)))
    }
}
