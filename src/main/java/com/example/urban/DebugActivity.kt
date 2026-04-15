package com.example.urban

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.urban.databinding.ActivityDebugBinding

class DebugActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDebugBinding
    private lateinit var adapter: DebugBeaconAdapter
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var scanning = false
    private val handler = Handler(Looper.getMainLooper())

    private val registeredBeacons = mutableMapOf<String, Beacon>()
    private val statsMap = mutableMapOf<String, DebugStats>()

    private val ticker = object : Runnable {
        override fun run() {
            refreshStats()
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDebugBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Debug Dashboard"

        loadBeacons(filesDir).forEach { registeredBeacons[it.mac] = it }

        adapter = DebugBeaconAdapter()
        binding.recyclerDebug.layoutManager = LinearLayoutManager(this)
        binding.recyclerDebug.adapter = adapter

        val btManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        val btAdapter = btManager.adapter
        bluetoothLeScanner = btAdapter.bluetoothLeScanner

        if (btAdapter != null && btAdapter.isEnabled) {
            startScan(btAdapter.isLeExtendedAdvertisingSupported)
        }
        
        handler.post(ticker)
    }

    private fun startScan(supportExtended: Boolean) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) return
        
        val settingsBuilder = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)

        // Use same non-legacy settings as MainActivity to catch all beacon types
        if (supportExtended) {
            settingsBuilder.setLegacy(false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                settingsBuilder.setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
            }
        }

        val settings = settingsBuilder.build()

        scanning = true
        try {
            bluetoothLeScanner?.startScan(null, settings, scanCallback)
        } catch (e: SecurityException) {
            Log.e("DebugBLE", "Scan permission error: ${e.message}")
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val mac = result.device.address
            val beacon = registeredBeacons[mac] ?: return

            val stats = statsMap.getOrPut(mac) { DebugStats(beacon) }
            stats.addSample(result.rssi)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { onScanResult(0, it) }
        }
    }

    private fun refreshStats() {
        val now = System.currentTimeMillis()
        val displayList = statsMap.values
            .filter { now - it.lastSeen < 10000 } // only show active beacons
            .map { it.toData() }
            .sortedByDescending { it.filteredRssi }
        
        adapter.updateData(displayList)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(ticker)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
            try {
                bluetoothLeScanner?.stopScan(scanCallback)
            } catch (e: Exception) {
                Log.e("DebugBLE", "Error stopping scan: ${e.message}")
            }
        }
    }

    private class DebugStats(val beacon: Beacon) {
        var lastSeen = 0L
        var totalPackets = 0
        var rawRssi = 0
        private val rssiSamples = mutableListOf<Int>()
        private val filter = RssiFilter()
        private val distFilter = DistanceFilter()
        private var startTime = System.currentTimeMillis()

        fun addSample(rssi: Int) {
            lastSeen = System.currentTimeMillis()
            totalPackets++
            rawRssi = rssi
            rssiSamples.add(rssi)
            filter.addSample(rssi)
        }

        fun toData(): DebugBeaconData {
            val filteredRssi = filter.calculateBatch() ?: rawRssi.toDouble()
            val rawDist = DistanceCalculator.calculate(filteredRssi)
            val smoothDist = distFilter.update(rawDist) ?: rawDist
            
            val durationSec = (System.currentTimeMillis() - startTime) / 1000.0
            val freq = if (durationSec > 0) totalPackets / durationSec else 0.0

            // Simple variance calculation for debug view
            val avg = if (rssiSamples.isNotEmpty()) rssiSamples.average() else 0.0
            val variance = if (rssiSamples.size > 1) {
                rssiSamples.map { Math.pow(it - avg, 2.0) }.sum() / rssiSamples.size
            } else 0.0

            return DebugBeaconData(
                beacon = beacon,
                rawRssi = rawRssi,
                filteredRssi = filteredRssi,
                distance = smoothDist,
                rssiVariance = variance,
                distanceVariance = 0.1, // placeholder
                packetLoss = 0.0,      // placeholder
                frequency = freq,
                lastSeen = lastSeen
            )
        }
    }
}
