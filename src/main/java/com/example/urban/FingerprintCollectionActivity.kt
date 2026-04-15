package com.example.urban

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import coil.load
import com.example.urban.databinding.ActivityFingerprintCollectionBinding
import java.io.File

/**
 * Phase 1 — Offline fingerprint survey.
 *
 * The user walks to each reference point, taps that location on the floor-plan
 * (or enters X/Y manually), then presses "Record".  The app scans every
 * registered BLE beacon for [COLLECTION_DURATION_MS] milliseconds, averages
 * the filtered RSSI from each beacon, and saves a [FingerprintRecord] to
 * fingerprints.json.
 *
 * The floor-plan shows:
 *   ● Green numbered dots — already-saved fingerprints
 *   ● Red dot             — the pending collection point
 *
 * "Undo Last" removes the most-recently saved fingerprint in case of a mistake.
 */
class FingerprintCollectionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFingerprintCollectionBinding

    // ── BLE ──────────────────────────────────────────────────────────────────
    private val registeredBeacons = HashMap<String, Beacon>()
    private val filterMap         = HashMap<String, RssiFilter>()
    private val rssiMap           = HashMap<String, Double>()
    private val lastSeenMap       = HashMap<String, Long>()
    private val STALE_MS          = 5_000L
    private var scanning          = false
    private val handler           = Handler(Looper.getMainLooper())
    private val SCAN_RESTART_MS   = 4 * 60 * 1000L

    // ── Fingerprint state ────────────────────────────────────────────────────
    private val fingerprints       = mutableListOf<FingerprintRecord>()
    private var selectedX: Double? = null
    private var selectedY: Double? = null
    private var isCollecting       = false

    /** Accumulates RSSI samples (from every ticker tick) during a recording. */
    private val collectionSamples  = HashMap<String, MutableList<Double>>()
    private val COLLECTION_DURATION_MS = 4_000L
    private var collectionStartMs  = 0L

    // ── Room config ──────────────────────────────────────────────────────────
    private lateinit var roomConfig: RoomConfig
    private var isMapLoaded = false

    // ── Runnables ────────────────────────────────────────────────────────────

    private val TICK_MS = 500L

    private val tickerRunnable = object : Runnable {
        override fun run() {
            processRssiBatches()
            if (isCollecting) accumulateSamples()
            refreshUi()
            handler.postDelayed(this, TICK_MS)
        }
    }

    /** Fired once after [COLLECTION_DURATION_MS] to finalize the fingerprint. */
    private val finishCollectionRunnable = Runnable { finalizeCollection() }

    private val scanRestartRunnable = object : Runnable {
        override fun run() {
            Log.d("FPCollection", "Restarting BLE scan")
            stopScan(); startScan()
            handler.postDelayed(this, SCAN_RESTART_MS)
        }
    }

    // ── BLE scan callback ────────────────────────────────────────────────────

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val mac = result.device.address
            if (!registeredBeacons.containsKey(mac)) return
            filterMap.getOrPut(mac) { RssiFilter() }.addSample(result.rssi)
            lastSeenMap[mac] = System.currentTimeMillis()
        }
        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { onScanResult(0, it) }
        }
        override fun onScanFailed(errorCode: Int) {
            Log.e("FPCollection", "Scan failed: $errorCode")
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFingerprintCollectionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        binding.btnRecord.setOnClickListener     { startCollection() }
        binding.btnSetManually.setOnClickListener { showCoordinateDialog() }
        binding.btnUndoLast.setOnClickListener   { undoLast() }

        binding.fpMapOverlay.setOnPointSelectedListener { x, y ->
            selectedX = x; selectedY = y
            binding.fpMapOverlay.setPendingPoint(x, y)
            updateSelectedPointText()
            refreshButtonStates()
        }
    }

    override fun onResume() {
        super.onResume()

        // Reload everything so changes made in other admin screens are picked up
        isMapLoaded = false
        roomConfig = loadRoomConfig(filesDir)
        registeredBeacons.clear()
        loadBeacons(filesDir).forEach { registeredBeacons[it.mac] = it }

        fingerprints.clear()
        fingerprints.addAll(loadFingerprints(filesDir))

        binding.fpMapOverlay.setRoomBounds(roomConfig.width, roomConfig.height)
        binding.fpMapOverlay.setFingerprints(fingerprints)
        checkAndLoadMap()

        // Reset live scan state
        filterMap.clear(); rssiMap.clear(); lastSeenMap.clear()

        startScan()
        handler.post(tickerRunnable)
        handler.removeCallbacks(scanRestartRunnable)
        handler.postDelayed(scanRestartRunnable, SCAN_RESTART_MS)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tickerRunnable)
        handler.removeCallbacks(scanRestartRunnable)
        handler.removeCallbacks(finishCollectionRunnable)
        isCollecting = false
        stopScan()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    // ── Map loading ──────────────────────────────────────────────────────────

    private fun checkAndLoadMap() {
        if (isMapLoaded) return
        val path = roomConfig.imagePath
        if (!path.isNullOrEmpty()) {
            val file = File(path)
            if (file.exists()) {
                binding.ivMap.load(file)
                binding.tvNoMap.visibility = View.GONE
                isMapLoaded = true
                return
            }
        }
        binding.tvNoMap.visibility = View.VISIBLE
    }

    // ── RSSI pipeline ─────────────────────────────────────────────────────────

    private fun processRssiBatches() {
        val now = System.currentTimeMillis()
        for ((mac, filter) in filterMap) {
            if (now - (lastSeenMap[mac] ?: 0L) > STALE_MS) {
                filter.reset()
                rssiMap.remove(mac)
                continue
            }
            val filteredRssi = filter.calculateBatch() ?: continue
            rssiMap[mac] = filteredRssi
        }
    }

    /** Copy current rssiMap snapshot into the collection buffers. */
    private fun accumulateSamples() {
        rssiMap.forEach { (mac, rssi) ->
            collectionSamples.getOrPut(mac) { mutableListOf() }.add(rssi)
        }
    }

    // ── Collection logic ──────────────────────────────────────────────────────

    private fun startCollection() {
        if (selectedX == null || selectedY == null) {
            Toast.makeText(this, "Tap the map (or use Set X/Y) to choose a point first",
                Toast.LENGTH_SHORT).show()
            return
        }
        if (isCollecting) return

        isCollecting = true
        collectionSamples.clear()
        collectionStartMs = System.currentTimeMillis()
        binding.btnRecord.isEnabled = false

        handler.postDelayed(finishCollectionRunnable, COLLECTION_DURATION_MS)
    }

    private fun finalizeCollection() {
        isCollecting = false
        val x = selectedX ?: return
        val y = selectedY ?: return

        if (collectionSamples.isEmpty()) {
            Toast.makeText(this, "No beacons detected — move closer to a beacon and retry",
                Toast.LENGTH_LONG).show()
            refreshButtonStates()
            return
        }

        val avgRssi = collectionSamples.mapValues { (_, samples) -> samples.average() }
        val fp = FingerprintRecord(
            x       = x,
            y       = y,
            rssiMap = avgRssi,
            label   = "FP ${fingerprints.size + 1}"
        )
        fingerprints.add(fp)
        saveFingerprints(filesDir, fingerprints)

        binding.fpMapOverlay.setFingerprints(fingerprints)
        binding.fpMapOverlay.clearPendingPoint()
        selectedX = null; selectedY = null

        Toast.makeText(
            this,
            "${fp.label} saved — ${avgRssi.size} beacon(s) at (${"%.2f".format(x)}, ${"%.2f".format(y)}) m",
            Toast.LENGTH_SHORT
        ).show()

        updateSelectedPointText()
        refreshButtonStates()
    }

    private fun undoLast() {
        if (fingerprints.isEmpty()) {
            Toast.makeText(this, "Nothing to undo", Toast.LENGTH_SHORT).show()
            return
        }
        val removed = fingerprints.removeAt(fingerprints.lastIndex)
        saveFingerprints(filesDir, fingerprints)
        binding.fpMapOverlay.setFingerprints(fingerprints)
        Toast.makeText(this, "${removed.label} removed", Toast.LENGTH_SHORT).show()
    }

    private fun showCoordinateDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 16, 64, 0)
        }
        val etX = EditText(this).apply {
            hint = "X (metres)"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val etY = EditText(this).apply {
            hint = "Y (metres)"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        layout.addView(etX)
        layout.addView(etY)

        AlertDialog.Builder(this)
            .setTitle("Set Collection Point")
            .setView(layout)
            .setPositiveButton("Set") { _, _ ->
                val x = etX.text.toString().toDoubleOrNull()
                val y = etY.text.toString().toDoubleOrNull()
                if (x != null && y != null) {
                    selectedX = x; selectedY = y
                    binding.fpMapOverlay.setPendingPoint(x, y)
                    updateSelectedPointText()
                    refreshButtonStates()
                } else {
                    Toast.makeText(this, "Enter valid numbers for X and Y", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    private fun refreshUi() {
        val activeCount = rssiMap.size

        binding.tvFpCount.text =
            "Fingerprints: ${fingerprints.size}  |  Active beacons: $activeCount"

        if (isCollecting) {
            val elapsed  = System.currentTimeMillis() - collectionStartMs
            val progress = ((elapsed.toFloat() / COLLECTION_DURATION_MS) * 100).toInt().coerceIn(0, 100)
            binding.progressCollect.visibility = View.VISIBLE
            binding.progressCollect.progress   = progress
            binding.tvStatus.text = "Recording… ($activeCount beacons in range)"
        } else {
            binding.progressCollect.visibility = View.GONE
            binding.tvStatus.text = when {
                selectedX != null -> "Point set — press Record to begin 4-second scan"
                else              -> "Tap the map to select a collection point"
            }
        }
    }

    private fun refreshButtonStates() {
        binding.btnRecord.isEnabled = !isCollecting && selectedX != null
    }

    private fun updateSelectedPointText() {
        val x = selectedX; val y = selectedY
        binding.tvSelectedPoint.text = if (x != null && y != null)
            "Selected: (${"%.2f".format(x)} m,  ${"%.2f".format(y)} m)"
        else
            "Selected: none"
    }

    // ── BLE helpers ───────────────────────────────────────────────────────────

    private fun startScan() {
        if (scanning) return
        val hasPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) ==
                    PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED
        }
        if (!hasPermission) return

        val adapter = (getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager)
            ?.adapter ?: return
        val btScanner = adapter.bluetoothLeScanner ?: return

        val settingsBuilder = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)

        if (adapter.isLeExtendedAdvertisingSupported) {
            settingsBuilder.setLegacy(false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                settingsBuilder.setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
            }
        }

        try {
            btScanner.startScan(null, settingsBuilder.build(), scanCallback)
            scanning = true
        } catch (e: SecurityException) {
            Log.e("FPCollection", "startScan: ${e.message}")
        }
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        val btScanner = (getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager)
            ?.adapter?.bluetoothLeScanner ?: return
        try { btScanner.stopScan(scanCallback) }
        catch (e: SecurityException) { Log.e("FPCollection", "stopScan: ${e.message}") }
    }
}
