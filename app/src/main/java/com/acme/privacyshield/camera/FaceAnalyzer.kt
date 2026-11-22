package com.acme.privacyshield.camera

import android.graphics.ImageFormat
import android.os.SystemClock
import android.util.Log
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.LifecycleOwner
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import android.hardware.camera2.CaptureRequest
import android.util.Range
import android.util.Size
import com.acme.privacyshield.core.engine.GuardConfig
import com.acme.privacyshield.core.engine.GuardDecision
import com.acme.privacyshield.core.engine.GuardEngine
import com.acme.privacyshield.core.light.GlintDetector
import com.acme.privacyshield.core.model.FaceObservation
import com.acme.privacyshield.core.model.FrameObservation
import com.acme.privacyshield.core.model.LumaStats
import com.acme.privacyshield.core.model.NormRect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Drives the front camera and feeds [GuardEngine], and nothing else.
 *
 * Privacy properties this class is responsible for:
 *  - **No preview use case is ever bound.** The user never sees the camera feed, and nothing is
 *    ever rendered anywhere.
 *  - **No frame is retained.** [ImageProxy] is closed as soon as detection completes; the raw
 *    bytes are sampled for brightness only. There is no Bitmap anywhere in the pipeline, so there
 *    is nothing to accidentally write or send.
 *  - **No disk and no network.** The class has no file or socket code paths at all.
 *
 * Power properties:
 *  - Only luma sampling happens per delivered frame; the detector runs at most at
 *    [GuardEngine.recommendedIntervalMs], and the camera can be fully unbound by [ShieldHost]
 *    when nobody is in front (duty cycling).
 */
class FaceAnalyzer(
    private val config: GuardConfig,
    onDecision: (GuardDecision) -> Unit,
) {

    private val engine = GuardEngine(config)
    private val onDecision = onDecision
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "privacy-shield-analysis").apply { priority = Thread.NORM_PRIORITY - 1 }
    }

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setMinFaceSize(0.08f)
            .enableTracking()
            .build(),
    )

    /** Set false by [ShieldHost] when the camera is unbound for power saving. */
    @Volatile
    var enabled: Boolean = true

    /** True while the analysis use case is bound to a camera. */
    @Volatile
    var isBound: Boolean = false
        private set

    private val busy = AtomicBoolean(false)
    private var lastAnalyzedAtMs = 0L
    private var cameraProvider: ProcessCameraProvider? = null

    fun engineSnapshot() = engine.snapshot()

    fun setSnoozed(snoozed: Boolean) = engine.setSnoozed(snoozed)

    fun overrideVisibility() = engine.overrideVisibility(SystemClock.elapsedRealtime())

    fun reestablishOwner() = engine.reestablishOwner(SystemClock.elapsedRealtime())

    /** Suggested camera-on state for duty cycling; [ShieldHost] polls this. */
    fun shouldStream(): Boolean =
        enabled && engine.shouldCameraStream(SystemClock.elapsedRealtime())

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    fun bind(owner: LifecycleOwner, provider: ProcessCameraProvider, done: (Throwable?) -> Unit) {
        cameraProvider = provider
        val selector = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(640, 480),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                ),
            )
            .build()

        val analysisBuilder = ImageAnalysis.Builder()
            .setResolutionSelector(selector)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)

        // Cap the sensor frame rate. Camera streaming power is dominated by the sensor/ISP running
        // at its default 30 fps, and the engine only consumes one frame per 120-1200 ms anyway.
        // 15 fps leaves headroom for auto-exposure to lengthen the shutter in a dark café while
        // still giving the detector fresh frames. Applied as an interop option because CameraX has
        // no first-class frame-rate API.
        runCatching {
            Camera2Interop.Extender(analysisBuilder)
                .setCaptureRequestOption(
                    CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                    Range(FRAME_RATE_CAP_MIN, FRAME_RATE_CAP_MAX),
                )
        }.onFailure { Log.w(TAG, "frame-rate cap not applied on this device", it) }

        val analysis = analysisBuilder.build()
        analysis.setAnalyzer(analysisExecutor) { image -> analyze(image) }

        try {
            provider.unbindAll()
            provider.bindToLifecycle(
                owner,
                CameraSelector.DEFAULT_FRONT_CAMERA,
                analysis,
            )
            // The front torch is never turned on and exposure stays in its default mode: changing
            // either would draw attention and corrupt the lighting statistics the engine relies on.
            isBound = true
            done(null)
        } catch (t: Throwable) {
            Log.e(TAG, "camera bind failed", t)
            isBound = false
            done(t)
        }
    }

    fun unbind() {
        try {
            cameraProvider?.unbindAll()
        } catch (t: Throwable) {
            Log.w(TAG, "unbind failed", t)
        }
        isBound = false
    }

    fun release() {
        unbind()
        detector.close()
        analysisExecutor.shutdown()
    }

    // -----------------------------------------------------------------------

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    private fun analyze(image: ImageProxy) {
        if (!enabled) {
            image.close()
            return
        }

        val now = SystemClock.elapsedRealtime()
        // Cheap gate first: drop frames we would not use anyway. Keeps the analysis thread idle.
        if (now - lastAnalyzedAtMs < engine.recommendedIntervalMs(now)) {
            image.close()
            return
        }
        // If a detection is still running (slow device), drop rather than queue.
        if (!busy.compareAndSet(false, true)) {
            image.close()
            return
        }
        lastAnalyzedAtMs = now

        var luma: LumaStats? = null
        var glint: com.acme.privacyshield.core.model.GlintStats? = null
        try {
            if (image.format == ImageFormat.YUV_420_888) {
                val plane = image.planes[0]
                luma = LumaSampler.sample(
                    yPlane = plane.buffer,
                    width = image.width,
                    height = image.height,
                    rowStride = plane.rowStride,
                    pixelStride = plane.pixelStride,
                )
                if (config.lensDetectionEnabled) {
                    val grid = LumaGrid.sample(
                        plane.buffer, image.width, image.height,
                        plane.rowStride, plane.pixelStride,
                        GlintDetector.GRID_COLS, GlintDetector.GRID_ROWS,
                    )
                    glint = GlintDetector.detect(grid, GlintDetector.GRID_COLS, GlintDetector.GRID_ROWS)
                }
            }
        } catch (t: Throwable) {
            // A malformed plane must not kill the shield loop; the engine's fail-soft handles null luma.
            Log.w(TAG, "luma sampling failed", t)
        }

        val mediaImage = image.image
        if (mediaImage == null) {
            finish(image, FrameObservation(now, emptyList(), luma, glint), detectedFaces = null)
            return
        }

        val rotation = image.imageInfo.rotationDegrees
        val input = InputImage.fromMediaImage(mediaImage, rotation)
        // Dimensions of the upright (rotated) image; ML Kit returns boxes in that frame.
        val uprightW = if (rotation == 90 || rotation == 270) image.height else image.width
        val uprightH = if (rotation == 90 || rotation == 270) image.width else image.height

        val startedAt = SystemClock.elapsedRealtime()
        detector.process(input)
            .addOnSuccessListener { faces ->
                val observations = faces.mapNotNull { face ->
                    val b = face.boundingBox
                    if (uprightW <= 0 || uprightH <= 0 || b.isEmpty) return@mapNotNull null
                    val box = NormRect(
                        left = (b.left.toFloat() / uprightW).coerceIn(0f, 1f),
                        top = (b.top.toFloat() / uprightH).coerceIn(0f, 1f),
                        right = (b.right.toFloat() / uprightW).coerceIn(0f, 1f),
                        bottom = (b.bottom.toFloat() / uprightH).coerceIn(0f, 1f),
                    )
                    FaceObservation(
                        trackingId = face.trackingId,
                        box = box,
                        // ML Kit's yaw sign flips with the mirrored front camera; only |yaw| is used.
                        yawDegrees = face.headEulerAngleY.takeIf { !it.isNaN() }?.let(::abs),
                        rollDegrees = face.headEulerAngleZ.takeIf { !it.isNaN() },
                    )
                }
                finish(
                    image,
                    FrameObservation(
                        timestampMs = SystemClock.elapsedRealtime(),
                        faces = observations,
                        luma = luma,
                        glint = glint,
                        inferenceMs = SystemClock.elapsedRealtime() - startedAt,
                    ),
                    detectedFaces = observations,
                )
            }
            .addOnFailureListener { t ->
                Log.w(TAG, "face detection failed on this frame", t)
                // A failed frame is a *missing* observation, never a safe one: luma still flows so
                // darkness/obstruction fallbacks apply, and the engine keeps its prior state.
                finish(image, FrameObservation(SystemClock.elapsedRealtime(), emptyList(), luma, glint), detectedFaces = emptyList())
            }
    }

    private fun finish(
        image: ImageProxy,
        observation: FrameObservation,
        detectedFaces: List<FaceObservation>?,
    ) {
        try {
            val decision = engine.onFrame(observation)
            onDecision(decision)
        } finally {
            busy.set(false)
            // Closing here releases the ImageProxy (and its MediaImage) back to the camera.
            // This is the only lifetime the frame ever has.
            image.close()
        }
    }

    companion object {
        private const val TAG = "PrivacyShield"
        private const val FRAME_RATE_CAP_MIN = 10
        private const val FRAME_RATE_CAP_MAX = 15
    }
}
