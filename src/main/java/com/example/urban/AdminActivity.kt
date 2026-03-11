package com.example.urban

import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import com.example.urban.databinding.ActivityAdminBinding

// ─── Data Model ───────────────────────────────────────────────────────────────

data class Beacon(
    val mac: String,
    val name: String,
    val x: Double,
    val y: Double,
    val measuredPower: Int
)

// ─── JSON Helpers ─────────────────────────────────────────────────────────────

fun loadBeacons(filesDir: File): MutableList<Beacon> {
    val file = File(filesDir, "beacons.json")
    if (!file.exists()) return mutableListOf()
    return try {
        val root = JSONObject(file.readText())
        val arr = root.getJSONArray("beacons")
        MutableList(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            Beacon(o.getString("mac"), o.getString("name"), o.getDouble("x"), o.getDouble("y"), o.getInt("measuredPower"))
        }
    } catch (e: Exception) { mutableListOf() }
}

fun saveBeacons(filesDir: File, beacons: List<Beacon>) {
    val arr = JSONArray()
    beacons.forEach { b ->
        arr.put(JSONObject().apply {
            put("mac", b.mac); put("name", b.name); put("x", b.x)
            put("y", b.y); put("measuredPower", b.measuredPower)
        })
    }
    File(filesDir, "beacons.json").writeText(JSONObject().put("beacons", arr).toString(4))
}

// ─── Add/Edit Bottom Sheet ────────────────────────────────────────────────────

class BeaconFormSheet(
    private val existing: Beacon? = null,       // null = add mode
    private val onSave: (Beacon) -> Unit
) : BottomSheetDialogFragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?) =
        inflater.inflate(R.layout.sheet_beacon_form, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val etMac    = view.findViewById<android.widget.EditText>(R.id.etMac)
        val etName   = view.findViewById<android.widget.EditText>(R.id.etName)
        val etX      = view.findViewById<android.widget.EditText>(R.id.etX)
        val etY      = view.findViewById<android.widget.EditText>(R.id.etY)
        val etPower  = view.findViewById<android.widget.EditText>(R.id.etPower)
        val btnSave  = view.findViewById<android.widget.Button>(R.id.btnSave)
        val btnCancel= view.findViewById<android.widget.Button>(R.id.btnCancel)
        val tvTitle  = view.findViewById<android.widget.TextView>(R.id.tvFormTitle)

        // Pre-fill if editing
        existing?.let {
            tvTitle.text = "Edit Beacon"
            etMac.setText(it.mac); etMac.isEnabled = false  // MAC is the key, don't change it
            etName.setText(it.name)
            etX.setText(it.x.toString())
            etY.setText(it.y.toString())
            etPower.setText(it.measuredPower.toString())
        }

        btnCancel.setOnClickListener { dismiss() }

        btnSave.setOnClickListener {
            val mac   = etMac.text.toString().uppercase().trim()
            val name  = etName.text.toString().trim()
            val xStr  = etX.text.toString().trim()
            val yStr  = etY.text.toString().trim()
            val pwrStr= etPower.text.toString().trim()

            // Validate
            val macRegex = Regex("^([0-9A-Fa-f]{2}:)+[0-9A-Fa-f]{2}$")
            when {
                mac.isEmpty() || !mac.matches(macRegex) -> { etMac.error = "Invalid MAC (e.g. AA:BB:CC:DD:EE:FF)"; return@setOnClickListener }
                name.isEmpty() -> { etName.error = "Required"; return@setOnClickListener }
                xStr.toDoubleOrNull() == null -> { etX.error = "Must be a number"; return@setOnClickListener }
                yStr.toDoubleOrNull() == null -> { etY.error = "Must be a number"; return@setOnClickListener }
                pwrStr.toIntOrNull() == null  -> { etPower.error = "Must be an integer"; return@setOnClickListener }
            }

            onSave(Beacon(mac, name, xStr.toDouble(), yStr.toDouble(), pwrStr.toInt()))
            dismiss()
        }
    }
}

// ─── AdminActivity ────────────────────────────────────────────────────────────

class AdminActivity : AppCompatActivity() {

    private lateinit var binding: com.example.urban.databinding.ActivityAdminBinding
    private lateinit var adapter: AdminBeaconAdapter
    private val beacons = mutableListOf<Beacon>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAdminBinding.inflate(layoutInflater)
        setContentView(binding.root)

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
        BeaconFormSheet(onSave = { newBeacon ->
            if (beacons.any { it.mac == newBeacon.mac }) {
                Toast.makeText(this, "MAC ${newBeacon.mac} already exists", Toast.LENGTH_SHORT).show()
                return@BeaconFormSheet
            }
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
