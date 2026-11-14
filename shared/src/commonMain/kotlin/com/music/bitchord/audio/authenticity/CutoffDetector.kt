package com.music.bitchord.audio.authenticity

import kotlin.math.ceil
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Fork: a sharp drop in a [Spectrum]: [hz] where the level falls, [stepDb] how far it falls, and
 * [shoulderHz] how wide its top is (from [CutoffDetector.SHOULDER_TOP_DB] to
 * [CutoffDetector.SHOULDER_BOTTOM_DB] under the level before it). LAME's polyphase low-pass rolls
 * off over ~150 Hz there; an AAC encoder zeroing its coefficients past the bandwidth drops within
 * the window's own resolution (~50 Hz).
 */
data class Cutoff(val hz: Double, val stepDb: Double, val shoulderHz: Double = 0.0)

/**
 * Fork: finds the brick-wall low-pass a lossy encoder leaves (LAME's 17 kHz at 128 kbps, 20.5 kHz at
 * 320, AAC's own), which a later lossless copy keeps.
 *
 * The spectrum is smoothed into [BAND_HZ] bands; at every band edge from [SEARCH_FROM_HZ] up, the
 * mean level (in dB) of [GAP_HZ]..[SPAN_HZ] below the edge is compared with the same span above
 * it. The gap leaves out the filter's own transition. The edge with the largest difference wins
 * if that difference reaches [MIN_STEP_DB]: a recording's natural roll-off loses a few dB over
 * such a span, an encoder's low-pass tens.
 *
 * The last [TOP_FRACTION] of the band is never used as "above": a genuine file's own anti-alias
 * filter sits there, so at 44.1 kHz no edge past ~20.7 kHz can be found at all.
 */
object CutoffDetector {

    const val BAND_HZ = 100.0
    const val SEARCH_FROM_HZ = 9_000.0
    const val GAP_HZ = 300.0
    const val SPAN_HZ = 1_500.0
    /** At least this much of "above" must fit under the top, or the edge is not tried. */
    const val MIN_ABOVE_HZ = 600.0
    const val TOP_FRACTION = 0.98
    const val MIN_STEP_DB = 25.0

    fun find(spectrum: Spectrum): Cutoff? {
        val perBand = max(1, (BAND_HZ / spectrum.binHz).roundToInt())
        val bandHz = perBand * spectrum.binHz
        val count = min(
            (spectrum.nyquist * TOP_FRACTION / bandHz).toInt(),
            spectrum.power.size / perBand,
        )
        val level = DoubleArray(count) { band ->
            var sum = 0.0
            for (bin in band * perBand until (band + 1) * perBand) sum += spectrum.power[bin]
            10.0 * log10(sum / perBand + 1e-30)
        }
        val gap = max(1, (GAP_HZ / bandHz).roundToInt())
        val span = max(gap + 1, (SPAN_HZ / bandHz).roundToInt())
        val minAbove = max(1, ceil(MIN_ABOVE_HZ / bandHz).toInt())

        var bestEdge = -1
        var bestBelow = 0.0
        var bestAbove = 0.0
        for (edge in max(span, ceil(SEARCH_FROM_HZ / bandHz).toInt()) until count) {
            val aboveFrom = edge + gap
            val aboveTo = min(edge + span, count)
            if (aboveTo - aboveFrom < minAbove) break
            val below = mean(level, edge - span, edge - gap)
            val above = mean(level, aboveFrom, aboveTo)
            if (bestEdge < 0 || below - above > bestBelow - bestAbove) {
                bestEdge = edge
                bestBelow = below
                bestAbove = above
            }
        }
        if (bestEdge < 0 || bestBelow - bestAbove < MIN_STEP_DB) return null

        // Where the level crosses halfway between the two sides: the last band at or over it.
        val half = (bestBelow + bestAbove) / 2.0
        var last = bestEdge - span
        for (band in bestEdge - span until min(bestEdge + span, count)) {
            if (level[band] >= half) last = band
        }
        val hz = (last + 1) * bandHz
        return Cutoff(hz = hz, stepDb = bestBelow - bestAbove, shoulderHz = shoulder(spectrum, hz, bestBelow))
    }

    /** dB under the level before the drop where the shoulder starts and ends. */
    const val SHOULDER_TOP_DB = 3.0
    const val SHOULDER_BOTTOM_DB = 20.0
    /** How far either side of the drop the shoulder is looked for. */
    private const val SHOULDER_SEARCH_HZ = 600.0

    /**
     * Bin by bin (three averaged, to steady the noise) around [hz]: from the last bin still within
     * [SHOULDER_TOP_DB] of [before] to the first after it [SHOULDER_BOTTOM_DB] under.
     */
    private fun shoulder(spectrum: Spectrum, hz: Double, before: Double): Double {
        val last = spectrum.power.size - 2
        val from = max(1, ((hz - SHOULDER_SEARCH_HZ) / spectrum.binHz).toInt())
        val to = min(last, ((hz + SHOULDER_SEARCH_HZ) / spectrum.binHz).toInt())
        fun level(bin: Int) = 10.0 * log10((spectrum.power[bin - 1] + spectrum.power[bin] + spectrum.power[bin + 1]) / 3.0 + 1e-30)
        var top = -1
        for (bin in from..to) if (level(bin) >= before - SHOULDER_TOP_DB) top = bin
        if (top < 0) return 0.0
        for (bin in top + 1..to) {
            if (level(bin) <= before - SHOULDER_BOTTOM_DB) return (bin - top) * spectrum.binHz
        }
        return (to - top) * spectrum.binHz
    }

    private fun mean(values: DoubleArray, from: Int, to: Int): Double {
        var sum = 0.0
        for (i in from until to) sum += values[i]
        return sum / (to - from)
    }
}
