package com.example.urban

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
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
import coil.load
import com.example.urban.databinding.ActivityBeaconDetailBinding
import java.io.File

class BeaconDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBeaconDetailBinding

    // ── Selected beacons (passed from MainActivity) ──────────────────────────
    private val selectedMacs = HashSet<String>()

    // ── Registered beacons loaded from storage ───────────────────────────────
    private val registeredBeacons = HashMap<String, Beacon>()

    // ── Room configuration ───────────────────────────────────────────────────
    private lateinit var roomConfig: RoomConfig

    // ── Fingerprint database (Phase 1 output) ────────────────────────────────
    private val fingerprints = mutableListOf<FingerprintRecord>()

    // ── Live scan state (RSSI Kalman filter per beacon) ──────────────────────
    private val filterMap   = HashMap<String, RssiFilter>()
    private val rssiMap     = HashMap<String, Double>()    // MAC → filtered RSSI (dBm)
    private val lastSeenMap = HashMap<String, Long>()

    // ── Position EMA Filter ──────────────────────────────────────────────────
    private val positionFilter = PositionEmaFilter(alpha = 0.45)

    private val STALE_MS = 5_000L

    private var scanning = false
    private val handler  = Handler(Looper.getMainLooper())
    private val SCAN_RESTART_MS = 4 * 60 * 1000L

    private val scanRestartRunnable = object : Runnable {
        override fun run() {
            Log.d("BeaconDetail", "Restarting scan to avoid Android throttle")
            stopScan(); startScan()
            handler.postDelayed(this, SCAN_RESTART_MS)
        }
    }

    private val tickerRunnable = object : Runnable {
        override fun run() {
            processRssiBatches()
            refreshUi()
            handler.postDelayed(this, 1_000L)
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val mac = result.device.address
            if (!selectedMacs.contains(mac)) return
            if (!registeredBeacons.containsKey(mac)) return

            filterMap.getOrPut(mac) { RssiFilter() }.addSample(result.rssi)
            lastSeenMap[mac] = System.currentTimeMillis()
        }
        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { onScanResult(0, it) }
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
        supportActionBar?.title = "Live Map"

        intent.getStringArrayListExtra("selected_macs")?.let { selectedMacs.addAll(it) }

        binding.rvBeacons.layoutManager = LinearLayoutManager(this)
        binding.rvBeacons.adapter = LiveBeaconAdapter()
    }

    override fun onResume() {
        super.onResume()

        roomConfig = loadRoomConfig(filesDir)

        registeredBeacons.clear()
        val currentBeacons = loadBeacons(filesDir)
        val currentMacs    = currentBeacons.map { it.mac }.toSet()
        selectedMacs.retainAll(currentMacs)
        currentBeacons.forEach {
            if (selectedMacs.contains(it.mac)) registeredBeacons[it.mac] = it
        }

        // Load the fingerprint database built in Phase 1
        fingerprints.clear()
        fingerprints.addAll(loadFingerprints(filesDir))

        filterMap.clear(); rssiMap.clear(); lastSeenMap.clear()
        positionFilter.reset()

        startScan()
        handler.post(tickerRunnable)
        handler.removeCallbacks(scanRestartRunnable)
        handler.postDelayed(scanRestartRunnable, SCAN_RESTART_MS)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tickerRunnable)
        handler.removeCallbacks(scanRestartRunnable)
        stopScan()
    }

    // ── Map loading ──────────────────────────────────────────────────────────

    private var isMapLoaded = false

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

    // ── RSSI pipeline (Stage 1 Kalman only — distance no longer needed) ──────

    private fun processRssiBatches() {
        val now = System.currentTimeMillis()
        for ((mac, filter) in filterMap) {
            // Evict beacons that have gone silent
            if (now - (lastSeenMap[mac] ?: 0L) > STALE_MS) {
                filter.reset()
                rssiMap.remove(mac)
                continue
            }
            val filteredRssi = filter.calculateBatch() ?: continue
            rssiMap[mac] = filteredRssi
        }
        checkAndLoadMap()
    }

    // ── UI refresh ───────────────────────────────────────────────────────────

    private fun refreshUi() {
        val adapter = binding.rvBeacons.adapter as? LiveBeaconAdapter ?: return

        // Build rows sorted strongest RSSI first
        val rows = selectedMacs.mapNotNull { mac ->
            val beacon = registeredBeacons[mac] ?: return@mapNotNull null
            LiveBeaconRow(
                mac          = mac,
                name         = beacon.name,
                filteredRssi = rssiMap[mac]
            )
        }.sortedWith(
            compareBy(
                { if (it.filteredRssi != null) 0 else 1 },
                { -(it.filteredRssi ?: -200.0) }           // strongest first
            )
        )

        adapter.refresh(rows)

        val hasSelection = selectedMacs.isNotEmpty()
        binding.rvBeacons.visibility = if (hasSelection) View.VISIBLE else View.GONE
        binding.tvEmpty.visibility   = if (hasSelection) View.GONE    else View.VISIBLE

        val activeCount = rssiMap.size
        binding.tvBeaconCount.text =
            "Active: $activeCount beacons  |  Fingerprints: ${fingerprints.size}"

        // Phase 2: k-NN position estimate
        val rawPosition = FingerprintEngine.estimate(rssiMap, fingerprints)

        if (rawPosition != null) {
            // Apply EMA filter to the predicted position
            val smoothedPosition = positionFilter.update(rawPosition.first, rawPosition.second)
            
            binding.userDotView.setRoomBounds(roomConfig.width, roomConfig.height)
            binding.userDotView.updatePosition(smoothedPosition.first, smoothedPosition.second)
            binding.tvPosition.text =
                "Position: (${"%.2f".format(smoothedPosition.first)} m,  ${"%.2f".format(smoothedPosition.second)} m)"
        } else {
            if (fingerprints.isEmpty()) {
                binding.tvPosition.text = "Position: no fingerprints — collect via Admin first"
            } else {
                binding.tvPosition.text = "Position: scanning… ($activeCount beacons active)"
            }
        }
    }

    // ── BLE helpers ──────────────────────────────────────────────────────────

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
