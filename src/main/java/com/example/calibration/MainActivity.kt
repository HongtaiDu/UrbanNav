package com.example.calibration

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.calibration.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var scanning = false
    private val handler = Handler(Looper.getMainLooper())

    // How long (ms) before a device is evicted from the list
    private val STALE_EVICT_MS = 8_000L

    // Ticker that runs every second to evict stale devices and refresh the list
    private val stalenessTickerRunnable = object : Runnable {
        override fun run() {
            evictStaleDevices()
            refreshList()
            handler.postDelayed(this, 1_000L)
        }
    }

    private val deviceMap = LinkedHashMap<String, BleDevice>()
    private val filterMap  = HashMap<String, RssiFilter>()
    private lateinit var adapter: BeaconAdapter

    private val requiredPermissions: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        }

    private val REQUEST_PERMISSIONS = 1

    // ── BLE Scan Callback ────────────────────────────────────────────────────

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val mac  = result.device.address
            val name = result.scanRecord?.deviceName ?: "Unknown Device"
            val rawRssi = result.rssi

            val filter = filterMap.getOrPut(mac) { RssiFilter(windowSize = 10) }
            val filteredRssi = filter.update(rawRssi)

            val device = BleDevice(
                name         = name,
                mac          = mac,
                rssi         = rawRssi,
                filteredRssi = filteredRssi,
                lastSeen     = System.currentTimeMillis()
            )

            runOnUiThread {
                deviceMap[mac] = device
                setStatus("Scanning… ${deviceMap.size} device(s) found")
            }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { onScanResult(0, it) }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e("BLE", "Scan failed: $errorCode")
            runOnUiThread { setStatus("❌ Scan failed: $errorCode") }
        }
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.title = "RSSI Calibration"

        adapter = BeaconAdapter(emptyList()) { device -> openDetail(device) }
        binding.rvBeacons.layoutManager = LinearLayoutManager(this)
        binding.rvBeacons.adapter = adapter

        binding.btnScanAgain.setOnClickListener { if (!scanning) startScanFlow() }

        startScanFlow()
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(stalenessTickerRunnable)
        handler.post(stalenessTickerRunnable)
        if (!scanning) startScanFlow()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(stalenessTickerRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopScan()
    }

    // ── Scan Flow ────────────────────────────────────────────────────────────

    private fun startScanFlow() {
        if (!hasPermissions()) {
            ActivityCompat.requestPermissions(this, requiredPermissions, REQUEST_PERMISSIONS)
            return
        }

        val btAdapter = (getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (btAdapter == null || !btAdapter.isEnabled) {
            setStatus("❌ Bluetooth is off.")
            return
        }

        // Check for Location Services toggle (GPS)
        if (!isLocationEnabled()) {
            setStatus("❌ Location Services (GPS) are off.")
            return
        }

        if (!btAdapter.isLeExtendedAdvertisingSupported) {
            Log.w("BLE", "Device does not support Extended Advertising.")
        }

        bluetoothLeScanner = btAdapter.bluetoothLeScanner ?: run {
            setStatus("❌ BLE scanner unavailable.")
            return
        }
        startScan(btAdapter.isLeExtendedAdvertisingSupported)
    }

    private fun isLocationEnabled(): Boolean {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    private fun startScan(supportExtended: Boolean) {
        if (scanning) return

        val settingsBuilder = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)

        // Enable Extended Advertising if supported
        if (supportExtended) {
            settingsBuilder.setLegacy(false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                settingsBuilder.setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
            }
        }

        val settings = settingsBuilder.build()

        scanning = true
        binding.btnScanAgain.isEnabled = false
        binding.btnScanAgain.text = "Scanning…"
        setStatus("Scanning…")

        try {
            bluetoothLeScanner?.startScan(null, settings, scanCallback)
        } catch (e: SecurityException) {
            setStatus("❌ Permission denied.")
            setScanButtonReady()
            scanning = false
        }
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        try { bluetoothLeScanner?.stopScan(scanCallback) }
        catch (e: SecurityException) { Log.e("BLE", "stopScan: ${e.message}") }
        runOnUiThread { setScanButtonReady() }
    }

    // ── Staleness ────────────────────────────────────────────────────────────

    private fun evictStaleDevices() {
        val cutoff = System.currentTimeMillis() - STALE_EVICT_MS
        val staleKeys = deviceMap.entries
            .filter { it.value.lastSeen < cutoff }
            .map { it.key }
        staleKeys.forEach { mac ->
            deviceMap.remove(mac)
            filterMap.remove(mac)
        }
        if (staleKeys.isNotEmpty()) {
            setStatus("Scanning… ${deviceMap.size} device(s) found")
        }
    }

    // ── UI Helpers ───────────────────────────────────────────────────────────

    private fun refreshList() {
        val sorted = deviceMap.values.sortedByDescending { it.rssi }
        adapter.updateList(sorted)
        binding.rvBeacons.visibility = if (sorted.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun openDetail(device: BleDevice) {
        startActivity(Intent(this, BeaconDetailActivity::class.java).apply {
            putExtra("beacon_name", device.name)
            putExtra("beacon_mac",  device.mac)
        })
    }

    private fun setStatus(msg: String) { binding.tvScanStatus.text = msg }
    private fun setScanButtonReady() {
        binding.btnScanAgain.isEnabled = true
        binding.btnScanAgain.text = "Scan"
    }

    private fun hasPermissions() = requiredPermissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            startScanFlow()
        }
    }
}
