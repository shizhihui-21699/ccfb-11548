package com.acme.privacyshield.camera

import android.Manifest
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.acme.privacyshield.core.engine.GuardConfig
import com.acme.privacyshield.core.engine.GuardDecision
import com.acme.privacyshield.core.engine.ShieldLevel

/**
 * Binds the shield's camera pipeline to one activity's lifecycle.
 *
 * The lifetime rules *are* the privacy design:
 *  - the camera binds in `onStart` and unbinds in `onStop`, so on every non-sensitive screen, in
 *    the recents switcher, and while the screen is off, **no camera is open at all**;
 *  - duty cycling (see [FaceAnalyzer.shouldStream]) further drops the camera entirely when nobody
 *    has been in front of a sensitive screen for a while.
 *
 * Call [attach] from `onCreate`, [dispose] from `onDestroy`.
 */
class ShieldHost(
    private val activity: ComponentActivity,
    private val config: GuardConfig,
    private val isSnoozed: () -> Boolean,
    private val listener: Listener,
) {

    interface Listener {
        fun onDecision(decision: GuardDecision)
        fun onStatus(status: Status)
    }

    enum class Status { ACTIVE, NO_PERMISSION, UNAVAILABLE }

    private val main = Handler(Looper.getMainLooper())

    private var analyzer: FaceAnalyzer? = null
    private var provider: ProcessCameraProvider? = null
    private var started = false
    private var disposed = false
    private var snoozed: Boolean = false
    private var lastStatus: Status? = null

    private val permissionLauncher =
        activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                start()
            } else {
                emitStatus(Status.NO_PERMISSION)
            }
        }

    /**
     * Keeps the camera in line with the engine's power recommendation. Runs only while the
     * activity is started; [stop] removes it.
     */
    private val dutyCycleTicker = object : Runnable {
        override fun run() {
            val a = analyzer
            if (a == null || !started || disposed) return
            // Re-check the snooze on a timer: a 30-minute snooze set in settings must expire while
            // this screen is open, otherwise the shield silently stays down (fail-open).
            refreshSnooze()
            val wantStream = a.shouldStream()
            if (wantStream && !a.isBound) {
                bind(a)
            } else if (!wantStream && a.isBound) {
                a.unbind()
            }
            main.postDelayed(this, if (wantStream) 1_000L else 400L)
        }
    }

    /** Re-reads the snooze from settings; called on start and by the ticker. */
    private fun refreshSnooze() {
        val active = isSnoozed()
        if (active != snoozed) {
            snoozed = active
            analyzer?.setSnoozed(active)
        }
    }

    fun attach() {
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = start()
            override fun onStop(owner: LifecycleOwner) = stop()
        })
    }

    /** Ask for CAMERA if needed, then start; safe to call from a button. */
    fun requestPermission() {
        if (hasPermission()) start() else permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    fun setSnoozed(value: Boolean) {
        snoozed = value
        analyzer?.setSnoozed(value)
        if (value) {
            emitDecision(
                GuardDecision(level = ShieldLevel.NONE, reasons = emptySet(), snoozed = true),
            )
        }
    }

    fun overrideVisibility() {
        analyzer?.overrideVisibility()
    }

    /** Call only after the device credential prompt has returned RESULT_OK. */
    fun reestablishOwner() {
        analyzer?.reestablishOwner()
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        stop()
        analyzer?.release()
        analyzer = null
    }

    // -----------------------------------------------------------------------

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun start() {
        if (disposed || started) return
        if (!hasPermission()) {
            emitStatus(Status.NO_PERMISSION)
            return
        }
        started = true
        lastStatus = null
        refreshSnooze()

        val a = analyzer ?: FaceAnalyzer(config) { decision ->
            main.post { if (!disposed) emitDecision(decision) }
        }.also {
            it.setSnoozed(snoozed)
            analyzer = it
        }

        val future = ProcessCameraProvider.getInstance(activity)
        future.addListener({
            if (disposed || !started) return@addListener
            try {
                provider = future.get()
                bind(a)
                main.removeCallbacks(dutyCycleTicker)
                main.postDelayed(dutyCycleTicker, 1_500L)
            } catch (t: Throwable) {
                emitStatus(Status.UNAVAILABLE)
            }
        }, ContextCompat.getMainExecutor(activity))
    }

    private fun bind(a: FaceAnalyzer) {
        val p = provider ?: return
        a.bind(activity, p) { error ->
            emitStatus(if (error != null) Status.UNAVAILABLE else Status.ACTIVE)
        }
    }

    private fun stop() {
        started = false
        main.removeCallbacks(dutyCycleTicker)
        analyzer?.unbind()
    }

    private fun emitDecision(d: GuardDecision) = listener.onDecision(d)

    private fun emitStatus(s: Status) {
        if (lastStatus == s) return
        lastStatus = s
        listener.onStatus(s)
    }
}
