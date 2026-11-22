package com.acme.privacyshield.ui

import android.os.Bundle
import android.widget.Toast
import com.acme.privacyshield.R
import com.acme.privacyshield.data.AppSettings
import com.acme.privacyshield.databinding.ActivityMainBinding
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.openClientButton.setOnClickListener {
            startActivity(android.content.Intent(this, ClientDetailActivity::class.java))
        }

        binding.settingsButton.setOnClickListener {
            val app = application as com.acme.privacyshield.PrivacyShieldApp
            val settings = app.settings
            if (settings.isSnoozed(AppSettings.nowMs())) {
                Toast.makeText(this, R.string.main_snoozed_hint, Toast.LENGTH_LONG).show()
            }
            startActivity(android.content.Intent(this, ShieldSettingsActivity::class.java))
        }
    }
}
