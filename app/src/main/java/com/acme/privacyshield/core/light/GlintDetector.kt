package com.acme.privacyshield.core.light

import com.acme.privacyshield.core.model.GlintStats

/**
 * **Experimental** heuristic for "someone is holding a phone up to photograph the screen".
 *
 * Real lens detection needs either a specular-dot model or a trained classifier; this is neither.
 * It looks for a small number of very bright, compact, isolated spots against a darker
 * neighbourhood — the shape a camera lens makes when it reflects the screen back at you.
 *
 * Known failure modes, all of which is why the feature is off by default:
 *  - specular highlights on glasses, watches, jewellery and windows score similarly;
 *  - a phone held at an oblique angle may show no glint at all;
 *  - bright patterned clothing produces lit cells that are not compact.
 *
 * The detector therefore only ever *adds* evidence; it never suppresses a face-based block, and
 * its output is combined with a multi-frame streak requirement upstream.
 */
object GlintDetector {

    /** Grid used by [com.acme.privacyshield.camera.LumaGrid]; tune together with the thresholds. */
    const val GRID_COLS = 16
    const val GRID_ROWS = 12

    /**
     * @param grid per-cell mean luma, row-major, values 0..255
     * @param gridCols number of columns in [grid]
     * @param gridRows number of rows in [grid]
     */
    fun detect(grid: FloatArray, gridCols: Int, gridRows: Int): GlintStats {
        if (gridCols < 3 || gridRows < 3 || grid.size < gridCols * gridRows) {
            return GlintStats(0f, 0, gridCols, gridRows)
        }

        fun at(c: Int, r: Int): Float = grid[r * gridCols + c]

        var best = 0f
        var lit = 0

        for (r in 0 until gridRows) {
            for (c in 0 until gridCols) {
                val peak = at(c, r)
                if (peak < 150f) continue // a lens glint is a bright spot, full stop

                // Ring = the 3x3 neighbourhood minus the centre. Compare the peak against the
                // ring's mean: a glint is a sharp local maximum, a bright wall is not.
                var ringSum = 0f
                var ringCount = 0
                for (dr in -1..1) {
                    for (dc in -1..1) {
                        if (dr == 0 && dc == 0) continue
                        val rr = r + dr
                        val cc = c + dc
                        if (rr in 0 until gridRows && cc in 0 until gridCols) {
                            ringSum += at(cc, rr)
                            ringCount++
                        }
                    }
                }
                if (ringCount == 0) continue
                val ringMean = ringSum / ringCount
                val score = if (ringMean < 1f) peak / 1f else peak / ringMean
                if (score > 2.2f && peak > 180f) lit++
                if (score > best) best = score
            }
        }

        // A handset in frame typically produces one or two compact highlights, not a full grid.
        return GlintStats(
            bestScore = best,
            litCellCount = lit,
            gridCols = gridCols,
            gridRows = gridRows,
        )
    }
}
