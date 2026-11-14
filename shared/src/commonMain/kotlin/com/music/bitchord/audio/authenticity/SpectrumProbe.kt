package com.music.bitchord.audio.authenticity

import com.music.bitchord.audio.Fft
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow

/**
 * Fork: an excerpt's average power spectrum, all channels summed (Welch's method: Hann windows,
 * half overlapping, power averaged).
 *
 * [power] is linear, per bin, 0 Hz to Nyquist; [frames] is how many windows went into it. Only
 * level differences matter downstream, so the scale is simply "full scale = 1, divided by the
 * window's energy".
 */
class Spectrum(val power: DoubleArray, val binHz: Double, val frames: Int, val sampleRate: Int) {
    val nyquist: Double get() = sampleRate / 2.0

    fun db(bin: Int): Double = 10.0 * log10(power[bin] + FLOOR)

    private companion object {
        /** Keeps digital silence finite: -300 dB, far under any 32-bit file's own noise. */
        const val FLOOR = 1e-30
    }
}

object SpectrumProbe {

    /** Bins no wider than this, so a cut-off can be placed within ~100 Hz once smoothed. */
    const val MAX_BIN_HZ = 12.0

    /** A window quieter than this (RMS, dBFS) is left out: silence says nothing about a cut-off. */
    const val SILENT_DBFS = -60.0

    /** The FFT length for [sampleRate]: 4096 at 44.1 and 48 kHz, 8192 at 88.2 and 96 kHz. */
    fun fftSizeFor(sampleRate: Int): Int {
        var n = 1024
        while (sampleRate.toDouble() / n > MAX_BIN_HZ) n = n shl 1
        return n
    }

    /**
     * [excerpt]'s spectrum, or null when it is shorter than one window or every window was silent.
     *
     * Channels go through the FFT two at a time, one as the real part and one as the imaginary:
     * for a pair z = l + i·r, |L[k]|² + |R[k]|² = (|Z[k]|² + |Z[N−k]|²) / 2, so the summed power of
     * both costs one transform. A lone channel is the same formula with r = 0.
     */
    fun measure(excerpt: PcmExcerpt): Spectrum? {
        val rate = excerpt.sampleRate
        val n = fftSizeFor(rate)
        val hop = n / 2
        if (excerpt.frames < n) return null

        val window = DoubleArray(n) { 0.5 - 0.5 * cos(2.0 * PI * it / n) }
        val windowEnergy = window.sumOf { it * it }
        val scale = 1.0 / (1L shl (excerpt.bitsPerSample - 1)).toDouble()
        val channels = excerpt.channels
        val samples = excerpt.samples
        val silent = 10.0.pow(SILENT_DBFS / 10.0)

        val bins = n / 2 + 1
        val total = DoubleArray(bins)
        val frame = DoubleArray(bins)
        val re = DoubleArray(n)
        val im = DoubleArray(n)
        var used = 0

        var start = 0
        while (start + n <= excerpt.frames) {
            frame.fill(0.0)
            var energy = 0.0
            var channel = 0
            while (channel < channels) {
                val second = channel + 1 < channels
                for (i in 0 until n) {
                    val base = (start + i) * channels + channel
                    val l = samples[base] * scale * window[i]
                    val r = if (second) samples[base + 1] * scale * window[i] else 0.0
                    re[i] = l
                    im[i] = r
                    energy += l * l + r * r
                }
                Fft.transform(re, im)
                for (k in 0 until bins) {
                    val m = (n - k) % n
                    frame[k] += (re[k] * re[k] + im[k] * im[k] + re[m] * re[m] + im[m] * im[m]) / 2.0
                }
                channel += 2
            }
            // Mean square of the windowed signal per channel, against full scale.
            if (energy / windowEnergy / channels >= silent) {
                for (k in 0 until bins) total[k] += frame[k]
                used++
            }
            start += hop
        }
        if (used == 0) return null
        val norm = 1.0 / (used * windowEnergy * channels)
        for (k in 0 until bins) total[k] *= norm
        return Spectrum(total, rate.toDouble() / n, used, rate)
    }
}
