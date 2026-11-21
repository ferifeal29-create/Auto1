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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * Manages MediaProjection, VirtualDisplay, and ImageReader to capture live screen Bitmaps.
 * Automatically handles orientation switches (e.g., portrait dashboard -> landscape Gridmere)
 * using VirtualDisplay.resize + setSurface (safe on Android 14+/API 34+ without recreating VirtualDisplay).
 */
class ScreenCaptureManager(private val context: Context) {

    private val projectionManager =
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val captureLock = Any()
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null

    private var captureWidth: Int = 0
    private var captureHeight: Int = 0
    private var captureDensity: Int = DisplayMetrics.DENSITY_DEFAULT
    private var onProjectionStoppedCallback: (() -> Unit)? = null

    val frameProcessorLoop = FrameProcessorLoop()

    private val cacheLock = Any()
    private var lastFrameBitmap: Bitmap? = null
    @Volatile
    private var lastCacheUpdateMs: Long = 0L

    private val imageAvailableListener = ImageReader.OnImageAvailableListener { reader ->
        val isCurrentReader = synchronized(captureLock) {
            mediaProjection != null && reader === imageReader
        }
        if (!isCurrentReader) return@OnImageAvailableListener

        val image = try {
            reader.acquireLatestImage()
        } catch (_: IllegalStateException) {
            null
        } ?: return@OnImageAvailableListener

        val t0 = System.currentTimeMillis()
        val bitmap = try {
            ImageBitmapConverter.imageToDetectionBitmap(image)
        } finally {
            image.close()
        } ?: return@OnImageAvailableListener

        val now = System.currentTimeMillis()
        val shouldRefreshCache = (now - lastCacheUpdateMs) >= 120L ||
            synchronized(cacheLock) { lastFrameBitmap == null || lastFrameBitmap?.isRecycled == true }
        if (shouldRefreshCache) {
            updateCachedFrame(bitmap)
            lastCacheUpdateMs = now
        }

        frameProcessorLoop.submitBitmap(
            bitmap = bitmap,
            convertDurationMs = (now - t0).coerceAtLeast(0L),
            timestampMs = now
        )
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            stop()
            onProjectionStoppedCallback?.invoke()
        }

        override fun onCapturedContentResize(width: Int, height: Int) {
            if (width <= 0 || height <= 0) return
            val (scaledW, scaledH) = scaleDimensions(width, height)
            synchronized(captureLock) {
                if (mediaProjection == null || virtualDisplay == null) return
                if (scaledW != captureWidth || scaledH != captureHeight) {
                    resizeCapturePipelineLocked(scaledW, scaledH, captureDensity)
                }
            }
        }
    }

    val isActive: Boolean
        get() = synchronized(captureLock) { mediaProjection != null && imageReader != null }

    fun start(
        resultCode: Int,
        data: Intent,
        onStopped: (() -> Unit)? = null
    ): Boolean {
        stop()

        val projection = try {
            projectionManager.getMediaProjection(resultCode, data)
        } catch (_: Exception) {
            null
        } ?: return false

        val thread = HandlerThread("AutoFishScreenCapture").apply { start() }
        val looperHandler = Handler(thread.looper)

        val (width, height, density) = readScaledDisplayMetrics()

        synchronized(captureLock) {
            onProjectionStoppedCallback = onStopped
            mediaProjection = projection
            handlerThread = thread
            handler = looperHandler
            captureWidth = width
            captureHeight = height
            captureDensity = density

            projection.registerCallback(projectionCallback, looperHandler)
            createImageReaderAndDisplayLocked(projection, width, height, density, looperHandler)
        }
        return true
    }

    /**
     * Starts a continuous frame processing loop that feeds converted [Bitmap.Config.ARGB_8888]
     * frames from MediaProjection [ImageReader] directly into [onFrame] for object detection.
     */
    fun startFrameProcessingLoop(
        scope: CoroutineScope,
        frameWaitTimeoutMs: Long = 100L,
        onFrame: suspend (DetectionFrame) -> Unit
    ): Job = frameProcessorLoop.start(
        scope = scope,
        frameWaitTimeoutMs = frameWaitTimeoutMs,
        onFrame = onFrame
    )

    /**
     * Captures ONLY a freshly rendered frame from ImageReader (never returns stale cached bitmaps).
     * Suspends cleanly on the event-driven [FrameProcessorLoop] until the next frame arrives.
     */
    suspend fun captureFreshBitmap(waitIfEmptyMs: Long = 28L): Bitmap? = withContext(Dispatchers.Default) {
        if (!ensureOrientationMatchesDisplay()) return@withContext null
        return@withContext frameProcessorLoop.awaitNextFrame(waitIfEmptyMs)?.bitmap
    }

    /**
     * Captures the latest frame, falling back to a copy of the last captured frame if the
     * display is currently static and no new Image has arrived.
     */
    suspend fun captureLatestBitmap(): Bitmap? = withContext(Dispatchers.Default) {
        if (!ensureOrientationMatchesDisplay()) return@withContext null
        val fresh = frameProcessorLoop.awaitNextFrame(timeoutMs = 0L)?.bitmap
        if (fresh != null) {
            updateCachedFrame(fresh)
            lastCacheUpdateMs = System.currentTimeMillis()
            return@withContext fresh
        }
        return@withContext copyCachedFrame()
    }

    fun stop() {
        frameProcessorLoop.stop()
        clearCachedFrame()

        synchronized(captureLock) {
            try {
                virtualDisplay?.release()
            } catch (_: Exception) {
            }
            virtualDisplay = null

            try {
                imageReader?.setOnImageAvailableListener(null, null)
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

    private fun ensureOrientationMatchesDisplay(): Boolean {
        val (targetW, targetH, targetDensity) = readScaledDisplayMetrics()
        synchronized(captureLock) {
            if (mediaProjection == null) return false
            if (targetW != captureWidth || targetH != captureHeight) {
                resizeCapturePipelineLocked(targetW, targetH, targetDensity)
            }
            return imageReader != null
        }
    }

    @SuppressLint("WrongConstant")
    private fun resizeCapturePipelineLocked(width: Int, height: Int, density: Int) {
        captureWidth = width
        captureHeight = height
        captureDensity = density
        frameProcessorLoop.clearPending()
        clearCachedFrame()
        lastCacheUpdateMs = 0L

        val display = virtualDisplay
        val projection = mediaProjection
        val looperHandler = handler ?: return

        if (display != null) {
            val oldReader = imageReader
            val newReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3).apply {
                setOnImageAvailableListener(imageAvailableListener, looperHandler)
            }
            imageReader = newReader
            try {
                display.resize(width, height, density)
                display.setSurface(newReader.surface)
                oldReader?.setOnImageAvailableListener(null, null)
                oldReader?.close()
                return
            } catch (_: Exception) {
                // Fall back to recreating only if resize/setSurface throws on older OEMs
                try {
                    display.release()
                } catch (_: Exception) {
                }
                virtualDisplay = null
                oldReader?.setOnImageAvailableListener(null, null)
                oldReader?.close()
                newReader.setOnImageAvailableListener(null, null)
                newReader.close()
                imageReader = null
            }
        }

        if (projection != null) {
            createImageReaderAndDisplayLocked(projection, width, height, density, looperHandler)
        }
    }

    @SuppressLint("WrongConstant")
    private fun createImageReaderAndDisplayLocked(
        projection: MediaProjection,
        width: Int,
        height: Int,
        density: Int,
        looperHandler: Handler
    ) {
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3).apply {
            setOnImageAvailableListener(imageAvailableListener, looperHandler)
        }
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
        val (scaledW, scaledH) = scaleDimensions(rawWidth, rawHeight)
        return Triple(scaledW, scaledH, density)
    }

    private fun scaleDimensions(rawWidth: Int, rawHeight: Int): Pair<Int, Int> {
        val maxSide = max(rawWidth, rawHeight)
        val scale = if (maxSide > 1280) 1280f / maxSide else 1f
        val scaledW = max(320, (rawWidth * scale).toInt() and 0xFFFFFFFE.toInt())
        val scaledH = max(240, (rawHeight * scale).toInt() and 0xFFFFFFFE.toInt())
        return scaledW to scaledH
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
