package com.example.urban

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.urban.databinding.ActivityBeaconDetailBinding

class BeaconDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBeaconDetailBinding

    private lateinit var targetMac: String
    private var measuredPower: Int = -59

    private val handler = Handler(Looper.getMainLooper())
    private var scanning = false
    private val rssiFilter = RssiFilter(windowSize = 10)

    // ── Watchdog: fires if no packet received within 5 seconds ───────────────
    private val OUT_OF_RANGE_MS = 5_000L
    private val outOfRangeRunnable = Runnable { showOutOfRange() }

    private fun resetWatchdog() {
        handler.removeCallbacks(outOfRangeRunnable)
        handler.postDelayed(outOfRangeRunnable, OUT_OF_RANGE_MS)
    }

    private fun cancelWatchdog() {
        handler.removeCallbacks(outOfRangeRunnable)
    }

    // ── BLE Scan Callback ────────────────────────────────────────────────────

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (result.device.address != targetMac) return

            // Packet received — beacon is alive, reset the watchdog
            resetWatchdog()

            val rawRssi      = result.rssi
            val filteredRssi = rssiFilter.update(rawRssi)
            val distance     = DistanceCalculator.calculate(filteredRssi, measuredPower)

            runOnUiThread {
                showLiveState()
                binding.textDistance.text     = DistanceCalculator.format(distance)
                binding.textRawRssi.text      = "Raw RSSI: ${rawRssi} dBm"
                binding.textFilteredRssi.text = "Filtered RSSI: ${"%.1f".format(filteredRssi)} dBm"
                binding.textSamples.text      = "Filter samples: ${rssiFilter.sampleCount}/10"
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e("BeaconDetail", "Scan failed: $errorCode")
        }
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBeaconDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Beacon Detail"

        val name  = intent.getStringExtra("beacon_name") ?: "Unknown"
        targetMac = intent.getStringExtra("beacon_mac")  ?: ""

        val registered = loadBeacons(filesDir).find { it.mac == targetMac }
        measuredPower  = registered?.measuredPower ?: -59

        binding.textBeaconTitle.text   = name
        binding.textBeaconMac.text     = "MAC: $targetMac"
        binding.textMeasuredPower.text = "Calibrated power: ${measuredPower} dBm"

        binding.btnGoBack.setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        rssiFilter.reset()
        showLiveState()
        binding.textDistance.text = "Scanning…"
        startScan()
        resetWatchdog()
    }

    override fun onPause() {
        super.onPause()
        cancelWatchdog()
        stopScan()
    }

    // ── UI State ─────────────────────────────────────────────────────────────

    private fun showLiveState() {
        binding.textDistance.visibility     = View.VISIBLE
        binding.textRawRssi.visibility      = View.VISIBLE
        binding.textFilteredRssi.visibility = View.VISIBLE
        binding.textSamples.visibility      = View.VISIBLE
        binding.divider.visibility          = View.VISIBLE
        binding.layoutOutOfRange.visibility = View.GONE
    }

    private fun showOutOfRange() {
        binding.textDistance.visibility     = View.GONE
        binding.textRawRssi.visibility      = View.GONE
        binding.textFilteredRssi.visibility = View.GONE
        binding.textSamples.visibility      = View.GONE
        binding.divider.visibility          = View.GONE
        binding.layoutOutOfRange.visibility = View.VISIBLE
    }

    // ── BLE Helpers ──────────────────────────────────────────────────────────

    private fun startScan() {
        if (scanning || targetMac.isEmpty()) return

        val hasPermission = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
        if (!hasPermission) { binding.textDistance.text = "No permission"; return }

        val btScanner = (getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager)
            ?.adapter?.bluetoothLeScanner ?: return

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()

        try {
            btScanner.startScan(null, settings, scanCallback)
            scanning = true
        } catch (e: SecurityException) {
            Log.e("BeaconDetail", "startScan: ${e.message}")
        }
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        val btScanner = (getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager)
            ?.adapter?.bluetoothLeScanner ?: return
        try { btScanner.stopScan(scanCallback) }
        catch (e: SecurityException) { Log.e("BeaconDetail", "stopScan: ${e.message}") }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }
}