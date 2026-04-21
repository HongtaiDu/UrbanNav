package com.example.urban

import kotlin.math.pow

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

object PositioningEngine {

    /**
     * Estimates user position using Inverse Distance Weighting (IDW).
     * Each beacon acts as a "gravity well" pulling the estimate toward it,
     * weighted by the inverse of its distance.
     *
     * Requires at least 3 beacons to produce a result.
     */
    fun weightedCentroid(beacons: List<LiveBeacon>): Pair<Double, Double>? {
        if (beacons.size < 3) return null

        var totalWeight = 0.0
        var weightedX = 0.0
        var weightedY = 0.0

        for (beacon in beacons.sortedBy { it.currentDistance }) {
            val dist = Math.max(beacon.currentDistance, 0.1)
            val weight = 1.0 / dist.pow(1)

            weightedX += beacon.x * weight
            weightedY += beacon.y * weight
            totalWeight += weight
        }

        return Pair(weightedX / totalWeight, weightedY / totalWeight)
    }
}
