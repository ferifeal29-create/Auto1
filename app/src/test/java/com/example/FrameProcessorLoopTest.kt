package com.example

import android.graphics.Bitmap
import android.graphics.Color
import com.example.capture.FrameProcessorLoop
import com.example.capture.ImageBitmapConverter
import com.example.model.CalibrationConfig
import com.example.vision.MinigameAnalyzer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FrameProcessorLoopTest {

    @Test
    fun `converts tight-stride RGBA buffer into ARGB_8888 detection bitmap`() {
        val width = 8
        val height = 4
        val src = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (y in 0 until height) {
            for (x in 0 until width) {
                src.setPixel(x, y, Color.argb(255, x * 30, y * 60, 200))
            }
        }
        val buf = ByteBuffer.allocate(width * height * 4)
        src.copyPixelsToBuffer(buf)
        buf.rewind()

        val bmp = ImageBitmapConverter.rgbaBufferToDetectionBitmap(
            buffer = buf,
            width = width,
            height = height,
            pixelStride = 4,
            rowStride = width * 4
        )

        assertNotNull(bmp)
        bmp!!
        assertEquals(Bitmap.Config.ARGB_8888, bmp.config)
        assertEquals(width, bmp.width)
        assertEquals(height, bmp.height)
        val p = bmp.getPixel(3, 2)
        assertEquals(90, Color.red(p))
        assertEquals(120, Color.green(p))
        assertEquals(200, Color.blue(p))
        src.recycle()
        bmp.recycle()
    }

    @Test
    fun `handles aligned rowStride and short last-row ImageReader buffer cleanly`() {
        val width = 6
        val height = 4
        val pixelStride = 4
        val rowStride = 32 // 24 bytes active + 8 bytes alignment padding per row

        val src = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (y in 0 until height) {
            for (x in 0 until width) {
                src.setPixel(x, y, Color.argb(255, 10 + x * 20, 40 + y * 30, 180))
            }
        }
        val tight = ByteArray(width * height * pixelStride)
        src.copyPixelsToBuffer(ByteBuffer.wrap(tight))

        // Simulate hardware ImageReader buffer that omits trailing padding after the last row
        val bufferSize = (height - 1) * rowStride + width * pixelStride
        val buf = ByteBuffer.allocate(bufferSize)
        val tightRowBytes = width * pixelStride
        for (y in 0 until height) {
            buf.position(y * rowStride)
            buf.put(tight, y * tightRowBytes, tightRowBytes)
        }
        buf.rewind()

        val bmp = ImageBitmapConverter.rgbaBufferToDetectionBitmap(
            buffer = buf,
            width = width,
            height = height,
            pixelStride = pixelStride,
            rowStride = rowStride
        )

        assertNotNull("Expected short-last-row padded buffer to convert cleanly", bmp)
        bmp!!
        assertEquals(Bitmap.Config.ARGB_8888, bmp.config)
        assertEquals(width, bmp.width)
        assertEquals(height, bmp.height)
        val lastPixel = bmp.getPixel(width - 1, height - 1)
        assertEquals(110, Color.red(lastPixel))
        assertEquals(130, Color.green(lastPixel))
        assertEquals(180, Color.blue(lastPixel))
        src.recycle()
        bmp.recycle()
    }

    @Test
    fun `frame processor loop conflates stale frames and feeds latest ARGB_8888 bitmap to detector`() = runBlocking {
        withTimeout(5_000L) {
            val loop = FrameProcessorLoop()
            val analyzer = MinigameAnalyzer()
            val config = CalibrationConfig()

            // Submit two stale frames before starting consumer -> first should be dropped & recycled
            val stale1 = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888)
            val stale2 = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.rgb(1, 90, 170))
            }
            loop.submitBitmap(stale1, convertDurationMs = 1L, timestampMs = 100L)
            loop.submitBitmap(stale2, convertDurationMs = 2L, timestampMs = 110L)

            assertTrue("Expected first unconsumed frame to be recycled", stale1.isRecycled)
            assertEquals(1L, loop.stats.value.droppedFrames)

            val processed = CompletableDeferred<Long>()
            val job = loop.start(this, frameWaitTimeoutMs = 50L) { frame ->
                assertEquals(Bitmap.Config.ARGB_8888, frame.bitmap.config)
                assertEquals(320, frame.width)
                assertEquals(180, frame.height)
                // Feed into object detector
                analyzer.analyze(frame.bitmap, config)
                processed.complete(frame.frameNumber)
            }

            val detectedFrameNum = processed.await()
            assertEquals(2L, detectedFrameNum)
            loop.stop()
            job.join()
            assertTrue("Expected processed frame bitmap to be recycled after detection", stale2.isRecycled)
            assertEquals(1L, loop.stats.value.processedFrames)
        }
    }
}
