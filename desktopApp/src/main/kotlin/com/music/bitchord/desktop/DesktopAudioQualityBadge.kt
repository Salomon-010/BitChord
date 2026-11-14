package com.music.bitchord.desktop

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.bitchord.data.model.AudioQuality
import com.music.bitchord.data.model.AudioQualityInfo
import com.music.bitchord.data.model.AudioQualityTier
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.navidrome.NavidromeAudioFormats
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * Sigui: the quality of a Navidrome file — "Hi-Res", "Lossless" or "Compressed" — and its detail
 * line ("FLAC · 24 bits · 96 kHz"), from what the server says of the file. The classing is
 * [AudioQuality] in `shared`; this is only how the PC draws it.
 *
 * Not the badge under the player's seek bar, which reads what is actually playing.
 */
internal object DesktopFileQuality {
    /** "Hi-Res" and "Lossless" stay in English in every language, as the app's other badges do. */
    fun label(info: AudioQualityInfo): String = when (info.tier) {
        AudioQualityTier.HI_RES -> "Hi-Res"
        AudioQualityTier.LOSSLESS -> "Lossless"
        AudioQualityTier.COMPRESSED -> DesktopStrings["d_quality_compressed", "Compressed"]
    }

    /** The detail line in the app's language: its decimal comma and its word for bits. */
    fun detail(info: AudioQualityInfo): String {
        val locale = runCatching { Locale.forLanguageTag(DesktopStrings.resolvedTag()) }.getOrDefault(Locale.getDefault())
        val separator = DecimalFormatSymbols.getInstance(locale).decimalSeparator
        return AudioQuality.detail(info, separator) { DesktopStrings.format("d_quality_bits", it, fallback = "%1\$d bits") }
    }

    /**
     * The quality of [song], or null for a song that isn't Navidrome's. Asks the server once for a
     * song this run hasn't listed, unless the app is offline.
     */
    @Composable
    fun remember(song: Song?): AudioQualityInfo? {
        val known = song?.let(NavidromeAudioFormats::quality)
        val loaded by produceState(known, song?.videoId) {
            value = known
            if (song == null || value != null || !ForkNavidrome.owns(song) || ForkOffline.offline.value) return@produceState
            value = NavidromeAudioFormats.loadQuality(song)
        }
        return loaded
    }
}

/** The small outlined pill: the accent colour for Hi-Res, white for Lossless, grey for Compressed. */
@Composable
internal fun DesktopAudioQualityBadge(info: AudioQualityInfo, modifier: Modifier = Modifier) {
    val color = when (info.tier) {
        AudioQualityTier.HI_RES -> DesktopAccent
        AudioQualityTier.LOSSLESS -> Color.White.copy(alpha = 0.85f)
        AudioQualityTier.COMPRESSED -> DesktopSecondary
    }
    Text(
        DesktopFileQuality.label(info),
        modifier = modifier
            .border(1.dp, color.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp),
        color = color,
        fontSize = 10.sp,
        lineHeight = 12.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        style = MaterialTheme.typography.labelSmall,
    )
}
