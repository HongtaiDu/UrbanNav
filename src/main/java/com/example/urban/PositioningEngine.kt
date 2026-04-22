package com.example.urban

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

data class LiveBeacon(
    val beacon: Beacon,
    val currentDistance: Double
) {
    val x get() = beacon.x
    val y get() = beacon.y
}

class PositionEmaFilter(private val alpha: Double = 0.3) {
    private var lastX: Double? = null
    private var lastY: Double? = null

    fun update(newX: Double, newY: Double): Pair<Double, Double> {
        val currentX = lastX?.let { (1 - alpha) * it + alpha * newX } ?: newX
        val currentY = lastY?.let { (1 - alpha) * it + alpha * newY } ?: newY
        
        lastX = currentX
        lastY = currentY
        
        return Pair(currentX, currentY)
    }

    fun reset() {
        lastX = null
        lastY = null
    }
}

/**
 * 2D Velocity-Capped Kalman Filter (VC-Kalman).
 *
 * Dynamically adjusts Process Noise (Q) based on estimated speed.
 * - Moving fast? Q increases to track you instantly.
 * - Standing still? Q drops to near-zero to "lock" the position and ignore signal jitter.
 */
class PositionKalmanFilter(
    private val minQ: Double = 0.005,  // Near-zero drift when stationary
    private val maxQ: Double = 0.3,    // High drift when moving to stay responsive
    private val R: Double = 2.0        // Measurement noise (fixed)
) {
    private var x: Double? = null
    private var y: Double? = null
    private var Px: Double = 1.0
    private var Py: Double = 1.0
    
    // To estimate velocity
    private var lastUpdateTs: Long = 0

    fun update(newX: Double, newY: Double): Pair<Double, Double> {
        val now = System.currentTimeMillis()
        if (x == null || y == null) {
            x = newX
            y = newY
            lastUpdateTs = now
            return Pair(newX, newY)
        }

        // 1. Estimate Current Velocity (v = d/t)
        val dt = (now - lastUpdateTs) / 1000.0
        val dist = sqrt((newX - x!!).pow(2) + (newY - y!!).pow(2))
        val velocity = if (dt > 0) dist / dt else 0.0
        
        // 2. Adaptive Q (Process Noise)
        // High velocity -> High Q (Responsive)
        // Low velocity  -> Low Q (Stable)
        val qFactor = min(1.0, velocity / 1.5) // Max responsiveness at 1.5 m/s (walking speed)
        val adaptiveQ = minQ + (maxQ - minQ) * qFactor

        // --- Update X ---
        val pxPred = Px + adaptiveQ
        val kx = pxPred / (pxPred + R)
        x = x!! + kx * (newX - x!!)
        Px = (1.0 - kx) * pxPred

        // --- Update Y ---
        val pyPred = Py + adaptiveQ
        val ky = pyPred / (pyPred + R)
        y = y!! + ky * (newY - y!!)
        Py = (1.0 - ky) * pyPred

        lastUpdateTs = now
        return Pair(x!!, y!!)
    }

    fun reset() {
        x = null; y = null
        Px = 1.0; Py = 1.0
        lastUpdateTs = 0
    }
}

object PositioningEngine {

    /**
     * Estimates user position using Inverse Distance Weighting (IDW).
     */
    fun weightedCentroid(beacons: List<LiveBeacon>): Pair<Double, Double>? {
        if (beacons.size < 3) return null

        var totalWeight = 0.0
        var weightedX = 0.0
        var weightedY = 0.0

        for (beacon in beacons.sortedBy { it.currentDistance }) {
            val dist = max(beacon.currentDistance, 0.5)
            val weight = 1.0 / dist.pow(0.5)

            weightedX += beacon.x * weight
            weightedY += beacon.y * weight
            totalWeight += weight
        }

        return Pair(weightedX / totalWeight, weightedY / totalWeight)
    }
}
