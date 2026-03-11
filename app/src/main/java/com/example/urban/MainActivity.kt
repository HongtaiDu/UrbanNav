package com.example.urban

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.urban.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var bluetoothAdapter: android.bluetooth.BluetoothAdapter? = null
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var scanning = false
    private val handler = Handler(Looper.getMainLooper())
    private val SCAN_PERIOD = 3000L
    private val REQUEST_PERMISSIONS = 1
    private var devicesFound = 0
    private var scanCount = 0
    private var scanTimeoutRunnable: Runnable? = null

    // Two simulated beacons
    private val mockBeacons = listOf(
        MockBeacon("UrbanNav-Beacon-A", "AA:BB:CC:DD:EE:01", 0),
        MockBeacon("UrbanNav-Beacon-B", "AA:BB:CC:DD:EE:02", 0)
    )

    private val requiredPermissions = arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )

    private fun isKnownBeacon(mac: String): Boolean {
        val knownBeacons = loadBeacons(filesDir)
        return knownBeacons.any { it.mac.equals(mac, ignoreCase = true) }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            devicesFound++
            val device = result.device
            val name = try { device.name } catch (e: SecurityException) { null }
            val address = device.address
            val rssi = result.rssi
            Log.d("BLE_SCAN", "Device: ${name ?: "Unknown"} | MAC: $address | RSSI: $rssi")

            if (!isKnownBeacon(address)) return

            runOnUiThread {
                appendStatus("[$devicesFound] ${name ?: "Unknown"} | $address | RSSI: $rssi")
            }
        }

        override fun onScanFailed(errorCode: Int) {
            val errorMsg = when (errorCode) {
                ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "Scan already started"
                ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "App registration failed"
                ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> "Internal error"
                ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "Feature unsupported"
                else -> "Unknown error"
            }
            Log.e("BLE_SCAN", "Scan failed: $errorMsg (code: $errorCode)")
            runOnUiThread {
                // Cancel the delayed completion callback so it doesn't run
                scanTimeoutRunnable?.let { handler.removeCallbacks(it) }
                scanTimeoutRunnable = null
                scanning = false

                appendStatus("❌ Scan failed: $errorMsg (code: $errorCode)")
                appendStatus("Scan stopped due to error. No beacons shown.")
                binding.btnScanAgain.isEnabled = true
                binding.btnScanAgain.text = "Scan"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.title = "UrbanNav"

        binding.textviewFirst.text = "=== BLE Scanner ===\n\n"

        // Setup RecyclerView
        binding.rvBeacons.layoutManager = LinearLayoutManager(this)

        // Scan Again button
        binding.btnScanAgain.setOnClickListener {
            if (!scanning) {
                // Hide beacon list during scan
                binding.rvBeacons.visibility = View.GONE
                appendStatus("\n--- New Scan ---")
                startBleScan()
            }
        }

        // Admin button
        binding.btnAdmin.setOnClickListener {
            val intent = Intent(this, AdminLoginActivity::class.java)
            startActivity(intent)
        }

        // Log environment info
        appendStatus("Device: ${android.os.Build.MODEL}")
        appendStatus("Android API: ${android.os.Build.VERSION.SDK_INT}")
        val isEmulator = android.os.Build.FINGERPRINT.contains("generic") ||
                android.os.Build.MODEL.contains("Emulator") ||
                android.os.Build.MODEL.contains("SDK")
        if (isEmulator) {
            appendStatus("⚠️ Running on EMULATOR — BLE scanning will likely find no real devices.")
            appendStatus("   (The emulator simulates a Bluetooth adapter but does NOT use your PC's Bluetooth.)")
            appendStatus("")
        }

        // Check if Bluetooth is available
        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager
        if (bluetoothManager == null) {
            appendStatus("❌ Bluetooth service not available on this device.")
            return
        }
        appendStatus("✅ Bluetooth service available.")

        val adapter = bluetoothManager.adapter
        bluetoothAdapter = adapter
        if (adapter == null) {
            appendStatus("❌ No Bluetooth adapter found.")
            return
        }
        appendStatus("✅ Bluetooth adapter found.")
        appendStatus("   Adapter enabled: ${adapter.isEnabled}")

        bluetoothLeScanner = adapter.bluetoothLeScanner
        if (bluetoothLeScanner == null) {
            appendStatus("❌ BLE scanner not available. Is Bluetooth enabled?")
            return
        }
        appendStatus("✅ BLE scanner ready.")
        appendStatus("")

        // Check permissions
        appendStatus("Checking permissions...")
        if (hasPermissions()) {
            appendStatus("✅ All permissions granted.")
            appendStatus("")
            startBleScan()
        } else {
            appendStatus("⏳ Requesting permissions...")
            ActivityCompat.requestPermissions(this, requiredPermissions, REQUEST_PERMISSIONS)
        }
    }

    private fun hasPermissions(): Boolean {
        return requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun startBleScan() {
        if (scanning) return

        // Check if Bluetooth is still enabled
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            appendStatus("")
            appendStatus("❌ Bluetooth is not available or turned off.")
            appendStatus("   Please enable Bluetooth and try again.")
            binding.rvBeacons.visibility = View.GONE
            return
        }

        // Refresh the scanner in case Bluetooth was toggled
        bluetoothLeScanner = adapter.bluetoothLeScanner
        if (bluetoothLeScanner == null) {
            appendStatus("")
            appendStatus("❌ BLE scanner not available. Is Bluetooth enabled?")
            binding.rvBeacons.visibility = View.GONE
            return
        }

        devicesFound = 0
        scanCount++

        // Disable button during scan
        binding.btnScanAgain.isEnabled = false
        binding.btnScanAgain.text = "Scanning..."

        // Hide beacon list while scanning
        binding.rvBeacons.visibility = View.GONE

        try {
            scanTimeoutRunnable = Runnable {
                scanning = false
                bluetoothLeScanner?.stopScan(scanCallback)
                Log.d("BLE_SCAN", "Scan stopped")
                runOnUiThread {
                    appendStatus("")
                    appendStatus("=== Scan #$scanCount completed ===")
                    if (devicesFound == 0) {
                        appendStatus("No real devices found.")
                    } else {
                        appendStatus("Total devices found: $devicesFound")
                    }

                    // Show mock beacons after scan completes
                    appendStatus("")
                    appendStatus("📍 ${mockBeacons.size} simulated beacons available:")
                    mockBeacons.forEach {
                        appendStatus("   • ${it.name} (${it.rssi} dBm)")
                    }
                    showMockBeacons()

                    // Re-enable button
                    binding.btnScanAgain.isEnabled = true
                    binding.btnScanAgain.text = "Scan"
                }
            }
            handler.postDelayed(scanTimeoutRunnable!!, SCAN_PERIOD)

            scanning = true
            bluetoothLeScanner?.startScan(scanCallback)
            appendStatus("📡 Scan #$scanCount — Scanning for BLE devices... (${SCAN_PERIOD / 1000}s)")
            appendStatus("")
            Log.d("BLE_SCAN", "Scan started")
        } catch (e: SecurityException) {
            Log.e("BLE_SCAN", "Permission denied: ${e.message}")
            appendStatus("❌ Permission denied: ${e.message}")
            binding.btnScanAgain.isEnabled = true
            binding.btnScanAgain.text = "Scan"
        }
    }

    private fun showMockBeacons() {
        val adapter = BeaconAdapter(mockBeacons) { beacon ->
            // Click on a beacon -> go to detail page
            val intent = Intent(this, BeaconDetailActivity::class.java).apply {
                putExtra("beacon_name", beacon.name)
                putExtra("beacon_mac", beacon.mac)
            }
            startActivity(intent)
        }
        binding.rvBeacons.adapter = adapter
        binding.rvBeacons.visibility = View.VISIBLE
    }

    private fun appendStatus(message: String) {
        binding.textviewFirst.append("$message\n")
        Log.d("BLE_SCAN", message)
        // Auto-scroll to bottom
        binding.scrollView.post {
            binding.scrollView.fullScroll(View.FOCUS_DOWN)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                appendStatus("✅ All permissions granted.")
                appendStatus("")
                startBleScan()
            } else {
                appendStatus("❌ Permissions denied. Cannot scan.")
                permissions.forEachIndexed { index, perm ->
                    val status = if (grantResults[index] == PackageManager.PERMISSION_GRANTED) "✅" else "❌"
                    appendStatus("   $status ${perm.substringAfterLast('.')}")
                }
            }
        }
    }
}