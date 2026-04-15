package com.example.urban

import android.os.Bundle
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
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.example.urban.databinding.ActivityAdminBinding

// ─── Add/Edit Bottom Sheet ────────────────────────────────────────────────────

class BeaconFormSheet(
    private val existing: Beacon? = null,       // null = add mode
    private val onSave: (Beacon) -> Unit
) : BottomSheetDialogFragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?) =
        inflater.inflate(R.layout.sheet_beacon_form, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val etMac    = view.findViewById<EditText>(R.id.etMac)
        val etName   = view.findViewById<EditText>(R.id.etName)
        val etX      = view.findViewById<EditText>(R.id.etX)
        val etY      = view.findViewById<EditText>(R.id.etY)
        val btnSave  = view.findViewById<Button>(R.id.btnSave)
        val btnCancel= view.findViewById<Button>(R.id.btnCancel)
        val tvTitle  = view.findViewById<TextView>(R.id.tvFormTitle)

        // Pre-fill if editing
        existing?.let {
            tvTitle.text = "Edit Beacon"
            etMac.setText(it.mac); etMac.isEnabled = false
            etName.setText(it.name)
            etX.setText(it.x.toString())
            etY.setText(it.y.toString())
        }

        btnCancel.setOnClickListener { dismiss() }

        btnSave.setOnClickListener {
            val mac   = etMac.text.toString().uppercase().trim()
            val name  = etName.text.toString().trim()
            val xStr  = etX.text.toString().trim()
            val yStr  = etY.text.toString().trim()

            val macRegex = Regex("^([0-9A-Fa-f]{2}:)+[0-9A-Fa-f]{2}$")
            when {
                mac.isEmpty() || !mac.matches(macRegex) -> { etMac.error = "Invalid MAC"; return@setOnClickListener }
                name.isEmpty() -> { etName.error = "Required"; return@setOnClickListener }
                xStr.toDoubleOrNull() == null -> { etX.error = "Must be a number"; return@setOnClickListener }
                yStr.toDoubleOrNull() == null -> { etY.error = "Must be a number"; return@setOnClickListener }
            }

            onSave(Beacon(mac, name, xStr.toDouble(), yStr.toDouble()))
            dismiss()
        }
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
