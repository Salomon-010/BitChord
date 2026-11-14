package com.music.bitchord.data.model

/**
 * Sigui: a file's quality tier and its "FLAC · 24 bits · 96 kHz" line, from what a server says of the
 * file (Navidrome's `suffix`, `bitDepth`, `samplingRate`, `bitRate`), not from what is playing.
 *
 *  - Hi-Res: a lossless file of 24 bits or more, or above 48 kHz; DSD always.
 *  - Lossless: any other lossless file (16 bits at 44.1 or 48 kHz, or a depth the server didn't give).
 *  - Compressed: MP3, AAC, Opus, Vorbis, WMA.
 *  - A format not listed here gets no tier at all.
 */
enum class AudioQualityTier { HI_RES, LOSSLESS, COMPRESSED }

/** What a server says of one file. Zero and null both mean "not stated". */
data class AudioFormat(
    val suffix: String?,
    val bitDepth: Int? = null,
    val sampleRateHz: Int? = null,
    val bitRateKbps: Int? = null,
)

/** A file's tier, with the parts of its detail line ([AudioQuality.detail] joins them). */
data class AudioQualityInfo(
    val tier: AudioQualityTier,
    /** FLAC, ALAC, MP3, DSD64… */
    val codec: String,
    /** Only for lossless PCM, and only when stated. */
    val bitDepth: Int? = null,
    /** Only for lossless PCM, and only when stated. */
    val sampleRateHz: Int? = null,
    /** Only for compressed files, and only when stated. */
    val bitRateKbps: Int? = null,
)

object AudioQuality {
    private enum class Codec(val label: String, val lossless: Boolean, val dsd: Boolean = false) {
        FLAC("FLAC", true), ALAC("ALAC", true), WAV("WAV", true), AIFF("AIFF", true),
        APE("APE", true), WAVPACK("WavPack", true), DSD("DSD", true, dsd = true),
        MP3("MP3", false), AAC("AAC", false), OPUS("Opus", false), VORBIS("Vorbis", false), WMA("WMA", false),
    }

    private const val HI_RES_DEPTH = 24
    private const val HI_RES_ABOVE_HZ = 48_000
    private const val DSD_BASE_HZ = 44_100

    fun classify(format: AudioFormat): AudioQualityInfo? {
        val depth = format.bitDepth?.takeIf { it > 0 }
        val rate = format.sampleRateHz?.takeIf { it > 0 }
        val codec = codecOf(format.suffix, depth) ?: return null
        return when {
            !codec.lossless -> AudioQualityInfo(
                tier = AudioQualityTier.COMPRESSED,
                codec = codec.label,
                bitRateKbps = format.bitRateKbps?.takeIf { it > 0 },
            )
            // DSD is 1 bit at megahertz rates: named by its multiple of 44.1 kHz (DSD64, DSD128…).
            codec.dsd -> AudioQualityInfo(
                tier = AudioQualityTier.HI_RES,
                codec = rate?.let { "DSD${it / DSD_BASE_HZ}" }?.takeIf { rate >= DSD_BASE_HZ } ?: codec.label,
            )
            else -> AudioQualityInfo(
                tier = if ((depth ?: 0) >= HI_RES_DEPTH || (rate ?: 0) > HI_RES_ABOVE_HZ) {
                    AudioQualityTier.HI_RES
                } else {
                    AudioQualityTier.LOSSLESS
                },
                codec = codec.label,
                bitDepth = depth,
                sampleRateHz = rate,
            )
        }
    }

    /** The detail line, such as "FLAC · 24 bits · 96 kHz" or "MP3 · 320 kbps". */
    fun detail(
        info: AudioQualityInfo,
        decimalSeparator: Char = '.',
        bits: (Int) -> String = { "$it bits" },
    ): String = listOfNotNull(
        info.codec,
        info.bitDepth?.let(bits),
        info.sampleRateHz?.let { "${kilohertz(it, decimalSeparator)} kHz" },
        info.bitRateKbps?.let { "$it kbps" },
    ).joinToString(" · ")

    /** 96000 → "96", 44100 → "44.1" (with [decimalSeparator]), 176400 → "176.4". */
    fun kilohertz(hz: Int, decimalSeparator: Char = '.'): String {
        val tenths = (hz + 50) / 100
        val whole = tenths / 10
        val fraction = tenths % 10
        return if (fraction == 0) "$whole" else "$whole$decimalSeparator$fraction"
    }

    /** An `m4a`/`mp4` is ALAC when the server states a bit depth (AAC has none), else AAC. */
    private fun codecOf(suffix: String?, depth: Int?): Codec? = when (suffix?.trim()?.lowercase()?.removePrefix(".")) {
        "flac" -> Codec.FLAC
        "alac" -> Codec.ALAC
        "m4a", "mp4" -> if (depth != null) Codec.ALAC else Codec.AAC
        "wav", "wave" -> Codec.WAV
        "aiff", "aif", "aifc" -> Codec.AIFF
        "ape" -> Codec.APE
        "wv" -> Codec.WAVPACK
        "dsf", "dff" -> Codec.DSD
        "mp3" -> Codec.MP3
        "aac" -> Codec.AAC
        "opus" -> Codec.OPUS
        "ogg", "oga" -> Codec.VORBIS
        "wma" -> Codec.WMA
        else -> null
    }
}
