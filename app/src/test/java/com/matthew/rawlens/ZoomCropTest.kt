// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The crop a viewfinder zoom implies, and the CFA surgery that applies it. Pure geometry and
 * buffer movement, so it runs on the JVM: this is the part of the zoom feature whose
 * correctness does not need a phone.
 */
class ZoomCropTest {

    private fun cfa(width: Int, height: Int, pattern: BayerPattern = BayerPattern.RGGB): UnpackedRawCfa {
        val values = FloatArray(width * height) { it.toFloat() }
        return UnpackedRawCfa(
            width = width,
            height = height,
            pattern = pattern,
            values = values,
            sourceCrop = RawCrop(0, 0, width, height),
            sensorCropLeft = 0,
            sensorCropTop = 0,
        )
    }

    // --- geometry -------------------------------------------------------------------

    @Test
    fun unityZoomCropsNothing() {
        assertNull(ZoomCrop.rectFor(4000, 3000, 1.0f))
        assertNull(ZoomCrop.rectFor(4000, 3000, 0.6f))
        assertNull(ZoomCrop.rectFor(4000, 3000, Float.NaN))
        assertTrue(ZoomCrop.isIdentity(1.005f))
        assertTrue(!ZoomCrop.isIdentity(1.006f))
    }

    @Test
    fun twoTimesCropsToTheCentreHalf() {
        val crop = ZoomCrop.rectFor(4000, 3000, 2.0f)!!
        assertEquals(RawCrop(1000, 750, 2000, 1500), crop)
    }

    @Test
    fun fractionalZoomKeepsEvenEdgesAndStaysInsideTheFrame() {
        // 4000/1.3 = 3076.9, 3000/1.3 = 2307.7: both round and then align down to even.
        val crop = ZoomCrop.rectFor(4000, 3000, 1.3f)!!
        assertEquals(0, crop.left % 2)
        assertEquals(0, crop.top % 2)
        assertEquals(0, crop.width % 2)
        assertEquals(0, crop.height % 2)
        assertTrue(crop.left >= 0 && crop.top >= 0)
        assertTrue(crop.left + crop.width <= 4000)
        assertTrue(crop.top + crop.height <= 3000)
        // Centred within one alignment step on each side.
        assertTrue(kotlin.math.abs((4000 - crop.width) / 2 - crop.left) <= 1)
        assertTrue(kotlin.math.abs((3000 - crop.height) / 2 - crop.top) <= 1)
    }

    @Test
    fun everySupportedMultiplierProducesALegalRectangle() {
        for (zoom in floatArrayOf(1.05f, 1.2f, 1.5f, 2f, 2.5f, 3f, 4.6f, 7f, 10f)) {
            for ((w, h) in listOf(4000 to 3000, 4032 to 3024, 4080 to 3060, 8192 to 6144)) {
                val crop = ZoomCrop.rectFor(w, h, zoom)!!
                assertTrue("zoom=$zoom ${w}x$h -> $crop", crop.left >= 0 && crop.top >= 0)
                assertTrue("zoom=$zoom ${w}x$h -> $crop", crop.left + crop.width <= w)
                assertTrue("zoom=$zoom ${w}x$h -> $crop", crop.top + crop.height <= h)
                assertTrue("zoom=$zoom ${w}x$h -> $crop", crop.width % 2 == 0 && crop.height % 2 == 0)
                assertTrue("zoom=$zoom ${w}x$h -> $crop",
                    crop.width >= ZoomCrop.MIN_CFA_EDGE && crop.height >= ZoomCrop.MIN_CFA_EDGE)
            }
        }
    }

    @Test
    fun absurdZoomIsBoundedByTheMinimumEdge() {
        val crop = ZoomCrop.rectFor(4000, 3000, 1000f)!!
        assertTrue(crop.width >= ZoomCrop.MIN_CFA_EDGE)
        assertTrue(crop.height >= ZoomCrop.MIN_CFA_EDGE)
    }

    // --- CFA surgery ----------------------------------------------------------------

    @Test
    fun cropCopiesTheRightPixelsAndMovesTheSensorOrigin() {
        val source = cfa(64, 48)
        val cropped = ZoomCrop.applyTo(source, RawCrop(16, 8, 32, 24))
        assertEquals(32, cropped.width)
        assertEquals(24, cropped.height)
        assertEquals(16, cropped.sensorCropLeft)
        assertEquals(8, cropped.sensorCropTop)
        assertEquals(RawCrop(16, 8, 32, 24), cropped.sourceCrop)
        for (row in 0 until 24) {
            for (column in 0 until 32) {
                assertEquals(
                    source.values[(row + 8) * 64 + (column + 16)],
                    cropped.values[row * 32 + column],
                    0f,
                )
            }
        }
    }

    @Test
    fun cropKeepsTheBayerPhase() {
        // The colours the crop's first 2x2 cell presents must equal the source cell it came
        // from: this is the check that an odd origin would fail (RGGB would read as GRBG).
        for (pattern in BayerPattern.values()) {
            val source = cfa(64, 48, pattern)
            val crop = RawCrop(18, 10, 32, 24)
            val cropped = ZoomCrop.applyTo(source, crop)
            assertSame(pattern, cropped.pattern)
            for (dy in 0..1) {
                for (dx in 0..1) {
                    assertEquals(
                        source.pattern.colorAt(crop.left + dx, crop.top + dy).name,
                        cropped.pattern.colorAt(dx, dy).name,
                    )
                }
            }
        }
    }

    @Test
    fun oddOriginsAndOddSizesAreRejected() {
        val source = cfa(64, 48)
        for (bad in listOf(RawCrop(17, 8, 32, 24), RawCrop(16, 9, 32, 24),
                           RawCrop(16, 8, 31, 24), RawCrop(16, 8, 32, 23))) {
            val failure = runCatching { ZoomCrop.applyTo(source, bad) }.exceptionOrNull()
            assertTrue("$bad should be refused, got $failure", failure is IllegalArgumentException)
        }
    }

    @Test
    fun outOfFrameCropIsRejected() {
        val source = cfa(64, 48)
        val failure = runCatching { ZoomCrop.applyTo(source, RawCrop(40, 32, 32, 24)) }
            .exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun highlightClippingMaskTravelsWithTheCrop() {
        // Mark every pixel whose column is odd, then confirm the cropped mask says the same
        // thing about the same sensor pixels (an 18-column origin is even, so columns keep
        // their parity).
        val width = 64
        val height = 48
        val mask = CfaHighlightClipping.from(width, height) { index -> (index % width) % 2 == 1 }
        val source = UnpackedRawCfa(
            width = width,
            height = height,
            pattern = BayerPattern.RGGB,
            values = FloatArray(width * height) { it.toFloat() },
            sourceCrop = RawCrop(0, 0, width, height),
            highlightClipping = mask,
        )
        val crop = RawCrop(18, 10, 32, 24)
        val cropped = ZoomCrop.applyTo(source, crop)
        val croppedMask = cropped.highlightClipping!!
        assertEquals(32, croppedMask.width)
        assertEquals(24, croppedMask.height)
        for (row in 0 until 24) {
            for (column in 0 until 32) {
                assertEquals(
                    mask.isClipped((row + 10) * width + (column + 18)),
                    croppedMask.isClipped(row * 32 + column),
                )
            }
        }
    }

    @Test
    fun captureSharpeningClipStateIsRegeneratedFromTheCrop() {
        val width = 64
        val height = 48
        val source = UnpackedRawCfa(
            width = width,
            height = height,
            pattern = BayerPattern.RGGB,
            // A bright block at the top-left of the crop so the state has something to say.
            values = FloatArray(width * height) { index ->
                val x = index % width
                val y = index / width
                if (x in 18 until 26 && y in 10 until 18) 0.999f else 0.1f
            },
            sourceCrop = RawCrop(0, 0, width, height),
        )
        val cropped = ZoomCrop.applyTo(source, RawCrop(18, 10, 32, 24))
        val state = cropped.captureSharpeningClipState
        assertTrue(state != null)
        // The bright pixels land at local (0..7, 0..7); their 2x2 cells must be flagged.
        assertTrue(state!!.bitsAt(0, 0) != 0)
        assertTrue(state.bitsAt(3, 3) != 0)
        assertEquals(0, state.bitsAt(15, 11))
    }

    // --- what the capture path calls ------------------------------------------------

    @Test
    fun applyIfNeededIsANoOpAtUnity() {
        val source = cfa(64, 48)
        assertSame(source, ZoomCrop.applyIfNeeded(source, 1f))
        assertSame(source, ZoomCrop.applyIfNeeded(source, 0.9f))
        assertSame(source, ZoomCrop.applyIfNeeded(source, Float.NaN))
    }

    @Test
    fun applyIfNeededCropsForAMultiplier() {
        val cropped = ZoomCrop.applyIfNeeded(cfa(400, 300), 2f)
        assertEquals(200, cropped.width)
        assertEquals(150, cropped.height)
        assertEquals(100, cropped.sensorCropLeft)
        // (300 - 150) / 2 = 75, which is ODD: the origin is aligned DOWN to 74 so the crop
        // keeps the 2x2 Bayer phase. That one-pixel asymmetry at the bottom edge is the
        // price of an even origin, and it is why the assertion is 74 and not 75.
        assertEquals(74, cropped.sensorCropTop)
    }

    @Test
    fun applyIfNeededStopsAtTheMinimumMosaicInsteadOfGoingIllegal() {
        // A frame already near the minimum cannot be cropped by the requested factor: the
        // rectangle is clamped up to MIN_CFA_EDGE rather than producing a 1x1 mosaic that
        // AMaZE would reject.
        val cropped = ZoomCrop.applyIfNeeded(cfa(16, 16), 10f)
        assertEquals(ZoomCrop.MIN_CFA_EDGE, cropped.width)
        assertEquals(ZoomCrop.MIN_CFA_EDGE, cropped.height)
    }
}
