package com.example.urban

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class UserDotView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    private val dotPaint = Paint().apply {
        color = Color.parseColor("#4285F4") // Google Blue
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private var xPos = 0f
    private var yPos = 0f
    private var hasPosition = false
    private var maxX = 5f // Default room size
    private var maxY = 5f

    fun setRoomBounds(w: Double, h: Double) {
        maxX = w.toFloat()
        maxY = h.toFloat()
    }

    fun updatePosition(x: Double, y: Double) {
        // Clamp to room bounds so the dot never leaves the view
        xPos = x.toFloat().coerceIn(0f, maxX)
        yPos = y.toFloat().coerceIn(0f, maxY)
        hasPosition = true
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!hasPosition) return

        // 1. Calculate the scale for both dimensions
        val scaleX = width.toFloat() / maxX
        val scaleY = height.toFloat() / maxY

        // 2. Use the SMALLEST scale for both to maintain aspect ratio (Letterboxing)
        val scale = Math.min(scaleX, scaleY)

        // 3. Center the map if the room is smaller than the screen
        val offsetX = (width - (maxX * scale)) / 2f
        val offsetY = (height - (maxY * scale)) / 2f

        // 4. Transform Meters to Pixels
        val screenX = offsetX + (xPos * scale)

        // 5. Calculate screenY for Bottom-Left origin (0,0)
        // In Android, Y increases downwards, so we subtract yPos from maxY
        val screenY = offsetY + ((maxY - yPos) * scale)

        canvas.drawCircle(screenX, screenY, 25f, dotPaint)
    }
}
