package com.example.vision

import android.graphics.Bitmap
import android.graphics.Color
import com.example.model.RelativePoint
import kotlin.math.max

data class WaterBlockDetectionResult(
    val detectedPoint: RelativePoint?,
    val fallbackAlternatePoint: RelativePoint?,
    val confidence: Float
)

/**
 * Automatically locates clickable water blocks adjacent to the character in Gridmere
 * (left, right, or just below character), checks whether the worm bait slot already
 * has its active orange selection frame, and verifies if fishing cast succeeded.
 */
class WaterBlockDetector {

    fun detectWaterNearCharacter(bitmap: Bitmap): WaterBlockDetectionResult {
        val width = bitmap.width
        val height = bitmap.height
        if (width < 64 || height < 64) {
            return WaterBlockDetectionResult(null, null, 0f)
        }

        // Candidate patches next to the centered character (avoiding character body at x=0.43..0.57)
        val candidates = listOf(
            // Left of character (1 tile away & 2 tiles away)
            RelativePoint(0.37f, 0.56f),
            RelativePoint(0.32f, 0.56f),
            RelativePoint(0.26f, 0.56f),
            RelativePoint(0.37f, 0.65f),
            RelativePoint(0.32f, 0.44f),
            RelativePoint(0.25f, 0.68f),
            // Right of character (1 tile away & 2 tiles away)
            RelativePoint(0.63f, 0.56f),
            RelativePoint(0.68f, 0.56f),
            RelativePoint(0.74f, 0.56f),
            RelativePoint(0.63f, 0.65f),
            RelativePoint(0.68f, 0.44f),
            RelativePoint(0.74f, 0.68f)
        )

        var bestPoint: RelativePoint? = null
        var bestScore = 0f
        var bestSideLeft = true

        var bestOtherSidePoint: RelativePoint? = null
        var bestOtherSideScore = 0f

        val scored = candidates.map { pt ->
            pt to scoreWaterPatch(bitmap, pt)
        }

        val top = scored.maxByOrNull { it.second }
        if (top != null && top.second >= 0.25f) {
            bestPoint = top.first
            bestScore = top.second
            bestSideLeft = top.first.x < 0.5f

            val opposite = scored
                .filter { (it.first.x < 0.5f) != bestSideLeft }
                .maxByOrNull { it.second }
            if (opposite != null && opposite.second >= 0.18f) {
                bestOtherSidePoint = opposite.first
                bestOtherSideScore = opposite.second
            }
        }

        // Fallback alternate if water wasn't confident on opposite side
        val alternate = bestOtherSidePoint ?: if (bestPoint != null) {
            if (bestPoint.x < 0.5f) RelativePoint(0.66f, 0.56f) else RelativePoint(0.34f, 0.56f)
        } else {
            null
        }

        return WaterBlockDetectionResult(
            detectedPoint = bestPoint,
            fallbackAlternatePoint = alternate,
            confidence = max(bestScore, bestOtherSideScore).coerceIn(0f, 1f)
        )
    }

    /**
     * Checks if the worm bait slot currently has the bright orange active selection border.
     */
    fun isBaitSlotSelected(bitmap: Bitmap, baitPoint: RelativePoint): Boolean {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 32 || h < 32) return false

        val cx = (baitPoint.x.coerceIn(0.05f, 0.95f) * w).toInt()
        val cy = (baitPoint.y.coerceIn(0.05f, 0.95f) * h).toInt()
        val rx = max(6, (w * 0.025f).toInt())
        val ry = max(5, (h * 0.045f).toInt())

        var orangeHits = 0
        var samples = 0

        // Sample top & side edges around the slot where the orange border sits
        for (dx in -rx..rx step max(1, rx / 3)) {
            val px = (cx + dx).coerceIn(0, w - 1)
            for (edgeY in intArrayOf(cy - ry, cy - ry + 2, cy + ry - 1)) {
                val py = edgeY.coerceIn(0, h - 1)
                if (isOrangeBorder(bitmap.getPixel(px, py))) orangeHits++
                samples++
            }
        }
        for (dy in -ry..ry step max(1, ry / 3)) {
            val py = (cy + dy).coerceIn(0, h - 1)
            for (edgeX in intArrayOf(cx - rx, cx + rx)) {
                val px = edgeX.coerceIn(0, w - 1)
                if (isOrangeBorder(bitmap.getPixel(px, py))) orangeHits++
                samples++
            }
        }

        return samples > 0 && orangeHits >= 4
    }

    private fun isOrangeBorder(pixel: Int): Boolean {
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)
        return r >= 200 && g in 105..195 && b <= 75 && (r - b) >= 135
    }

    private fun scoreWaterPatch(bitmap: Bitmap, center: RelativePoint): Float {
        val w = bitmap.width
        val h = bitmap.height
        val cx = (center.x * w).toInt().coerceIn(0, w - 1)
        val cy = (center.y * h).toInt().coerceIn(0, h - 1)
        val halfW = max(8, (w * 0.038f).toInt())
        val halfH = max(8, (h * 0.060f).toInt())

        val sx = (cx - halfW).coerceIn(0, w - 2)
        val ex = (cx + halfW).coerceIn(sx + 1, w - 1)
        val sy = (cy - halfH).coerceIn(0, h - 2)
        val ey = (cy + halfH).coerceIn(sy + 1, h - 1)

        val stepX = max(1, (ex - sx) / 8)
        val stepY = max(1, (ey - sy) / 8)

        var waterCount = 0
        var pureSkyCount = 0
        var total = 0

        var y = sy
        while (y <= ey) {
            var x = sx
            while (x <= ex) {
                val pixel = bitmap.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)

                // Gridmere translucent aqua/cyan water block (distinct from cobalt sky where b - g >= 82)
                val isAquaWater = b in 150..242 && g in 120..222 && r in 10..115 &&
                    (b - g) in 0..68 && (b - r) >= 70
                // Slightly deeper/shaded subsurface water tile
                val isDeepAquaWater = b in 112..170 && g in 82..148 && r in 8..65 &&
                    (b - g) in 5..55 && (b - r) >= 55
                // Toxic/nuclear green water or glowing lava water
                val isNuclearWater = g >= 165 && r in 30..135 && b in 40..150 && (g - r) >= 40
                val isLavaWater = r >= 185 && g in 55..155 && b <= 65 && (r - b) >= 120

                val isCobaltSky = b >= 200 && (b - g) >= 82 && r <= 70

                if (isAquaWater || isDeepAquaWater || isNuclearWater || isLavaWater) {
                    waterCount++
                } else if (isCobaltSky) {
                    pureSkyCount++
                }
                total++
                x += stepX
            }
            y += stepY
        }

        if (total == 0) return 0f
        val waterRatio = waterCount.toFloat() / total
        val skyPenalty = (pureSkyCount.toFloat() / total) * 0.45f
        return (waterRatio - skyPenalty).coerceIn(0f, 1f)
    }
}
