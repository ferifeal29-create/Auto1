package com.example.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.automation.AutoFishAccessibilityService
import com.example.automation.AutoFishForegroundService
import com.example.automation.AutoFishStateStore
import com.example.capture.MediaProjectionHolder
import com.example.data.CalibrationRepository
import com.example.model.BotDashboardState
import com.example.model.CalibrationConfig
import com.example.model.CalibrationTarget
import com.example.model.MinigameControlMode
import com.example.model.PostCatchAction
import com.example.model.RelativePoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class AutoFishTab(val label: String) {
    DASHBOARD("Dashboard"),
    CALIBRATION("Kalibrasi"),
    GUIDE("Panduan")
}

class AutoFishViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = CalibrationRepository(application.applicationContext)

    val dashboardState: StateFlow<BotDashboardState> = AutoFishStateStore.state

    val calibrationConfig: StateFlow<CalibrationConfig> = repository.calibrationConfig
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = CalibrationConfig()
        )

    val lifetimeStats: StateFlow<Pair<Int, Int>> = repository.lifetimeStats
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = 0 to 0
        )

    private val _selectedTab = MutableStateFlow(AutoFishTab.DASHBOARD)
    val selectedTab: StateFlow<AutoFishTab> = _selectedTab.asStateFlow()

    private val _selectedTarget = MutableStateFlow(CalibrationTarget.BAIT_SLOT)
    val selectedTarget: StateFlow<CalibrationTarget> = _selectedTarget.asStateFlow()

    init {
        refreshAccessibilityStatus()
    }

    fun selectTab(tab: AutoFishTab) {
        _selectedTab.value = tab
    }

    fun selectCalibrationTarget(target: CalibrationTarget) {
        _selectedTarget.value = target
    }

    fun refreshAccessibilityStatus() {
        val enabled = AutoFishAccessibilityService.isServiceEnabled(getApplication())
        AutoFishStateStore.setAccessibilityConnected(enabled)
    }

    fun onMediaProjectionGranted(resultCode: Int, data: Intent) {
        MediaProjectionHolder.setGrant(resultCode, data)
        AutoFishForegroundService.start(getApplication())
    }

    fun stopAutomation() {
        AutoFishForegroundService.stop(getApplication())
    }

    fun updatePoint(target: CalibrationTarget, point: RelativePoint) {
        val updated = calibrationConfig.value.withUpdatedPoint(target, point)
        saveConfig(updated)
    }

    fun updateMinigameMode(mode: MinigameControlMode) {
        saveConfig(calibrationConfig.value.copy(minigameControlMode = mode))
    }

    fun updatePostCatchAction(action: PostCatchAction) {
        saveConfig(calibrationConfig.value.copy(postCatchAction = action))
    }

    fun updateUseBaitSelection(enabled: Boolean) {
        saveConfig(calibrationConfig.value.copy(useBaitSelection = enabled))
    }

    fun updateInvertSingleControl(invert: Boolean) {
        saveConfig(calibrationConfig.value.copy(invertSingleControl = invert))
    }

    fun updateCastDelay(delayMs: Long) {
        saveConfig(calibrationConfig.value.copy(castDelayMs = delayMs.coerceIn(400L, 4000L)))
    }

    fun updateRecastDelay(delayMs: Long) {
        saveConfig(calibrationConfig.value.copy(recastDelayMs = delayMs.coerceIn(300L, 4000L)))
    }

    fun resetCalibration() {
        viewModelScope.launch {
            repository.resetToDefaults()
        }
    }

    private fun saveConfig(config: CalibrationConfig) {
        viewModelScope.launch {
            repository.saveConfig(config)
        }
    }
}
