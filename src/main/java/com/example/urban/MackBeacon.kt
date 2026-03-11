package com.example.urban

data class BleDevice(
    val name: String,
    val mac: String,
    val rssi: Int,                               // latest raw RSSI
    val filteredRssi: Double = rssi.toDouble(),  // after filter pipeline
    val distance: Double = -1.0,                 // -1 = not yet calculated
    val lastSeen: Long = System.currentTimeMillis() // epoch ms — set on every update
)