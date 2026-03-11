package com.example.urban

/**
 * Per-beacon RSSI filter pipeline:
 * raw RSSI → median filter → weighted moving average → filtered RSSI
 */
class RssiFilter(private val windowSize: Int = 10) {

    private val window = ArrayDeque<Int>(windowSize)

    // Weighted moving average state
    private var weightedRssi: Double? = null
    private val alpha = 0.3  // weight for newest median value (0 = ignore new, 1 = ignore history)

    /**
     * Add a new raw RSSI sample and return the filtered RSSI.
     */
    fun update(rawRssi: Int): Double {
        // --- Step 1: Add to rolling window ---
        if (window.size >= windowSize) window.removeFirst()
        window.addLast(rawRssi)

        // --- Step 2: Median filter ---
        val median = median(window)

        // --- Step 3: Weighted moving average ---
        weightedRssi = if (weightedRssi == null) {
            median                              // first sample — seed with median
        } else {
            alpha * median + (1.0 - alpha) * weightedRssi!!
        }

        return weightedRssi!!
    }

    /**
     * Reset all state (e.g. when a beacon goes out of range).
     */
    fun reset() {
        window.clear()
        weightedRssi = null
    }

    /**
     * How many samples are currently in the window.
     */
    val sampleCount get() = window.size

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun median(values: ArrayDeque<Int>): Double {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[mid - 1] + sorted[mid]) / 2.0
        } else {
            sorted[mid].toDouble()
        }
    }
}