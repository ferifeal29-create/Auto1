package com.example.automation

import com.example.model.ActivityLogEntry
import com.example.model.BotDashboardState
import com.example.model.BotStatus
import com.example.model.LogLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

object AutoFishStateStore {

    private val logIdGen = AtomicLong(0L)
    private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val _state = MutableStateFlow(BotDashboardState())
    val state: StateFlow<BotDashboardState> = _state.asStateFlow()

    fun setAccessibilityConnected(connected: Boolean) {
        _state.update { it.copy(isAccessibilityEnabled = connected) }
    }

    fun setMediaProjectionActive(active: Boolean) {
        _state.update { it.copy(isMediaProjectionActive = active) }
    }

    fun startSession() {
        _state.update {
            it.copy(
                isRunning = true,
                status = BotStatus.STARTING,
                currentActivity = "Menyiapkan Screen Capture & Engine...",
                fishCaughtCount = 0,
                fishSoldCount = 0,
                runningDurationMs = 0L,
                lastFishX = null,
                lastBarX = null,
                isMinigameDetected = false,
                isDialogDetected = false,
                lastError = null
            )
        }
        appendLog("Sesi AutoFish dimulai", LogLevel.SUCCESS)
    }

    fun updateDuration(durationMs: Long) {
        _state.update { it.copy(runningDurationMs = durationMs) }
    }

    fun updateStatus(status: BotStatus, activity: String) {
        _state.update {
            it.copy(
                status = status,
                currentActivity = activity,
                lastError = null
            )
        }
    }

    fun updateVisionTelemetry(
        fishX: Float?,
        barX: Float?,
        minigameDetected: Boolean,
        dialogDetected: Boolean
    ) {
        _state.update {
            it.copy(
                lastFishX = fishX,
                lastBarX = barX,
                isMinigameDetected = minigameDetected,
                isDialogDetected = dialogDetected
            )
        }
    }

    fun incrementCaught() {
        _state.update { it.copy(fishCaughtCount = it.fishCaughtCount + 1) }
    }

    fun incrementSold() {
        _state.update { it.copy(fishSoldCount = it.fishSoldCount + 1) }
    }

    fun setError(message: String) {
        _state.update {
            it.copy(
                isRunning = false,
                status = BotStatus.ERROR,
                currentActivity = message,
                lastError = message,
                isMediaProjectionActive = false
            )
        }
        appendLog(message, LogLevel.ERROR)
    }

    fun stopSession(reason: String = "Dihentikan oleh pengguna") {
        _state.update {
            it.copy(
                isRunning = false,
                status = BotStatus.IDLE,
                currentActivity = reason,
                isMediaProjectionActive = false,
                isMinigameDetected = false,
                isDialogDetected = false
            )
        }
        appendLog(reason, LogLevel.INFO)
    }

    fun appendLog(message: String, level: LogLevel = LogLevel.INFO) {
        val time = synchronized(timeFormatter) { timeFormatter.format(Date()) }
        val entry = ActivityLogEntry(
            id = logIdGen.incrementAndGet(),
            timeText = time,
            message = message,
            level = level
        )
        _state.update { current ->
            val updated = (listOf(entry) + current.logs).take(40)
            current.copy(logs = updated)
        }
    }
}
