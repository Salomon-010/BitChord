package com.music.bitchord.desktop

import kotlin.math.floor

/**
 * Fork: the desktop's output defaults, apart so they are named once and can be tested.
 *
 * Upstream started on "16-bit PCM"; the fork starts on 32-bit float, which Windows' shared mixer
 * takes as it is (it mixes in float itself). Only the default: a precision the listener saved is
 * read as it was.
 */
internal object ForkAudioDefaults {
    const val OUTPUT_PRECISION = "FLOAT_32"
}

/**
 * Fork: float samples to 16-bit PCM, rounded with triangular (TPDF) dither instead of upstream's
 * truncation by 32767.
 *
 * Upstream wrote `(x * 32767).toInt()`: the decoder had divided by 32768, so every non-zero sample of
 * a 16-bit file came out one step nearer zero, and anything finer than 16 bits (a 24-bit file, the
 * volume, the equaliser) was truncated, a distortion that follows the signal.
 *
 * Here a sample is scaled by 32768. One that already lies on the 16-bit grid (a 16-bit file at unity
 * gain with nothing processing it) is written as it is, so such a file goes out sample for sample;
 * dither only decorrelates a rounding error, and there is none. Anything between two steps is
 * rounded after adding TPDF noise of ±1 step (the difference of two uniform draws), which makes the
 * error white and independent of the signal. Full scale is clamped to −32768..32767.
 *
 * One instance per output, used only on the audio thread.
 */
internal class ForkPcm16Quantizer(seed: Long = 0x2545F4914F6CDD1DL) {

    private val quantizer = ForkPcmQuantizer(16, seed)

    /** [sample] × [gain] as a 16-bit value. */
    fun quantize(sample: Float, gain: Float): Int = quantizer.quantize(sample, gain)

    /** [count] samples × [gain] as little-endian 16-bit PCM into [out] (2 bytes a sample). */
    fun toBytes(samples: FloatArray, count: Int, gain: Float, out: ByteArray) {
        for (index in 0 until count) {
            val value = quantize(samples[index], gain)
            out[index * 2] = value.toByte()
            out[index * 2 + 1] = (value shr 8).toByte()
        }
    }

    /**
     * [count] samples × [gain] rounded onto the 16-bit grid and handed back as floats (value / 32768),
     * for the native WASAPI writer, which multiplies by 32768 and so gets the exact value back: the
     * dither happens once, here, where it is tested.
     */
    fun toGrid(samples: FloatArray, count: Int, gain: Float, out: FloatArray) {
        for (index in 0 until count) out[index] = quantize(samples[index], gain) / FULL_SCALE.toFloat()
    }

    companion object {
        const val FULL_SCALE = 32_768.0
    }
}

/**
 * Fork: float samples to [bits]-bit integers (16 or 24), rounded with TPDF dither of ±1 step; a
 * sample already on the grid is kept as it is, so a file of that depth at unity gain goes through
 * unchanged. The rule [ForkPcm16Quantizer] states, for any depth; full scale clamped to
 * −2^(bits−1)..2^(bits−1)−1. One instance per output, audio thread only.
 */
internal class ForkPcmQuantizer(val bits: Int, seed: Long = 0x2545F4914F6CDD1DL) {

    init {
        require(bits in 8..24) { "dithered to $bits bits" }
    }

    private val fullScale = (1L shl (bits - 1)).toDouble()
    private val max = fullScale - 1
    private val min = -fullScale
    private var state = if (seed == 0L) 1L else seed

    /** [sample] × [gain] as a [bits]-bit value. */
    fun quantize(sample: Float, gain: Float): Int {
        val scaled = sample.toDouble() * gain * fullScale
        if (scaled.isNaN()) return 0
        val onGrid = scaled == floor(scaled)
        val value = if (onGrid) scaled else floor(scaled + tpdf() + 0.5)
        return when {
            value >= max -> max.toInt()
            value <= min -> min.toInt()
            else -> value.toInt()
        }
    }

    /** Triangular noise in (−1, 1) step: two uniform draws in [0, 1), one taken from the other. */
    private fun tpdf(): Double = uniform() - uniform()

    /** xorshift64*: fast, no allocation, good enough for dither. */
    private fun uniform(): Double {
        var x = state
        x = x xor (x ushr 12)
        x = x xor (x shl 25)
        x = x xor (x ushr 27)
        state = x
        return ((x * 0x2545F4914F6CDD1DL) ushr 11).toDouble() / (1L shl 53).toDouble()
    }
}
