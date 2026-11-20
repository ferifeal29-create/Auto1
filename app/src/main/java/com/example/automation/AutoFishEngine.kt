package com.example.automation

import android.graphics.Bitmap
import com.example.capture.ScreenCaptureManager
import com.example.data.CalibrationRepository
import com.example.model.BotStatus
import com.example.model.CalibrationConfig
import com.example.model.LogLevel
import com.example.model.MinigameControlMode
import com.example.model.PostCatchAction
import com.example.vision.BiteDetector
import com.example.vision.DialogDetector
import com.example.vision.MinigameAnalyzer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Finite-state automation engine executing the 3-phase Gridmere fishing loop:
 * Fase 1: Select Worm Bait Slot -> Tap Water Block to Cast
 * Fase 2: Detect Bite Cinematic Black Bars -> Tap Down Arrow (↓)
 * Fase 3: Zero-Gap Continuous Closed-Loop Minigame Tracking -> Pull (↓) when Cyan Bar Full -> Auto Sell/Keep -> Recast
 */
class AutoFishEngine(
    private val captureManager: ScreenCaptureManager,
    private val biteDetector: BiteDetector,
    private val minigameAnalyzer: MinigameAnalyzer,
    private val dialogDetector: DialogDetector,
    private val calibrationRepository: CalibrationRepository,
    private val gestureProvider: () -> AutoFishAccessibilityService?
) {

    suspend fun runLoop(configProvider: () -> CalibrationConfig) {
        for (remainingSec in 7 downTo 1) {
            AutoFishStateStore.updateStatus(
                BotStatus.STARTING,
                "Menunggu $remainingSec detik — silakan buka layar game Gridmere..."
            )
            delay(1000L)
        }

        while (currentCoroutineContext().isActive) {
            val config = configProvider()
            val accessibility = gestureProvider()
            if (accessibility == null) {
                AutoFishStateStore.setError("Accessibility Service terputus. Aktifkan kembali untuk melanjutkan.")
                break
            }

            // 1. Pre-check current game state (dialog / active minigame / active bite)
            val preCheck = captureLatestSafe()
            var preOutcome: PreCheckOutcome = PreCheckOutcome.READY_TO_CAST
            if (preCheck != null) {
                try {
                    if (dialogDetector.detect(preCheck, config).isFishCaughtDialogVisible) {
                        preOutcome = PreCheckOutcome.DIALOG_OPEN
                    } else if (minigameAnalyzer.analyze(preCheck, config).isMinigameActive) {
                        preOutcome = PreCheckOutcome.MINIGAME_ACTIVE
                    } else if (biteDetector.detect(preCheck).isBiteCinematicVisible) {
                        preOutcome = PreCheckOutcome.BITE_ACTIVE
                    }
                } finally {
                    preCheck.recycle()
                }
            }

            when (preOutcome) {
                PreCheckOutcome.DIALOG_OPEN -> {
                    handleFishCaughtDialog(accessibility, configProvider, countNewCatch = true)
                    continue
                }
                PreCheckOutcome.MINIGAME_ACTIVE -> {
                    val caught = playMinigame(accessibility, configProvider)
                    if (caught) {
                        handleFishCaughtDialog(accessibility, configProvider, countNewCatch = true)
                    }
                    continue
                }
                PreCheckOutcome.BITE_ACTIVE -> {
                    val hooked = hookAndAwaitMinigame(accessibility, configProvider)
                    if (hooked == WaitOutcome.MINIGAME_STARTED) {
                        val caught = playMinigame(accessibility, configProvider)
                        if (caught) {
                            handleFishCaughtDialog(accessibility, configProvider, countNewCatch = true)
                        }
                    } else if (hooked == WaitOutcome.DIALOG_ALREADY_OPEN) {
                        handleFishCaughtDialog(accessibility, configProvider, countNewCatch = true)
                    }
                    continue
                }
                PreCheckOutcome.READY_TO_CAST -> Unit
            }

            // 2. FASE 1: Select Worm Bait Slot (if enabled) -> Tap Water Block to Cast
            if (config.useBaitSelection) {
                AutoFishStateStore.updateStatus(
                    BotStatus.CASTING,
                    "Memilih Umpan Cacing (${formatCoords(config.baitPoint.x, config.baitPoint.y)})"
                )
                val baitTapped = accessibility.tapRelative(config.baitPoint, durationMs = 55L)
                if (!baitTapped) {
                    AutoFishStateStore.appendLog("Gagal mengetuk slot umpan cacing, mencoba lagi", LogLevel.WARNING)
                    delay(500L)
                    continue
                }
                delay(240L)
            }

            AutoFishStateStore.updateStatus(
                BotStatus.CASTING,
                "Melempar kail ke Blok Air (${formatCoords(config.castPoint.x, config.castPoint.y)})"
            )
            val castTapped = accessibility.tapRelative(config.castPoint, durationMs = 60L)
            if (!castTapped) {
                AutoFishStateStore.appendLog("Gagal mengirim gesture Cast ke blok air, mencoba lagi", LogLevel.WARNING)
                delay(600L)
                continue
            }
            delay(config.castDelayMs.coerceIn(400L, 4000L))

            // 3. FASE 2 & 3: Wait Bite (Black Bars) -> Tap Down Arrow (↓) -> Minigame -> Dialog
            val waitOutcome = waitForBiteAndHook(accessibility, configProvider)
            when (waitOutcome) {
                WaitOutcome.MINIGAME_STARTED -> {
                    AutoFishStateStore.appendLog("Minigame Catch The Fish dimulai!", LogLevel.INFO)
                    val caught = playMinigame(accessibility, configProvider)
                    if (caught) {
                        handleFishCaughtDialog(accessibility, configProvider, countNewCatch = true)
                    } else {
                        AutoFishStateStore.appendLog("Minigame selesai tanpa dialog, bersiap cast ulang", LogLevel.WARNING)
                    }
                }
                WaitOutcome.DIALOG_ALREADY_OPEN -> {
                    handleFishCaughtDialog(accessibility, configProvider, countNewCatch = true)
                }
                WaitOutcome.TIMED_OUT -> {
                    AutoFishStateStore.appendLog("Bite timeout tercapai, melakukan cast ulang", LogLevel.WARNING)
                }
            }

            // 4. RECAST DELAY
            val latestConfig = configProvider()
            AutoFishStateStore.updateStatus(
                BotStatus.RECASTING,
                "Menunggu ${latestConfig.recastDelayMs}ms sebelum Cast berikutnya..."
            )
            delay(latestConfig.recastDelayMs.coerceIn(300L, 4000L))
        }
    }

    private suspend fun waitForBiteAndHook(
        accessibility: AutoFishAccessibilityService,
        configProvider: () -> CalibrationConfig
    ): WaitOutcome {
        AutoFishStateStore.updateStatus(
            BotStatus.WAITING_BITE,
            "Menunggu umpan dimakan (memantau bar hitam miring)..."
        )
        val startMs = System.currentTimeMillis()
        var consecutiveBlackFrames = 0

        while (currentCoroutineContext().isActive) {
            val config = configProvider()
            val elapsed = System.currentTimeMillis() - startMs
            if (elapsed >= config.biteTimeoutMs) {
                return WaitOutcome.TIMED_OUT
            }

            val bitmap = captureFreshSafe(waitIfEmptyMs = 16L)
            if (bitmap != null) {
                var dialogOpen = false
                var minigameActive = false
                var biteBarsVisible = false
                var fishX: Float? = null
                var barX: Float? = null

                try {
                    dialogOpen = dialogDetector.detect(bitmap, config).isFishCaughtDialogVisible
                    if (!dialogOpen) {
                        val minigame = minigameAnalyzer.analyze(bitmap, config)
                        minigameActive = minigame.isMinigameActive
                        fishX = minigame.fishX
                        barX = minigame.barX
                        if (!minigameActive) {
                            biteBarsVisible = biteDetector.detect(bitmap).isBiteCinematicVisible
                        }
                    }
                } finally {
                    bitmap.recycle()
                }

                if (dialogOpen) {
                    AutoFishStateStore.updateVisionTelemetry(null, null, false, true)
                    return WaitOutcome.DIALOG_ALREADY_OPEN
                }

                if (minigameActive) {
                    AutoFishStateStore.updateVisionTelemetry(fishX, barX, true, false)
                    return WaitOutcome.MINIGAME_STARTED
                }

                if (biteBarsVisible) {
                    consecutiveBlackFrames++
                    if (consecutiveBlackFrames >= 1) {
                        return hookAndAwaitMinigame(accessibility, configProvider)
                    }
                } else {
                    consecutiveBlackFrames = 0
                }
            }
            delay(45L)
        }
        throw CancellationException("Stopped while waiting for bite")
    }

    private suspend fun hookAndAwaitMinigame(
        accessibility: AutoFishAccessibilityService,
        configProvider: () -> CalibrationConfig
    ): WaitOutcome {
        val config = configProvider()
        AutoFishStateStore.updateStatus(
            BotStatus.HOOKING,
            "Umpan dimakan! Menekan panah bawah ↓ (${formatCoords(config.hookPoint.x, config.hookPoint.y)})"
        )
        AutoFishStateStore.appendLog("Bar hitam terdeteksi (ikan menyambar)! Menekan panah bawah ↓", LogLevel.INFO)
        accessibility.tapRelative(config.hookPoint, durationMs = 65L)

        var retriedHook = false
        for (probe in 0 until 28) {
            delay(80L)
            val latestConfig = configProvider()
            val bmp = captureFreshSafe(waitIfEmptyMs = 16L) ?: continue

            var dialogOpen = false
            var minigameActive = false
            var stillBlackBars = false
            var fishX: Float? = null
            var barX: Float? = null

            try {
                dialogOpen = dialogDetector.detect(bmp, latestConfig).isFishCaughtDialogVisible
                if (!dialogOpen) {
                    val analysis = minigameAnalyzer.analyze(bmp, latestConfig)
                    minigameActive = analysis.isMinigameActive
                    fishX = analysis.fishX
                    barX = analysis.barX
                    if (!minigameActive) {
                        stillBlackBars = biteDetector.detect(bmp).isBiteCinematicVisible
                    }
                }
            } finally {
                bmp.recycle()
            }

            if (dialogOpen) {
                AutoFishStateStore.updateVisionTelemetry(null, null, false, true)
                return WaitOutcome.DIALOG_ALREADY_OPEN
            }
            if (minigameActive) {
                AutoFishStateStore.updateVisionTelemetry(fishX, barX, true, false)
                return WaitOutcome.MINIGAME_STARTED
            }
            if (stillBlackBars && !retriedHook && probe >= 5) {
                retriedHook = true
                AutoFishStateStore.appendLog("Bar hitam masih aktif, tekan ulang panah bawah ↓", LogLevel.WARNING)
                accessibility.tapRelative(latestConfig.hookPoint, durationMs = 70L)
            }
        }
        return WaitOutcome.TIMED_OUT
    }

    /**
     * Zero-gap closed-loop minigame controller:
     * - Captures ONLY fresh display frames (never acts on stale cached frames)
     * - Captures & analyzes in parallel DURING each 46ms continuous touch segment
     * - Chains continuous holds via continueStroke (zero lift/drop gaps while chasing the fish)
     * - Immediately releases/reverses when fish flips direction or centers in box
     */
    private suspend fun playMinigame(
        accessibility: AutoFishAccessibilityService,
        configProvider: () -> CalibrationConfig
    ): Boolean {
        AutoFishStateStore.updateStatus(
            BotStatus.MINIGAME,
            "Menyelaraskan kotak hijau/kuning dengan pergerakan ikan"
        )
        var missingMinigameFrames = 0
        var lastKnownFishX = 0.5f

        var prevFishX: Float? = null
        var prevFrameTimeMs = 0L
        var smoothedFishVel = 0f
        var lastCatchPullAtMs = 0L

        var activeHold: AutoFishAccessibilityService.HoldHandle? = null
        var activeHoldDir = 0 // -1 = left, 1 = right, 0 = none

        try {
            while (currentCoroutineContext().isActive) {
                val config = configProvider()

                // If currently holding a button, wait 24ms into the 46ms stroke before grabbing the fresh frame
                if (activeHold != null) {
                    delay(24L)
                }

                val bitmap = captureFreshSafe(waitIfEmptyMs = 16L)
                if (bitmap == null) {
                    if (activeHold != null) {
                        val ok = activeHold.completion.await()
                        activeHold = if (ok && activeHoldDir != 0) {
                            val pt = pointForDir(config, activeHoldDir)
                            accessibility.dispatchContinuousHold(activeHold, pt, segmentMs = 46L)
                        } else {
                            activeHoldDir = 0
                            null
                        }
                    } else {
                        delay(16L)
                    }
                    continue
                }

                var dialogVisible = false
                var minigameActive = false
                var fishX: Float? = null
                var barX: Float? = null
                var barWidth = 0.14f
                var catchProgress = 0f
                var isCatchReady = false

                try {
                    val dialog = dialogDetector.detect(bitmap, config)
                    dialogVisible = dialog.isFishCaughtDialogVisible
                    if (!dialogVisible) {
                        val analysis = minigameAnalyzer.analyze(bitmap, config)
                        minigameActive = analysis.isMinigameActive
                        fishX = analysis.fishX
                        barX = analysis.barX
                        barWidth = analysis.barWidth
                        catchProgress = analysis.catchProgress
                        isCatchReady = analysis.isCatchReady
                    }
                } finally {
                    bitmap.recycle()
                }

                if (dialogVisible) {
                    accessibility.releaseHold(activeHold)
                    activeHold = null
                    activeHoldDir = 0
                    AutoFishStateStore.updateVisionTelemetry(null, null, false, true)
                    return true
                }

                if (minigameActive && barX != null) {
                    missingMinigameFrames = 0
                    val nowMs = System.currentTimeMillis()
                    val trackedFishX = fishX ?: lastKnownFishX
                    lastKnownFishX = trackedFishX

                    // Press ↓ as soon as the thin cyan progress strip reaches 100%
                    if (isCatchReady && (nowMs - lastCatchPullAtMs) >= 420L) {
                        lastCatchPullAtMs = nowMs
                        accessibility.releaseHold(activeHold)
                        activeHold = null
                        activeHoldDir = 0
                        AutoFishStateStore.updateStatus(
                            BotStatus.HOOKING,
                            "Bar biru penuh (${(catchProgress * 100).toInt()}%)! Menekan panah bawah ↓"
                        )
                        AutoFishStateStore.appendLog(
                            "Bar progres penuh (${(catchProgress * 100).toInt()}%)! Menekan panah bawah ↓ untuk menangkap ikan",
                            LogLevel.SUCCESS
                        )
                        accessibility.tapRelative(config.hookPoint, durationMs = 70L)
                        delay(160L)
                        continue
                    }

                    if (prevFrameTimeMs > 0L && prevFishX != null && fishX != null) {
                        val dtSec = ((nowMs - prevFrameTimeMs) / 1000f).coerceIn(0.02f, 0.30f)
                        val instantVel = ((fishX - prevFishX) / dtSec).coerceIn(-2.8f, 2.8f)
                        smoothedFishVel = if (smoothedFishVel * instantVel < -0.02f) {
                            instantVel * 0.72f
                        } else {
                            smoothedFishVel * 0.40f + instantVel * 0.60f
                        }
                    }
                    prevFishX = trackedFishX
                    prevFrameTimeMs = nowMs

                    AutoFishStateStore.updateVisionTelemetry(
                        fishX = trackedFishX,
                        barX = barX,
                        minigameDetected = true,
                        dialogDetected = false
                    )

                    val leadOffset = (smoothedFishVel * 0.045f).coerceIn(-0.06f, 0.06f)
                    val targetFishX = (trackedFishX + leadOffset).coerceIn(0.02f, 0.98f)
                    val delta = targetFishX - barX

                    val enterDeadzone = (barWidth * 0.15f).coerceIn(0.018f, 0.034f)
                    val stopDeadzone = enterDeadzone * 0.60f

                    val desiredDir = when (config.minigameControlMode) {
                        MinigameControlMode.DUAL_DIRECTION -> {
                            val threshold = if (activeHoldDir != 0) stopDeadzone else enterDeadzone
                            when {
                                delta < -threshold -> -1
                                delta > threshold -> 1
                                else -> 0
                            }
                        }
                        MinigameControlMode.SINGLE_TAP_HOLD -> {
                            val threshold = if (activeHoldDir != 0) stopDeadzone else enterDeadzone
                            val shouldPress = if (config.invertSingleControl) {
                                delta < -threshold
                            } else {
                                delta > threshold
                            }
                            if (shouldPress) 1 else 0
                        }
                    }

                    // Await the remainder of the current 46ms hold segment before chaining/releasing
                    if (activeHold != null) {
                        val segmentOk = activeHold.completion.await()
                        if (!segmentOk) {
                            activeHold = null
                            activeHoldDir = 0
                        }
                    }

                    when {
                        desiredDir == 0 -> {
                            if (activeHold != null) {
                                accessibility.releaseHold(activeHold)
                                activeHold = null
                                activeHoldDir = 0
                            }
                            AutoFishStateStore.updateStatus(
                                BotStatus.MINIGAME,
                                "Tepat di ikan! Ikan=${"%.2f".format(trackedFishX)} | Kotak=${"%.2f".format(barX)}"
                            )
                            delay(18L)
                        }

                        desiredDir == activeHoldDir && activeHold != null -> {
                            // Zero-gap continuation of the exact same direction!
                            val targetPoint = pointForDir(config, desiredDir)
                            activeHold = accessibility.dispatchContinuousHold(activeHold, targetPoint, segmentMs = 46L)
                            if (activeHold == null) {
                                activeHoldDir = 0
                            }
                        }

                        else -> {
                            // Start holding new direction (release opposite hold first if active)
                            if (activeHold != null) {
                                accessibility.releaseHold(activeHold)
                                activeHold = null
                                activeHoldDir = 0
                            }
                            val targetPoint = pointForDir(config, desiredDir)
                            val dirLabel = if (config.minigameControlMode == MinigameControlMode.SINGLE_TAP_HOLD) {
                                "TAHAN"
                            } else if (desiredDir < 0) {
                                "KIRI"
                            } else {
                                "KANAN"
                            }
                            AutoFishStateStore.updateStatus(
                                BotStatus.MINIGAME,
                                "Ikan=${"%.2f".format(trackedFishX)} | Kotak=${"%.2f".format(barX)} -> $dirLabel"
                            )
                            activeHold = accessibility.dispatchContinuousHold(null, targetPoint, segmentMs = 46L)
                            activeHoldDir = if (activeHold != null) desiredDir else 0
                        }
                    }
                } else {
                    missingMinigameFrames++
                    if (missingMinigameFrames >= 2 && activeHold != null) {
                        accessibility.releaseHold(activeHold)
                        activeHold = null
                        activeHoldDir = 0
                    }
                    if (missingMinigameFrames >= 7) {
                        AutoFishStateStore.updateStatus(
                            BotStatus.DIALOG_DETECTED,
                            "Minigame selesai, menunggu dialog Fish Caught!..."
                        )
                        for (probe in 0 until 20) {
                            delay(180L)
                            val latestConfig = configProvider()
                            val probeBmp = captureLatestSafe() ?: continue
                            val foundDialog = try {
                                dialogDetector.detect(probeBmp, latestConfig).isFishCaughtDialogVisible
                            } finally {
                                probeBmp.recycle()
                            }
                            if (foundDialog) {
                                return true
                            }
                        }
                        return false
                    }
                    delay(35L)
                }
            }
            throw CancellationException("Stopped during minigame")
        } finally {
            withContext(NonCancellable) {
                accessibility.releaseHold(activeHold)
            }
        }
    }

    private fun pointForDir(config: CalibrationConfig, dir: Int) = when {
        config.minigameControlMode == MinigameControlMode.SINGLE_TAP_HOLD -> config.minigamePrimaryPoint
        dir < 0 -> config.minigameLeftPoint
        else -> config.minigameRightPoint
    }

    private suspend fun handleFishCaughtDialog(
        accessibility: AutoFishAccessibilityService,
        configProvider: () -> CalibrationConfig,
        countNewCatch: Boolean
    ) {
        val config = configProvider()
        AutoFishStateStore.updateStatus(
            BotStatus.DIALOG_DETECTED,
            "Dialog \"Fish Caught!\" terdeteksi!"
        )
        if (countNewCatch) {
            AutoFishStateStore.incrementCaught()
            calibrationRepository.incrementLifetimeStats(caughtDelta = 1, soldDelta = 0)
            AutoFishStateStore.appendLog("Ikan tertangkap! Dialog Fish Caught terdeteksi", LogLevel.SUCCESS)
        }

        delay(260L)

        val isSell = config.postCatchAction == PostCatchAction.SELL
        val actionPoint = if (isSell) config.sellPoint else config.keepPoint
        val actionLabel = if (isSell) "Sell" else "Keep"

        AutoFishStateStore.updateStatus(
            BotStatus.SELLING,
            "Menekan tombol $actionLabel (${formatCoords(actionPoint.x, actionPoint.y)})"
        )
        accessibility.tapRelative(actionPoint, durationMs = 65L)

        AutoFishStateStore.updateStatus(
            BotStatus.VERIFYING_CLOSED,
            "Memastikan dialog Fish Caught sudah tertutup..."
        )

        var closed = false
        for (attempt in 1..6) {
            delay(260L)
            val bmp = captureLatestSafe() ?: continue
            val isClosedNow = try {
                dialogDetector.isDialogClosed(bmp, config)
            } finally {
                bmp.recycle()
            }

            if (isClosedNow) {
                closed = true
                break
            } else if (attempt == 2 || attempt == 4) {
                AutoFishStateStore.appendLog("Dialog masih terlihat, tekan $actionLabel ulang...", LogLevel.WARNING)
                accessibility.tapRelative(actionPoint, durationMs = 70L)
            }
        }

        if (closed) {
            if (isSell) {
                AutoFishStateStore.incrementSold()
                calibrationRepository.incrementLifetimeStats(caughtDelta = 0, soldDelta = 1)
                AutoFishStateStore.appendLog("Ikan berhasil dijual & dialog tertutup", LogLevel.SUCCESS)
            } else {
                AutoFishStateStore.appendLog("Ikan disimpan (Keep) & dialog tertutup", LogLevel.SUCCESS)
            }
        } else {
            AutoFishStateStore.appendLog("Peringatan: gagal memverifikasi penutupan dialog", LogLevel.WARNING)
        }

        AutoFishStateStore.updateVisionTelemetry(null, null, false, false)
    }

    private suspend fun captureFreshSafe(waitIfEmptyMs: Long = 16L): Bitmap? {
        return try {
            captureManager.captureFreshBitmap(waitIfEmptyMs)
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun captureLatestSafe(): Bitmap? {
        return try {
            captureManager.captureLatestBitmap()
        } catch (_: Exception) {
            null
        }
    }

    private fun formatCoords(x: Float, y: Float): String =
        "${(x * 100).toInt()}%, ${(y * 100).toInt()}%"

    private enum class PreCheckOutcome {
        READY_TO_CAST,
        BITE_ACTIVE,
        MINIGAME_ACTIVE,
        DIALOG_OPEN
    }

    private enum class WaitOutcome {
        MINIGAME_STARTED,
        DIALOG_ALREADY_OPEN,
        TIMED_OUT
    }
}
