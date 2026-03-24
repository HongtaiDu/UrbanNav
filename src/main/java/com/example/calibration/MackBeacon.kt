package com.example.calibration

data class BleDevice(
    val name: String,
    val mac: String,
    val rssi: Int,                               // latest raw RSSI
    val filteredRssi: Double = rssi.toDouble(),  // after filter pipeline
    val lastSeen: Long = System.currentTimeMillis()
)