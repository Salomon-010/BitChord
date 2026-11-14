package com.music.bitchord.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FftTest {

    @Test
    fun `an impulse has a flat spectrum`() {
        val re = DoubleArray(64).also { it[0] = 1.0 }
        val im = DoubleArray(64)
        Fft.transform(re, im)
        for (k in 0 until 64) assertEquals(1.0, hypot(re[k], im[k]), 1e-12)
    }

    @Test
    fun `a cosine lands in its own bin and its mirror`() {
        val n = 256
        val re = DoubleArray(n) { cos(2 * PI * 10 * it / n) }
        val im = DoubleArray(n)
        Fft.transform(re, im)
        for (k in 0 until n) {
            val expected = if (k == 10 || k == n - 10) n / 2.0 else 0.0
            assertEquals(expected, hypot(re[k], im[k]), 1e-9, "bin $k")
        }
    }

    @Test
    fun `the inverse gives the input back`() {
        val n = 1024
        val original = DoubleArray(n) { ((it * 7919) % 101 - 50).toDouble() }
        val re = original.copyOf()
        val im = DoubleArray(n)
        Fft.transform(re, im)
        Fft.transform(re, im, inverse = true)
        assertTrue(original.indices.all { abs(re[it] - original[it]) < 1e-9 && abs(im[it]) < 1e-9 })
    }

    @Test
    fun `a length that is not a power of two is refused`() {
        assertFailsWith<IllegalArgumentException> { Fft.transform(DoubleArray(100), DoubleArray(100)) }
    }
}
