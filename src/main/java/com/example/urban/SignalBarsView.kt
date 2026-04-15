package com.example.urban

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Draws 4 ascending bars like a WiFi/signal icon.
 *
 * Levels:
 *   STRONG  (filteredRssi > -65)  → 4 bars, green  (#38A169)
 *   MEDIUM  (filteredRssi > -80)  → 2 bars, yellow (#D69E2E)
 *   WEAK    (filteredRssi ≤ -80)  → 1 bar,  red    (#E53E3E)
 *   NONE    (not yet received)    → 4 bars, light grey (inactive)
 */
class SignalBarsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class Level { NONE, WEAK, MEDIUM, STRONG }

    private val BAR_COUNT = 4
    private val BAR_GAP_RATIO = 0.25f      // gap between bars as fraction of bar width
    private val COLOR_STRONG  = 0xFF38A169.toInt()
    private val COLOR_MEDIUM  = 0xFFD69E2E.toInt()
    private val COLOR_WEAK    = 0xFFE53E3E.toInt()
    private val COLOR_INACTIVE = 0xFFCBD5E0.toInt()

    private val activePaint   = Paint(Paint.ANTI_ALIAS_FLAG)
    private val inactivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_INACTIVE }

    var level: Level = Level.NONE
        set(value) { field = value; invalidate() }

    /** Convenience: derive level from a filtered RSSI value. */
    fun setRssi(filteredRssi: Double) {
        level = when {
            filteredRssi > -65.0 -> Level.STRONG
            filteredRssi > -80.0 -> Level.MEDIUM
            else                 -> Level.WEAK
        }
    }

    /** Call when the beacon goes out of range to reset to the grey state. */
    fun reset() { level = Level.NONE }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()

        // How many bars are "lit" and what colour?
        val (litBars, activeColor) = when (level) {
            Level.STRONG  -> Pair(4, COLOR_STRONG)
            Level.MEDIUM  -> Pair(2, COLOR_MEDIUM)
            Level.WEAK    -> Pair(1, COLOR_WEAK)
            Level.NONE    -> Pair(0, COLOR_INACTIVE)
        }

        activePaint.color = activeColor

        // Total width split into BAR_COUNT bars + (BAR_COUNT-1) gaps
        val gapFraction = BAR_GAP_RATIO
        // barW * BAR_COUNT + barW * gapFraction * (BAR_COUNT-1) = w
        val barW = w / (BAR_COUNT + gapFraction * (BAR_COUNT - 1))
        val gap  = barW * gapFraction

        for (i in 0 until BAR_COUNT) {
            // Bar height grows linearly: bar 0 = 25% of h, bar 3 = 100% of h
            val heightFraction = 0.25f + 0.75f * (i.toFloat() / (BAR_COUNT - 1))
            val barH = h * heightFraction

            val left   = i * (barW + gap)
            val right  = left + barW
            val top    = h - barH
            val bottom = h

            val rect = RectF(left, top, right, bottom)
            val radius = barW * 0.2f   // slightly rounded corners

            val paint = if (i < litBars) activePaint else inactivePaint
            canvas.drawRoundRect(rect, radius, radius, paint)
        }
    }

    // Default size: 48 × 36 dp
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val density = resources.displayMetrics.density
        val defaultW = (48 * density).toInt()
        val defaultH = (36 * density).toInt()
        val w = resolveSize(defaultW, widthMeasureSpec)
        val h = resolveSize(defaultH, heightMeasureSpec)
        setMeasuredDimension(w, h)
    }
}