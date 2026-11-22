package com.acme.privacyshield.core.model

/**
 * One face as reported by the on-device detector, in normalized upright-image coordinates.
 *
 * There is intentionally no image, no crop and no feature vector in here: once a frame has been
 * reduced to this structure, the pixels are gone. Nothing upstream or downstream can reconstruct
 * the picture from an observation.
 */
data class FaceObservation(
    /** Detector tracking id if available; may be null or change between frames. */
    val trackingId: Int?,
    val box: NormRect,
    /** Head yaw in degrees, 0 = facing the camera. Null when the detector cannot estimate it. */
    val yawDegrees: Float?,
    /** Head roll in degrees. */
    val rollDegrees: Float?,
)

/**
 * Global brightness statistics of the frame. Sampled from the luma plane only — never a full
 * decode, never a bitmap.
 */
data class LumaStats(
    /** Mean luma, 0..255. */
    val mean: Float,
    /** Standard deviation of luma, 0..255. Very low means a flat/featureless scene. */
    val stdDev: Float,
    /** Fraction of samples below [DARK_LUMA]. */
    val darkFraction: Float,
    /** Fraction of samples above [SATURATED_LUMA]. */
    val saturatedFraction: Float,
) {
    fun smoothed(previous: LumaStats?, alpha: Float): LumaStats {
        if (previous == null) return this
        val a = alpha.coerceIn(0f, 1f)
        return LumaStats(
            mean = previous.mean + (mean - previous.mean) * a,
            stdDev = previous.stdDev + (stdDev - previous.stdDev) * a,
            darkFraction = previous.darkFraction + (darkFraction - previous.darkFraction) * a,
            saturatedFraction = previous.saturatedFraction + (saturatedFraction - previous.saturatedFraction) * a,
        )
    }

    companion object {
        const val DARK_LUMA = 32f
        const val SATURATED_LUMA = 245f
    }
}

/**
 * Result of the experimental "a camera lens is aimed at the screen" heuristic.
 * [bestScore] is a peak-to-ring contrast ratio; see `LensGlintDetector`.
 */
data class GlintStats(
    val bestScore: Float,
    val litCellCount: Int,
    val gridCols: Int,
    val gridRows: Int,
)

/** Everything the decision engine is allowed to know about a single analysed frame. */
data class FrameObservation(
    /** Monotonic time in ms (SystemClock.elapsedRealtime base). Drives all engine timing. */
    val timestampMs: Long,
    val faces: List<FaceObservation>,
    val luma: LumaStats?,
    val glint: GlintStats? = null,
    /** How long the detector took on this frame; used for adaptive throttling. */
    val inferenceMs: Long = 0L,
)
