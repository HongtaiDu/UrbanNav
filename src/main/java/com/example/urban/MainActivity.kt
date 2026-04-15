package com.example.urban

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper

import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.urban.databinding.ActivityMainBinding
import com.google.android.material.textfield.TextInputEditText

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var scanning = false
    private val handler = Handler(Looper.getMainLooper())

    // How long (ms) before a device is evicted from the list
    private val STALE_EVICT_MS = 8_000L

    // Restart the BLE scan every 4 minutes to prevent Android from throttling it
    private val SCAN_RESTART_MS = 4 * 60 * 1000L

    private val scanRestartRunnable = object : Runnable {
        override fun run() {
            Log.d("BLE", "Restarting scan to avoid Android throttle")
            stopScan()
            startScan()
            handler.postDelayed(this, SCAN_RESTART_MS)
        }
    }

    // Ticker that runs every second to process batches, evict stale devices and refresh the list
    private val stalenessTickerRunnable = object : Runnable {
        override fun run() {
            updateFilteredRssis()
            evictStaleDevices()
            refreshList()
            handler.postDelayed(this, 1_000L)
        }
    }

    private val deviceMap = LinkedHashMap<String, BleDevice>()
    private val filterMap  = HashMap<String, RssiFilter>()
    private val distanceFilterMap = HashMap<String, DistanceFilter>()  // Kalman #2 (distance)
    private val registeredBeacons = HashMap<String, Beacon>()
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
            val mac = result.device.address

            // Only process beacons that are registered in the management system (beacons.json)
            if (!registeredBeacons.containsKey(mac)) return

            // Add raw sample to the 1-second batch buffer
            val filter = filterMap.getOrPut(mac) { RssiFilter() }
            filter.addSample(result.rssi)

            runOnUiThread {
                val existing = deviceMap[mac]
                val name = result.scanRecord?.deviceName ?: registeredBeacons[mac]?.name ?: "Unknown Beacon"

                // Update deviceMap with latest raw info and timestamp
                deviceMap[mac] = BleDevice(
                    name = name,
                    mac = mac,
                    rssi = result.rssi,
                    filteredRssi = existing?.filteredRssi ?: result.rssi.toDouble(),
                    distance = existing?.distance ?: -1.0,
                    lastSeen = System.currentTimeMillis()
                )
                setStatus("Searching… ${deviceMap.size} device(s) found")
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

        // Set up the custom Toolbar
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = "UrbanNav"

        loadBeacons(filesDir).forEach { registeredBeacons[it.mac] = it }

        adapter = BeaconAdapter(emptyList()) { selectedMacs ->
            binding.btnConnect.isEnabled = selectedMacs.isNotEmpty()
        }
        binding.rvBeacons.layoutManager = LinearLayoutManager(this)
        binding.rvBeacons.adapter = adapter

        binding.btnConnect.setOnClickListener {
            val selected = adapter.getSelectedMacs()
            if (selected.isNotEmpty()) {
                val intent = Intent(this, BeaconDetailActivity::class.java)
                intent.putStringArrayListExtra("selected_macs", ArrayList(selected))
                startActivity(intent)

                // Clear selection so the next session starts fresh
                adapter.clearSelection()
            }
        }
        binding.btnAdmin.setOnClickListener {
            showAdminPasswordDialog()
        }

        startScanFlow()
    }

    private fun showAdminPasswordDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_admin_password, null)
        val etPassword = dialogView.findViewById<TextInputEditText>(R.id.et_password)
        val tvError = dialogView.findViewById<TextView>(R.id.tv_error)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton("Enter", null) // Set null to override behavior later
            .setNegativeButton("Cancel") { d, _ -> d.dismiss() }
            .create()

        dialog.show()

        // Override the "Enter" button click to keep the dialog open if password is wrong
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val password = etPassword.text.toString()
            if (password == "1") {
                dialog.dismiss()
                startActivity(Intent(this, AdminHubActivity::class.java))
            } else {
                tvError.visibility = View.VISIBLE
                etPassword.text?.clear()
            }
        }
    }

    override fun onResume() {
        super.onResume()

        // Reload registered beacons
        registeredBeacons.clear()
        loadBeacons(filesDir).forEach { registeredBeacons[it.mac] = it }

        // Clear previous scan results to ensure we only show what's currently registered
        deviceMap.clear()
        filterMap.clear()
        distanceFilterMap.clear()

        // Restart the staleness ticker whenever the screen is visible
        handler.removeCallbacks(stalenessTickerRunnable)
        handler.post(stalenessTickerRunnable)

        // Always start a fresh scan when the screen becomes visible
        startScanFlow()

        // Schedule periodic scan restarts to avoid Android BLE throttling
        handler.removeCallbacks(scanRestartRunnable)
        handler.postDelayed(scanRestartRunnable, SCAN_RESTART_MS)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(stalenessTickerRunnable)
        handler.removeCallbacks(scanRestartRunnable)
        stopScan()
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
        bluetoothLeScanner = btAdapter.bluetoothLeScanner ?: run {
            setStatus("❌ BLE scanner unavailable.")
            return
        }
        startScan(btAdapter.isLeExtendedAdvertisingSupported)
    }

    private fun startScan(supportExtended: Boolean = true) {
        if (scanning) return

        val settingsBuilder = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)

        // Only use extended advertising mode if the phone supports it
        if (supportExtended) {
            settingsBuilder.setLegacy(false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                settingsBuilder.setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
            }
        }

        val settings = settingsBuilder.build()

        scanning = true
        setStatus("Searching for beacons...")

        try {
            // No UUID filter — rely on registered MAC check in scanCallback
            bluetoothLeScanner?.startScan(null, settings, scanCallback)
        } catch (e: SecurityException) {
            setStatus("❌ Permission denied.")
            scanning = false
        }
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        try { bluetoothLeScanner?.stopScan(scanCallback) }
        catch (e: SecurityException) { Log.e("BLE", "stopScan: ${e.message}") }
    }

    // ── Filtering & Staleness ────────────────────────────────────────────────

    private fun updateFilteredRssis() {
        for ((mac, filter) in filterMap) {
            // Stage 1: Kalman filter on RSSI
            val filteredRssi = filter.calculateBatch() ?: continue
            val device = deviceMap[mac] ?: continue

            // Stage 2: RSSI → distance via equation
            val rawDistance = DistanceCalculator.calculate(filteredRssi)

            // Stage 3: Kalman filter on distance
            val distFilter = distanceFilterMap.getOrPut(mac) { DistanceFilter() }
            val smoothedDistance = distFilter.update(rawDistance) ?: rawDistance

            deviceMap[mac] = device.copy(
                filteredRssi = filteredRssi,
                distance = smoothedDistance
            )
        }
    }

    private fun evictStaleDevices() {
        val cutoff = System.currentTimeMillis() - STALE_EVICT_MS
        val staleKeys = deviceMap.entries
            .filter { it.value.lastSeen < cutoff }
            .map { it.key }
        staleKeys.forEach { mac ->
            deviceMap.remove(mac)
            filterMap.remove(mac)
            distanceFilterMap.remove(mac)
        }
        if (staleKeys.isNotEmpty()) {
            setStatus("Searching… ${deviceMap.size} device(s) found")
        }
    }

    // ── UI Helpers ───────────────────────────────────────────────────────────

    private fun refreshList() {
        val sorted = deviceMap.values.sortedByDescending { it.rssi }
        adapter.updateList(sorted)
        binding.rvBeacons.visibility = if (sorted.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun setStatus(msg: String) { binding.tvScanStatus.text = msg }

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