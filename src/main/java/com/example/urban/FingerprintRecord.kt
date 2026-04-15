package com.example.urban

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * A single fingerprint: the physical location (x, y) in metres plus the
 * average RSSI observed from each beacon at that location during the
 * offline survey (Phase 1).
 */
data class FingerprintRecord(
    val x: Double,
    val y: Double,
    val rssiMap: Map<String, Double>,   // MAC → average RSSI (dBm)
    val label: String = ""
)

// ─── JSON Helpers ─────────────────────────────────────────────────────────────

fun loadFingerprints(filesDir: File): MutableList<FingerprintRecord> {
    val file = File(filesDir, "fingerprints.json")
    if (!file.exists()) return mutableListOf()
    return try {
        val root = JSONObject(file.readText())
        val arr  = root.getJSONArray("fingerprints")
        MutableList(arr.length()) { i ->
            val o       = arr.getJSONObject(i)
            val rssiObj = o.getJSONObject("rssi")
            val rssiMap = rssiObj.keys().asSequence()
                .associate { key -> key to rssiObj.getDouble(key) }
            FingerprintRecord(
                x      = o.getDouble("x"),
                y      = o.getDouble("y"),
                rssiMap = rssiMap,
                label  = o.optString("label", "")
            )
        }
    } catch (e: Exception) {
        mutableListOf()
    }
}

fun saveFingerprints(filesDir: File, fingerprints: List<FingerprintRecord>) {
    val arr = JSONArray()
    fingerprints.forEach { fp ->
        val rssiObj = JSONObject()
        fp.rssiMap.forEach { (mac, rssi) -> rssiObj.put(mac, rssi) }
        arr.put(JSONObject().apply {
            put("x",     fp.x)
            put("y",     fp.y)
            put("rssi",  rssiObj)
            put("label", fp.label)
        })
    }
    File(filesDir, "fingerprints.json")
        .writeText(JSONObject().put("fingerprints", arr).toString(4))
}
