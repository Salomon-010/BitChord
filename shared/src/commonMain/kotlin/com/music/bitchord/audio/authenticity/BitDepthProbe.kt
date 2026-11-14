package com.music.bitchord.audio.authenticity

/**
 * Fork: how many of a file's bits are really used.
 *
 * Every non-zero sample of every excerpt is OR-ed together; the trailing zero bits of that are
 * bits no sample ever set. A 16-bit master stored as 24 bits has eight of them. Two's complement
 * keeps a negative sample's trailing zeros those of its magnitude, so no sign handling is needed.
 */
object BitDepthProbe {

    /** Fewer non-zero samples than this is too little to say anything (a near-silent excerpt). */
    const val MIN_NONZERO_SAMPLES = 4096

    /** The bits in use, at most the excerpts' declared depth; null when there is too little to read. */
    fun effectiveBits(excerpts: List<PcmExcerpt>): Int? {
        val declared = excerpts.firstOrNull()?.bitsPerSample ?: return null
        var union = 0
        var nonZero = 0L
        for (excerpt in excerpts) {
            require(excerpt.bitsPerSample == declared) { "excerpts differ in depth" }
            for (sample in excerpt.samples) {
                if (sample != 0) {
                    union = union or sample
                    nonZero++
                }
            }
        }
        if (nonZero < MIN_NONZERO_SAMPLES) return null
        return declared - union.countTrailingZeroBits()
    }
}
