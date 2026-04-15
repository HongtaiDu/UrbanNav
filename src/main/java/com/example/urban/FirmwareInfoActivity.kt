package com.example.urban

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import com.example.urban.databinding.ActivityFirmwareInfoBinding

class FirmwareInfoActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFirmwareInfoBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFirmwareInfoBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Firmware Info"

        binding.btnViewSource.setOnClickListener {
            val intent = Intent(this, SourceCodeActivity::class.java)
            intent.putExtra("file_name", "main.c")
            startActivity(intent)
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
