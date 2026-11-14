package com.music.bitchord.data.navidrome

import com.music.bitchord.data.model.AudioFormat
import com.music.bitchord.data.model.AudioQuality
import com.music.bitchord.data.model.AudioQualityInfo
import com.music.bitchord.data.model.Song
import java.util.concurrent.ConcurrentHashMap

/**
 * Sigui: what each Navidrome file is (format, bit depth, sample rate, bitrate), for the quality badge.
 *
 * Kept as each row becomes a [Song] ([NavidromeRepository]'s `toSong`), the way
 * [NavidromeReplayGain] keeps its gain, because [Song] only carries "lossless or not". A song
 * this process hasn't listed (a restored queue, a handed-over one) is asked for with one
 * `getSong` by [load].
 */
object NavidromeAudioFormats {
    private const val MAX_KEPT = 20_000
    private val formats = ConcurrentHashMap<String, AudioFormat>()

    fun remember(row: NavidromeClient.SongRow) {
        // A whole library listed twice over is still small; past that, start again rather than grow.
        if (formats.size >= MAX_KEPT) formats.clear()
        formats[row.id] = row.audioFormat()
    }

    /** The format this process already knows for [song], or null (not Navidrome, or not seen yet). */
    fun cached(song: Song): AudioFormat? = serverId(song)?.let(formats::get)

    /** [cached], else one `getSong`; null for a song that isn't Navidrome's or a server that can't say. */
    suspend fun load(song: Song): AudioFormat? {
        val id = serverId(song) ?: return null
        formats[id]?.let { return it }
        val row = runCatching { NavidromeRepository.client().song(id) }
            .onFailure { if (it is kotlin.coroutines.cancellation.CancellationException) throw it }
            .getOrNull() ?: return null
        remember(row)
        return formats[id]
    }

    /** The badge for [song] from what is already known, without asking the server. */
    fun quality(song: Song): AudioQualityInfo? = cached(song)?.let(AudioQuality::classify)

    /** The badge for [song], asking the server when it has to. */
    suspend fun loadQuality(song: Song): AudioQualityInfo? = load(song)?.let(AudioQuality::classify)

    internal fun clearForTest() = formats.clear()

    private fun serverId(song: Song): String? =
        song.videoId.takeIf(NavidromeSettings::isNavidromeId)?.removePrefix(NavidromeSettings.ID_PREFIX)

    internal fun NavidromeClient.SongRow.audioFormat() = AudioFormat(
        suffix = suffix,
        bitDepth = bitDepth,
        sampleRateHz = samplingRate,
        bitRateKbps = bitRate,
    )
}
