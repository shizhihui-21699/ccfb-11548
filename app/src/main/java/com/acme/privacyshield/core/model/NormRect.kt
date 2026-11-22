package com.acme.privacyshield.core.model

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Axis-aligned rectangle in normalized image coordinates (0..1 on both axes).
 *
 * Deliberately not `android.graphics.RectF`: the decision engine must stay pure Kotlin so it can
 * be unit-tested on the JVM without Robolectric, and so nobody is tempted to pass a Bitmap into it.
 */
data class NormRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)
    val area: Float get() = width * height
    val centerX: Float get() = (left + right) * 0.5f
    val centerY: Float get() = (top + bottom) * 0.5f

    fun intersectionArea(other: NormRect): Float {
        val w = min(right, other.right) - max(left, other.left)
        val h = min(bottom, other.bottom) - max(top, other.top)
        if (w <= 0f || h <= 0f) return 0f
        return w * h
    }

    fun iou(other: NormRect): Float {
        val inter = intersectionArea(other)
        if (inter <= 0f) return 0f
        val union = area + other.area - inter
        if (union <= 0f) return 0f
        return inter / union
    }

    /** Euclidean distance between centers, in normalized units. */
    fun centerDistanceTo(other: NormRect): Float {
        val dx = centerX - other.centerX
        val dy = centerY - other.centerY
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    /** Distance of the center from the middle of the frame, 0 at center, ~1 at a corner. */
    fun centerOffset(): Float = abs(centerX - 0.5f) + abs(centerY - 0.5f)

    fun clampedToFrame(): NormRect = NormRect(
        left.coerceIn(0f, 1f),
        top.coerceIn(0f, 1f),
        right.coerceIn(0f, 1f),
        bottom.coerceIn(0f, 1f),
    )

    fun scaled(s: Float): NormRect = NormRect(left * s, top * s, right * s, bottom * s)
}
