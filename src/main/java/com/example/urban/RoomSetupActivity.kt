package com.example.urban

import android.net.Uri
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import coil.load
import com.example.urban.databinding.ActivityRoomSetupBinding
import java.io.File
import java.io.FileOutputStream

class RoomSetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRoomSetupBinding
    private var selectedImagePath: String? = null

    private val pickMedia = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            handleImageSelected(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRoomSetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Room Setup"

        // Load existing config
        val config = loadRoomConfig(filesDir)
        binding.etRoomWidth.setText(config.width.toString())
        binding.etRoomHeight.setText(config.height.toString())
        selectedImagePath = config.imagePath
        updateImageUi()

        binding.btnUploadImage.setOnClickListener {
            pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }

        binding.btnRemoveImage.setOnClickListener {
            selectedImagePath = null
            updateImageUi()
        }

        binding.btnSave.setOnClickListener {
            val wStr = binding.etRoomWidth.text.toString().trim()
            val hStr = binding.etRoomHeight.text.toString().trim()

            val w = wStr.toDoubleOrNull()
            val h = hStr.toDoubleOrNull()

            if (w == null || w <= 0) {
                binding.etRoomWidth.error = "Must be a positive number"
                return@setOnClickListener
            }
            if (h == null || h <= 0) {
                binding.etRoomHeight.error = "Must be a positive number"
                return@setOnClickListener
            }

            saveRoomConfig(filesDir, RoomConfig(w, h, selectedImagePath))
            Toast.makeText(this, "Room config saved (${w}m × ${h}m)", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun updateImageUi() {
        if (selectedImagePath != null) {
            val file = File(selectedImagePath!!)
            binding.tvImagePath.text = file.name
            binding.btnRemoveImage.visibility = View.VISIBLE
            if (file.exists()) {
                binding.ivMapPreview.load(file)
                binding.ivMapPreview.visibility = View.VISIBLE
            }
        } else {
            binding.tvImagePath.text = "No image selected"
            binding.btnRemoveImage.visibility = View.GONE
            binding.ivMapPreview.visibility = View.GONE
        }
    }

    private fun handleImageSelected(uri: Uri) {
        try {
            val fileName = "map_${System.currentTimeMillis()}.jpg"
            val destFile = File(filesDir, fileName)

            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }

            selectedImagePath = destFile.absolutePath
            updateImageUi()
            Toast.makeText(this, "Image uploaded", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to copy image", Toast.LENGTH_SHORT).show()
            e.printStackTrace()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }
}
