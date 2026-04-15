package com.example.urban

/**
 * Second-stage 1-D Kalman filter that smooths the **distance** values
 * produced by [DistanceCalculator].
 *
 * Pipeline:  raw RSSI → [RssiFilter] (Kalman #1)
 *                      → [DistanceCalculator] (equation)
 *                      → [DistanceFilter] (Kalman #2)  ← this class
 *
 * Because distance is derived from a log-scale equation, its noise
 * characteristics differ from raw RSSI.  The tuning knobs here are
 * intentionally gentler so that the position dot moves smoothly on the map.
 *
 * Tuning knobs:
 *   Q (processNoise)     – how fast the real distance can change between
 *                           updates (user walking speed).  Larger → more
 *                           responsive but jitterier.
 *   R (measurementNoise) – how noisy a single distance estimate is.
 *                           Larger → smoother but slower to react.
 */
class DistanceFilter {

    // ── Kalman tuning parameters ─────────────────────────────────────────────

    /** Process noise – expected change in distance per update (metres²). */
    private val Q = 0.5

    /** Measurement noise – variance of a single distance estimate (metres²). */
    private val R = 6.0

    // ── Kalman state ─────────────────────────────────────────────────────────

    /** Current filtered distance estimate.  Null until first measurement. */
    private var x: Double? = null

    /** Error covariance. */
    private var P: Double = 1.0

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Feed a new raw distance measurement and return the filtered distance.
     *
     * @param rawDistance distance in metres from [DistanceCalculator.calculate].
     *                    Negative values (out of range) are ignored.
     * @return smoothed distance, or null if no valid measurement has been received.
     */
    fun update(rawDistance: Double): Double? {
        if (rawDistance < 0) return x   // out-of-range → keep last estimate

        if (x == null) {
            // First valid measurement — initialise directly
            x = rawDistance
            P = 1.0
            return x
        }

        // ── Predict ──────────────────────────────────────────────────────
        val pPred = P + Q

        // ── Update ───────────────────────────────────────────────────────
        val K = pPred / (pPred + R)
        x = x!! + K * (rawDistance - x!!)
        P = (1.0 - K) * pPred

        return x
    }

    /**
     * Returns the current filtered distance, or null if no data yet.
     */
    fun currentEstimate(): Double? = x

    /**
     * Reset all state (e.g. when a beacon goes out of range for a long time).
     */
    fun reset() {
        x = null
        P = 1.0
    }
}