package com.acme.privacyshield.camera

import java.nio.ByteBuffer

/**
 * Reduces a frame's luma plane to a small grid of cell means for [GlintDetector].
 * Same reasoning as [LumaSampler]: sparse reads, no bitmap, no allocation per pixel.
 */
object LumaGrid {

    fun sample(
        yPlane: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        cols: Int,
        rows: Int,
    ): FloatArray {
        val out = FloatArray(cols * rows)
        if (width <= 0 || height <= 0) return out
        val buffer = yPlane.duplicate()

        val stepX = (width / cols).coerceAtLeast(1)
        val stepY = (height / rows).coerceAtLeast(1)

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val x0 = c * stepX
                val y0 = r * stepY
                var sum = 0L
                var n = 0
                var yy = y0
                // 2x2 subsample per cell is plenty for a 16x12 grid.
                while (yy < y0 + stepY && yy < height) {
                    var xx = x0
                    while (xx < x0 + stepX && xx < width) {
                        val index = yy * rowStride + xx * pixelStride
                        if (index < buffer.limit()) {
                            sum += buffer.get(index).toInt() and 0xFF
                            n++
                        }
                        xx += 2
                    }
                    yy += 2
                }
                out[r * cols + c] = if (n == 0) 0f else sum.toFloat() / n
            }
        }
        return out
    }
}
