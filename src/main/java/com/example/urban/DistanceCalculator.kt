package com.example.urban

import kotlin.math.pow

/**
 * Converts a filtered RSSI value into an estimated distance in metres.
 *
 * Strategy:
 *   1. If RSSI ≥ -59 dBm → clamp to 1.0 m  (very close)
 *   2. For RSSI between measured calibration points → linear interpolation
 *   3. Beyond the last calibration point → trend line:  RSSI = -0.9 * d - 59
 *      rearranged:  d = (RSSI + 59) / -0.9
 *   4. Weaker than [maxRangeRssi] → out of range (-1.0)
 *
 * Calibration data measured in-situ:
 *   1.00 m → -55.0 dBm
 *   1.25 m → -59.0 dBm
 *   1.50 m → -59.8 dBm
 *   1.75 m → -60.2 dBm
 *   2.00 m → -61.0 dBm
 *   3.00 m → -61.9 dBm
 *   4.00 m → -63.2 dBm
 *   5.00 m → -64.1 dBm
 *   6.00 m → -66.7 dBm
 *   7.00 m → -67.1 dBm
 */
object DistanceCalculator {

    // ── Tuning knobs (for log-distance equation, kept for reference) ─────────

    /** RSSI measured at exactly 1 metre (calibrate per beacon for best results). */
    private const val txPower = -55.0

    /** Path loss exponent — start at 2.0 and increase for cluttered environments. */
    private const val n = 2.5

    /** RSSI threshold below which we consider the beacon "out of range". */
    private const val maxRangeRssi = -90.0

    // ── Calibration table (RSSI → Distance) ─────────────────────────────────
    // Sorted from strongest (closest) to weakest (farthest)

    private val calibration = listOf(
        -55.0 to  1.00,   // measured
        -59.0 to  1.25,   // measured
        -59.8 to  1.50,   // measured
        -60.2 to  1.75,   // measured
        -61.0 to  2.00,   // measured
        -63.0 to  3.00,   // measured
        -63.8 to  4.00,   // measured
        -64.3 to  5.00,   // measured
        -66.7 to  6.00,   // measured
        -67.1 to  7.00,   // measured
        -67.8 to  8.00,   // measured
    )

    // ── Trend line for extrapolation beyond 7 m ─────────────────────────────
    // RSSI = -0.9 * distance - 59
    // Rearranged: distance = (RSSI + 59) / -0.9

    private const val trendSlope = -0.9
    private const val trendIntercept = -59.0

    // ── API ──────────────────────────────────────────────────────────────────

    /**
     * @param filteredRssi the RSSI value after the Kalman filter pipeline
     * @return estimated distance in metres, or -1.0 if out of range
     */
    fun calculate(filteredRssi: Double): Double {

        // ── Log-distance path loss equation (commented out) ──────────────
        // val distance = 10.0.pow((txPower - filteredRssi) / (10.0 * n))
        // return distance.coerceAtLeast(0.1)

        // ── Special case: very close ─────────────────────────────────────
        if (filteredRssi >= -59.0) return 1.0

        // ── Out of range ─────────────────────────────────────────────────
        if (filteredRssi <= maxRangeRssi) return -1.0

        // ── Lookup table with linear interpolation ───────────────────────
        for (i in 0 until calibration.size - 1) {
            val (rssi1, dist1) = calibration[i]
            val (rssi2, dist2) = calibration[i + 1]

            if (filteredRssi <= rssi1 && filteredRssi > rssi2) {
                val ratio = (filteredRssi - rssi1) / (rssi2 - rssi1)
                return dist1 + ratio * (dist2 - dist1)
            }
        }

        // ── Beyond last calibration point → use trend line ───────────────
        // RSSI = -0.9 * distance - 59  →  distance = (RSSI + 59) / -0.9
        if (filteredRssi <= calibration.last().first) {
            val distance = (filteredRssi - trendIntercept) / trendSlope
            return distance.coerceAtLeast(calibration.last().second)
        }

        return -1.0
    }

    /**
     * Formats distance for display, e.g. "1.23 m" or "Out of range"
     */
    fun format(distanceMetres: Double): String {
        if (distanceMetres < 0) return "Out of range"
        return if (distanceMetres < 10.0) {
            "%.2f m".format(distanceMetres)
        } else {
            "%.1f m".format(distanceMetres)
        }
    }
}

//package com.example.urban
//
//import kotlin.math.pow
//
///**
// * Converts a filtered RSSI value into an estimated distance in metres
// * using the Log-Distance Path Loss model:
// *
// *     d = 10 ^ ((txPower - rssi) / (10 * n))
// *
// * Tuning parameters:
// *   txPower – the RSSI measured at exactly 1 metre from the beacon.
// *             Measure this per-beacon if possible; -59 dBm is a common default.
// *   n       – the path loss exponent.  Depends on the environment:
// *               Free space ≈ 2.0
// *               Indoor (open) ≈ 2.0–3.0
// *               Indoor (walls/obstacles) ≈ 3.0–4.0
// *
// * Signals weaker than [maxRangeRssi] return -1.0 (out of range).
// */
//object DistanceCalculator {
//
//    // ── Tuning knobs ─────────────────────────────────────────────────────────
//
//    /** RSSI measured at exactly 1 metre (calibrate per beacon for best results). */
//    private const val txPower = -55.0
//
//    /** Path loss exponent — start at 2.0 and increase for cluttered environments. */
//    private const val n = 4
//
//    /** RSSI threshold below which we consider the beacon "out of range". */
//    private const val maxRangeRssi = -90.0
//
//    // ── API ──────────────────────────────────────────────────────────────────
//
//    /**
//     * @param filteredRssi the RSSI value after the Kalman filter pipeline
//     * @return estimated distance in metres, or -1.0 if out of range
//     */
//    fun calculate(filteredRssi: Double): Double {
//        // Too weak — treat as out of range
//        if (filteredRssi <= maxRangeRssi) return -1.0
//
//        val distance = (10.0.pow((txPower - filteredRssi) / (10.0 * n)))
//
//        // Clamp to a minimum of 0.1 m to avoid nonsensical values
//        return distance.coerceAtLeast(0.1)
//    }
//
//    /**
//     * Formats distance for display, e.g. "1.23 m" or "Out of range"
//     */
//    fun format(distanceMetres: Double): String {
//        if (distanceMetres < 0) return "Out of range"
//        return if (distanceMetres < 10.0) {
//            "%.2f m".format(distanceMetres)
//        } else {
//            "%.1f m".format(distanceMetres)
//        }
//    }
//}
// ==========================================================================================
//package com.example.urban
//
///**
// * Converts a filtered RSSI value into an estimated distance in metres
// * using a calibration lookup table with linear interpolation.
// *
// * 1m: measured directly as -65 dBm
// * 2m–20m: from trend line  RSSI = -0.7 * distance - 74
// * Signals weaker than the 20m threshold return -1.0 (out of range).
// */
//object DistanceCalculator {
//
//    // Calibration table: RSSI (dBm) → Distance (metres)
//    // 1m: measured directly as -65 dBm
//    // 2m–20m: from trend line RSSI = -0.7 * distance - 74
//    private val calibration = listOf(
//        -55.0 to  1.0,   // measured
//        -59.0 to  1.25,   // measured
//        -60.0 to  1.5,   // measured
//        -60.9 to  1.75,   // measured
//        -61.8 to  2.0,   // measured
//        -62.7 to  3.0,   // measured
//        -63.6 to  4.0,   // -0.7(4) - 74
//        -64.5 to  5.0,   // -0.7(5) - 74
//        -65.4 to  6.0,   // -0.7(6) - 74
//        -66.3 to  7.0,   // -0.7(7) - 74
//        -67.2 to  8.0,   // -0.7(8) - 74
//        -70.0 to  9.0,
//        -71.0 to 10.0,
//        -72.0 to 11.0,
//        -73.0 to 12.0,   // -0.7(12) - 74
//        -74.0 to 13.0,   // -0.7(13) - 74
//        -75.0 to 14.0,   // -0.7(14) - 74
//        -76.0 to 15.0,   // -0.7(15) - 74
//    )
//
//    /**
//     * @param filteredRssi the RSSI value after the filter pipeline
//     * @return estimated distance in metres, or -1.0 if out of range (>20m)
//     */
//    fun calculate(filteredRssi: Double): Double {
//        // Stronger than closest calibration point → clamp to 1m
//        if (filteredRssi >= calibration.first().first) return calibration.first().second
//
//        // Weaker than farthest calibration point → out of range
//        if (filteredRssi <= calibration.last().first) return -1.0
//
//        // Find the two surrounding calibration points and interpolate
//        for (i in 0 until calibration.size - 1) {
//            val (rssi1, dist1) = calibration[i]
//            val (rssi2, dist2) = calibration[i + 1]
//
//            if (filteredRssi <= rssi1 && filteredRssi > rssi2) {
//                // Linear interpolation
//                val ratio = (filteredRssi - rssi1) / (rssi2 - rssi1)
//                return dist1 + ratio * (dist2 - dist1)
//            }
//        }
//
//        return -1.0
//    }
//
//    /**
//     * Formats distance for display, e.g. "1.23 m" or "Out of range"
//     */
//    fun format(distanceMetres: Double): String {
//        if (distanceMetres < 0) return "Out of range"
//        return if (distanceMetres < 10.0) {
//            "%.2f m".format(distanceMetres)
//        } else {
//            "%.1f m".format(distanceMetres)
//        }
//    }
//}