# Fork notes: porting LK-R's crop zoom to HDR+ and MFSR

Status of this fork: upstream `af7eb9f2` (2.4.58 line) verbatim, plus one feature --
`LkrZoom.X1_2` (1.2x) in the LK-R viewfinder chip -- and `.github/workflows/build.yml`,
which builds and signs this fork. Everything else is upstream, unchanged.

These notes record the exact change points for the next step, verified against the source
above, so the work does not have to be rediscovered.

## How upstream implements LK-R zoom (the pattern to copy)

1. `MfsrSettings.kt` -- `enum class LkrZoom(factor, label)`: `X1, X1_2, X1_5, X2, FULL_2X`.
   `crop(w, h)` is the generic centre window `w / factor`, rounded to an even origin and an
   even size (Bayer phase). `nextZoom()` is the chip cycle.
2. `LkrMerge.kt` -- `Settings(scale, crop, zoom, fullRes)`; any `factor > 1` maps to
   `scale = 2, crop = zoom.crop(w, h)`. The merged frame is therefore solved on a 2x grid
   inside the framed window and area-downsampled back to the sensor size, so the output is
   always sensor resolution with real super-resolved detail.
3. `LkrZoomControls.kt` -- the chip cycles the enum and calls `applyLkrPreviewZoom(factor)`,
   which centre-scales `viewfinder` / `rawViewfinder` / `bguViewfinder`. Capture is
   untouched: every frame is still full sensor.

## HDR+ -- feasible, but crop-only (no super-resolution)

`HdrPlusMerge` merges in the CFA domain and has no x2 solve, so a zoomed HDR+ frame is a
plain window of the sensor: same pixel count as the crop, no extra detail. State this in the
UI copy if it is exposed; do not promise LK-R behaviour.

Change points:

* `ZslMergeSaveJobs.kt:124` builds each `HdrPlusMerge.FrameInput` with
  `crop = geometry.processingCrop`. Intersect that rect with `zoom.crop(imageWidth,
  imageHeight)`, keeping the origin and the size even and at least 16 px (the merge requires
  an even crop of at least 16x16).
* `ZslMergeSaveSettings` (8 fields, no zoom today) needs the multiplier. Preferred carrier:
  a field frozen at the shutter, set where the settings object is constructed in
  `RawCameraController.zslMergeSaveSettings(...)` from the active merge settings. A
  process-wide holder works but reintroduces the "drag after the shutter re-frames a burst
  that is already captured" hazard already fixed for the old fork zoom.
* `LkrZoomControls.kt` shows the chip only while LK-R is the active engine; it has to be
  visible for HDR+ too, with its own persisted key.
* Nothing else: `HdrPlusMerge` already derives the output's Bayer phase and origin from the
  frame crop (`first.normalization.sensorPattern.shifted(sensorCropX, sensorCropY)`,
  `UnpackedRawCfa(..., first.crop, sensorCropX, sensorCropY)`), so DNG tags stay correct once
  the input crop changes.

## MFSR -- needs the decode window, not a parameter

`MfsrMerge.runEngine` takes `decoders: List<() -> Array<FloatArray>>` producing full
`h x w` planes, and `scale = if (settings.mfsrFullRes) 2 else 1`. A zoom therefore cannot be
threaded in as one argument:

1. Each frame is decoded through index maps built from the metadata's processing crop; the
   zoom window has to replace that rect, so the decoder emits the window only.
2. `w`, `h` become the window size and `scale` is forced to 2 for a cropped step (the engine
   already supports 2x -- that is what `mfsrFullRes` does today).
3. The output (`outW = w * scale`) has to be area-downsampled back to the sensor size, and
   `MfsrResult.width/height` plus the sub-region lens-shading path
   (`MfsrMerge` has a dedicated "merge of a sensor sub-region" helper) must be told the same
   frame, or the shading is applied at the wrong scale.

Risk: a mistake here does not degrade gracefully -- it shifts the image or breaks the CFA
phase. This is why it is a separate, device-verified step.

## Verification

* Unit: extend `MfsrSettingsTest.lkrZoomCropsTheCentreAndKeepsSensorOutput` with the same
  crop assertions for whatever step is added (1.2x of 4080x3060 is a 3400x2550 window at an
  even 340,254 origin).
* Device: the crop must be visible in the file dimensions and in the log, e.g.
  `ZoomCrop`-style lines or `MFSR crop 1.2x: 4096x3072 -> ...`; a full-size output means the
  multiplier never reached the merge, not that the crop failed.
