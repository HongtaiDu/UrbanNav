package com.example.urban

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class BleDevice(
    val name: String,
    val mac: String,
    val rssi: Int,                               // latest raw RSSI
    val filteredRssi: Double = rssi.toDouble(),  // after filter pipeline
    val distance: Double = -1.0,                 // -1 = not yet calculated
    val lastSeen: Long = System.currentTimeMillis() // epoch ms — set on every update
)

data class Beacon(
    val mac: String,
    val name: String,
    val x: Double,
    val y: Double
)

/**
 * Room-level configuration: dimensions and floor plan image.
 * Stored once, shared by all beacons.
 */
data class RoomConfig(
    val width: Double = 10.0,
    val height: Double = 10.0,
    val imagePath: String? = null
)

// ─── Beacon JSON Helpers ──────────────────────────────────────────────────────

fun loadBeacons(filesDir: File): MutableList<Beacon> {
    val file = File(filesDir, "beacons.json")
    if (!file.exists()) return mutableListOf()
    return try {
        val root = JSONObject(file.readText())
        val arr = root.getJSONArray("beacons")
        MutableList(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            Beacon(
                o.getString("mac"),
                o.getString("name"),
                o.getDouble("x"),
                o.getDouble("y")
            )
        }
    } catch (e: Exception) {
        mutableListOf()
    }
}

fun saveBeacons(filesDir: File, beacons: List<Beacon>) {
    val arr = JSONArray()
    beacons.forEach { b ->
        arr.put(JSONObject().apply {
            put("mac", b.mac)
            put("name", b.name)
            put("x", b.x)
            put("y", b.y)
        })
    }
    File(filesDir, "beacons.json").writeText(JSONObject().put("beacons", arr).toString(4))
}

// ─── Room Config JSON Helpers ─────────────────────────────────────────────────

fun loadRoomConfig(filesDir: File): RoomConfig {
    val file = File(filesDir, "room_config.json")
    if (!file.exists()) return RoomConfig()
    return try {
        val o = JSONObject(file.readText())
        RoomConfig(
            width = o.optDouble("width", 10.0),
            height = o.optDouble("height", 10.0),
            imagePath = if (o.has("imagePath") && !o.isNull("imagePath")) o.getString("imagePath") else null
        )
    } catch (e: Exception) {
        RoomConfig()
    }
}

fun saveRoomConfig(filesDir: File, config: RoomConfig) {
    val o = JSONObject().apply {
        put("width", config.width)
        put("height", config.height)
        put("imagePath", config.imagePath ?: JSONObject.NULL)
    }
    File(filesDir, "room_config.json").writeText(o.toString(4))
}
