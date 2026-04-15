package com.example.urban

import kotlin.math.sqrt

/**
 * Phase 2 — Weighted k-Nearest Neighbours (wk-NN) position estimator.
 *
 * Algorithm
 * ---------
 * 1. Collect every MAC address that appears in any stored fingerprint.
 * 2. Express both the live scan and every stored fingerprint as vectors
 *    over that full MAC space (missing beacons → MISSING_RSSI = -100 dBm).
 * 3. Compute the Euclidean distance between the live vector and each
 *    stored fingerprint vector.
 * 4. Sort ascending, pick the k nearest.
 * 5. Assign each neighbour a weight = 1 / distance  (closer in RSSI space
 *    → higher weight).  If any distance is exactly 0 (perfect match) that
 *    fingerprint's coordinates are returned immediately.
 * 6. Return the weighted mean of the k neighbours' physical (x, y) coords.
 *
 * Using -100 dBm for absent beacons means that a fingerprint collected
 * while a beacon was invisible will be pushed far away from a live vector
 * where that beacon is audible (and vice-versa), which is the desired
 * behaviour.
 */
object FingerprintEngine {

    /** RSSI substitute for any beacon not present in a vector. */
    private const val MISSING_RSSI = -100.0

    /**
     * Estimate position using weighted k-NN.
     *
     * Each of the k nearest fingerprints contributes to the final position
     * proportionally to 1 / (its Euclidean distance in RSSI space).
     * A fingerprint with distance 2 therefore pulls the estimate twice as
     * hard as one with distance 4.
     *
     * @param liveRssi     MAC → current filtered RSSI from the live scan
     * @param fingerprints Fingerprint database loaded from storage
     * @param k            Number of nearest neighbours to use
     *                     (automatically clamped to the database size)
     * @return Estimated (x, y) in metres, or null if the database is empty
     */
    fun estimate(
        liveRssi: Map<String, Double>,
        fingerprints: List<FingerprintRecord>,
        k: Int = 3
    ): Pair<Double, Double>? {
        if (fingerprints.isEmpty()) return null

        val effectiveK = k.coerceIn(1, fingerprints.size)

        // Full MAC universe across the entire fingerprint database
        val allMacs = fingerprints.flatMap { it.rssiMap.keys }.toSet()

        // Live vector aligned to allMacs
        val liveVec = allMacs.map { mac -> liveRssi[mac] ?: MISSING_RSSI }

        // Rank fingerprints by Euclidean distance in RSSI space
        val ranked = fingerprints
            .map { fp ->
                val fpVec = allMacs.map { mac -> fp.rssiMap[mac] ?: MISSING_RSSI }
                Pair(euclidean(liveVec, fpVec), fp)
            }
            .sortedBy { it.first }

        val nearest = ranked.take(effectiveK)

        // If the closest fingerprint is a perfect RSSI match, return it directly
        if (nearest.first().first == 0.0) {
            val exact = nearest.first().second
            return Pair(exact.x, exact.y)
        }

        // Weighted mean: weight_i = 1 / dist_i
        var totalWeight = 0.0
        var weightedX   = 0.0
        var weightedY   = 0.0

        for ((dist, fp) in nearest) {
            val w = 1.0 / dist
            weightedX   += fp.x * w
            weightedY   += fp.y * w
            totalWeight += w
        }

        return Pair(weightedX / totalWeight, weightedY / totalWeight)
    }

    private fun euclidean(a: List<Double>, b: List<Double>): Double =
        sqrt(a.zip(b).sumOf { (ai, bi) -> (ai - bi) * (ai - bi) })
}
