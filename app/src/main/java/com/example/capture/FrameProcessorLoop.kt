package com.example.capture

import android.graphics.Bitmap
import android.media.Image
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

/**
 * A single frame converted from MediaProjection [ImageReader] output into an unpadded
 * [Bitmap.Config.ARGB_8888] bitmap suitable for visual object detection.
 */
data class DetectionFrame(
    val bitmap: Bitmap,
    val width: Int,
    val height: Int,
    val frameNumber: Long,
    val timestampMs: Long
)

data class FrameProcessingStats(
    val isRunning: Boolean = false,
    val processedFrames: Long = 0L,
    val droppedFrames: Long = 0L,
    val lastConvertDurationMs: Long = 0L,
    val lastFrameWidth: Int = 0,
    val lastFrameHeight: Int = 0
)

/**
 * Converts MediaProjection [ImageReader] RGBA_8888 planes into clean [Bitmap.Config.ARGB_8888]
 * bitmaps without row-stride padding artifacts or last-row BufferUnderflow crashes.
 */
object ImageBitmapConverter {

    private val bufferLock = Any()
    private var packedRowBuffer = ByteArray(0)

    fun imageToDetectionBitmap(image: Image): Bitmap? {
        val width = image.width
        val height = image.height
        if (width <= 0 || height <= 0) return null
        val planes = image.planes
        if (planes.isEmpty()) return null
        val plane = planes[0]
        return rgbaBufferToDetectionBitmap(
            buffer = plane.buffer,
            width = width,
            height = height,
            pixelStride = plane.pixelStride,
            rowStride = plane.rowStride
        )
    }

    fun rgbaBufferToDetectionBitmap(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        pixelStride: Int,
        rowStride: Int
    ): Bitmap? {
        if (width <= 0 || height <= 0 || pixelStride <= 0 || rowStride <= 0) return null

        return try {
            val dup = buffer.duplicate().apply { rewind() }
            val tightRowBytes = width * pixelStride
            val rowPadding = rowStride - tightRowBytes

            if (rowPadding == 0 && pixelStride == 4) {
                val required = tightRowBytes * height
                if (dup.remaining() < required) return null
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmap.copyPixelsFromBuffer(dup)
                return bitmap
            }

            if (pixelStride == 4) {
                // Handle GPU/VirtualDisplay aligned rowStride and short-last-row buffers safely
                val neededBytes = width * height * 4
                val maxAvailable = dup.limit()
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                synchronized(bufferLock) {
                    if (packedRowBuffer.size < neededBytes) {
                        packedRowBuffer = ByteArray(neededBytes)
                    }
                    val packed = packedRowBuffer
                    for (row in 0 until height) {
                        val srcOffset = row * rowStride
                        val dstOffset = row * tightRowBytes
                        if (srcOffset + tightRowBytes > maxAvailable) {
                            bitmap.recycle()
                            return null
                        }
                        dup.position(srcOffset)
                        dup.get(packed, dstOffset, tightRowBytes)
                    }
                    bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(packed, 0, neededBytes))
                }
                return bitmap
            }

            // Generic fallback for non-4-byte pixelStride
            val paddedWidth = width + (rowPadding / pixelStride).coerceAtLeast(0)
            val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
            padded.copyPixelsFromBuffer(dup)
            if (paddedWidth == width) {
                padded
            } else {
                val cropped = Bitmap.createBitmap(padded, 0, 0, width, height)
                padded.recycle()
                cropped
            }
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * Real-time conflated frame processing loop for object detection over MediaProjection
 * [ImageReader] output. Always retains only the newest converted [Bitmap.Config.ARGB_8888]
 * frame (dropping and recycling stale frames under backpressure) so object detection runs on
 * the freshest display state without latency buildup.
 */
class FrameProcessorLoop {

    private val frameLock = Any()
    private val frameCounter = AtomicLong(0L)

    private var pendingFrame: DetectionFrame? = null
    private var nextFrameWaiter: CompletableDeferred<DetectionFrame>? = null
    private var activeLoopJob: Job? = null

    private val _stats = MutableStateFlow(FrameProcessingStats())
    val stats: StateFlow<FrameProcessingStats> = _stats.asStateFlow()

    /**
     * Publishes a newly converted [Bitmap] from [ImageReader.OnImageAvailableListener].
     * Takes ownership of [bitmap] unless no waiter/loop is active and it replaces a stale frame.
     */
    fun submitBitmap(
        bitmap: Bitmap,
        convertDurationMs: Long = 0L,
        timestampMs: Long = System.currentTimeMillis()
    ) {
        if (bitmap.isRecycled) return
        val frame = DetectionFrame(
            bitmap = bitmap,
            width = bitmap.width,
            height = bitmap.height,
            frameNumber = frameCounter.incrementAndGet(),
            timestampMs = timestampMs
        )

        var dropped: Bitmap? = null
        var waiterToComplete: CompletableDeferred<DetectionFrame>? = null

        synchronized(frameLock) {
            val waiter = nextFrameWaiter
            if (waiter != null && !waiter.isCompleted) {
                nextFrameWaiter = null
                waiterToComplete = waiter
            } else {
                dropped = pendingFrame?.bitmap
                pendingFrame = frame
            }
        }

        dropped?.let { oldBmp ->
            if (oldBmp !== bitmap && !oldBmp.isRecycled) {
                oldBmp.recycle()
                _stats.value = _stats.value.copy(
                    droppedFrames = _stats.value.droppedFrames + 1L
                )
            }
        }

        _stats.value = _stats.value.copy(
            lastConvertDurationMs = convertDurationMs,
            lastFrameWidth = frame.width,
            lastFrameHeight = frame.height
        )

        waiterToComplete?.let { waiter ->
            if (!waiter.complete(frame) && !frame.bitmap.isRecycled) {
                frame.bitmap.recycle()
            }
        }
    }

    /**
     * Returns an already-pending fresh frame immediately, or suspends until the next frame
     * arrives from [ImageReader] (up to [timeoutMs]). Ownership of the returned [Bitmap] transfers
     * to the caller.
     */
    suspend fun awaitNextFrame(timeoutMs: Long = 28L): DetectionFrame? {
        val deferred = synchronized(frameLock) {
            val existing = pendingFrame
            if (existing != null) {
                pendingFrame = null
                return existing
            }
            if (timeoutMs <= 0L) {
                return null
            }
            val newWaiter = CompletableDeferred<DetectionFrame>()
            nextFrameWaiter?.cancel()
            nextFrameWaiter = newWaiter
            newWaiter
        }

        val awaited = withTimeoutOrNull(timeoutMs) {
            deferred.await()
        }
        if (awaited == null) {
            synchronized(frameLock) {
                if (nextFrameWaiter === deferred) {
                    nextFrameWaiter = null
                }
            }
            // Handle rare race where submitBitmap completed deferred right as timeout elapsed
            if (deferred.isCompleted && !deferred.isCancelled) {
                val lateFrame = runCatching { deferred.await() }.getOrNull()
                if (lateFrame != null && !lateFrame.bitmap.isRecycled) {
                    lateFrame.bitmap.recycle()
                }
            }
        }
        return awaited
    }

    /**
     * Runs a continuous real-time detection loop, converting and feeding each fresh [DetectionFrame]
     * to [onFrame] and automatically recycling the bitmap after detection completes.
     */
    suspend fun runLoop(
        frameWaitTimeoutMs: Long = 100L,
        onFrame: suspend (DetectionFrame) -> Unit
    ) {
        _stats.value = _stats.value.copy(isRunning = true)
        try {
            while (currentCoroutineContext().isActive) {
                val frame = awaitNextFrame(timeoutMs = frameWaitTimeoutMs) ?: continue
                try {
                    onFrame(frame)
                    _stats.value = _stats.value.copy(
                        processedFrames = _stats.value.processedFrames + 1L
                    )
                } finally {
                    if (!frame.bitmap.isRecycled) {
                        frame.bitmap.recycle()
                    }
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } finally {
            _stats.value = _stats.value.copy(isRunning = false)
        }
    }

    fun start(
        scope: CoroutineScope,
        frameWaitTimeoutMs: Long = 100L,
        onFrame: suspend (DetectionFrame) -> Unit
    ): Job {
        activeLoopJob?.cancel()
        val job = scope.launch {
            runLoop(frameWaitTimeoutMs = frameWaitTimeoutMs, onFrame = onFrame)
        }
        activeLoopJob = job
        return job
    }

    fun stop() {
        activeLoopJob?.cancel()
        activeLoopJob = null
        clearPending()
        _stats.value = _stats.value.copy(isRunning = false)
    }

    fun clearPending() {
        var toRecycle: Bitmap? = null
        var waiterToCancel: CompletableDeferred<DetectionFrame>? = null
        synchronized(frameLock) {
            toRecycle = pendingFrame?.bitmap
            pendingFrame = null
            waiterToCancel = nextFrameWaiter
            nextFrameWaiter = null
        }
        waiterToCancel?.cancel()
        toRecycle?.let { bmp ->
            if (!bmp.isRecycled) {
                bmp.recycle()
            }
        }
    }
}
