package com.example.urban

import kotlin.math.pow

data class LiveBeacon(
    val beacon: Beacon,
    val currentDistance: Double
) {
    val x get() = beacon.x
    val y get() = beacon.y
}

object PositioningEngine {
    fun trilaterate(beacons: List<LiveBeacon>): Pair<Double, Double>? {
        val active = beacons.filter { it.currentDistance > 0 }
        if (active.size < 3) return null

        var totalWeight = 0.0
        var weightedX = 0.0
        var weightedY = 0.0

        for (beacon in active) {
            val weight = 1.0 / maxOf(0.1, beacon.currentDistance.pow(2))
            weightedX += beacon.x * weight
            weightedY += beacon.y * weight
            totalWeight += weight
        }
        return Pair(weightedX / totalWeight, weightedY / totalWeight)
    }
}