package com.example.urban

import kotlin.math.pow

/**
 * Converts a filtered RSSI value into an estimated distance in metres
 * using the log-distance path loss model:
 *
 *   distance = 10 ^ ((measuredPower - filteredRssi) / (10 * n))
 *
 * measuredPower = calibrated RSSI at exactly 1 metre (stored per beacon)
 * n             = path loss exponent (2.0 = open space)
 */
object DistanceCalculator {

    private const val PATH_LOSS_EXPONENT = 2.0

    /**
     * @param filteredRssi  output of RssiFilter.update()
     * @param measuredPower the beacon's calibrated 1m RSSI (e.g. -59)
     * @return estimated distance in metres, clamped to 0.1m minimum
     */
    fun calculate(filteredRssi: Double, measuredPower: Int): Double {
        val exponent = (measuredPower - filteredRssi) / (10.0 * PATH_LOSS_EXPONENT)
        val distance = 10.0.pow(exponent)
        return maxOf(0.1, distance)   // never return negative or zero
    }

    /**
     * Formats distance for display, e.g. "1.23 m" or "12.3 m"
     */
    fun format(distanceMetres: Double): String {
        return if (distanceMetres < 10.0) {
            "%.2f m".format(distanceMetres)
        } else {
            "%.1f m".format(distanceMetres)
        }
    }
}