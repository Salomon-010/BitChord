package com.music.bitchord.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Fork: the plain-Kotlin FFT both the version alignment ([com.music.bitchord.playback.smart.ForkVersionAlignment])
 * and the lossless-authenticity probe ([com.music.bitchord.audio.authenticity.SpectrumProbe]) run on.
 * Moved here unchanged out of ForkVersionAlignment, itself a copy of upstream's
 * `VersionAudioAligner.fft` (the phone keeps that copy).
 */
internal object Fft {

    /** In-place iterative radix-2 FFT; a power-of-two length. [inverse] divides by the length. */
    fun transform(re: DoubleArray, im: DoubleArray, inverse: Boolean = false) {
        val n = re.size
        require(im.size == n) { "re and im differ in length" }
        require(n > 0 && n and (n - 1) == 0) { "length $n is not a power of two" }

        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }

        var len = 2
        while (len <= n) {
            val angle = 2.0 * PI / len * (if (inverse) 1.0 else -1.0)
            val stepRe = cos(angle)
            val stepIm = sin(angle)
            for (start in 0 until n step len) {
                var twiddleRe = 1.0
                var twiddleIm = 0.0
                for (k in 0 until len / 2) {
                    val evenRe = re[start + k]
                    val evenIm = im[start + k]
                    val oddRe = re[start + k + len / 2] * twiddleRe - im[start + k + len / 2] * twiddleIm
                    val oddIm = re[start + k + len / 2] * twiddleIm + im[start + k + len / 2] * twiddleRe
                    re[start + k] = evenRe + oddRe
                    im[start + k] = evenIm + oddIm
                    re[start + k + len / 2] = evenRe - oddRe
                    im[start + k + len / 2] = evenIm - oddIm
                    val nextRe = twiddleRe * stepRe - twiddleIm * stepIm
                    twiddleIm = twiddleRe * stepIm + twiddleIm * stepRe
                    twiddleRe = nextRe
                }
            }
            len = len shl 1
        }

        if (inverse) {
            for (i in 0 until n) {
                re[i] /= n
                im[i] /= n
            }
        }
    }
}
