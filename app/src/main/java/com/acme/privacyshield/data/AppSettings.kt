package com.acme.privacyshield.data

import android.content.Context
import android.os.SystemClock
import com.acme.privacyshield.core.engine.GuardConfig

/**
 * Non-image preferences only. Nothing here can reconstruct a face.
 *
 * Deliberately does **not** persist any per-person data: no owner template, no face embeddings,
 * no landmarks, no crops. The engine's owner model lives in RAM for the lifetime of one screen.
 */
class AppSettings(context: Context) {

    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Snooze uses the monotonic clock so changing the device clock cannot extend it. */
    private var snoozeSetAtMs: Long? = null
    private var snoozeDurationMs: Long = 0L

    var sensitivity: Float
        get() = prefs.getFloat(KEY_SENSITIVITY, DEFAULT_SENSITIVITY).coerceIn(0f, 1f)
        set(value) {
            prefs.edit().putFloat(KEY_SENSITIVITY, value.coerceIn(0f, 1f)).apply()
        }

    var lensDetectionEnabled: Boolean
        get() = prefs.getBoolean(KEY_LENS, false)
        set(value) {
            prefs.edit().putBoolean(KEY_LENS, value).apply()
        }

    fun snooze(nowMs: Long, durationMs: Long = SNOOZE_DURATION_MS) {
        snoozeSetAtMs = nowMs
        snoozeDurationMs = durationMs
        prefs.edit().putLong(KEY_SNOOZE_AT, nowMs).putLong(KEY_SNOOZE_DUR, durationMs).apply()
    }

    fun cancelSnooze() {
        snoozeSetAtMs = null
        snoozeDurationMs = 0L
        prefs.edit().remove(KEY_SNOOZE_AT).remove(KEY_SNOOZE_DUR).apply()
    }

    fun isSnoozed(nowMs: Long): Boolean {
        val setAt = snoozeSetAtMs ?: prefs.getLong(KEY_SNOOZE_AT, -1L).takeIf { it >= 0 } ?: return false
        val dur = if (snoozeDurationMs > 0) snoozeDurationMs else prefs.getLong(KEY_SNOOZE_DUR, 0L)
        snoozeSetAtMs = setAt
        snoozeDurationMs = dur
        if (dur <= 0) return false
        val active = nowMs - setAt < dur
        if (!active) cancelSnooze()
        return active
    }

    /**
     * Maps the 0..1 sensitivity slider onto engine thresholds.
     *
     * Higher sensitivity = the shield trips sooner:
     *  - shorter sustained evidence before blocking,
     *  - a wider yaw cone counts as "looking at the screen",
     *  - smaller faces count as readable.
     * Release delay is left alone: a sensitive setting should not make the shield flicker.
     */
    fun guardConfig(): GuardConfig {
        val base = GuardConfig()
        val s = sensitivity
        return base.copy(
            bystanderOnboardMs = lerp(900f, 350f, s).toLong(),
            gazeMaxYawDegrees = lerp(30f, 48f, s),
            minReadableFaceArea = lerp(0.014f, 0.006f, s),
            lensDetectionEnabled = lensDetectionEnabled,
        )
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    companion object {
        private const val FILE = "privacy_shield_settings"
        private const val KEY_SENSITIVITY = "sensitivity"
        private const val KEY_LENS = "lens_detection"
        private const val KEY_SNOOZE_AT = "snooze_at"
        private const val KEY_SNOOZE_DUR = "snooze_dur"
        const val DEFAULT_SENSITIVITY = 0.5f
        const val SNOOZE_DURATION_MS = 30L * 60L * 1000L

        fun nowMs() = SystemClock.elapsedRealtime()
    }
}
