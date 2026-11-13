// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Horizontal zoom track for the viewfinder: 1× on the left, [ZoomStops.MAX_MULTIPLIER] on the
 * right, one detent per physical rear lens, a thumb you can drag anywhere in between.
 *
 * Layout is a single row: the track, tick marks with their labels underneath, and the live
 * multiplier above the thumb. Everything is drawn in this view (no child views), so a drag
 * never has to fight a parent for the touch stream, and the whole thing is one composited
 * layer over the preview.
 *
 * The view owns no camera state: it reports the multiplier it was dragged to through
 * [onZoom] and is told what to display through [setZoom], which is what lets the controller
 * (lens switch, snapping to a physical lens, pinch) stay the only place that decides.
 */
class ZoomSliderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /** Called with the dragged multiplier; already snapped to a detent when it is close. */
    var onZoom: ((Float) -> Unit)? = null

    /** Called once when a drag ends, so the caller can settle (re-render, resume preview). */
    var onZoomSettled: ((Float) -> Unit)? = null

    private var detents: List<Float> = listOf(1f)
    private var zoom = 1f
    private var dragging = false
    private var activeDetent: Float? = null

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66FFFFFF
        style = Paint.Style.FILL
    }
    private val activeTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.FILL
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x99FFFFFF.toInt()
        strokeWidth = dp(1.5f)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val activeTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        strokeWidth = dp(2.5f)
        strokeCap = Paint.Cap.ROUND
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCCFFFFFF.toInt()
        textSize = dp(10f)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val activeLabelPaint = Paint(labelPaint).apply { color = 0xFFFFFFFF.toInt() }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.FILL
    }
    private val thumbRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66000000
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
    }
    private val trackRect = RectF()

    init {
        isClickable = true
        // A track is a fine-grained control; the semantics reader should still be able to
        // name it even though it draws its own labels.
        contentDescription = context.getString(R.string.zoom_slider_description)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    /** Detents to draw, sorted, always including 1×. */
    fun setDetents(multipliers: List<Float>) {
        detents = if (multipliers.isEmpty()) listOf(1f) else multipliers.distinct().sorted()
        invalidate()
    }

    /** Show [multiplier] without reporting it back through [onZoom]. */
    fun setZoom(multiplier: Float) {
        zoom = ZoomStops.clamp(multiplier)
        activeDetent = ZoomStops.snap(zoom, detents)
        invalidate()
    }

    fun currentZoom(): Float = zoom

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = resolveSize((resources.displayMetrics.widthPixels * 0.62f).roundToInt(),
            widthMeasureSpec)
        val height = resolveSize((dp(46f)).roundToInt(), heightMeasureSpec)
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        val trackLeft = paddingLeft + dp(6f)
        val trackRight = width - paddingRight - dp(6f)
        val thumbRadius = dp(7f)
        val trackCenterY = height - paddingBottom - dp(18f)
        trackRect.set(trackLeft, trackCenterY - dp(1.5f), trackRight, trackCenterY + dp(1.5f))
        canvas.drawRoundRect(trackRect, dp(2f), dp(2f), trackPaint)

        // Filled part of the track shows where the current frame sits.
        val thumbX = trackLeft + (trackRight - trackLeft) * ZoomStops.fractionOf(zoom)
        canvas.drawRoundRect(
            RectF(trackLeft, trackRect.top, thumbX, trackRect.bottom), dp(2f), dp(2f),
            activeTrackPaint,
        )

        for (detent in detents) {
            val x = trackLeft + (trackRight - trackLeft) * ZoomStops.fractionOf(detent)
            val active = activeDetent != null && abs(detent - activeDetent!!) < 0.001f
            val half = if (active) dp(6f) else dp(4f)
            canvas.drawLine(x, trackCenterY - half, x, trackCenterY + half,
                if (active) activeTickPaint else tickPaint)
            canvas.drawText(
                ZoomStops.label(detent), x, trackCenterY + dp(14f),
                if (active) activeLabelPaint else labelPaint,
            )
        }

        // The live value rides above the thumb rather than replacing a tick label, so both
        // the neighbouring detents and the current position stay readable while dragging.
        canvas.drawCircle(thumbX, trackCenterY, thumbRadius, thumbPaint)
        canvas.drawCircle(thumbX, trackCenterY, thumbRadius, thumbRingPaint)
        val label = ZoomStops.label(zoom)
        val labelY = trackCenterY - thumbRadius - dp(4f) - (labelPaint.ascent() + labelPaint.descent()) / 2f
        canvas.drawText(label, thumbX.coerceIn(trackLeft, trackRight), labelY, activeLabelPaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                if (!isEnabled) return false
                if (!dragging) {
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                moveTo(event.x)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!dragging) return false
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                onZoomSettled?.invoke(zoom)
                if (event.actionMasked == MotionEvent.ACTION_CANCEL) performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun moveTo(x: Float) {
        val trackLeft = paddingLeft + dp(6f)
        val trackRight = width - paddingRight - dp(6f)
        if (trackRight <= trackLeft) return
        val fraction = ((x - trackLeft) / (trackRight - trackLeft)).coerceIn(0f, 1f)
        val raw = ZoomStops.multiplierAt(fraction)
        val snapped = ZoomStops.snap(raw, detents)
        val next = snapped ?: raw
        val detentChanged = snapped != null && (activeDetent == null ||
            abs(snapped - activeDetent!!) > 0.001f)
        activeDetent = snapped
        if (abs(next - zoom) < 1e-4f) return
        zoom = next
        if (detentChanged) haptic()
        invalidate()
        onZoom?.invoke(zoom)
    }

    private fun haptic() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            performHapticFeedback(HapticFeedbackConstants.TEXT_HANDLE_MOVE)
        } else {
            @Suppress("DEPRECATION")
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }
    }
}
