//package com.example.urban
//
///**
// * Per-beacon RSSI filter pipeline using a rolling window:
// * Maintains a window of raw RSSI samples from the last 2 seconds.
// * Every time calculation is requested, it trims outliers, calculates the
// * median of the rolling window, and updates a weighted moving average.
// */
//class RssiFilter {
//
//    private data class Sample(val timestamp: Long, val rssi: Int)
//    private val samples = mutableListOf<Sample>()
//
//    // Window configuration
//    private val windowDurationMs = 1000L // 1 second rolling window
//    private val trimFraction = 0.10      // remove top/bottom 10%
//
//    // Weighted moving average state
//    private var weightedRssi: Double? = null
//    private val alpha = 0.7  // weight for newest median value (0 = ignore new, 1 = ignore history)
//
//    /**
//     * Add a raw RSSI sample to the rolling window.
//     */
//    fun addSample(rawRssi: Int) {
//        val now = System.currentTimeMillis()
//        samples.add(Sample(now, rawRssi))
//        cleanup(now)
//    }
//
//    /**
//     * Processes the rolling window:
//     *   1. Remove samples older than the window duration.
//     *   2. Sort and trim the top/bottom 10% of outliers.
//     *   3. Take the median of the remaining samples.
//     *   4. Update a weighted moving average with the median.
//     *
//     * Returns the new filtered RSSI, or the previous value if the window is empty.
//     */
//    fun calculateBatch(): Double? {
//        val now = System.currentTimeMillis()
//        cleanup(now)
//
//        if (samples.isEmpty()) return weightedRssi
//
//        // 1. Get raw values from current rolling window
//        val rawValues = samples.map { it.rssi }
//
//        // 2. Sort and trim top/bottom 10%
//        val sorted = rawValues.sorted()
//        val trimCount = (sorted.size * trimFraction).toInt()
//        val trimmed = if (trimCount > 0 && sorted.size > trimCount * 2) {
//            sorted.subList(trimCount, sorted.size - trimCount)
//        } else {
//            sorted
//        }
//
//        // 3. Median of trimmed list
//        val medianValue = median(trimmed)
//
//        // 4. Weighted moving average
//        weightedRssi = if (weightedRssi == null) {
//            medianValue
//        } else {
//            (alpha * medianValue) + ((1.0 - alpha) * weightedRssi!!)
//        }
//
//        return weightedRssi
//    }
//
//    /**
//     * Remove samples older than [windowDurationMs].
//     */
//    private fun cleanup(now: Long) {
//        val cutoff = now - windowDurationMs
//        samples.removeAll { it.timestamp < cutoff }
//    }
//
//    /**
//     * Reset all state.
//     */
//    fun reset() {
//        samples.clear()
//        weightedRssi = null
//    }
//
//    /**
//     * How many samples are currently in the rolling window.
//     */
//    val bufferedCount get() = samples.size
//
//    private fun median(values: List<Int>): Double {
//        if (values.isEmpty()) return 0.0
//        val mid = values.size / 2
//        return if (values.size % 2 == 0) {
//            (values[mid - 1] + values[mid]) / 2.0
//        } else {
//            values[mid].toDouble()
//        }
//    }
//}

package com.example.urban

/**
 * Per-beacon RSSI filter using a 1-D Kalman filter.
 *
 * Model assumptions (constant-state, noisy measurements):
 *   - State:       the "true" RSSI value (single scalar)
 *   - Prediction:  RSSI doesn't change on its own → state transition = identity
 *   - Measurement: each raw RSSI sample is the true value + Gaussian noise
 *
 * Tuning knobs:
 *   Q (processNoise)     – how much we expect the *real* signal to drift
 *                           between updates.  Larger → tracks fast movement
 *                           but more jitter.
 *   R (measurementNoise) – how noisy individual RSSI readings are.
 *                           Larger → smoother output but slower to react.
 *
 * The public API (addSample / calculateBatch / reset / bufferedCount) is
 * unchanged so no other files need to be modified.
 */
class RssiFilter {

    // ── Kalman tuning parameters ─────────────────────────────────────────────

    /** Process noise – how much the true RSSI can change per update. */
    private val Q = 1.2

    /** Measurement noise – how noisy a single raw RSSI reading is. */
    private val R = 12.5

    // ── Kalman state ─────────────────────────────────────────────────────────

    /** Current state estimate (filtered RSSI). Null until first sample. */
    private var x: Double? = null

    /** Current estimate uncertainty (error covariance). */
    private var P: Double = 1.0

    // ── Sample buffer (keeps the addSample / calculateBatch contract) ────────

    private data class Sample(val timestamp: Long, val rssi: Int)
    private val samples = mutableListOf<Sample>()
    private val windowDurationMs = 1000L   // keep 1 second of samples

    /**
     * Add a raw RSSI sample to the buffer.
     * Samples are consumed in [calculateBatch].
     */
    fun addSample(rawRssi: Int) {
        val now = System.currentTimeMillis()
        samples.add(Sample(now, rawRssi))
        cleanup(now)
    }

    /**
     * Processes all buffered samples through the Kalman filter and returns
     * the current filtered RSSI estimate, or null if no data has ever arrived.
     *
     * Each sample in the buffer is fed sequentially into the filter so that
     * bursts of BLE advertisements within one tick are all incorporated.
     */
    fun calculateBatch(): Double? {
        val now = System.currentTimeMillis()
        cleanup(now)

        if (samples.isEmpty()) return x      // nothing new — return last estimate

        for (sample in samples) {
            kalmanUpdate(sample.rssi.toDouble())
        }

        // Buffer consumed — clear so samples aren't processed twice
        samples.clear()

        return x
    }

    /**
     * Single Kalman filter iteration (predict + update).
     */
    private fun kalmanUpdate(measurement: Double) {
        if (x == null) {
            // First measurement — initialise state directly
            x = measurement
            P = 1.0
            return
        }

        // ── Predict ──────────────────────────────────────────────────────
        // State prediction: x_pred = x  (constant model, no drift term)
        // Covariance prediction:
        val pPred = P + Q

        // ── Update ───────────────────────────────────────────────────────
        // Kalman gain
        val K = pPred / (pPred + R)

        // State update
        x = x!! + K * (measurement - x!!)

        // Covariance update
        P = (1.0 - K) * pPred
    }

    /**
     * Remove samples older than [windowDurationMs].
     */
    private fun cleanup(now: Long) {
        val cutoff = now - windowDurationMs
        samples.removeAll { it.timestamp < cutoff }
    }

    /**
     * Reset all state (e.g. when a beacon goes out of range).
     */
    fun reset() {
        samples.clear()
        x = null
        P = 1.0
    }

    /**
     * How many samples are currently in the buffer.
     */
    val bufferedCount get() = samples.size
}