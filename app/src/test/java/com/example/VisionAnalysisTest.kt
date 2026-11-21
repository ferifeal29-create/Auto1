package com.example

import android.graphics.Bitmap
import android.graphics.Color
import com.example.model.CalibrationConfig
import com.example.vision.BiteDetector
import com.example.vision.DialogDetector
import com.example.vision.MinigameAnalyzer
import com.example.vision.WaterBlockDetector
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
    private val waterBlockDetector = WaterBlockDetector()

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

    @Test
    fun `water block detector locates water on left or right of character and rejects cobalt sky`() {
        val width = 1000
        val height = 450
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        // Fill with cobalt daytime sky
        fillRect(bitmap, 0, 0, width, height, Color.rgb(35, 110, 240))
        val skyOnly = waterBlockDetector.detectWaterNearCharacter(bitmap)
        assertTrue("Pure sky should not detect water, got $skyOnly", skyOnly.detectedPoint == null)

        // Place aqua water block on the RIGHT side of character (x=0.60..0.78, y=0.45..0.72)
        fillRect(bitmap, 600, 200, 780, 325, Color.rgb(45, 175, 210))
        val rightWater = waterBlockDetector.detectWaterNearCharacter(bitmap)
        assertNotNull("Expected right water detected", rightWater.detectedPoint)
        assertTrue("Detected water should be on right (>0.55), got ${rightWater.detectedPoint}", rightWater.detectedPoint!!.x > 0.55f)

        // Clear back to sky, place water block on the LEFT side of character (x=0.22..0.40, y=0.45..0.72)
        fillRect(bitmap, 0, 0, width, height, Color.rgb(35, 110, 240))
        fillRect(bitmap, 220, 200, 400, 325, Color.rgb(45, 175, 210))
        val leftWater = waterBlockDetector.detectWaterNearCharacter(bitmap)
        assertNotNull("Expected left water detected", leftWater.detectedPoint)
        assertTrue("Detected water should be on left (<0.45), got ${leftWater.detectedPoint}", leftWater.detectedPoint!!.x < 0.45f)

        bitmap.recycle()
    }

    @Test
    fun `water block detector identifies active orange bait selection border`() {
        val width = 1000
        val height = 450
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        fillRect(bitmap, 0, 0, width, height, Color.rgb(30, 80, 160))

        val baitPt = config.baitPoint
        assertFalse("Empty dark slot should not report selected", waterBlockDetector.isBaitSlotSelected(bitmap, baitPt))

        val cx = (baitPt.x * width).toInt()
        val cy = (baitPt.y * height).toInt()
        val rx = (width * 0.025f).toInt()
        val ry = (height * 0.045f).toInt()
        val orange = Color.rgb(245, 155, 25)
        fillRect(bitmap, cx - rx - 2, cy - ry - 2, cx + rx + 2, cy - ry + 3, orange)
        fillRect(bitmap, cx - rx - 2, cy + ry - 3, cx + rx + 2, cy + ry + 2, orange)
        fillRect(bitmap, cx - rx - 2, cy - ry - 2, cx - rx + 3, cy + ry + 2, orange)
        fillRect(bitmap, cx + rx - 3, cy - ry - 2, cx + rx + 2, cy + ry + 2, orange)

        assertTrue("Orange border around bait slot should report selected", waterBlockDetector.isBaitSlotSelected(bitmap, baitPt))
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

    @Test
    fun `detectors accurately classify real Gridmere screenshots`() {
        val dir = java.io.File("../public/assets/aistudio")
        if (!dir.exists()) return

        fun loadGameFrame(filename: String): Bitmap {
            val file = java.io.File(dir, filename)
            val bmp = android.graphics.BitmapFactory.decodeFile(file.absolutePath)!!
            if (bmp.height <= bmp.width) return bmp
            var topY = 0
            var botY = bmp.height - 1
            val cx = bmp.width / 2
            for (y in (bmp.height * 0.25).toInt() until (bmp.height * 0.75).toInt()) {
                val p = bmp.getPixel(cx, y)
                if (Color.red(p) < 230 || Color.green(p) < 230 || Color.blue(p) < 230) {
                    topY = y
                    break
                }
            }
            for (y in (bmp.height * 0.75).toInt() downTo (bmp.height * 0.25).toInt()) {
                val p = bmp.getPixel(cx, y)
                if (Color.red(p) < 230 || Color.green(p) < 230 || Color.blue(p) < 230) {
                    botY = y
                    break
                }
            }
            val cropped = Bitmap.createBitmap(bmp, 0, topY, bmp.width, (botY - topY + 1).coerceAtLeast(10))
            bmp.recycle()
            return cropped
        }

        // 1. Fish Caught Dialog
        loadGameFrame("game_Screenshot_20261119-031506_92e34bdd.jpg").let { bmp ->
            val dlg = dialogDetector.detect(bmp, config)
            assertTrue("Expected Fish Caught dialog visible", dlg.isFishCaughtDialogVisible)
            assertTrue("Expected Sell button detected", dlg.hasSellButton)
            assertTrue("Expected Keep button detected", dlg.hasKeepButton)
            bmp.recycle()
        }

        // 2. Bait selected + Water on left
        loadGameFrame("game_Screenshot_20261120-110437_3f652c2a.jpg").let { bmp ->
            assertTrue("Expected worm bait slot selected", waterBlockDetector.isBaitSlotSelected(bmp, config.baitPoint))
            val water = waterBlockDetector.detectWaterNearCharacter(bmp)
            assertNotNull("Expected water block detected", water.detectedPoint)
            assertTrue("Expected water on left of character", water.detectedPoint!!.x < 0.45f)
            assertFalse("Idle state should not trigger bite", biteDetector.detect(bmp).isBiteCinematicVisible)
            bmp.recycle()
        }

        // 3. Bite black cinematic bars
        loadGameFrame("game_Screenshot_20261120-110515_2b048c6d.jpg").let { bmp ->
            assertTrue("Expected bite cinematic bars detected", biteDetector.detect(bmp).isBiteCinematicVisible)
            bmp.recycle()
        }

        // 4. Minigame mid-progress (~47%)
        loadGameFrame("Screenshot_20261120-112632_239af61a.jpg").let { bmp ->
            val res = minigameAnalyzer.analyze(bmp, config)
            assertTrue("Expected minigame active", res.isMinigameActive)
            assertTrue("Expected fish inside bar", res.isFishInsideBar)
            assertFalse("Mid-progress should NOT be catch-ready", res.isCatchReady)
            assertTrue("Expected catchProgress in 0.30..0.65, got ${res.catchProgress}", res.catchProgress in 0.30f..0.65f)
            bmp.recycle()
        }

        // 5. Minigame full progress (catch-ready)
        loadGameFrame("Screenshot_20261120-112655_a3173573.jpg").let { bmp ->
            val res = minigameAnalyzer.analyze(bmp, config)
            assertTrue("Expected minigame active", res.isMinigameActive)
            assertTrue("Expected fish inside bar", res.isFishInsideBar)
            assertTrue("Expected catch-ready at full progress, got $res", res.isCatchReady)
            bmp.recycle()
        }
    }
}
