// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The zoom track's scale and its detents. Pure arithmetic, so it runs on the JVM -- the part
 * of the zoom feature whose correctness does not need a phone in hand.
 */
class ZoomStopsTest {

    // A typical three-camera phone: ultra-wide, main, 3x tele, expressed the way the app
    // measures lenses (focal length / sensor width; 24 mm-equivalent main = 24/36).
    private val main = 24f / 36f
    private val ultraWide = 16f / 36f
    private val tele3x = 72f / 36f
    private val tele5x = 120f / 36f

    @Test
    fun logarithmicTrackIsSymmetricInDoublings() {
        assertEquals(0f, ZoomStops.fractionOf(1f), 1e-6f)
        assertEquals(1f, ZoomStops.fractionOf(10f), 1e-6f)
        // 2x sits a third of the way along a 1..10 track (log2(2)/log2(10)).
        assertEquals(0.30103f, ZoomStops.fractionOf(2f), 1e-4f)
        assertEquals(5f, ZoomStops.multiplierAt(ZoomStops.fractionOf(5f)), 1e-3f)
    }

    @Test
    fun multiplierRoundTripsAcrossTheWholeTrack() {
        for (step in 0..100) {
            val fraction = step / 100f
            val multiplier = ZoomStops.multiplierAt(fraction)
            assertEquals(fraction, ZoomStops.fractionOf(multiplier), 1e-3f)
            assertTrue(multiplier >= ZoomStops.MIN_MULTIPLIER)
            assertTrue(multiplier <= ZoomStops.MAX_MULTIPLIER)
        }
    }

    @Test
    fun clampingKeepsTheTrackInRange() {
        assertEquals(ZoomStops.MIN_MULTIPLIER, ZoomStops.clamp(0.2f), 0f)
        assertEquals(ZoomStops.MIN_MULTIPLIER, ZoomStops.clamp(Float.NaN), 0f)
        assertEquals(ZoomStops.MAX_MULTIPLIER, ZoomStops.clamp(40f), 0f)
    }

    @Test
    fun everyPhysicalLensGetsADetent() {
        val detents = ZoomStops.detents(listOf(ultraWide, main, tele3x))
        assertEquals(listOf(1f, 3f), detents)
    }

    @Test
    fun ultraWideIsNotPulledOntoTheTrackBelowOneX() {
        // 0.6x cannot be shown on a 1..10 track, so the ultra-wide simply has no detent
        // rather than being rounded up to 1x (which would claim it is the main lens).
        val detents = ZoomStops.detents(listOf(ultraWide, main))
        assertEquals(listOf(1f), detents)
    }

    @Test
    fun duplicateRoutesToOneOpticProduceOneDetent() {
        val detents = ZoomStops.detents(listOf(main, main * 1.01f, tele3x, tele3x * 0.99f))
        assertEquals(2, detents.size)
        assertTrue(detents.any { kotlin.math.abs(it - 3f) < 0.05f })
    }

    @Test
    fun teleBeyondTheTrackIsDropped() {
        val detents = ZoomStops.detents(listOf(main, tele5x * 4f))
        assertEquals(listOf(1f), detents)
    }

    @Test
    fun unknownOpticsStillGiveATrack() {
        assertEquals(listOf(1f), ZoomStops.detents(listOf(Float.MAX_VALUE)))
        assertEquals(listOf(1f), ZoomStops.detents(emptyList()))
    }

    @Test
    fun snapOnlyWithinToleranceAndEqualInOctaves() {
        val detents = listOf(1f, 2f, 5f)
        assertEquals(2f, ZoomStops.snap(2.02f, detents))
        assertNull(ZoomStops.snap(2.2f, detents))
        // The same relative pull at the top of the track also snaps: the tolerance is in
        // octaves, so 5.15x (0.043 octaves) is out but 5.1x (0.029) is in.
        assertEquals(5f, ZoomStops.snap(5.1f, detents))
        assertNull(ZoomStops.snap(5.15f, detents))
    }

    @Test
    fun labelsShowOneDecimalOnlyWhenItSaysSomething() {
        assertEquals("1×", ZoomStops.label(1f))
        assertEquals("3×", ZoomStops.label(2.999f))
        assertEquals("2.5×", ZoomStops.label(2.5f))
        assertEquals("10×", ZoomStops.label(10f))
    }

    @Test
    fun previewScaleIsTheRatioToTheActiveLens() {
        // On the main lens, 3x on the track is 3x of preview magnification.
        assertEquals(3f, ZoomStops.previewScale(3f, 1f), 1e-4f)
        // On the 3x tele, the same track position needs no magnification at all.
        assertEquals(1f, ZoomStops.previewScale(3f, 3f), 1e-4f)
        // And 6x on the 3x tele is 2x of it.
        assertEquals(2f, ZoomStops.previewScale(6f, 3f), 1e-4f)
        // An unknown optic must not magnify anything.
        assertEquals(1f, ZoomStops.previewScale(3f, Float.MAX_VALUE), 1e-4f)
    }
}
