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

        // Fallback alternate only if water wasn't confident on opposite side
        val alternate = bestOtherSidePoint ?: if (bestPoint != null && bestScore < 0.45f) {
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
        val minRx = max(4, (w * 0.016f).toInt())
        val maxRx = max(minRx + 2, (w * 0.036f).toInt())
        val minRy = max(4, (h * 0.032f).toInt())
        val maxRy = max(minRy + 2, (h * 0.072f).toInt())

        var leftEdgeHits = 0
        var rightEdgeHits = 0
        var topEdgeHits = 0
        var bottomEdgeHits = 0

        val stepX = max(1, (w * 0.002f).toInt())
        val stepY = max(1, (h * 0.004f).toInt())

        // Scan left & right vertical perimeter bands
        for (dy in -minRy..minRy step stepY) {
            val py = (cy + dy).coerceIn(0, h - 1)
            for (dx in minRx..maxRx step stepX) {
                val lx = (cx - dx).coerceIn(0, w - 1)
                val rx = (cx + dx).coerceIn(0, w - 1)
                if (isOrangeBorder(bitmap.getPixel(lx, py))) leftEdgeHits++
                if (isOrangeBorder(bitmap.getPixel(rx, py))) rightEdgeHits++
            }
        }

        // Scan top & bottom horizontal perimeter bands
        for (dx in -minRx..minRx step stepX) {
            val px = (cx + dx).coerceIn(0, w - 1)
            for (dy in minRy..maxRy step stepY) {
                val ty = (cy - dy).coerceIn(0, h - 1)
                val by = (cy + dy).coerceIn(0, h - 1)
                if (isOrangeBorder(bitmap.getPixel(px, ty))) topEdgeHits++
                if (isOrangeBorder(bitmap.getPixel(px, by))) bottomEdgeHits++
            }
        }

        val sidesWithOrange = listOf(leftEdgeHits, rightEdgeHits, topEdgeHits, bottomEdgeHits).count { it >= 2 }
        val totalHits = leftEdgeHits + rightEdgeHits + topEdgeHits + bottomEdgeHits
        return sidesWithOrange >= 2 || totalHits >= 10
    }

    private fun isOrangeBorder(pixel: Int): Boolean {
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)
        return r >= 200 && g in 100..195 && b <= 75 && (r - b) >= 130
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
