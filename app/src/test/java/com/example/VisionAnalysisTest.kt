package com.example

import android.graphics.Bitmap
import android.graphics.Color
import com.example.model.CalibrationConfig
import com.example.vision.BiteDetector
import com.example.vision.DialogDetector
import com.example.vision.MinigameAnalyzer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VisionAnalysisTest {

    private val config = CalibrationConfig()
    private val biteDetector = BiteDetector()
    private val minigameAnalyzer = MinigameAnalyzer()
    private val dialogDetector = DialogDetector()

    @Test
    fun `minigame analyzer detects fish, green bar, and half vs full cyan catch progress`() {
        val width = 1000
        val height = 450
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        fillRect(bitmap, 0, 0, width, height, Color.rgb(35, 130, 210))

        val rect = config.minigameBarRegion
        val trackTop = (rect.top * height + (rect.bottom - rect.top) * height * 0.34f).toInt()
        val trackBottom = (rect.top * height + (rect.bottom - rect.top) * height * 0.82f).toInt()
        val trackLeft = (rect.left * width).toInt()
        val trackRight = (rect.right * width).toInt()
        val trackWidth = trackRight - trackLeft

        fillRect(bitmap, trackLeft, trackTop, trackRight, trackBottom, Color.rgb(25, 115, 200))

        // Draw green indicator bar around 70% of track
        fillRect(
            bitmap,
            trackLeft + (trackWidth * 0.62f).toInt(),
            trackTop,
            trackLeft + (trackWidth * 0.78f).toInt(),
            trackBottom,
            Color.rgb(40, 220, 75)
        )

        // Draw dark navy fish around 25% of track
        fillRect(
            bitmap,
            trackLeft + (trackWidth * 0.22f).toInt(),
            trackTop + 2,
            trackLeft + (trackWidth * 0.28f).toInt(),
            trackBottom - 2,
            Color.rgb(22, 52, 105)
        )

        // Draw HALF-FILLED cyan progress strip right under track
        val progTop = trackBottom + 1
        val progBottom = progTop + 4
        val midX = trackLeft + (trackWidth * 0.50f).toInt()
        fillRect(bitmap, trackLeft, progTop, midX, progBottom, Color.rgb(40, 210, 245))
        fillRect(bitmap, midX, progTop, trackRight, progBottom, Color.rgb(22, 45, 75))

        val halfResult = minigameAnalyzer.analyze(bitmap, config)
        assertTrue("Expected minigame active, got $halfResult", halfResult.isMinigameActive)
        assertNotNull(halfResult.barX)
        assertNotNull(halfResult.fishX)
        assertTrue("BarX should be right of 0.55, got ${halfResult.barX}", halfResult.barX!! > 0.55f)
        assertTrue("FishX should be left of 0.40, got ${halfResult.fishX}", halfResult.fishX!! < 0.40f)
        assertFalse("Half progress bar should NOT be catch-ready, got $halfResult", halfResult.isCatchReady)
        assertTrue("Half progress should be ~0.5, got ${halfResult.catchProgress}", halfResult.catchProgress in 0.35f..0.65f)

        // Now fill the entire cyan progress strip to 100%
        fillRect(bitmap, trackLeft, progTop, trackRight, progBottom, Color.rgb(40, 210, 245))
        val fullResult = minigameAnalyzer.analyze(bitmap, config)
        assertTrue("Full progress bar SHOULD be catch-ready, got $fullResult", fullResult.isCatchReady)
        assertTrue("Full progress should be >= 0.90, got ${fullResult.catchProgress}", fullResult.catchProgress >= 0.90f)

        bitmap.recycle()
    }

    @Test
    fun `minigame analyzer detects yellow outline box when fish is outside`() {
        val width = 1000
        val height = 450
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        fillRect(bitmap, 0, 0, width, height, Color.rgb(35, 130, 210))

        val rect = config.minigameBarRegion
        val trackTop = (rect.top * height + (rect.bottom - rect.top) * height * 0.34f).toInt()
        val trackBottom = (rect.top * height + (rect.bottom - rect.top) * height * 0.82f).toInt()
        val trackLeft = (rect.left * width).toInt()
        val trackRight = (rect.right * width).toInt()
        val trackWidth = trackRight - trackLeft

        fillRect(bitmap, trackLeft, trackTop, trackRight, trackBottom, Color.rgb(25, 115, 200))

        val boxL = trackLeft + (trackWidth * 0.17f).toInt()
        val boxR = trackLeft + (trackWidth * 0.33f).toInt()
        val yellow = Color.rgb(215, 225, 45)
        fillRect(bitmap, boxL, trackTop, boxR, trackTop + 3, yellow)
        fillRect(bitmap, boxL, trackBottom - 3, boxR, trackBottom, yellow)
        fillRect(bitmap, boxL, trackTop, boxL + 3, trackBottom, yellow)
        fillRect(bitmap, boxR - 3, trackTop, boxR, trackBottom, yellow)

        fillRect(
            bitmap,
            trackLeft + (trackWidth * 0.72f).toInt(),
            trackTop + 3,
            trackLeft + (trackWidth * 0.78f).toInt(),
            trackBottom - 3,
            Color.rgb(20, 48, 98)
        )

        val result = minigameAnalyzer.analyze(bitmap, config)
        assertTrue("Expected minigame active with yellow outline box, got $result", result.isMinigameActive)
        assertNotNull(result.barX)
        assertNotNull(result.fishX)
        assertTrue("Yellow BarX should be left of 0.40, got ${result.barX}", result.barX!! < 0.40f)
        assertTrue("FishX should be right of 0.60, got ${result.fishX}", result.fishX!! > 0.60f)
        bitmap.recycle()
    }

    @Test
    fun `bite detector detects top and bottom black cinematic bars and rejects normal sky`() {
        val width = 1000
        val height = 450
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        fillRect(bitmap, 0, 0, width, height, Color.rgb(55, 145, 235))
        fillRect(bitmap, (width * 0.34f).toInt(), (height * 0.86f).toInt(), (width * 0.62f).toInt(), height, Color.rgb(30, 90, 175))
        val normalResult = biteDetector.detect(bitmap)
        assertFalse("Normal sky should not trigger bite detection", normalResult.isBiteCinematicVisible)

        fillRect(bitmap, 0, 0, width, (height * 0.14f).toInt(), Color.rgb(4, 4, 4))
        fillRect(bitmap, 0, (height * 0.86f).toInt(), width, height, Color.rgb(4, 4, 4))
        val biteResult = biteDetector.detect(bitmap)
        assertTrue("Cinematic black bars should trigger bite detection, got $biteResult", biteResult.isBiteCinematicVisible)
        bitmap.recycle()
    }

    @Test
    fun `dialog detector detects Fish Caught modal and verifies closed state`() {
        val width = 1000
        val height = 450
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        fillRect(bitmap, 0, 0, width, height, Color.rgb(60, 165, 235))

        val dlg = config.dialogRegion
        fillRect(
            bitmap,
            (dlg.left * width).toInt(),
            (dlg.top * height).toInt(),
            (dlg.right * width).toInt(),
            (dlg.bottom * height).toInt(),
            Color.rgb(18, 45, 98)
        )

        val sx = (config.sellPoint.x * width).toInt()
        val sy = (config.sellPoint.y * height).toInt()
        fillRect(bitmap, sx - 50, sy - 18, sx + 50, sy + 18, Color.rgb(30, 220, 50))

        val kx = (config.keepPoint.x * width).toInt()
        val ky = (config.keepPoint.y * height).toInt()
        fillRect(bitmap, kx - 50, ky - 18, kx + 50, ky + 18, Color.rgb(30, 145, 245))

        val openResult = dialogDetector.detect(bitmap, config)
        assertTrue("Expected Fish Caught dialog detected, got $openResult", openResult.isFishCaughtDialogVisible)
        assertTrue("Expected Sell button detected", openResult.hasSellButton)
        assertTrue("Expected Keep button detected", openResult.hasKeepButton)
        assertFalse("Dialog should not report closed", dialogDetector.isDialogClosed(bitmap, config))

        fillRect(bitmap, 0, 0, width, height, Color.rgb(60, 165, 235))
        assertTrue("Expected dialog verified closed on day sky", dialogDetector.isDialogClosed(bitmap, config))

        // Verify closed even on dark night background
        fillRect(bitmap, 0, 0, width, height, Color.rgb(18, 42, 92))
        assertTrue("Expected dialog verified closed on night sky", dialogDetector.isDialogClosed(bitmap, config))
        bitmap.recycle()
    }

    private fun fillRect(
        bitmap: Bitmap,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        color: Int
    ) {
        val l = left.coerceIn(0, bitmap.width)
        val t = top.coerceIn(0, bitmap.height)
        val r = right.coerceIn(l, bitmap.width)
        val b = bottom.coerceIn(t, bitmap.height)
        val w = r - l
        val h = b - t
        if (w <= 0 || h <= 0) return
        val row = IntArray(w) { color }
        for (y in t until b) {
            bitmap.setPixels(row, 0, w, l, y, w, 1)
        }
    }
}
