// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import java.util.Locale
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.round

/**
 * The zoom scale a viewfinder slider runs on, and how it relates to the physical lenses.
 *
 * The scale is logarithmic (one octave per doubling), which is what makes a 1×–10× track
 * usable by thumb: linear would give 1×–3× a tenth of the travel that 3×–10× gets, and the
 * useful range of a phone camera is the bottom of it. Every physical rear lens becomes a
 * detent on that track at its own multiplier (focal length over the 1× baseline, the same
 * figure [LensZoom.label] prints), so "drag until it clicks" means "switch to that lens"
 * and nothing is resampled.
 *
 * Multipliers between detents are the digital ones: the capture keeps reading the whole
 * sensor and [ZoomCrop] trims the frame afterwards, so a burst still fuses frames that
 * share one field of view.
 */
internal object ZoomStops {

    /** Widest the slider goes. Below 1× only a real ultra-wide lens can sit. */
    const val MIN_MULTIPLIER = 1f
    const val MAX_MULTIPLIER = 10f

    /** A drag this close to a detent lands on it (in octaves of the track). */
    const val SNAP_TOLERANCE_OCTAVES = 0.035f

    fun clamp(value: Float): Float =
        if (!value.isFinite()) MIN_MULTIPLIER else value.coerceIn(MIN_MULTIPLIER, MAX_MULTIPLIER)

    /** Track position 0..1 for [multiplier]. */
    fun fractionOf(multiplier: Float): Float {
        val span = log2(MAX_MULTIPLIER / MIN_MULTIPLIER)
        return ((log2(clamp(multiplier) / MIN_MULTIPLIER)) / span).coerceIn(0f, 1f)
    }

    /** Multiplier at track position [fraction] 0..1. */
    fun multiplierAt(fraction: Float): Float {
        val span = log2(MAX_MULTIPLIER / MIN_MULTIPLIER)
        val powered = MIN_MULTIPLIER * 2f.pow(fraction.coerceIn(0f, 1f) * span)
        return clamp(powered)
    }

    /**
     * The nearest detent within [SNAP_TOLERANCE_OCTAVES], or null when the drag sits between
     * two of them. Tolerance in octaves rather than multipliers is what keeps the pull
     * feeling equal at 1.2× and at 8×.
     */
    fun snap(multiplier: Float, detents: List<Float>,
             toleranceOctaves: Float = SNAP_TOLERANCE_OCTAVES): Float? {
        if (detents.isEmpty()) return null
        val target = clamp(multiplier)
        val nearest = detents.minByOrNull { abs(log2(it / target)) } ?: return null
        return if (abs(log2(nearest / target)) <= toleranceOctaves) clamp(nearest) else null
    }

    /**
     * Detent multipliers for the rear lens metrics the camera reports, clamped to the track
     * and de-duplicated (two routes to the same optic must not create two ticks).
     */
    fun detents(rearMetrics: Collection<Float>): List<Float> {
        if (rearMetrics.none { LensZoom.isKnown(it) }) return listOf(MIN_MULTIPLIER)
        val baseline = LensZoom.baseline(rearMetrics)
        val values = rearMetrics.filter { LensZoom.isKnown(it) }
            .map { it / baseline }
            .filter { it >= MIN_MULTIPLIER && it <= MAX_MULTIPLIER }
            .sorted()
        val distinct = ArrayList<Float>(values.size)
        for (value in values) {
            if (distinct.isEmpty() || abs(log2(value / distinct.last())) > 0.03f) distinct.add(value)
        }
        if (distinct.isEmpty()) distinct.add(MIN_MULTIPLIER)
        if (distinct.none { abs(it - MIN_MULTIPLIER) < 0.03f }) distinct.add(0, MIN_MULTIPLIER)
        return distinct
    }

    /** "1×", "2.5×", "10×": one decimal only where it says something. */
    fun label(multiplier: Float): String {
        val value = clamp(multiplier)
        val rounded = round(value * 10f) / 10f
        return if (abs(rounded - round(rounded)) < 0.05f) {
            "${round(rounded).toInt()}×"
        } else {
            String.format(Locale.US, "%.1f×", rounded)
        }
    }

    /**
     * How much the preview surface must grow for [multiplier] while the active lens' own
     * multiplier is [lensMultiplier]: the surface always shows the whole sensor, so the
     * extra magnification is exactly the ratio of the two.
     */
    fun previewScale(multiplier: Float, lensMultiplier: Float): Float {
        // opticalMetric() reports Float.MAX_VALUE for a lens whose focal length or sensor
        // size the HAL does not expose; that must read as "no magnification", not as a
        // scale of zero.
        if (!LensZoom.isKnown(lensMultiplier)) return 1f
        return (clamp(multiplier) / lensMultiplier).coerceIn(1f, MAX_MULTIPLIER)
    }
}
