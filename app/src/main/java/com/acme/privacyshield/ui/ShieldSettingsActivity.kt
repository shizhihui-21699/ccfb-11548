package com.acme.privacyshield.ui

import android.os.Bundle
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import com.acme.privacyshield.PrivacyShieldApp
import com.acme.privacyshield.data.AppSettings
import com.acme.privacyshield.data.AuditLog
import com.acme.privacyshield.databinding.ActivityShieldSettingsBinding

class ShieldSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityShieldSettingsBinding
    private lateinit var settings: AppSettings
    private lateinit var audit: AuditLog

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityShieldSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = (application as PrivacyShieldApp).settings
        audit = AuditLog(this)

        binding.sensitivitySeek.progress = (settings.sensitivity * 100).toInt()
        binding.sensitivitySeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) settings.sensitivity = progress / 100f
            }
            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        })

        binding.snoozeSwitch.isChecked = settings.isSnoozed(AppSettings.nowMs())
        binding.snoozeSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked) settings.snooze(AppSettings.nowMs()) else settings.cancelSnooze()
        }

        binding.lensSwitch.isChecked = settings.lensDetectionEnabled
        binding.lensSwitch.setOnCheckedChangeListener { _, checked ->
            settings.lensDetectionEnabled = checked
        }

        refreshAudit()
        binding.clearAuditButton.setOnClickListener {
            audit.clear()
            refreshAudit()
        }
    }

    private fun refreshAudit() {
        val lines = audit.recentLines(max = 40)
        binding.auditText.text =
            if (lines.isEmpty()) getString(com.acme.privacyshield.R.string.settings_audit_empty)
            else lines.joinToString("\n")
    }
}
