package com.example.capture

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import kotlin.math.max

/**
 * Manages MediaProjection, VirtualDisplay, and ImageReader to capture live screen Bitmaps.
 * Automatically detects orientation switches (e.g., portrait dashboard -> landscape Gridmere)
 * and resizes the VirtualDisplay and ImageReader accordingly.
 *
 * - [captureFreshBitmap]: returns ONLY a newly rendered frame (never stale cached data), ideal for fast minigame tracking.
 * - [captureLatestBitmap]: falls back to the latest cached frame when the screen is static (ideal for dialog verification).
 */
class ScreenCaptureManager(private val context: Context) {

    private val projectionManager =
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null

    private var captureWidth: Int = 0
    private var captureHeight: Int = 0
    private var captureDensity: Int = DisplayMetrics.DENSITY_DEFAULT
    private var onProjectionStoppedCallback: (() -> Unit)? = null

    private val cacheLock = Any()
    private var lastFrameBitmap: Bitmap? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            stop()
            onProjectionStoppedCallback?.invoke()
        }
    }

    val isActive: Boolean
        get() = mediaProjection != null && imageReader != null

    fun start(
        resultCode: Int,
        data: Intent,
        onStopped: (() -> Unit)? = null
    ): Boolean {
        stop()
        onProjectionStoppedCallback = onStopped

        val projection = projectionManager.getMediaProjection(resultCode, data) ?: return false
        mediaProjection = projection

        val thread = HandlerThread("AutoFishScreenCapture").apply { start() }
        handlerThread = thread
        val looperHandler = Handler(thread.looper)
        handler = looperHandler

        projection.registerCallback(projectionCallback, looperHandler)

        val (width, height, density) = readScaledDisplayMetrics()
        captureWidth = width
        captureHeight = height
        captureDensity = density

        createImageReaderAndDisplay(projection, width, height, density, looperHandler)
        return true
    }

    /**
     * Captures ONLY a freshly rendered frame from ImageReader (never returns stale cached bitmaps).
     * Polls briefly every 6ms up to [waitIfEmptyMs] for the compositor to deliver the next frame.
     */
    suspend fun captureFreshBitmap(waitIfEmptyMs: Long = 28L): Bitmap? = withContext(Dispatchers.Default) {
        val projection = mediaProjection ?: return@withContext null
        ensureOrientationMatchesDisplay(projection)

        val reader = imageReader ?: return@withContext null
        var image = acquireLatestSafe(reader)
        if (image == null && waitIfEmptyMs > 0L) {
            val deadline = System.currentTimeMillis() + waitIfEmptyMs
            while (image == null && System.currentTimeMillis() < deadline) {
                delay(6L)
                image = acquireLatestSafe(reader)
            }
        }
        if (image == null) {
            return@withContext null
        }
        return@withContext imageToBitmap(image, cacheCopy = false)
    }

    /**
     * Captures the latest frame, falling back to a copy of the last captured frame if the
     * display is currently static and no new Image has arrived.
     */
    suspend fun captureLatestBitmap(): Bitmap? = withContext(Dispatchers.Default) {
        val projection = mediaProjection ?: return@withContext null
        ensureOrientationMatchesDisplay(projection)

        val reader = imageReader ?: return@withContext null
        val image = acquireLatestSafe(reader) ?: return@withContext copyCachedFrame()
        return@withContext imageToBitmap(image, cacheCopy = true) ?: copyCachedFrame()
    }

    private fun acquireLatestSafe(reader: ImageReader): Image? {
        return try {
            reader.acquireLatestImage()
        } catch (_: IllegalStateException) {
            null
        }
    }

    private fun imageToBitmap(image: Image, cacheCopy: Boolean): Bitmap? {
        try {
            val planes = image.planes
            if (planes.isEmpty()) return null
            val plane = planes[0]
            val buffer: ByteBuffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * image.width

            val tempWidth = image.width + max(0, rowPadding / max(1, pixelStride))
            val paddedBitmap = Bitmap.createBitmap(
                tempWidth,
                image.height,
                Bitmap.Config.ARGB_8888
            )
            paddedBitmap.copyPixelsFromBuffer(buffer)

            val fresh = if (tempWidth == image.width) {
                paddedBitmap
            } else {
                val cropped = Bitmap.createBitmap(paddedBitmap, 0, 0, image.width, image.height)
                paddedBitmap.recycle()
                cropped
            }

            if (cacheCopy) {
                updateCachedFrame(fresh)
            }
            return fresh
        } catch (_: Exception) {
            return null
        } finally {
            image.close()
        }
    }

    fun stop() {
        clearCachedFrame()

        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }
        virtualDisplay = null

        try {
            imageReader?.close()
        } catch (_: Exception) {
        }
        imageReader = null

        try {
            mediaProjection?.unregisterCallback(projectionCallback)
            mediaProjection?.stop()
        } catch (_: Exception) {
        }
        mediaProjection = null

        try {
            handlerThread?.quitSafely()
        } catch (_: Exception) {
        }
        handlerThread = null
        handler = null
    }

    private fun updateCachedFrame(fresh: Bitmap) {
        val copy = try {
            fresh.copy(Bitmap.Config.ARGB_8888, false)
        } catch (_: Exception) {
            null
        } ?: return

        synchronized(cacheLock) {
            lastFrameBitmap?.recycle()
            lastFrameBitmap = copy
        }
    }

    private fun copyCachedFrame(): Bitmap? {
        synchronized(cacheLock) {
            val cached = lastFrameBitmap ?: return null
            if (cached.isRecycled) return null
            return try {
                cached.copy(Bitmap.Config.ARGB_8888, false)
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun clearCachedFrame() {
        synchronized(cacheLock) {
            lastFrameBitmap?.recycle()
            lastFrameBitmap = null
        }
    }

    private fun ensureOrientationMatchesDisplay(projection: MediaProjection) {
        val (targetW, targetH, targetDensity) = readScaledDisplayMetrics()
        if (targetW != captureWidth || targetH != captureHeight) {
            captureWidth = targetW
            captureHeight = targetH
            captureDensity = targetDensity
            clearCachedFrame()

            val looperHandler = handler ?: return
            try {
                virtualDisplay?.release()
            } catch (_: Exception) {
            }
            try {
                imageReader?.close()
            } catch (_: Exception) {
            }
            createImageReaderAndDisplay(projection, targetW, targetH, targetDensity, looperHandler)
        }
    }

    @SuppressLint("WrongConstant")
    private fun createImageReaderAndDisplay(
        projection: MediaProjection,
        width: Int,
        height: Int,
        density: Int,
        looperHandler: Handler
    ) {
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        imageReader = reader
        virtualDisplay = projection.createVirtualDisplay(
            "AutoFishCapture",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            looperHandler
        )
    }

    private fun readScaledDisplayMetrics(): Triple<Int, Int, Int> {
        val (rawWidth, rawHeight, density) = readRawDisplayMetrics()
        val maxSide = max(rawWidth, rawHeight)
        val scale = if (maxSide > 1280) 1280f / maxSide else 1f
        val scaledW = max(320, (rawWidth * scale).toInt() and 0xFFFFFFFE.toInt())
        val scaledH = max(240, (rawHeight * scale).toInt() and 0xFFFFFFFE.toInt())
        return Triple(scaledW, scaledH, density)
    }

    @Suppress("DEPRECATION")
    private fun readRawDisplayMetrics(): Triple<Int, Int, Int> {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = dm.getDisplay(android.view.Display.DEFAULT_DISPLAY)
        if (display != null) {
            val metrics = DisplayMetrics()
            display.getRealMetrics(metrics)
            if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
                return Triple(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
            }
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            val density = context.resources.displayMetrics.densityDpi
            Triple(bounds.width(), bounds.height(), density)
        } else {
            val metrics = DisplayMetrics()
            windowManager.defaultDisplay.getRealMetrics(metrics)
            Triple(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
        }
    }
}
