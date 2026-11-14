package com.music.bitchord.audio.authenticity

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.min

/** Fork: what [LosslessAuthenticity] makes of a file. */
enum class AuthenticityKind {
    /** No encoder low-pass, every declared bit in use. */
    Genuine,
    /** The same low-pass in most excerpts, under [LosslessAuthenticity.GENUINE_FROM_HZ]: made from an MP3 or AAC. */
    LossyTranscode,
    /** A high-rate file whose content stops at a 44.1/48 kHz file's band: resampled up. */
    Upsampled,
    /** Every sample's low bits zero: a lower depth stored in a wider one (a 16-bit master as 24 bits). */
    PaddedBitDepth,
    /** Silence, too little audio, or a low-pass in some excerpts only. */
    Inconclusive,
}

/** Fork: one excerpt's reading: where it was, how many spectrum windows it gave, the drop found in it. */
data class ExcerptReading(val startSeconds: Double, val windows: Int, val cutoff: Cutoff?)

/**
 * Fork: [kind] is the most telling finding; the others stay readable beside it (a 24-bit FLAC
 * made from an MP3 is [AuthenticityKind.LossyTranscode] with [effectiveBits] 16).
 *
 * [confidence] (0..1) is a ranking aid, not a probability: for a low-pass, the share of excerpts
 * that agree on it times the drop's strength (0.5 at [CutoffDetector.MIN_STEP_DB], 1 from
 * [LosslessAuthenticity.FULL_CONFIDENCE_STEP_DB] on); 1 for padded bits, which are exact; for a
 * genuine file, the share of excerpts that could be read; 0 when inconclusive.
 */
data class AuthenticityVerdict(
    val kind: AuthenticityKind,
    val confidence: Double,
    /** The agreed low-pass, when one was found in enough excerpts. */
    val cutoffHz: Double?,
    /** The MP3 bitrate whose LAME low-pass sits nearest [cutoffHz]; indicative only (AAC cuts elsewhere). */
    val estimatedKbps: Int?,
    val declaredBits: Int,
    /** Bits in use; null when the excerpts held too few non-zero samples. */
    val effectiveBits: Int?,
    val sampleRate: Int,
    val excerpts: List<ExcerptReading>,
)

/**
 * Fork: tells a real lossless file from one made out of a lossy one (a "fake FLAC"), from a few
 * excerpts of its decoded audio. Pure computation: fetching and decoding the excerpts is the
 * caller's. A spectral finding needs most excerpts to agree, because a recording can be dull in
 * one passage; an encoder's low-pass is the same everywhere.
 *
 * Known false positives: old or deliberately filtered masters (tape, vinyl transfers, some
 * remasters) carry a real low-pass that reads like an encoder's, so a finding is a suspicion to
 * show, not a verdict to act on.
 */
object LosslessAuthenticity {

    /** Bumped whenever the maths changes, so stored verdicts can be measured again. */
    const val VERSION = 1

    /** A low-pass at or above this is a genuine 44.1/48 kHz file's anti-alias filter. */
    const val GENUINE_FROM_HZ = 21_000.0

    /** From this rate up, a low-pass under [UPSAMPLED_UP_TO_HZ] means the content came from 44.1/48 kHz. */
    const val HIGH_RATE = 88_200
    const val UPSAMPLED_UP_TO_HZ = 24_500.0

    /** Excerpts whose low-pass lies within this of the median one agree. */
    const val AGREE_HZ = 300.0

    const val FULL_CONFIDENCE_STEP_DB = 45.0

    /** LAME 3.100's default low-pass per CBR bitrate at 44.1 kHz (`optimum_bandwidth`). */
    private val LAME_LOWPASS = listOf(
        64 to 11_000.0, 80 to 13_500.0, 96 to 15_100.0, 112 to 15_600.0, 128 to 17_000.0,
        160 to 17_500.0, 192 to 18_600.0, 224 to 19_400.0, 256 to 19_700.0, 320 to 20_500.0,
    )

    fun analyse(excerpts: List<PcmExcerpt>): AuthenticityVerdict {
        require(excerpts.isNotEmpty()) { "no excerpts" }
        val first = excerpts.first()
        require(excerpts.all { it.sampleRate == first.sampleRate && it.channels == first.channels && it.bitsPerSample == first.bitsPerSample }) {
            "excerpts differ in format"
        }
        val rate = first.sampleRate
        val declared = first.bitsPerSample

        val readings = excerpts.map { excerpt ->
            val spectrum = SpectrumProbe.measure(excerpt)
            ExcerptReading(excerpt.startSeconds, spectrum?.frames ?: 0, spectrum?.let(CutoffDetector::find))
        }
        val effective = BitDepthProbe.effectiveBits(excerpts)
        val padded = effective != null && effective < declared

        fun verdict(kind: AuthenticityKind, confidence: Double, cutoff: Double? = null) = AuthenticityVerdict(
            kind = kind,
            confidence = confidence,
            cutoffHz = cutoff,
            estimatedKbps = cutoff?.takeIf { it < GENUINE_FROM_HZ }?.let(::nearestLameKbps),
            declaredBits = declared,
            effectiveBits = effective,
            sampleRate = rate,
            excerpts = readings,
        )

        val readable = readings.filter { it.windows > 0 }
        val limit = if (rate >= HIGH_RATE) UPSAMPLED_UP_TO_HZ else GENUINE_FROM_HZ
        val low = readable.mapNotNull { it.cutoff }.filter { it.hz < limit }
        if (low.isNotEmpty()) {
            val median = low.map { it.hz }.sorted()[low.size / 2]
            val agreeing = low.filter { abs(it.hz - median) <= AGREE_HZ }
            val needed = if (readable.size >= 3) ceil(readable.size * 2.0 / 3.0).toInt() else readable.size
            if (agreeing.size >= needed) {
                val step = agreeing.map { it.stepDb }.sorted()[agreeing.size / 2]
                val strength = min(1.0, (step - CutoffDetector.MIN_STEP_DB) / (FULL_CONFIDENCE_STEP_DB - CutoffDetector.MIN_STEP_DB) * 0.5 + 0.5)
                val confidence = agreeing.size.toDouble() / readable.size * strength
                val hz = agreeing.map { it.hz }.sorted()[agreeing.size / 2]
                val kind = if (hz < GENUINE_FROM_HZ) AuthenticityKind.LossyTranscode else AuthenticityKind.Upsampled
                return verdict(kind, confidence, hz)
            }
            // A low-pass in some excerpts only: suspicious, not shown either way.
            if (!padded) return verdict(AuthenticityKind.Inconclusive, 0.0)
        }
        if (padded) return verdict(AuthenticityKind.PaddedBitDepth, 1.0)
        if (readable.isEmpty() || effective == null) return verdict(AuthenticityKind.Inconclusive, 0.0)
        return verdict(AuthenticityKind.Genuine, readable.size.toDouble() / readings.size)
    }

    private fun nearestLameKbps(hz: Double): Int = LAME_LOWPASS.minBy { abs(it.second - hz) }.first
}
