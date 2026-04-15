package com.example.urban

import android.os.Bundle
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import com.example.urban.databinding.ActivitySourceCodeBinding
import java.io.BufferedReader
import java.io.InputStreamReader

class SourceCodeActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySourceCodeBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySourceCodeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        
        val fileName = intent.getStringExtra("file_name") ?: "main.c"
        supportActionBar?.title = fileName

        try {
            val inputStream = assets.open(fileName)
            val reader = BufferedReader(InputStreamReader(inputStream))
            val content = reader.use { it.readText() }
            binding.tvSourceCode.text = content
        } catch (e: Exception) {
            binding.tvSourceCode.text = "Error loading file: ${e.message}"
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
