package com.example.urban

import android.os.Bundle
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import com.example.urban.databinding.ActivityBeaconDetailBinding

class BeaconDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBeaconDetailBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBeaconDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Show back arrow
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Beacon Detail"

        // Get beacon info from intent
        val name = intent.getStringExtra("beacon_name") ?: "Unknown"
        val mac = intent.getStringExtra("beacon_mac") ?: "XX:XX:XX:XX:XX:XX"

        binding.textBeaconTitle.text = name
        binding.textBeaconMac.text = "MAC: $mac"
        binding.textDistance.text = "0 m"
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}