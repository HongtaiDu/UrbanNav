package com.example.urban

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import com.example.urban.databinding.ActivityAdminHubBinding

class AdminHubActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAdminHubBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAdminHubBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Set up the toolbar
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Admin Mode"

        binding.btnRoomSetup.setOnClickListener {
            startActivity(Intent(this, RoomSetupActivity::class.java))
        }

        binding.btnBeaconManagement.setOnClickListener {
            startActivity(Intent(this, AdminActivity::class.java))
        }

        binding.btnFingerprintCollection.setOnClickListener {
            startActivity(Intent(this, FingerprintCollectionActivity::class.java))
        }

        binding.btnFirmwareInfo.setOnClickListener {
            startActivity(Intent(this, FirmwareInfoActivity::class.java))
        }

        binding.btnDebugDashboard.setOnClickListener {
            startActivity(Intent(this, DebugActivity::class.java))
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
