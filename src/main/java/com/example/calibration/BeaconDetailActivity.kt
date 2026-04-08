package com.example.calibration

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.calibration.databinding.ActivityBeaconDetailBinding

class BeaconDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBeaconDetailBinding

    private lateinit var targetMac: String

    private val handler = Handler(Looper.getMainLooper())
    private var scanning = false

    // ── Calibration state ────────────────────────────────────────────────────

    private val CALIBRATION_DURATION_MS = 45_000L
    private val rssiSamples = mutableListOf<Int>()
    private var calibrating = false
    private var countDownTimer: CountDownTimer? = null

    // ── BLE Scan Callback ────────────────────────────────────────────────────

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (result.device.address != targetMac) return
            if (!calibrating) return

            val rawRssi = result.rssi
            rssiSamples.add(rawRssi)

            val avg = rssiSamples.average()

            runOnUiThread {
                binding.textLiveRssi.text    = "Live RSSI: $rawRssi dBm"
                binding.textRunningAvg.text  = "Running avg: ${"%.1f".format(avg)} dBm"
                binding.textSampleCount.text = "Samples: ${rssiSamples.size}"
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
        supportActionBar?.title = "RSSI Calibration"

        val name  = intent.getStringExtra("beacon_name") ?: "Unknown"
        targetMac = intent.getStringExtra("beacon_mac")  ?: ""

        binding.textBeaconTitle.text = name
        binding.textBeaconMac.text   = "MAC: $targetMac"

        binding.btnStart.setOnClickListener { startCalibration() }
        binding.btnGoBack.setOnClickListener { finish() }

        resetUi()
    }

    override fun onPause() {
        super.onPause()
        stopCalibration(finished = false)
    }

    // ── Calibration ──────────────────────────────────────────────────────────

    private fun startCalibration() {
        rssiSamples.clear()
        calibrating = true

        binding.btnStart.isEnabled       = false
        binding.btnStart.text            = "⏳ Calibrating…"
        binding.layoutResult.visibility  = View.GONE
        binding.textStatus.text          = "Hold phone 1 metre from beacon"
        binding.textCountdown.text       = "45"
        binding.progressCalibration.progress = 0
        binding.textSampleCount.text     = "Samples: 0"
        binding.textLiveRssi.text        = "Live RSSI: —"
        binding.textRunningAvg.text      = "Running avg: —"

        startScan()

        countDownTimer = object : CountDownTimer(CALIBRATION_DURATION_MS, 1_000L) {
            override fun onTick(millisUntilFinished: Long) {
                val secondsLeft = (millisUntilFinished / 1_000L).toInt()
                binding.textCountdown.text       = secondsLeft.toString()
                binding.progressCalibration.progress = 45 - secondsLeft
            }

            override fun onFinish() {
                binding.textCountdown.text       = "0"
                binding.progressCalibration.progress = 45
                stopCalibration(finished = true)
            }
        }.start()
    }

    private fun stopCalibration(finished: Boolean) {
        calibrating = false
        countDownTimer?.cancel()
        countDownTimer = null
        stopScan()

        if (finished && rssiSamples.isNotEmpty()) {
            showResult()
        } else if (!finished && rssiSamples.isEmpty()) {
            // Interrupted before any data — just reset
            resetUi()
        }
        // If interrupted mid-way but has some data, leave the running avg visible
        binding.btnStart.isEnabled = true
        binding.btnStart.text      = if (finished) "🔁 Calibrate Again" else "▶  Start Calibration"
        if (!finished) binding.textStatus.text = "Tap START to begin 45-second calibration"
    }

    private fun showResult() {
        val avg        = rssiSamples.average()
        val rounded    = avg.toInt()            // typical convention: round to nearest int

        binding.textStatus.text   = "Calibration complete!"
        binding.textFinalAvg.text = rounded.toString()
        binding.textFinalSamples.text =
            "Based on ${rssiSamples.size} samples  |  raw avg: ${"%.2f".format(avg)} dBm"

        binding.layoutResult.visibility = View.VISIBLE
    }

    private fun resetUi() {
        binding.textCountdown.text            = "45"
        binding.progressCalibration.progress  = 0
        binding.textSampleCount.text          = "Samples: 0"
        binding.textLiveRssi.text             = "Live RSSI: —"
        binding.textRunningAvg.text           = "Running avg: —"
        binding.layoutResult.visibility       = View.GONE
        binding.btnStart.isEnabled            = true
        binding.btnStart.text                 = "▶  Start Calibration"
        binding.textStatus.text               = "Tap START to begin 45-second calibration"
    }

    // ── BLE Helpers ──────────────────────────────────────────────────────────

    private fun startScan() {
        if (scanning || targetMac.isEmpty()) return

        val hasPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
        if (!hasPermission) {
            binding.textStatus.text = "❌ Bluetooth permission not granted"
            return
        }

        val adapter = (getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter ?: return
        val btScanner = adapter.bluetoothLeScanner ?: return

        val settingsBuilder = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)

        // Enable Extended Advertising if supported
        if (adapter.isLeExtendedAdvertisingSupported) {
            settingsBuilder.setLegacy(false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                settingsBuilder.setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
            }
        }

        val settings = settingsBuilder.build()

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
