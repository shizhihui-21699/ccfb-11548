package com.acme.privacyshield.camera

import com.acme.privacyshield.core.model.LumaStats
import java.nio.ByteBuffer

/**
 * Computes brightness statistics straight from the camera's luma plane.
 *
 * Deliberately operates on the raw `Y` plane with a stride-aware sampler: no bitmap, no colour
 * conversion, no allocation proportional to the frame. A 640x480 frame costs a few thousand
 * byte reads, which is negligible next to face detection, and it lets the engine react to lighting
 * changes (tunnel, glare, covered lens) even on frames where detection is skipped.
 */
object LumaSampler {

    /** Samples on a fixed grid so cost is independent of resolution. */
    private const val TARGET_SAMPLES = 4_096

    fun sample(
        yPlane: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
    ): LumaStats {
        if (width <= 0 || height <= 0) {
            return LumaStats(0f, 0f, 1f, 0f)
        }
        val step = chooseStep(width, height)
        val buffer = yPlane.duplicate()
        var count = 0
        var sum = 0.0
        var sumSq = 0.0
        var dark = 0
        var saturated = 0

        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val index = y * rowStride + x * pixelStride
                if (index < buffer.limit()) {
                    val v = buffer.get(index).toInt() and 0xFF
                    sum += v
                    sumSq += v * v
                    if (v < LumaStats.DARK_LUMA) dark++
                    if (v > LumaStats.SATURATED_LUMA) saturated++
                    count++
                }
                x += step
            }
            y += step
        }

        if (count == 0) return LumaStats(0f, 0f, 1f, 0f)
        val mean = sum / count
        val variance = (sumSq / count) - mean * mean
        val stdDev = if (variance > 0.0) kotlin.math.sqrt(variance).toFloat() else 0f
        return LumaStats(
            mean = mean.toFloat(),
            stdDev = stdDev,
            darkFraction = dark.toFloat() / count,
            saturatedFraction = saturated.toFloat() / count,
        )
    }

    private fun chooseStep(width: Int, height: Int): Int {
        val total = width.toLong() * height.toLong()
        if (total <= TARGET_SAMPLES) return 1
        // Integer step keeps the grid regular; ceil so we never exceed the target.
        var step = 2
        while ((width.toLong() / step) * (height.toLong() / step) > TARGET_SAMPLES) step++
        return step
    }
}
