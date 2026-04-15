package com.example.urban

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

/**
 * Transparent overlay for the fingerprint collection map.
 *
 * Draws on top of the floor-plan ImageView (same FrameLayout), using the
 * same letterbox transform as [UserDotView] so dots land on the correct
 * room coordinates.
 *
 * Visual encoding:
 *   ● Green dots (numbered) — already-saved fingerprint locations
 *   ● Red dot               — the pending collection point (before pressing Record)
 *
 * Touch handling converts screen pixels → room metres and forwards the
 * result to [setOnPointSelectedListener].
 */
class FingerprintMapOverlayView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private var maxX = 10f
    private var maxY = 10f

    private var pendingX: Float? = null
    private var pendingY: Float? = null
    private val savedPoints = mutableListOf<Pair<Float, Float>>()  // room coords

    private var onPointSelected: ((Double, Double) -> Unit)? = null

    // ── Paints ────────────────────────────────────────────────────────────────

    private val pendingPaint = Paint().apply {
        color = Color.parseColor("#E53E3E")   // red
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val savedPaint = Paint().apply {
        color = Color.parseColor("#38A169")   // green
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val labelPaint = Paint().apply {
        color = Color.WHITE
        textSize = 22f
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun setRoomBounds(w: Double, h: Double) {
        maxX = w.toFloat()
        maxY = h.toFloat()
    }

    /** Show a red dot at the given room position (tap result, before recording). */
    fun setPendingPoint(x: Double, y: Double) {
        pendingX = x.toFloat()
        pendingY = y.toFloat()
        invalidate()
    }

    /** Remove the pending red dot (called after a fingerprint is saved). */
    fun clearPendingPoint() {
        pendingX = null
        pendingY = null
        invalidate()
    }

    /** Replace the displayed green dots with the current fingerprint list. */
    fun setFingerprints(fps: List<FingerprintRecord>) {
        savedPoints.clear()
        fps.forEach { savedPoints.add(Pair(it.x.toFloat(), it.y.toFloat())) }
        invalidate()
    }

    fun setOnPointSelectedListener(listener: (Double, Double) -> Unit) {
        onPointSelected = listener
    }

    // ── Touch ─────────────────────────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            val (scale, offX, offY) = letterboxTransform()
            val roomX = ((event.x - offX) / scale).toDouble().coerceIn(0.0, maxX.toDouble())
            val roomY = (maxY - (event.y - offY) / scale).toDouble().coerceIn(0.0, maxY.toDouble())
            onPointSelected?.invoke(roomX, roomY)
            performClick()
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    // ── Drawing ───────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val (scale, offX, offY) = letterboxTransform()

        // Saved fingerprints — green numbered dots
        savedPoints.forEachIndexed { index, (rx, ry) ->
            val sx = offX + rx * scale
            val sy = offY + (maxY - ry) * scale
            canvas.drawCircle(sx, sy, 22f, savedPaint)
            canvas.drawText("${index + 1}", sx, sy + 8f, labelPaint)
        }

        // Pending point — red dot
        val px = pendingX
        val py = pendingY
        if (px != null && py != null) {
            val sx = offX + px * scale
            val sy = offY + (maxY - py) * scale
            canvas.drawCircle(sx, sy, 26f, pendingPaint)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Returns (scale, offsetX, offsetY) using the same letterbox maths as
     * [UserDotView] so both overlays stay aligned with the floor-plan image.
     */
    private fun letterboxTransform(): Triple<Float, Float, Float> {
        val scaleX = width.toFloat() / maxX
        val scaleY = height.toFloat() / maxY
        val scale  = min(scaleX, scaleY)
        val offX   = (width  - maxX * scale) / 2f
        val offY   = (height - maxY * scale) / 2f
        return Triple(scale, offX, offY)
    }
}
