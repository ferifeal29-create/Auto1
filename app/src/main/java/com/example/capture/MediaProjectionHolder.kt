package com.example.capture

import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ProjectionGrant(
    val resultCode: Int,
    val data: Intent
)

/**
 * Holds the MediaProjection permission grant between MainActivity and AutoFishForegroundService.
 * On Android 14+, each grant Intent is single-use when creating a MediaProjection instance.
 */
object MediaProjectionHolder {

    private var pendingGrant: ProjectionGrant? = null

    private val _hasGrant = MutableStateFlow(false)
    val hasGrant: StateFlow<Boolean> = _hasGrant.asStateFlow()

    @Synchronized
    fun setGrant(resultCode: Int, data: Intent) {
        pendingGrant = ProjectionGrant(resultCode, data)
        _hasGrant.value = true
    }

    @Synchronized
    fun consumeGrant(): ProjectionGrant? {
        val grant = pendingGrant
        pendingGrant = null
        _hasGrant.value = false
        return grant
    }

    @Synchronized
    fun peekHasGrant(): Boolean = pendingGrant != null

    @Synchronized
    fun clear() {
        pendingGrant = null
        _hasGrant.value = false
    }
}
