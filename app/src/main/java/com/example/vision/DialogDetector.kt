package com.example.vision

import android.graphics.Bitmap
import android.graphics.Color
import com.example.model.CalibrationConfig
import com.example.model.RelativePoint
import kotlin.math.max

data class DialogDetectionResult(
    val isFishCaughtDialogVisible: Boolean,
    val hasSellButton: Boolean,
    val hasKeepButton: Boolean,
    val navyPanelRatio: Float,
    val confidence: Float
)

/**
 * Pure Bitmap-based detector for Gridmere's "Fish Caught!" modal dialog
 * and its green "Sell" / blue "Keep" action buttons.
 * Handles semi-transparent navy backdrop over varying world blocks (sky, clouds, wood, lava).
 */
class DialogDetector {

    fun detect(bitmap: Bitmap, config: CalibrationConfig): DialogDetectionResult {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) {
            return DialogDetectionResult(
                isFishCaughtDialogVisible = false,
                hasSellButton = false,
                hasKeepButton = false,
                navyPanelRatio = 0f,
                confidence = 0f
            )
        }

        val navyPanelRatio = measureNavyDialogRatio(bitmap, config)
        val sellGreenRatio = measurePatchMatchRatio(bitmap, config.sellPoint) { r, g, b ->
            g > 135 && g > r + 40 && g > b + 40
        }
        val keepBlueRatio = measurePatchMatchRatio(bitmap, config.keepPoint) { r, g, b ->
            b > 190 && g in 110..205 && r < 65 && b > r + 125
        }

        val hasSellButton = sellGreenRatio >= 0.40f
        val hasKeepButton = keepBlueRatio >= 0.40f
        val isVisible = (hasSellButton && hasKeepButton && navyPanelRatio >= 0.12f) ||
            (navyPanelRatio >= 0.35f && (hasSellButton || hasKeepButton))

        val confidence = (
            (navyPanelRatio * 0.35f) +
                (if (hasSellButton) 0.325f else sellGreenRatio * 0.2f) +
                (if (hasKeepButton) 0.325f else keepBlueRatio * 0.2f)
            ).coerceIn(0f, 1f)

        return DialogDetectionResult(
            isFishCaughtDialogVisible = isVisible,
            hasSellButton = hasSellButton,
            hasKeepButton = hasKeepButton,
            navyPanelRatio = navyPanelRatio,
            confidence = confidence
        )
    }

    fun isDialogClosed(bitmap: Bitmap, config: CalibrationConfig): Boolean {
        val result = detect(bitmap, config)
        return !result.isFishCaughtDialogVisible && !result.hasSellButton
    }

    private fun measureNavyDialogRatio(bitmap: Bitmap, config: CalibrationConfig): Float {
        val rect = config.dialogRegion.clamped()
        val width = bitmap.width
        val height = bitmap.height
        val xStart = (rect.left * width).toInt().coerceIn(0, width - 2)
        val xEnd = (rect.right * width).toInt().coerceIn(xStart + 1, width - 1)
        val yStart = (rect.top * height).toInt().coerceIn(0, height - 2)
        val yEnd = (rect.bottom * height).toInt().coerceIn(yStart + 1, height - 1)

        val stepX = max(1, (xEnd - xStart) / 14)
        val stepY = max(1, (yEnd - yStart) / 16)

        var navyCount = 0
        var total = 0

        var y = yStart
        while (y <= yEnd) {
            var x = xStart
            while (x <= xEnd) {
                val pixel = bitmap.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)

                // Gridmere semi-transparent dark navy dialog panel
                val isNavy = r <= 88 && g in 15..128 && b in 52..185 &&
                    b > r + 15 && b >= g + 8
                if (isNavy) navyCount++
                total++
                x += stepX
            }
            y += stepY
        }

        return if (total > 0) navyCount.toFloat() / total else 0f
    }

    private inline fun measurePatchMatchRatio(
        bitmap: Bitmap,
        point: RelativePoint,
        predicate: (r: Int, g: Int, b: Int) -> Boolean
    ): Float {
        val clamped = point.clamped()
        val cx = (clamped.x * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
        val cy = (clamped.y * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
        val radiusX = max(5, (bitmap.width * 0.020f).toInt())
        val radiusY = max(3, (bitmap.height * 0.014f).toInt())

        val offsets = intArrayOf(-2, -1, 0, 1, 2)
        var matched = 0
        var total = 0

        for (oy in offsets) {
            val py = (cy + (oy * radiusY / 2)).coerceIn(0, bitmap.height - 1)
            for (ox in offsets) {
                val px = (cx + (ox * radiusX / 2)).coerceIn(0, bitmap.width - 1)
                val pixel = bitmap.getPixel(px, py)
                if (predicate(Color.red(pixel), Color.green(pixel), Color.blue(pixel))) {
                    matched++
                }
                total++
            }
        }

        return if (total > 0) matched.toFloat() / total else 0f
    }
}
