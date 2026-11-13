// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import kotlin.math.roundToInt

/**
 * The post-capture crop implied by the viewfinder's digital zoom ("full sensor first,
 * crop afterwards").
 *
 * Why this shape, and not a crop region on the capture request:
 *
 * * RawLens never sets `SCALER_CROP_REGION` or `CONTROL_ZOOM_RATIO`; it only reads them
 *   back as metadata. Zoom today means "switch to another physical camera id".
 * * A digital multiplier therefore leaves the sensor readout alone: the capture still
 *   requests the whole active array, the preview is magnified for framing only, and the
 *   framing is applied ONCE, after the merge, to the CFA that both the DNG writer and the
 *   develop (JPEG) path consume. Every frame in an MFSR/HDR+ burst then has an identical
 *   field of view, which is the whole point: alignment and fusion never see a moved or
 *   rescaled scene, and the pixels that get discarded are discarded after fusion.
 * * One crop point also means one implementation: the DNG gets a smaller ActiveArea and
 *   the JPEG/Ultra HDR/JPEG XL develop gets a smaller frame, without either path knowing
 *   about zoom.
 *
 * ## Even edges are a correctness requirement, not tidiness
 *
 * Cropping a Bayer mosaic by an odd offset flips the 2x2 CFA phase. That silently turns
 * RGGB into GRBG (a magenta/green cast in every raw converter, and mis-routed green cells
 * in the develop), and it desynchronises the per-2x2-cell structures that travel with the
 * CFA ([CaptureSharpeningClipState] stores one value per 2x2 cell). Both halves of the
 * codebase are also inconsistent about how they read [BayerPattern]: some call sites index
 * it with buffer-local coordinates (`pattern.colorAt(index and 1, index shr 1)`) and some
 * with absolute sensor coordinates (`colorAt(sensorX, sensorY)`). An even origin keeps
 * both readings correct, so [applyTo] rejects odd origins and odd sizes outright instead
 * of returning a CFA that develops into garbage.
 */
internal object ZoomCrop {

    /**
     * Smallest edge a cropped CFA may have. AMaZE requires an even crop of at least 4x4
     * ([UnpackedRawCfa.requireAmazeCompatible]); 8 keeps a margin for the border margin the
     * spatial stages want.
     */
    const val MIN_CFA_EDGE = 8

    /** True when [zoom] is close enough to 1.0 that cropping would be a no-op. */
    fun isIdentity(zoom: Float): Boolean = !zoom.isFinite() || zoom <= 1.005f

    /**
     * The centred crop rectangle [zoom] implies inside a [frameWidth] x [frameHeight]
     * frame, or null when nothing should be cropped (zoom at unity, or a frame already
     * smaller than the minimum).
     *
     * [evenAligned] is for CFA frames; pass false only for a plain RGB plane (the developed
     * bitmap), where odd origins are harmless.
     */
    fun rectFor(
        frameWidth: Int,
        frameHeight: Int,
        zoom: Float,
        minEdge: Int = MIN_CFA_EDGE,
        evenAligned: Boolean = true,
    ): RawCrop? {
        require(frameWidth > 0 && frameHeight > 0) { "frame dimensions must be positive" }
        require(minEdge > 0) { "minimum edge must be positive" }
        if (isIdentity(zoom)) return null

        val step = if (evenAligned) 2 else 1
        fun alignDown(value: Int): Int = value - value.mod(step)

        var width = alignDown((frameWidth / zoom).roundToInt()).coerceAtLeast(minEdge)
        var height = alignDown((frameHeight / zoom).roundToInt()).coerceAtLeast(minEdge)
        if (width > frameWidth) width = alignDown(frameWidth)
        if (height > frameHeight) height = alignDown(frameHeight)
        if (width < minEdge || height < minEdge) return null

        val left = alignDown((frameWidth - width) / 2).coerceAtLeast(0)
        val top = alignDown((frameHeight - height) / 2).coerceAtLeast(0)
        // Both edges aligned down, so the rectangle can never leave the frame.
        if (width >= frameWidth && height >= frameHeight) return null
        return RawCrop(left, top, width, height)
    }

    /**
     * [cfa] cropped to [crop], with everything that travels beside the mosaic moved with it:
     * the sensor origin, the source crop, the highlight-clipping mask, and the per-2x2-cell
     * capture-sharpening clip state (regenerated from the cropped mosaic, which reproduces it
     * exactly whenever the clipping mask is present).
     */
    fun applyTo(cfa: UnpackedRawCfa, crop: RawCrop): UnpackedRawCfa {
        require(crop.left % 2 == 0 && crop.top % 2 == 0) {
            "CFA crop origin must be even to keep the 2x2 Bayer phase " +
                "(got ${crop.left},${crop.top})"
        }
        require(crop.width % 2 == 0 && crop.height % 2 == 0) {
            "CFA crop size must be even (got ${crop.width}x${crop.height})"
        }
        require(crop.width >= 4 && crop.height >= 4) {
            "CFA crop must be at least 4x4 for AMaZE (got ${crop.width}x${crop.height})"
        }
        require(crop.left >= 0 && crop.top >= 0 &&
            crop.left + crop.width <= cfa.width &&
            crop.top + crop.height <= cfa.height) {
            "CFA crop ${crop.left},${crop.top} ${crop.width}x${crop.height} " +
                "escapes a ${cfa.width}x${cfa.height} mosaic"
        }
        require(cfa.values.size >= cfa.width * cfa.height) {
            "CFA buffer is shorter than its declared ${cfa.width}x${cfa.height}"
        }

        val cropped = FloatArray(crop.width * crop.height)
        for (row in 0 until crop.height) {
            val source = (crop.top + row) * cfa.width + crop.left
            System.arraycopy(cfa.values, source, cropped, row * crop.width, crop.width)
        }

        val moved = UnpackedRawCfa(
            width = crop.width,
            height = crop.height,
            // Unchanged on purpose: with an even origin the 2x2 phase is preserved, so the
            // pattern that describes the sensor still describes the cropped buffer, for both
            // the local-coordinate and the absolute-coordinate call sites.
            pattern = cfa.pattern,
            values = cropped,
            sourceCrop = RawCrop(
                left = cfa.sourceCrop.left + crop.left,
                top = cfa.sourceCrop.top + crop.top,
                width = crop.width,
                height = crop.height,
            ),
            sensorCropLeft = cfa.sensorCropLeft + crop.left,
            sensorCropTop = cfa.sensorCropTop + crop.top,
            highlightClipping = cfa.highlightClipping?.let { cropClippingMask(it, crop) },
            // Filled in below from the cropped mosaic: the state is per 2x2 cell and cannot be
            // sliced without re-packing, and re-packing from the cropped CFA is exact whenever
            // the clipping mask came across with it.
            captureSharpeningClipState = null,
        )
        return moved.copy(
            captureSharpeningClipState = CaptureSharpeningClipState.fromSensorCfa(moved),
        )
    }

    /**
     * [cfa] cropped for a capture that framed at [zoomMultiplier], or unchanged when the
     * multiplier is at unity or the crop would not fit (a merge that already produced a
     * smaller mosaic than the request assumed). Callers pass the multiplier frozen at the
     * shutter press, so every output of one capture crops the same way.
     */
    fun applyIfNeeded(cfa: UnpackedRawCfa, zoomMultiplier: Float): UnpackedRawCfa {
        if (isIdentity(zoomMultiplier)) return cfa
        val crop = rectFor(cfa.width, cfa.height, zoomMultiplier) ?: return cfa
        if (crop.left + crop.width > cfa.width || crop.top + crop.height > cfa.height) return cfa
        return applyTo(cfa, crop)
    }

    private fun cropClippingMask(mask: CfaHighlightClipping, crop: RawCrop): CfaHighlightClipping {
        require(cfaMaskContains(mask, crop)) {
            "highlight-clipping mask ${mask.width}x${mask.height} does not cover the crop"
        }
        val stride = (crop.width + 31) / 32
        val words = IntArray(stride * crop.height)
        for (row in 0 until crop.height) {
            val sourceRow = (crop.top + row) * mask.width + crop.left
            val targetRow = row * stride
            for (column in 0 until crop.width) {
                if (mask.isClipped(sourceRow + column)) {
                    words[targetRow + column / 32] =
                        words[targetRow + column / 32] or (1 shl (column and 31))
                }
            }
        }
        return CfaHighlightClipping(crop.width, crop.height, words)
    }

    private fun cfaMaskContains(mask: CfaHighlightClipping, crop: RawCrop): Boolean =
        crop.left >= 0 && crop.top >= 0 &&
            crop.left + crop.width <= mask.width &&
            crop.top + crop.height <= mask.height
}
