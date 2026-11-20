package com.example.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.graphics.Path
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.example.model.RelativePoint
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Accessibility Service responsible for dispatching calibrated touch gestures
 * (Cast, continuous zero-gap Minigame holds, Hook, Sell/Keep) into Gridmere.
 */
class AutoFishAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _isConnected.value = true
        AutoFishStateStore.setAccessibilityConnected(true)
        AutoFishStateStore.appendLog("Accessibility Service terhubung")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Gesture-only automation; visual analysis uses MediaProjection frames.
    }

    override fun onInterrupt() {
        // No-op
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        _isConnected.value = false
        AutoFishStateStore.setAccessibilityConnected(false)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        _isConnected.value = false
        AutoFishStateStore.setAccessibilityConnected(false)
        super.onDestroy()
    }

    suspend fun tapRelative(point: RelativePoint, durationMs: Long = 55L): Boolean {
        val (px, py) = toScreenPixels(point) ?: return false
        val safeDuration = durationMs.coerceIn(25L, 800L)

        val path = Path().apply { moveTo(px, py) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, safeDuration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        return suspendCancellableCoroutine { continuation ->
            val dispatched = dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (continuation.isActive) continuation.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (continuation.isActive) continuation.resume(false)
                    }
                },
                null
            )
            if (!dispatched && continuation.isActive) {
                continuation.resume(false)
            }
        }
    }

    /**
     * Begins or continues a zero-gap continuous touch hold at [point] for [segmentMs]
     * using StrokeDescription.continueStroke on API 26+.
     * Returns an active [HoldHandle] whose [completion] completes when [segmentMs] finishes.
     */
    fun dispatchContinuousHold(
        previousHandle: HoldHandle?,
        point: RelativePoint,
        segmentMs: Long = 48L
    ): HoldHandle? {
        val (px, py) = toScreenPixels(point) ?: return null
        val safeDuration = segmentMs.coerceIn(25L, 200L)
        val path = Path().apply { moveTo(px, py) }

        val stroke = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (previousHandle != null) {
                previousHandle.stroke.continueStroke(path, 0L, safeDuration, true)
            } else {
                GestureDescription.StrokeDescription(path, 0L, safeDuration, true)
            }
        } else {
            GestureDescription.StrokeDescription(path, 0L, safeDuration)
        }

        val deferred = CompletableDeferred<Boolean>()
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val dispatched = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    deferred.complete(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    deferred.complete(false)
                }
            },
            null
        )

        if (!dispatched) {
            deferred.complete(false)
            return null
        }

        return HoldHandle(
            stroke = stroke,
            lastPoint = point,
            completion = deferred,
            canContinue = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
        )
    }

    /**
     * Smoothly lifts the finger from an ongoing [HoldHandle].
     */
    suspend fun releaseHold(handle: HoldHandle?): Boolean {
        if (handle == null) return true
        // Wait for the currently running segment to finish before chaining the release stroke
        val segmentOk = handle.completion.await()
        if (!handle.canContinue || !segmentOk) {
            return true
        }
        val (px, py) = toScreenPixels(handle.lastPoint) ?: return false
        val path = Path().apply { moveTo(px, py) }
        val endStroke = handle.stroke.continueStroke(path, 0L, 12L, false)
        val gesture = GestureDescription.Builder().addStroke(endStroke).build()

        return suspendCancellableCoroutine { continuation ->
            val dispatched = dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (continuation.isActive) continuation.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (continuation.isActive) continuation.resume(false)
                    }
                },
                null
            )
            if (!dispatched && continuation.isActive) {
                continuation.resume(false)
            }
        }
    }

    private fun toScreenPixels(point: RelativePoint): Pair<Float, Float>? {
        val clamped = point.clamped()
        val (screenW, screenH) = currentScreenSize()
        if (screenW <= 0 || screenH <= 0) return null
        val px = (clamped.x * screenW).coerceIn(1f, (screenW - 1).toFloat())
        val py = (clamped.y * screenH).coerceIn(1f, (screenH - 1).toFloat())
        return px to py
    }

    @Suppress("DEPRECATION")
    private fun currentScreenSize(): Pair<Int, Int> {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            val metrics = DisplayMetrics()
            wm.defaultDisplay.getRealMetrics(metrics)
            metrics.widthPixels to metrics.heightPixels
        }
    }

    data class HoldHandle(
        val stroke: GestureDescription.StrokeDescription,
        val lastPoint: RelativePoint,
        val completion: CompletableDeferred<Boolean>,
        val canContinue: Boolean
    )

    companion object {
        @Volatile
        var instance: AutoFishAccessibilityService? = null
            private set

        private val _isConnected = MutableStateFlow(false)
        val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

        fun isServiceEnabled(context: Context): Boolean {
            if (instance != null) return true
            val expected = ComponentName(context, AutoFishAccessibilityService::class.java)
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false

            val splitter = TextUtils.SimpleStringSplitter(':')
            splitter.setString(enabledServices)
            while (splitter.hasNext()) {
                val component = ComponentName.unflattenFromString(splitter.next())
                if (component == expected) {
                    return true
                }
            }
            return false
        }
    }
}
