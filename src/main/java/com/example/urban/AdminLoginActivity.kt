package com.example.urban

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.example.urban.databinding.ActivityAdminLoginBinding

class AdminLoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAdminLoginBinding
    private val ADMIN_PASSWORD = "123456"
    private var attempts = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAdminLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Show back arrow in action bar
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Developer Access"

        binding.btnSubmit.setOnClickListener {
            val input = binding.editPassword.text.toString()

            if (input == ADMIN_PASSWORD) {
                // Password correct — go to admin page
                val intent = Intent(this, AdminHubActivity::class.java)
                startActivity(intent)
                finish()
            } else {
                // Password wrong — show error
                attempts++
                binding.textError.visibility = View.VISIBLE
                binding.textError.text = "❌ Wrong password. Try again. (Attempt $attempts)"
                binding.editPassword.text.clear()
            }
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