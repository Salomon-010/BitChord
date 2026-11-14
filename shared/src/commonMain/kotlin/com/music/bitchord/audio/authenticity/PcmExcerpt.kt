package com.music.bitchord.audio.authenticity

/**
 * Fork: a stretch of a file's audio as the file stores it, for [LosslessAuthenticity].
 *
 * Integer samples, interleaved, right-aligned to [bitsPerSample]: a 24-bit sample lies in
 * -2^23..2^23-1, not in an Int's top bits as FFmpeg's S32 output holds it (shift that right by
 * `32 - bitsPerSample` first). Integers, the file's own channels and its own rate, because
 * [BitDepthProbe] reads the low bits and a downmix, a resample or a float conversion would invent
 * them.
 */
class PcmExcerpt(
    val samples: IntArray,
    val channels: Int,
    val sampleRate: Int,
    val bitsPerSample: Int,
    /** Where in the song it was taken; reported back, not used in the maths. */
    val startSeconds: Double = 0.0,
) {
    init {
        require(channels >= 1) { "no channels" }
        require(sampleRate > 0) { "no sample rate" }
        require(bitsPerSample in 8..32) { "unsupported depth $bitsPerSample" }
        require(samples.size % channels == 0) { "samples not a whole number of frames" }
    }

    /** Sample frames, one sample per channel each. */
    val frames: Int get() = samples.size / channels
}
