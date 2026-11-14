package com.music.bitchord.audio.authenticity

import com.music.bitchord.audio.Fft
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** Test signals: noise with any spectral shape, quantised as a file would store it. */
internal object Signals {

    /** Pink-ish (−3 dB/octave) up to Nyquist. */
    val pink: (Double) -> Double = { hz -> 1.0 / sqrt(maxOf(hz, 20.0)) }

    /** [shape] up to [hz], nothing above: an encoder's low-pass. */
    fun brickwall(hz: Double, shape: (Double) -> Double = pink): (Double) -> Double = { f -> if (f <= hz) shape(f) else 0.0 }

    /**
     * [frames] (a power of two) of noise per channel whose amplitude spectrum follows [shape],
     * peaking at −6 dBFS, rounded to [bits] with TPDF dither (so above a low-pass there is the
     * dither floor a real file has, not digital silence). [shift] then moves the samples left,
     * as storing a 16-bit master in 24 bits does.
     */
    fun noise(
        shape: (Double) -> Double,
        frames: Int = 65_536,
        sampleRate: Int = 44_100,
        channels: Int = 2,
        bits: Int = 16,
        shift: Int = 0,
        seed: Int = 1,
        startSeconds: Double = 0.0,
    ): PcmExcerpt {
        val random = Random(seed)
        val perChannel = Array(channels) { shaped(shape, frames, sampleRate, random) }
        val peak = perChannel.maxOf { channel -> channel.maxOf { abs(it) } }.takeIf { it > 0 } ?: 1.0
        val full = (1L shl (bits - 1)).toDouble()
        val gain = 0.5 * full / peak
        val samples = IntArray(frames * channels)
        for (i in 0 until frames) {
            for (c in 0 until channels) {
                val dither = random.nextDouble() - random.nextDouble()
                val value = (perChannel[c][i] * gain + dither).roundToInt()
                samples[i * channels + c] = value.coerceIn(-full.toInt(), full.toInt() - 1) shl shift
            }
        }
        return PcmExcerpt(samples, channels, sampleRate, bits + shift, startSeconds)
    }

    fun silence(frames: Int = 65_536, sampleRate: Int = 44_100, bits: Int = 16) =
        PcmExcerpt(IntArray(frames * 2), 2, sampleRate, bits)

    /** A sine per channel at the given frequencies, at half scale. */
    fun sines(hz: List<Double>, frames: Int = 16_384, sampleRate: Int = 44_100, bits: Int = 16): PcmExcerpt {
        val full = (1L shl (bits - 1)).toDouble()
        val samples = IntArray(frames * hz.size)
        for (i in 0 until frames) {
            for (c in hz.indices) samples[i * hz.size + c] = (0.5 * full * sin(2 * PI * hz[c] * i / sampleRate)).roundToInt()
        }
        return PcmExcerpt(samples, hz.size, sampleRate, bits)
    }

    /** Random phase and a Rayleigh-ish magnitude per bin, scaled by [shape], back through the inverse FFT. */
    private fun shaped(shape: (Double) -> Double, frames: Int, sampleRate: Int, random: Random): DoubleArray {
        val re = DoubleArray(frames)
        val im = DoubleArray(frames)
        for (k in 1 until frames / 2) {
            val amplitude = shape(k.toDouble() * sampleRate / frames) * (0.5 + random.nextDouble())
            val phase = 2 * PI * random.nextDouble()
            re[k] = amplitude * cos(phase)
            im[k] = amplitude * sin(phase)
            re[frames - k] = re[k]
            im[frames - k] = -im[k]
        }
        Fft.transform(re, im, inverse = true)
        return re
    }
}
