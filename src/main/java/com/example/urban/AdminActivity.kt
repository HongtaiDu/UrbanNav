package com.example.urban

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.example.urban.databinding.ActivityAdminBinding

// ─── Add/Edit Bottom Sheet ────────────────────────────────────────────────────

class BeaconFormSheet(
    private val existing: Beacon? = null,       // null = add mode
    private val existingMacs: Set<String> = emptySet(),
    private val onSave: (Beacon) -> Unit
) : BottomSheetDialogFragment() {

    private var scanning = false
    private val handler = Handler(Looper.getMainLooper())
    private var bestMac: String? = null
    private var bestRssi: Int = -200

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val rssi = result.rssi
            if (rssi > bestRssi) {
                bestRssi = rssi
                bestMac = result.device.address
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?) =
        inflater.inflate(R.layout.sheet_beacon_form, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val etMac    = view.findViewById<EditText>(R.id.etMac)
        val etName   = view.findViewById<EditText>(R.id.etName)
        val etX      = view.findViewById<EditText>(R.id.etX)
        val etY      = view.findViewById<EditText>(R.id.etY)
        val btnSave  = view.findViewById<Button>(R.id.btnSave)
        val btnCancel= view.findViewById<Button>(R.id.btnCancel)
        val btnScanMac = view.findViewById<Button>(R.id.btnScanMac)
        val tvTitle  = view.findViewById<TextView>(R.id.tvFormTitle)

        // Pre-fill if editing
        existing?.let {
            tvTitle.text = "Edit Beacon"
            etMac.setText(it.mac); etMac.isEnabled = false
            etName.setText(it.name)
            etX.setText(it.x.toString())
            etY.setText(it.y.toString())
            btnScanMac.visibility = View.GONE
        }

        btnCancel.setOnClickListener { dismiss() }

        btnScanMac.setOnClickListener {
            if (scanning) return@setOnClickListener
            startMacScan(btnScanMac, etMac)
        }

        btnSave.setOnClickListener {
            val mac   = etMac.text.toString().uppercase().trim()
            val name  = etName.text.toString().trim()
            val xStr  = etX.text.toString().trim()
            val yStr  = etY.text.toString().trim()

            val macRegex = Regex("^([0-9A-Fa-f]{2}:)+[0-9A-Fa-f]{2}$")
            when {
                mac.isEmpty() || !mac.matches(macRegex) -> { etMac.error = "Invalid MAC"; return@setOnClickListener }
                existing == null && existingMacs.contains(mac) -> { etMac.error = "MAC already registered"; return@setOnClickListener }
                name.isEmpty() -> { etName.error = "Required"; return@setOnClickListener }
                xStr.toDoubleOrNull() == null -> { etX.error = "Must be a number"; return@setOnClickListener }
                yStr.toDoubleOrNull() == null -> { etY.error = "Must be a number"; return@setOnClickListener }
            }

            onSave(Beacon(mac, name, xStr.toDouble(), yStr.toDouble()))
            dismiss()
        }
    }

    private fun startMacScan(btn: Button, et: EditText) {
        val context = context ?: return
        
        val hasPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }

        if (!hasPermission) {
            Toast.makeText(context, "Bluetooth/Location permission required", Toast.LENGTH_SHORT).show()
            return
        }

        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            Toast.makeText(context, "Bluetooth scanner unavailable", Toast.LENGTH_SHORT).show()
            return
        }

        bestMac = null
        bestRssi = -200
        scanning = true
        btn.text = "Scanning..."
        btn.isEnabled = false

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            scanner.startScan(null, settings, scanCallback)
        } catch (e: SecurityException) {
            scanning = false
            btn.text = "Scan"
            btn.isEnabled = true
            return
        }

        handler.postDelayed({
            try {
                scanner.stopScan(scanCallback)
            } catch (e: SecurityException) { }
            
            scanning = false
            btn.text = "Scan"
            btn.isEnabled = true
            
            if (bestMac != null) {
                et.setText(bestMac)
                Toast.makeText(context, "Detected: $bestMac ($bestRssi dBm)", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "No devices found", Toast.LENGTH_SHORT).show()
            }
        }, 5000L)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        if (scanning) {
            val adapter = (context?.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            try {
                adapter?.bluetoothLeScanner?.stopScan(scanCallback)
            } catch (e: SecurityException) { }
        }
        handler.removeCallbacksAndMessages(null)
    }
}

// ─── AdminActivity ────────────────────────────────────────────────────────────

class AdminActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAdminBinding
    private lateinit var adapter: AdminBeaconAdapter
    private val beacons = mutableListOf<Beacon>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAdminBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Beacon Management"

        beacons.clear()
        beacons.addAll(loadBeacons(filesDir))

        adapter = AdminBeaconAdapter(beacons, onEdit = ::showEditSheet, onDelete = ::confirmDelete)
        binding.recyclerBeacons.layoutManager = LinearLayoutManager(this)
        binding.recyclerBeacons.adapter = adapter

        updateEmptyState()

        binding.fabAddBeacon.setOnClickListener { showAddSheet() }
    }

    private fun showAddSheet() {
        val existingMacs = beacons.map { it.mac }.toSet()
        BeaconFormSheet(existingMacs = existingMacs, onSave = { newBeacon ->
            beacons.add(newBeacon)
            saveBeacons(filesDir, beacons)
            adapter.refresh(beacons)
            updateEmptyState()
            Toast.makeText(this, "${newBeacon.name} registered", Toast.LENGTH_SHORT).show()
        }).show(supportFragmentManager, "add")
    }

    private fun showEditSheet(beacon: Beacon) {
        BeaconFormSheet(existing = beacon, onSave = { updated ->
            val idx = beacons.indexOfFirst { it.mac == beacon.mac }
            if (idx >= 0) beacons[idx] = updated
            saveBeacons(filesDir, beacons)
            adapter.refresh(beacons)
            Toast.makeText(this, "${updated.name} updated", Toast.LENGTH_SHORT).show()
        }).show(supportFragmentManager, "edit")
    }

    private fun confirmDelete(beacon: Beacon) {
        AlertDialog.Builder(this)
            .setTitle("Delete ${beacon.name}?")
            .setMessage("MAC: ${beacon.mac}\nThis cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                beacons.remove(beacon)
                saveBeacons(filesDir, beacons)
                adapter.refresh(beacons)
                updateEmptyState()
                Toast.makeText(this, "${beacon.name} removed", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateEmptyState() {
        binding.tvEmpty.visibility = if (beacons.isEmpty()) View.VISIBLE else View.GONE
        binding.recyclerBeacons.visibility = if (beacons.isEmpty()) View.GONE else View.VISIBLE
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }
}
