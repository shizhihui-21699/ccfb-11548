package com.acme.privacyshield.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.acme.privacyshield.BuildConfig
import com.acme.privacyshield.PrivacyShieldApp
import com.acme.privacyshield.R
import com.acme.privacyshield.camera.ShieldHost
import com.acme.privacyshield.core.engine.GuardDecision
import com.acme.privacyshield.core.engine.ShieldLevel
import com.acme.privacyshield.core.engine.ShieldReason
import com.acme.privacyshield.data.AppSettings
import com.acme.privacyshield.data.AuditLog
import com.acme.privacyshield.databinding.ActivityClientDetailBinding

/**
 * The sensitive screen. Everything here is about making the shield *legible*: the user must
 * understand why their data vanished and how to get it back, or they will learn to disable the
 * feature — which is the real failure mode for this kind of product.
 *
 * Three visual states, driven purely by [GuardDecision]:
 *  - [ShieldLevel.NONE]    – content as-is;
 *  - [ShieldLevel.PARTIAL] – sensitive *values* blanked, labels and chrome intact, banner up;
 *  - [ShieldLevel.FULL]    – opaque cover over everything below the header.
 *
 * `FLAG_SECURE` is always on here, so the system screenshot / screen-recording path and the recents
 * thumbnail are blocked independently of the camera logic. That is the defence against a
 * deliberate capture; the camera is the defence against a live over-the-shoulder human.
 */
class ClientDetailActivity : AppCompatActivity(), ShieldHost.Listener {

    private lateinit var binding: ActivityClientDetailBinding
    private lateinit var host: ShieldHost
    private lateinit var audit: AuditLog
    private lateinit var settings: AppSettings

    /** Debug-only: while true, live decisions are ignored so the QA simulator state stays put. */
    private var qaMode = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) host.requestPermission() else renderFailSafe()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Block screenshots, screen recording and the recents thumbnail for this window.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )
        if (Build.VERSION.SDK_INT >= 33) {
            // Extra safety: some OEM switchers ignore FLAG_SECURE for their thumbnail.
            runCatching { setRecentsScreenshotEnabled(false) }
        }

        binding = ActivityClientDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val app = application as PrivacyShieldApp
        settings = app.settings
        audit = AuditLog(this)

        // The snooze is re-read on every start and by the host's ticker, so a 30-minute snooze
        // expires even while this screen stays open.
        host = ShieldHost(this, settings.guardConfig(), { settings.isSnoozed(AppSettings.nowMs()) }, this)
        host.attach()

        binding.continueButton.setOnClickListener { host.overrideVisibility() }

        if (BuildConfig.DEBUG) setUpQaSimulator()

        if (hasCameraPermission()) {
            host.requestPermission()
        } else {
            renderFailSafe()
        }
    }

    /**
     * **QA only, debug builds only.** Camera-based detection cannot be exercised on a desk
     * emulator or in an automated screenshot test, so this exposes three buttons that drive the
     * real renderer through the three shield states. They feed [render] directly and never touch
     * the engine, the camera or the audit log, so they cannot mask a detection bug — they only
     * prove the UI states look right and that the redaction is complete.
     */
    private fun setUpQaSimulator() {
        binding.qaBar.visibility = View.VISIBLE
        binding.qaNone.setOnClickListener { qaMode = true; render(qaDecision(ShieldLevel.NONE)) }
        binding.qaPartial.setOnClickListener { qaMode = true; render(qaDecision(ShieldLevel.PARTIAL)) }
        binding.qaFull.setOnClickListener { qaMode = true; render(qaDecision(ShieldLevel.FULL)) }
    }

    private fun qaDecision(level: ShieldLevel) = GuardDecision(
        level = level,
        reasons = when (level) {
            ShieldLevel.PARTIAL -> setOf(ShieldReason.LOW_VISIBILITY)
            ShieldLevel.FULL -> setOf(ShieldReason.BYSTANDER)
            ShieldLevel.NONE -> emptySet()
        },
    )

    override fun onDestroy() {
        super.onDestroy()
        host.dispose()
    }

    // -----------------------------------------------------------------------

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    override fun onDecision(decision: GuardDecision) {
        if (isFinishing || isDestroyed) return
        // In debug QA mode the live engine would immediately overwrite the simulated state
        // (a desk emulator shows no face, so it keeps deciding NONE). Keep QA output stable.
        if (qaMode) return
        audit.record(decision)
        render(decision)
    }

    override fun onStatus(status: ShieldHost.Status) {
        val (textRes, colorRes) = when (status) {
            ShieldHost.Status.ACTIVE -> R.string.guard_status_on to R.color.status_ok
            ShieldHost.Status.NO_PERMISSION -> R.string.guard_status_setup to R.color.status_warn
            ShieldHost.Status.UNAVAILABLE -> R.string.guard_status_unavailable to R.color.status_alert
        }
        binding.statusChip.setText(textRes)
        binding.statusChip.setBackgroundTintList(ContextCompat.getColorStateList(this, colorRes))
        binding.statusChip.setOnClickListener {
            if (status == ShieldHost.Status.NO_PERMISSION) {
                permissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
        if (status != ShieldHost.Status.ACTIVE) renderFailSafe()
    }

    /**
     * Fail safe: with no camera we cannot verify who is watching, so cover the data and offer to
     * fix it. Never show the data "just in case the camera comes back".
     */
    private fun renderFailSafe() {
        applyRedaction(redact = true)
        binding.partialBar.visibility = View.GONE
        binding.fullShield.visibility = View.VISIBLE
        binding.fullShieldTitle.setText(R.string.shield_fallback_title)
        binding.fullShieldBody.setText(R.string.shield_fallback_body)
        binding.shieldAction.visibility = View.VISIBLE
        binding.shieldAction.setText(R.string.guard_grant)
        binding.shieldAction.setOnClickListener {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun render(decision: GuardDecision) {
        val level = if (decision.snoozed) ShieldLevel.NONE else decision.level

        binding.lowVisibilityBanner.visibility =
            if (decision.reasons.contains(ShieldReason.LOW_VISIBILITY)) View.VISIBLE else View.GONE

        when (level) {
            ShieldLevel.NONE -> {
                binding.fullShield.visibility = View.GONE
                binding.partialBar.visibility = View.GONE
                applyRedaction(redact = false)
            }

            ShieldLevel.PARTIAL -> {
                binding.fullShield.visibility = View.GONE
                applyRedaction(redact = true)
                binding.partialBar.visibility = View.VISIBLE
                binding.continueButton.visibility =
                    if (decision.primaryReason == ShieldReason.LOW_VISIBILITY) View.VISIBLE else View.GONE
            }

            ShieldLevel.FULL -> {
                applyRedaction(redact = true)
                binding.partialBar.visibility = View.GONE
                binding.fullShield.visibility = View.VISIBLE
                val obstructed = decision.primaryReason == ShieldReason.CAMERA_OBSTRUCTED
                binding.fullShieldTitle.setText(
                    when {
                        obstructed -> R.string.shield_obstructed_title
                        decision.canReestablishOwner -> R.string.shield_unknown_title
                        else -> R.string.shield_title
                    },
                )
                binding.fullShieldBody.setText(
                    when {
                        obstructed -> R.string.shield_obstructed_body
                        decision.canReestablishOwner -> R.string.shield_unknown_body
                        else -> R.string.shield_body
                    },
                )
                when {
                    // A covered lens is the user's own hand: a plain tap is fine.
                    obstructed -> {
                        binding.shieldAction.visibility = View.VISIBLE
                        binding.shieldAction.setText(R.string.shield_continue)
                        binding.shieldAction.setOnClickListener { host.overrideVisibility() }
                    }
                    // Ownership lost: the person now holding the phone could be an attacker, so
                    // recovery requires the device credential — never a bare tap.
                    decision.canReestablishOwner -> {
                        binding.shieldAction.visibility = View.VISIBLE
                        binding.shieldAction.setText(R.string.shield_verify_identity)
                        binding.shieldAction.setOnClickListener { promptReestablish() }
                    }
                    else -> binding.shieldAction.visibility = View.GONE
                }
            }
        }
    }

    /**
     * Re-establish "this is the employee" through the device biometric/PIN prompt.
     *
     * This is the only place the shield can be lifted for a reason other than the threat going
     * away, and it deliberately requires something a bystander does not have.
     */
    private fun promptReestablish() {
        val manager = getSystemService(android.app.KeyguardManager::class.java)
        if (manager == null || !manager.isDeviceSecure) {
            // No device credential configured: cannot verify by design. Leave the cover up and
            // send the user to lock-screen setup rather than silently weakening the guarantee.
            binding.fullShieldBody.setText(R.string.shield_no_device_credential)
            return
        }
        val intent = manager.createConfirmDeviceCredentialIntent(
            getString(R.string.shield_verify_identity),
            getString(R.string.shield_verify_identity_body),
        )
        if (intent == null) {
            binding.fullShieldBody.setText(R.string.shield_no_device_credential)
            return
        }
        credentialLauncher.launch(intent)
    }

    private val credentialLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) host.reestablishOwner()
        }

    /**
     * Redaction blanks the text itself rather than blurring it.
     *
     * A blur is partially recoverable from a screenshot and from an imperfect camera; empty text
     * is not. Values are re-rendered from string resources only when the shield lifts, so the view
     * tree holds no client data while shielded either.
     */
    private fun applyRedaction(redact: Boolean) {
        for (field in sensitiveFields()) {
            field.text = if (redact) MASK else valueFor(field)
        }
    }

    private fun sensitiveFields() = listOf(
        binding.valueName,
        binding.valueId,
        binding.valuePhone,
        binding.valueBalance,
        binding.valueTransactions,
    )

    private fun valueFor(field: android.widget.TextView): CharSequence = when (field.id) {
        R.id.valueName -> getString(R.string.client_name)
        R.id.valueId -> getString(R.string.client_id)
        R.id.valuePhone -> getString(R.string.client_phone)
        R.id.valueBalance -> getString(R.string.client_balance)
        else -> getString(R.string.client_transactions)
    }

    private companion object {
        const val MASK = "••••••••••••"
    }
}
