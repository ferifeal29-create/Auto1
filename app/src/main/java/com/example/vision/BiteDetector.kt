package com.example.vision

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.max

data class BiteDetectionResult(
    val isBiteCinematicVisible: Boolean,
    val topBlackRatio: Float,
    val bottomBlackRatio: Float
)

/**
 * Detects Gridmere's Fase 2 bite alert: diagonal black cinematic bars
 * appearing across the top and bottom (covering the hotbar) when a fish bites.
 */
class BiteDetector {

    fun detect(bitmap: Bitmap): BiteDetectionResult {
        val width = bitmap.width
        val height = bitmap.height
        if (width < 32 || height < 32) {
            return BiteDetectionResult(false, 0f, 0f)
        }

        // Top-right black wedge (avoids Day/Time box on top-left and menu button on far right)
        val topBlackRatio = measureBlackRatio(
            bitmap = bitmap,
            xStart = (width * 0.56f).toInt(),
            xEnd = (width * 0.88f).toInt(),
            yStart = (height * 0.040f).toInt(),
            yEnd = (height * 0.095f).toInt()
        )

        // Bottom-center black wedge (covers the hotbar area when fish bites)
        val bottomBlackRatio = measureBlackRatio(
            bitmap = bitmap,
            xStart = (width * 0.18f).toInt(),
            xEnd = (width * 0.68f).toInt(),
            yStart = (height * 0.890f).toInt(),
            yEnd = (height * 0.955f).toInt()
        )

        val isBite = topBlackRatio >= 0.72f && bottomBlackRatio >= 0.72f
        return BiteDetectionResult(
            isBiteCinematicVisible = isBite,
            topBlackRatio = topBlackRatio,
            bottomBlackRatio = bottomBlackRatio
        )
    }

    private fun measureBlackRatio(
        bitmap: Bitmap,
        xStart: Int,
        xEnd: Int,
        yStart: Int,
        yEnd: Int
    ): Float {
        val sx = xStart.coerceIn(0, bitmap.width - 2)
        val ex = xEnd.coerceIn(sx + 1, bitmap.width - 1)
        val sy = yStart.coerceIn(0, bitmap.height - 2)
        val ey = yEnd.coerceIn(sy + 1, bitmap.height - 1)

        val w = ex - sx + 1
        val h = ey - sy + 1
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, sx, sy, w, h)

        val stepX = max(1, w / 24)
        val stepY = max(1, h / 8)

        var blackCount = 0
        var total = 0

        var row = 0
        while (row < h) {
            val rowOffset = row * w
            var col = 0
            while (col < w) {
                val pixel = pixels[rowOffset + col]
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                if (r <= 22 && g <= 22 && b <= 22) {
                    blackCount++
                }
                total++
                col += stepX
            }
            row += stepY
        }

        return if (total > 0) blackCount.toFloat() / total else 0f
    }
}
