package com.acme.privacyshield.data

import android.content.Context
import android.os.SystemClock
import com.acme.privacyshield.core.engine.GuardDecision
import com.acme.privacyshield.core.engine.ShieldLevel
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Append-only **metadata** audit trail, for bank compliance: "how often was this screen shielded,
 * and why". It is written to app-private storage and never leaves the device (the app has no
 * INTERNET permission at all).
 *
 * What it records: a timestamp, the shield level, the reason code, and whether the shield was
 * suppressed by snooze. What it never records: any image, any face box, any count of people,
 * any location, any identifier that could be tied to a bystander.
 *
 * If your compliance team does not require this, delete the class and its two call sites — the
 * shield behaves identically without it.
 */
class AuditLog(context: Context) {

    private val file = File(context.filesDir, "shield_events.jsonl")
    private val lock = Any()
    private var lastWrittenLevel: ShieldLevel? = null
    private var lastWrittenAtMs: Long = 0L

    /** Writes only on level *transitions* to keep the file tiny and the IO negligible. */
    fun record(decision: GuardDecision, nowMs: Long = SystemClock.elapsedRealtime()) {
        val level = decision.level
        if (level == lastWrittenLevel && nowMs - lastWrittenAtMs < MIN_REPEAT_MS) return
        // Do not log a steady state repeatedly; only transitions and long-running states.
        lastWrittenLevel = level
        lastWrittenAtMs = nowMs

        val json = JSONObject().apply {
            put("t", isoNow())
            put("level", level.name)
            put("reason", decision.primaryReason?.name ?: "NONE")
            put("snoozed", decision.snoozed)
        }
        synchronized(lock) {
            runCatching {
                file.appendText(json.toString() + "\n")
                rotateIfNeeded()
            }
        }
    }

    fun recentLines(max: Int = 100): List<String> = synchronized(lock) {
        runCatching {
            if (!file.exists()) return emptyList()
            file.readLines().takeLast(max)
        }.getOrDefault(emptyList())
    }

    fun clear() = synchronized(lock) {
        runCatching { file.delete() }
        lastWrittenLevel = null
    }

    private fun rotateIfNeeded() {
        if (file.length() <= MAX_BYTES) return
        val keep = runCatching { file.readLines().takeLast(MAX_LINES_AFTER_ROTATE) }
            .getOrDefault(emptyList())
        runCatching {
            file.writeText(keep.joinToString(separator = "\n", postfix = "\n"))
        }
    }

    private fun isoNow(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        return fmt.format(Date())
    }

    private companion object {
        const val MIN_REPEAT_MS = 60_000L
        const val MAX_BYTES = 256L * 1024L
        const val MAX_LINES_AFTER_ROTATE = 500
    }
}
