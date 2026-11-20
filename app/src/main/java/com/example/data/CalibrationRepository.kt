package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.model.CalibrationConfig
import com.example.model.MinigameControlMode
import com.example.model.PostCatchAction
import com.example.model.RelativePoint
import com.example.model.RelativeRect
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.calibrationDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "autofish_calibration"
)

class CalibrationRepository(private val context: Context) {

    private object Keys {
        val SCHEMA_VERSION = intPreferencesKey("schema_version")
        val BAIT_X = floatPreferencesKey("bait_x")
        val BAIT_Y = floatPreferencesKey("bait_y")
        val CAST_X = floatPreferencesKey("cast_x")
        val CAST_Y = floatPreferencesKey("cast_y")
        val HOOK_X = floatPreferencesKey("hook_x")
        val HOOK_Y = floatPreferencesKey("hook_y")
        val SELL_X = floatPreferencesKey("sell_x")
        val SELL_Y = floatPreferencesKey("sell_y")
        val KEEP_X = floatPreferencesKey("keep_x")
        val KEEP_Y = floatPreferencesKey("keep_y")
        val PRIMARY_X = floatPreferencesKey("primary_x")
        val PRIMARY_Y = floatPreferencesKey("primary_y")
        val LEFT_X = floatPreferencesKey("left_x")
        val LEFT_Y = floatPreferencesKey("left_y")
        val RIGHT_X = floatPreferencesKey("right_x")
        val RIGHT_Y = floatPreferencesKey("right_y")
        val BAR_LEFT = floatPreferencesKey("bar_left")
        val BAR_TOP = floatPreferencesKey("bar_top")
        val BAR_RIGHT = floatPreferencesKey("bar_right")
        val BAR_BOTTOM = floatPreferencesKey("bar_bottom")
        val DIALOG_LEFT = floatPreferencesKey("dialog_left")
        val DIALOG_TOP = floatPreferencesKey("dialog_top")
        val DIALOG_RIGHT = floatPreferencesKey("dialog_right")
        val DIALOG_BOTTOM = floatPreferencesKey("dialog_bottom")
        val CONTROL_MODE = stringPreferencesKey("control_mode")
        val POST_CATCH_ACTION = stringPreferencesKey("post_catch_action")
        val USE_BAIT_SELECTION = booleanPreferencesKey("use_bait_selection")
        val AUTO_DETECT_WATER = booleanPreferencesKey("auto_detect_water")
        val INVERT_SINGLE = booleanPreferencesKey("invert_single")
        val CAST_DELAY_MS = longPreferencesKey("cast_delay_ms")
        val RECAST_DELAY_MS = longPreferencesKey("recast_delay_ms")
        val BITE_TIMEOUT_MS = longPreferencesKey("bite_timeout_ms")
        val TOTAL_CAUGHT = intPreferencesKey("total_caught")
        val TOTAL_SOLD = intPreferencesKey("total_sold")
    }

    val calibrationConfig: Flow<CalibrationConfig> = context.calibrationDataStore.data.map { prefs ->
        val default = CalibrationConfig()
        val storedVersion = prefs[Keys.SCHEMA_VERSION] ?: 0
        if (storedVersion < CalibrationConfig.CURRENT_SCHEMA_VERSION) {
            return@map default.copy(
                postCatchAction = prefs[Keys.POST_CATCH_ACTION]?.let { raw ->
                    runCatching { PostCatchAction.valueOf(raw) }.getOrNull()
                } ?: default.postCatchAction
            )
        }

        val mode = prefs[Keys.CONTROL_MODE]?.let { raw ->
            runCatching { MinigameControlMode.valueOf(raw) }.getOrNull()
        } ?: default.minigameControlMode
        val postAction = prefs[Keys.POST_CATCH_ACTION]?.let { raw ->
            runCatching { PostCatchAction.valueOf(raw) }.getOrNull()
        } ?: default.postCatchAction

        CalibrationConfig(
            baitPoint = RelativePoint(
                x = prefs[Keys.BAIT_X] ?: default.baitPoint.x,
                y = prefs[Keys.BAIT_Y] ?: default.baitPoint.y
            ).clamped(),
            castPoint = RelativePoint(
                x = prefs[Keys.CAST_X] ?: default.castPoint.x,
                y = prefs[Keys.CAST_Y] ?: default.castPoint.y
            ).clamped(),
            hookPoint = RelativePoint(
                x = prefs[Keys.HOOK_X] ?: default.hookPoint.x,
                y = prefs[Keys.HOOK_Y] ?: default.hookPoint.y
            ).clamped(),
            sellPoint = RelativePoint(
                x = prefs[Keys.SELL_X] ?: default.sellPoint.x,
                y = prefs[Keys.SELL_Y] ?: default.sellPoint.y
            ).clamped(),
            keepPoint = RelativePoint(
                x = prefs[Keys.KEEP_X] ?: default.keepPoint.x,
                y = prefs[Keys.KEEP_Y] ?: default.keepPoint.y
            ).clamped(),
            minigamePrimaryPoint = RelativePoint(
                x = prefs[Keys.PRIMARY_X] ?: default.minigamePrimaryPoint.x,
                y = prefs[Keys.PRIMARY_Y] ?: default.minigamePrimaryPoint.y
            ).clamped(),
            minigameLeftPoint = RelativePoint(
                x = prefs[Keys.LEFT_X] ?: default.minigameLeftPoint.x,
                y = prefs[Keys.LEFT_Y] ?: default.minigameLeftPoint.y
            ).clamped(),
            minigameRightPoint = RelativePoint(
                x = prefs[Keys.RIGHT_X] ?: default.minigameRightPoint.x,
                y = prefs[Keys.RIGHT_Y] ?: default.minigameRightPoint.y
            ).clamped(),
            minigameBarRegion = RelativeRect(
                left = prefs[Keys.BAR_LEFT] ?: default.minigameBarRegion.left,
                top = prefs[Keys.BAR_TOP] ?: default.minigameBarRegion.top,
                right = prefs[Keys.BAR_RIGHT] ?: default.minigameBarRegion.right,
                bottom = prefs[Keys.BAR_BOTTOM] ?: default.minigameBarRegion.bottom
            ).clamped(),
            dialogRegion = RelativeRect(
                left = prefs[Keys.DIALOG_LEFT] ?: default.dialogRegion.left,
                top = prefs[Keys.DIALOG_TOP] ?: default.dialogRegion.top,
                right = prefs[Keys.DIALOG_RIGHT] ?: default.dialogRegion.right,
                bottom = prefs[Keys.DIALOG_BOTTOM] ?: default.dialogRegion.bottom
            ).clamped(),
            minigameControlMode = mode,
            postCatchAction = postAction,
            useBaitSelection = prefs[Keys.USE_BAIT_SELECTION] ?: default.useBaitSelection,
            autoDetectWaterBlock = prefs[Keys.AUTO_DETECT_WATER] ?: default.autoDetectWaterBlock,
            invertSingleControl = prefs[Keys.INVERT_SINGLE] ?: default.invertSingleControl,
            castDelayMs = prefs[Keys.CAST_DELAY_MS] ?: default.castDelayMs,
            recastDelayMs = prefs[Keys.RECAST_DELAY_MS] ?: default.recastDelayMs,
            biteTimeoutMs = prefs[Keys.BITE_TIMEOUT_MS] ?: default.biteTimeoutMs
        )
    }

    val lifetimeStats: Flow<Pair<Int, Int>> = context.calibrationDataStore.data.map { prefs ->
        (prefs[Keys.TOTAL_CAUGHT] ?: 0) to (prefs[Keys.TOTAL_SOLD] ?: 0)
    }

    suspend fun saveConfig(config: CalibrationConfig) {
        context.calibrationDataStore.edit { prefs ->
            prefs[Keys.SCHEMA_VERSION] = CalibrationConfig.CURRENT_SCHEMA_VERSION
            prefs[Keys.BAIT_X] = config.baitPoint.x
            prefs[Keys.BAIT_Y] = config.baitPoint.y
            prefs[Keys.CAST_X] = config.castPoint.x
            prefs[Keys.CAST_Y] = config.castPoint.y
            prefs[Keys.HOOK_X] = config.hookPoint.x
            prefs[Keys.HOOK_Y] = config.hookPoint.y
            prefs[Keys.SELL_X] = config.sellPoint.x
            prefs[Keys.SELL_Y] = config.sellPoint.y
            prefs[Keys.KEEP_X] = config.keepPoint.x
            prefs[Keys.KEEP_Y] = config.keepPoint.y
            prefs[Keys.PRIMARY_X] = config.minigamePrimaryPoint.x
            prefs[Keys.PRIMARY_Y] = config.minigamePrimaryPoint.y
            prefs[Keys.LEFT_X] = config.minigameLeftPoint.x
            prefs[Keys.LEFT_Y] = config.minigameLeftPoint.y
            prefs[Keys.RIGHT_X] = config.minigameRightPoint.x
            prefs[Keys.RIGHT_Y] = config.minigameRightPoint.y
            prefs[Keys.BAR_LEFT] = config.minigameBarRegion.left
            prefs[Keys.BAR_TOP] = config.minigameBarRegion.top
            prefs[Keys.BAR_RIGHT] = config.minigameBarRegion.right
            prefs[Keys.BAR_BOTTOM] = config.minigameBarRegion.bottom
            prefs[Keys.DIALOG_LEFT] = config.dialogRegion.left
            prefs[Keys.DIALOG_TOP] = config.dialogRegion.top
            prefs[Keys.DIALOG_RIGHT] = config.dialogRegion.right
            prefs[Keys.DIALOG_BOTTOM] = config.dialogRegion.bottom
            prefs[Keys.CONTROL_MODE] = config.minigameControlMode.name
            prefs[Keys.POST_CATCH_ACTION] = config.postCatchAction.name
            prefs[Keys.USE_BAIT_SELECTION] = config.useBaitSelection
            prefs[Keys.AUTO_DETECT_WATER] = config.autoDetectWaterBlock
            prefs[Keys.INVERT_SINGLE] = config.invertSingleControl
            prefs[Keys.CAST_DELAY_MS] = config.castDelayMs
            prefs[Keys.RECAST_DELAY_MS] = config.recastDelayMs
            prefs[Keys.BITE_TIMEOUT_MS] = config.biteTimeoutMs
        }
    }

    suspend fun resetToDefaults() {
        saveConfig(CalibrationConfig())
    }

    suspend fun incrementLifetimeStats(caughtDelta: Int, soldDelta: Int) {
        context.calibrationDataStore.edit { prefs ->
            prefs[Keys.TOTAL_CAUGHT] = (prefs[Keys.TOTAL_CAUGHT] ?: 0) + caughtDelta
            prefs[Keys.TOTAL_SOLD] = (prefs[Keys.TOTAL_SOLD] ?: 0) + soldDelta
        }
    }
}
