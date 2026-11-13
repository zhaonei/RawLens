// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.content.Context
import android.hardware.camera2.TotalCaptureResult
import android.os.SystemClock

/**
 * Save settings a ZSL merge job reads, frozen at shutter time like every
 * other save (W4): the job runs later on the writer thread.
 */
/**
 * Waits for a [java.util.concurrent.FutureTask] and rethrows the task's own failure, so a DNG
 * that failed on its worker thread surfaces to the caller as the same exception type it threw
 * instead of an ExecutionException wrapper (the save jobs' callers classify those).
 */
private fun <T> java.util.concurrent.FutureTask<T>.awaitResult(): T =
    try {
        get()
    } catch (failure: java.util.concurrent.ExecutionException) {
        throw failure.cause ?: failure
    }

internal data class ZslMergeSaveSettings(
    val dngBackend: DngWriterBackend,
    val dngOverrides: DngMetadataOverrides,
    val dngCompression: DngCompression,
    val developedFormat: DevelopedFormat,
    val jxl: JxlOutputSettings,
    val exposureMode: CaptureExposureMode,
    val focusView: Pair<Float, Float>?,
    /**
     * Viewfinder zoom frozen at the shutter, carried on the settings like every other capture
     * choice. Reading the process-wide [UiZoomState] here instead would make the crop follow the
     * slider: a merge takes seconds, and any drag after the shutter would silently re-frame a
     * burst that is already captured.
     */
    val zoomMultiplier: Float = 1f,
)

/**
 * ZSL merge save jobs (HDR+ and MFSR), run on the writer thread with
 * settings frozen at shutter time. Extracted verbatim from
 * RawCameraController (ZSL merge jobs, docs/refactor/controller-remaining-plan.md);
 * the controller keeps ring selection, queue admission and completion.
 */
internal class HdrPlusZslSaveJob(private val context: Context, private val host: Host) {
    interface Host : RawFrameSaveJob.Host {
        /** Shared HDR+ Vulkan merge (lazily created, synchronized). */
        fun hdrPlusProcessor(): HdrPlusMerge
        /** Releases RAW reader slots as soon as merging finishes, before development/save. */
        fun releaseInputs()
        /** Gyro tracker running: per-frame gyro is reported only then. */
        fun gyroRunning(): Boolean
    }

    private data class HdrPlusFrameInput(
        val frame: BufferedRawFrame,
        val metadata: RawFrameMetadata,
        val result: TotalCaptureResult,
        val layout: RawPlaneLayout,
        val normalization: RawNormalization,
        val crop: RawCrop
    )

    /**
     * HDR+ ZSL burst merge on Vulkan (upstream merge_hdrplus 1:1 port):
     * coarse-to-fine tile alignment per alternate, then the robust
     * spatial or frequency merge toward the middle reference. One merged
     * CFA DNG out (plus JPEG when the capture format includes it).
     * Mirrors the HDR save tail.
     */
    fun run(
        selected: List<BufferedRawFrame>,
        settings: HdrPlusSettings,
        c: android.hardware.camera2.CameraCharacteristics,
        orientation: Int,
        cameraId: String,
        outputFormat: CaptureFormat,
        outputSettings: JpegOutputSettings,
        denoise: DenoiseSettings,
        captureTimeMillis: Long,
        captureGps: GpsLocation?,
        saveSettings: ZslMergeSaveSettings
    ) {
        val developedFormat = saveSettings.developedFormat
        val jxlSettings = saveSettings.jxl
        if (settings.keepSourceBurst) {
            val saver = DngSaver(context)
            val backend = saveSettings.dngBackend
            val overrides = saveSettings.dngOverrides
            selected.forEachIndexed { index, frame ->
                val metadata = RawFrameMetadataFactory.capture(
                    cameraId, frame.image, c, frame.result, orientation,
                    levelOverrides = saveSettings.dngOverrides,
                    zoomMultiplier = saveSettings.zoomMultiplier,
                )
                val buffer = frame.image.planes.single().buffer
                val position = buffer.position()
                val limit = buffer.limit()
                try {
                    saver.save(
                        frame.image, c, frame.result, orientation, overrides, metadata, backend,
                        fileNameSuffix = "F%02d".format(index),
                        captureId = captureTimeMillis,
                        gps = captureGps,
                        dngCompression = saveSettings.dngCompression
                    )
                } finally {
                    buffer.limit(limit)
                    buffer.position(position)
                }
            }
        }
        val inputs = selected.map { frame ->
            val metadata = RawFrameMetadataFactory.capture(
                cameraId, frame.image, c, frame.result, orientation,
                levelOverrides = saveSettings.dngOverrides,
                zoomMultiplier = saveSettings.zoomMultiplier,
            )
            val geometry = metadata.bufferGeometry as? RawBufferGeometry.Supported
                ?: throw UnsupportedOperationException("HDR+ RAW geometry is not supported")
            val normalization = metadata.normalizationOrNull()
                ?: throw UnsupportedOperationException("HDR+ normalization metadata is missing")
            metadata.rawDevelopmentUnsupportedReason?.let { error(it) }
            val plane = frame.image.planes.single()
            HdrPlusFrameInput(
                frame = frame, metadata = metadata, result = frame.result,
                layout = RawPlaneLayout(metadata.imageWidth, metadata.imageHeight,
                    plane.rowStride, plane.pixelStride,
                    geometry.sensorOriginX, geometry.sensorOriginY),
                normalization = normalization, crop = geometry.processingCrop
            )
        }
        val referenceIndex = inputs.size / 2
        host.reportSaveState("HDR+ MERGING")
        val tMerge0 = SystemClock.elapsedRealtime()
        val mergedRaw = try {
            host.hdrPlusProcessor().merge(
                inputs.map {
                    HdrPlusMerge.FrameInput(
                        it.frame.image, it.layout, it.crop, it.normalization,
                        timestampNanos = it.frame.timestampNanos,
                        gyroRadiansPerSecond = it.frame.motionRadiansPerSecond,
                        sensitivityIso = it.metadata.sensitivityIso ?: 0
                    )
                },
                referenceIndex, settings,
                noiseSource = WaveletDenoiser.NoiseSource.of(inputs[referenceIndex].metadata)
            )
        } finally {
            host.releaseInputs()
        } ?: run {
            Log.e(LOG_TAG, "HDR+ merge returned null")
            host.reportSaveState("HDR+ ERROR")
            return
        }
        val mergeMs = SystemClock.elapsedRealtime() - tMerge0
        // The decision was computed inside our own synchronized merge call;
        // fall back to keep-all only if the volatile was never published.
        val decision = host.hdrPlusProcessor().lastDecision
            ?.takeIf {
                it.keepIndices.size >= HdrPlusSettings.MIN_MERGE_FRAMES &&
                    it.keepIndices.all { index -> index in inputs.indices } &&
                    it.refIndex in it.keepIndices.indices
            }
            ?: HdrPlusAutoDecision(
                strength = settings.strength, highQuality = settings.highQuality,
                keepIndices = inputs.indices.toList(),
                refIndex = referenceIndex.coerceIn(inputs.indices),
                droppedIndices = emptyList(), strengthAuto = false, pathAuto = false
            )
        val keptInputs = decision.keepIndices.map { inputs[it] }
        Log.i(LOG_TAG, "HDR+ timing merge=${mergeMs}ms " +
            "frames=${keptInputs.size}/${inputs.size} ${mergedRaw.width}x${mergedRaw.height}")
        val refInput = keptInputs[decision.refIndex]
        val refMetadata = refInput.metadata.copy(
            compositeSourceCount = inputs.size,
            compositeUsedCount = keptInputs.size
        )
        val refResult = refInput.result
        val merged = requireNotNull(RawPreDemosaicPipeline.process(
            mergedRaw, refMetadata, PreDemosaicSettings()).cfa)
        val developer = host.developer()
        val effectiveMerged = if (denoise.aiEnabled) {
            developer.denoiseCfa(merged, refMetadata) ?: merged
        } else merged
        var referenceDng: String? = null
        val timings = host.hdrPlusProcessor().lastTimings
        val motion = host.hdrPlusProcessor().lastMotion
        // Same snapshot discipline as lastTimings: merge is synchronized
        // and this read follows our own merge call on the saver thread.
        val gyroReported = host.gyroRunning()
        // Per-frame strengths reach provenance only when they actually
        // vary (uniform reads as the burst strength line) — and never
        // when strength maps drove the merge (the maps supersede them).
        val frameStrengths = if (decision.strengthMaps != null) null
        else decision.frameStrengths?.takeIf { it.toSet().size > 1 }
        val provenance = HdrPlusProvenance(
            mergedFrames = keptInputs.size, selectedFrames = inputs.size,
            rejectedFrames = decision.droppedIndices.size,
            highQuality = decision.highQuality,
            strength = decision.strength,
            tileSize = HdrPlusSettings.TILE_SIZE,
            searchDistance = HdrPlusSettings.SEARCH_DISTANCE,
            referenceIndex = decision.refIndex,
            referenceTimestampNs = refMetadata.timestampNanos,
            referenceFrameNumber = refMetadata.frameNumber,
            sourceWidth = refMetadata.imageWidth, sourceHeight = refMetadata.imageHeight,
            sourceCameraId = refMetadata.cameraId,
            mergeMs = mergeMs,
            packMs = timings.packMs, gpuMs = timings.gpuMs, unpackMs = timings.unpackMs,
            mapsActive = decision.strengthMaps != null,
            frames = keptInputs.mapIndexed { keptPos, input ->
                // Delta vs the immediate capture-order predecessor
                // (which may itself have been dropped).
                val origIndex = decision.keepIndices[keptPos]
                val delta = motion?.pairDeltas?.getOrNull(origIndex - 1)
                    ?.takeIf { it.isFinite() }?.toFloat()
                HdrPlusFrameInfo(
                    input.metadata.exposureTimeNanos, input.metadata.sensitivityIso,
                    deltaVsPrev = delta,
                    gyroRadiansPerSecond =
                        input.frame.motionRadiansPerSecond.takeIf { gyroReported },
                    mergeStrength = frameStrengths?.getOrNull(keptPos)
                )
            }
        )
        // The DNG write is CPU quantisation plus 75 MB of IO; the develop is a GPU pass. They
        // read the same merged CFA and neither depends on the other, so they run at the same
        // time -- this used to be serial, paying both in full. The DNG's result is joined at
        // the end of the job, and a DNG failure still surfaces as the job's failure.
        val saveStarted = SystemClock.elapsedRealtime()
        val dngMs = java.util.concurrent.atomic.AtomicLong(0)
        val dngTask = if (outputFormat.includesDng) {
            java.util.concurrent.FutureTask {
                val t0 = SystemClock.elapsedRealtime()
                val name = DngSaver(context).saveMerged(
                    effectiveMerged, refMetadata, captureTimeMillis, gps = captureGps,
                    displayName = CaptureFileNames.hdrPlusDng(captureTimeMillis),
                    sixteenBit = true, hdrPlusProvenance = provenance,
                    dngCompression = saveSettings.dngCompression
                )
                dngMs.set(SystemClock.elapsedRealtime() - t0)
                name
            }.also { Thread(it, "rawlens-hdrplus-dng").start() }
        } else null
        val dngRequested = outputFormat.includesDng
        var developMs = 0L
        var encodeMs = 0L
        if (outputFormat.includesJpeg) {
            // The HDR+ average lives in the single-frame normalized domain, so the
            // merged develop meters through the LDR adaptive path on the merged CFA
            // itself (same highlight guards as single-frame). The DNG above stays
            // linear and dark; only this developed still lifts.
            if (developedFormat == DevelopedFormat.JXL) {
                val developed = developer.developMergedJxl(effectiveMerged, refMetadata,
                    RawDevelopmentSettings(
                        denoise = denoise,
                        exposureEv = 0.0,
                        adaptiveExposureStrength = adaptivePreviewStrength(
                            saveSettings.exposureMode, outputSettings
                        ),
                        adaptiveExposureTuning = AdaptiveExposureTuning.fromOutputSettings(
                            outputSettings, saveSettings.focusView
                        )
                    ), look = outputSettings, jxl = jxlSettings)
                JxlSaver(context).save(developed, refMetadata, refResult,
                    captureTimeMillis = captureTimeMillis,
                    typeSuffix = CaptureFileNames.TYPE_HDRPLUS, gps = captureGps)
                host.publishDevelopedJxlScope(developed)
                host.reportSaveState(if (dngRequested) "HDR+ MERGED+DNG • JXL" else "HDR+ MERGED • JXL")
            } else {
                val developed = developer.developMergedJpeg(effectiveMerged, refMetadata,
                    RawDevelopmentSettings(
                        denoise = denoise,
                        exposureEv = 0.0,
                        adaptiveExposureStrength = adaptivePreviewStrength(
                            saveSettings.exposureMode, outputSettings
                        ),
                        adaptiveExposureTuning = AdaptiveExposureTuning.fromOutputSettings(
                            outputSettings, saveSettings.focusView
                        )
                    ), outputSettings)
                try {
                    JpegSaver(context).save(developed, refMetadata,
                        refResult, captureTimeMillis = captureTimeMillis,
                        typeSuffix = CaptureFileNames.TYPE_HDRPLUS, gps = captureGps,
                        description = HdrPlusDngWriter.provenanceBlock(provenance))
                    host.publishDevelopedScope(developed.bitmap)
                    host.reportSaveState(if (dngRequested) "HDR+ MERGED+DNG" else "HDR+ MERGED")
                } finally {
                    recycleUltraHdrGainmapContents(developed)
                    developed.bitmap.recycle()
                }
            }
        } else {
            host.reportSaveState("HDR+ DNG SAVED")
        }
        val saveMs = SystemClock.elapsedRealtime() - saveStarted
        referenceDng = dngTask?.awaitResult()
        val totalMs = SystemClock.elapsedRealtime() - saveStarted
        // One line per capture with the stages that actually cost time. Serial used to be
        // dng + develop; the wait column is what the overlap saved.
        Log.i(
            LOG_TAG,
            "HDR+ save: frames=${keptInputs.size} merge=${mergeMs}ms dng=${dngMs.get()}ms " +
                "develop+encode=${saveMs}ms wait=${totalMs - saveMs}ms total=${totalMs}ms",
        )
    }

    private companion object {
        const val LOG_TAG = "RawLensCamera"
    }
}

internal class MfsrZslSaveJob(private val context: Context, private val host: HdrPlusZslSaveJob.Host) {
    /**
     * ZSL sharp merge job: unpack + sharpest-first order, native-resolve
     * SR merge (scale 1, RGB out), linear DNG via [LinearRgbDngSaver]
     * plus developed JPEG (Amaze bypassed through the sharp RGB tail;
     * same rcd_final color contract and AGX/encode as the CFA path).
     */
    fun run(
        selected: List<BufferedRawFrame>,
        settings: MfsrSettings,
        c: android.hardware.camera2.CameraCharacteristics,
        orientation: Int,
        cameraId: String,
        outputFormat: CaptureFormat,
        outputSettings: JpegOutputSettings,
        denoise: DenoiseSettings,
        captureTimeMillis: Long,
        captureGps: GpsLocation?,
        saveSettings: ZslMergeSaveSettings
    ) {
        val developedFormat = saveSettings.developedFormat
        val jxlSettings = saveSettings.jxl
        if (settings.keepSourceBurst) {
            val saver = DngSaver(context)
            val backend = saveSettings.dngBackend
            val overrides = saveSettings.dngOverrides
            selected.forEachIndexed { index, frame ->
                val metadata = RawFrameMetadataFactory.capture(
                    cameraId, frame.image, c, frame.result, orientation,
                    levelOverrides = saveSettings.dngOverrides,
                    zoomMultiplier = saveSettings.zoomMultiplier,
                )
                val buffer = frame.image.planes.single().buffer
                val position = buffer.position()
                val limit = buffer.limit()
                try {
                    saver.save(
                        frame.image, c, frame.result, orientation, overrides, metadata, backend,
                        fileNameSuffix = "F%02d".format(index),
                        captureId = captureTimeMillis,
                        gps = captureGps,
                        dngCompression = saveSettings.dngCompression
                    )
                } finally {
                    buffer.limit(limit)
                    buffer.position(position)
                }
            }
        }
        host.reportSaveState("${settings.label} MERGING")
        val tMerge0 = android.os.SystemClock.elapsedRealtime()
        val ordered = try {
            MfsrMerge.prepare(selected, settings.engine == MfsrEngine.LKR && settings.lkrMiddleReference,
                nativeSharpness = settings.engine == MfsrEngine.LKR) { frame ->
                RawFrameMetadataFactory.capture(cameraId, frame.image, c, frame.result, orientation, levelOverrides = saveSettings.dngOverrides)
            }
        } finally {
            // Images stay open for the just-in-time decode inside merge.
        }
        if (settings.engine == MfsrEngine.LKR) Log.i(LOG_TAG,
            "LK-R selection=${android.os.SystemClock.elapsedRealtime() - tMerge0}ms frames=${selected.size}")
        if (ordered.isNullOrEmpty()) {
            Log.e(LOG_TAG, "${settings.label} prepare produced no usable frames")
            host.reportSaveState("${settings.label} ERROR")
            host.releaseInputs()
            return
        }
        val merged = try {
            // Same shutter / ZSL / save tail for both engines; only the merge differs.
            if (settings.engine == MfsrEngine.LKR)
                MergeGpuQuiet.during { LkrMerge.mergeLive(context, ordered,
                    LkrMerge.Settings(dctPrior = settings.lkrDctPrior, tuning = LkrTuning.overrides(context),
                        zoom = settings.lkrZoom, fullRes = settings.lkrFullRes),
                    releaseInputs = host::releaseInputs) }
            else MfsrMerge.merge(context, ordered, settings)
        } finally {
            host.releaseInputs()
        } ?: run {
            Log.e(LOG_TAG, "${settings.label} merge returned null")
            host.reportSaveState("${settings.label} ERROR")
            return
        }
        try {
            val mergeMs = android.os.SystemClock.elapsedRealtime() - tMerge0
            Log.i(LOG_TAG, "${settings.label} timing merge=${mergeMs}ms frames=${merged.keptCount}/${selected.size} " +
                "${merged.width}x${merged.height}")
            val provenance = sharpMergeProvenance(merged, settings, selected.size,
                ordered.take(merged.keptCount).map { it.metadata },
                if (settings.engine == MfsrEngine.LKR && settings.lkrMiddleReference)
                    "auto (sharpest of the middle three, blur rejection)"
                else "auto (sharpness and blur rejection)")
            // Super-resolved output is a linear RGB plane, not a CFA, so the viewfinder crop
            // cannot go through ZoomCrop: the frame is cut where it is written and where it is
            // developed. Both read a WINDOW of the merge buffer, so the crop costs no copy.
            val window = ZoomCrop.rectFor(
                merged.width, merged.height, saveSettings.zoomMultiplier,
                minEdge = 2, evenAligned = false,
            )
            val outWidth = window?.width ?: merged.width
            val outHeight = window?.height ?: merged.height
            val windowColumn = window?.left ?: 0
            val windowRow = window?.top ?: 0
            if (window != null) {
                Log.i(LOG_TAG, "MFSR crop ${saveSettings.zoomMultiplier}x: " +
                    "${merged.width}x${merged.height} -> ${outWidth}x${outHeight} " +
                    "at $windowColumn,$windowRow")
            }
            // Same overlap as HDR+: the striped DNG write (CPU + IO) and the develop (GPU) read
            // the same merge buffer and are independent. The join has to happen before
            // merged.release() in the finally block -- the DNG thread reads merged.rgb.
            val saveStarted = SystemClock.elapsedRealtime()
            val dngMs = java.util.concurrent.atomic.AtomicLong(0)
            var referenceDng: String? = null
            val dngTask = if (outputFormat.includesDng) {
                java.util.concurrent.FutureTask {
                    val t0 = SystemClock.elapsedRealtime()
                    val name = saveMfsrDng(
                        merged, captureTimeMillis, captureGps, provenance,
                        outWidth, outHeight, windowColumn, windowRow,
                    )
                    dngMs.set(SystemClock.elapsedRealtime() - t0)
                    name
                }.also { Thread(it, "rawlens-mfsr-dng").start() }
            } else null
            val dngRequested = outputFormat.includesDng
            if (outputFormat.includesDng) {
                host.reportSaveState("${settings.label} DNG SAVED")
            }
            if (outputFormat.includesJpeg) {
                // Meter merged camera-linear RGB; DNG above retains the merge values.
                val development = RawDevelopmentSettings(
                    denoise = denoise,
                    adaptiveExposureStrength = adaptivePreviewStrength(saveSettings.exposureMode, outputSettings),
                    adaptiveExposureTuning = AdaptiveExposureTuning.fromOutputSettings(
                        outputSettings, saveSettings.focusView
                    )
                )
                val developer = host.developer()
                if (developedFormat == DevelopedFormat.JXL) {
                    val developed = developer.developMfsrJxl(
                        merged.rgb, outWidth, outHeight,
                        merged.refMetadata, development,
                        look = outputSettings, jxl = jxlSettings,
                        sourceRowStride = merged.width,
                        sourceColumnOffset = windowColumn,
                        sourceRowOffset = windowRow,
                    )
                    JxlSaver(context).save(developed, merged.refMetadata, merged.refResult,
                        captureTimeMillis = captureTimeMillis,
                        typeSuffix = settings.mergeFileSuffix(), gps = captureGps)
                    host.publishDevelopedJxlScope(developed)
                    host.reportSaveState(if (dngRequested) "${settings.label} MERGED+DNG • JXL" else "${settings.label} MERGED • JXL")
                } else {
                    val developed = developer.developMfsrJpeg(
                        merged.rgb, outWidth, outHeight,
                        merged.refMetadata, development, outputSettings,
                        sourceRowStride = merged.width,
                        sourceColumnOffset = windowColumn,
                        sourceRowOffset = windowRow,
                    )
                    try {
                        JpegSaver(context).save(developed, merged.refMetadata,
                            merged.refResult, captureTimeMillis = captureTimeMillis,
                            typeSuffix = settings.mergeFileSuffix(), gps = captureGps,
                            description = LinearRgbDngWriter.provenanceBlock(provenance))
                        host.publishDevelopedScope(developed.bitmap)
                        host.reportSaveState(if (dngRequested) "${settings.label} MERGED+DNG" else "${settings.label} MERGED")
                    } finally {
                        recycleUltraHdrGainmapContents(developed)
                        developed.bitmap.recycle()
                    }
                }
            } else {
                host.reportSaveState("${settings.label} DNG SAVED")
            }
            val saveMs = SystemClock.elapsedRealtime() - saveStarted
            // Joined inside the try, before merged.release(): the DNG thread is still reading
            // merged.rgb until it returns.
            referenceDng = dngTask?.awaitResult()
            val totalMs = SystemClock.elapsedRealtime() - saveStarted
            Log.i(
                LOG_TAG,
                "MFSR save: frames=${merged.keptCount} merge=${merged.mergeMs}ms " +
                    "dng=${dngMs.get()}ms develop+encode=${saveMs}ms " +
                    "wait=${totalMs - saveMs}ms total=${totalMs}ms crop=${outWidth}x$outHeight",
            )
        } finally {
            // Native output (MfsrMerge): free once the DNG/JPEG tail is done.
            merged.release()
        }
    }


    private fun saveMfsrDng(
        merged: MfsrMerge.MfsrResult,
        captureTimeMillis: Long,
        captureGps: GpsLocation?,
        provenance: MergeProvenance,
        outWidth: Int,
        outHeight: Int,
        windowColumn: Int,
        windowRow: Int,
    ): String {
        merged.rgb.rewind()
        // C11: striped from the native merge buffer (no 150 MB heap copy). With a zoom crop
        // the writer reads only the window's rows and columns, so the saved DNG is the framed
        // region rather than the whole sensor.
        return LinearRgbDngSaver(context).saveLinearRgbStriped(
            outWidth, outHeight, merged.rgb.asFloatBuffer(),
            merged.refMetadata, provenance, captureId = captureTimeMillis, gps = captureGps,
            sourceRowStride = merged.width,
            sourceColumnOffset = windowColumn,
            sourceRowOffset = windowRow,
        )
    }

    private companion object {
        const val LOG_TAG = "RawLensCamera"
    }
}
