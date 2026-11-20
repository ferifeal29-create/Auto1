package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccessibilityNew
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Phishing
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.model.ActivityLogEntry
import com.example.model.BotDashboardState
import com.example.model.BotStatus
import com.example.model.CalibrationConfig
import com.example.model.LogLevel
import com.example.ui.theme.JetBrainsMonoFamily
import java.util.Locale

@Composable
fun DashboardScreen(
    state: BotDashboardState,
    config: CalibrationConfig,
    lifetimeStats: Pair<Int, Int>,
    onStartClick: () -> Unit,
    onStopClick: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenCalibration: () -> Unit,
    modifier: Modifier = Modifier
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.secondary
    val surfaceColor = MaterialTheme.colorScheme.surface

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 720.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                HeroStatusCard(
                    state = state,
                    primaryColor = primaryColor,
                    secondaryColor = secondaryColor,
                    surfaceColor = surfaceColor
                )
            }

            item {
                StartStopActionRow(
                    isRunning = state.isRunning,
                    onStartClick = onStartClick,
                    onStopClick = onStopClick
                )
            }

            item {
                PermissionReadinessRow(
                    isAccessibilityEnabled = state.isAccessibilityEnabled,
                    isMediaProjectionActive = state.isMediaProjectionActive,
                    onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                    onOpenCalibration = onOpenCalibration
                )
            }

            item {
                TelemetryGrid(
                    state = state,
                    config = config,
                    lifetimeStats = lifetimeStats
                )
            }

            item {
                AnimatedVisibility(visible = state.isMinigameDetected || state.isRunning) {
                    LiveVisionBarCard(state = state)
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.History,
                            contentDescription = "Aktivitas",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Log Aktivitas Otomatisasi",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                    Text(
                        text = "${state.logs.size} event",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (state.logs.isEmpty()) {
                item {
                    EmptyLogCard()
                }
            } else {
                items(
                    items = state.logs,
                    key = { it.id }
                ) { entry ->
                    ActivityLogRow(entry = entry)
                }
            }

            item {
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun HeroStatusCard(
    state: BotDashboardState,
    primaryColor: Color,
    secondaryColor: Color,
    surfaceColor: Color
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(900),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    val badgeColor by animateColorAsState(
        targetValue = when (state.status) {
            BotStatus.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
            BotStatus.ERROR -> MaterialTheme.colorScheme.error
            BotStatus.MINIGAME, BotStatus.DIALOG_DETECTED, BotStatus.SELLING -> secondaryColor
            else -> primaryColor
        },
        label = "statusColor"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(
            width = 1.dp,
            color = if (state.isRunning) primaryColor.copy(alpha = 0.45f)
            else MaterialTheme.colorScheme.outline
        ),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .drawBehind {
                    drawRect(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                surfaceColor,
                                if (state.isRunning) {
                                    primaryColor.copy(alpha = 0.14f)
                                } else {
                                    surfaceColor.copy(alpha = 0.92f)
                                }
                            )
                        )
                    )
                }
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = badgeColor.copy(alpha = 0.16f),
                    border = BorderStroke(1.dp, badgeColor.copy(alpha = 0.4f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(9.dp)
                                .clip(CircleShape)
                                .background(
                                    if (state.isRunning) badgeColor.copy(alpha = pulseAlpha)
                                    else badgeColor
                                )
                        )
                        Text(
                            text = if (state.isRunning) "AKTIF • ${state.status.label.uppercase()}" else state.status.label.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = badgeColor,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Text(
                    text = formatDuration(state.runningDurationMs),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontFamily = JetBrainsMonoFamily,
                        fontWeight = FontWeight.Bold
                    ),
                    color = if (state.isRunning) primaryColor else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text(
                text = "AutoFish Gridmere",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            Text(
                text = state.currentActivity,
                style = MaterialTheme.typography.bodyLarge,
                color = if (state.lastError != null) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun StartStopActionRow(
    isRunning: Boolean,
    onStartClick: () -> Unit,
    onStopClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Button(
            onClick = onStartClick,
            enabled = !isRunning,
            modifier = Modifier
                .weight(1f)
                .height(56.dp)
                .testTag("start_button"),
            shape = MaterialTheme.shapes.medium,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.secondary,
                contentColor = MaterialTheme.colorScheme.onSecondary
            )
        ) {
            Icon(
                imageVector = Icons.Outlined.PlayArrow,
                contentDescription = "Mulai AutoFish",
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "START",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }

        Button(
            onClick = onStopClick,
            enabled = isRunning,
            modifier = Modifier
                .weight(1f)
                .height(56.dp)
                .testTag("stop_button"),
            shape = MaterialTheme.shapes.medium,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError
            )
        ) {
            Icon(
                imageVector = Icons.Outlined.Stop,
                contentDescription = "Hentikan AutoFish",
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "STOP",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun PermissionReadinessRow(
    isAccessibilityEnabled: Boolean,
    isMediaProjectionActive: Boolean,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenCalibration: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        val accColor = if (isAccessibilityEnabled) {
            MaterialTheme.colorScheme.secondary
        } else {
            MaterialTheme.colorScheme.tertiary
        }

        Card(
            modifier = Modifier
                .weight(1f)
                .testTag("accessibility_setup_card")
                .clip(MaterialTheme.shapes.medium)
                .clickable(onClick = onOpenAccessibilitySettings),
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            border = BorderStroke(1.dp, accColor.copy(alpha = 0.45f))
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = if (isAccessibilityEnabled) {
                        Icons.Outlined.CheckCircle
                    } else {
                        Icons.Outlined.AccessibilityNew
                    },
                    contentDescription = "Status Accessibility Service",
                    tint = accColor,
                    modifier = Modifier.size(22.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Accessibility",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (isAccessibilityEnabled) "Aktif" else "Ketuk untuk aktifkan",
                        style = MaterialTheme.typography.bodyMedium,
                        color = accColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        val projColor = if (isMediaProjectionActive) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }

        Card(
            modifier = Modifier
                .weight(1f)
                .testTag("open_calibration_shortcut")
                .clip(MaterialTheme.shapes.medium)
                .clickable(onClick = onOpenCalibration),
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = if (isMediaProjectionActive) Icons.Outlined.Cast else Icons.Outlined.Tune,
                    contentDescription = "Screen Capture & Kalibrasi",
                    tint = projColor,
                    modifier = Modifier.size(22.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Screen Capture",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (isMediaProjectionActive) "Merekam Frame" else "Siap saat START",
                        style = MaterialTheme.typography.bodyMedium,
                        color = projColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun TelemetryGrid(
    state: BotDashboardState,
    config: CalibrationConfig,
    lifetimeStats: Pair<Int, Int>
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            TelemetryCard(
                title = "Ikan Tertangkap",
                value = state.fishCaughtCount.toString(),
                subtitle = "Total: ${lifetimeStats.first}",
                icon = Icons.Outlined.Phishing,
                accent = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
            TelemetryCard(
                title = "Ikan Terjual",
                value = state.fishSoldCount.toString(),
                subtitle = "Total: ${lifetimeStats.second}",
                icon = Icons.Outlined.Sell,
                accent = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.weight(1f)
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            TelemetryCard(
                title = "Durasi Berjalan",
                value = formatDuration(state.runningDurationMs),
                subtitle = if (state.isRunning) "Sesi aktif" else "Sesi berhenti",
                icon = Icons.Outlined.Timer,
                accent = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.weight(1f)
            )
            TelemetryCard(
                title = "Mode Aksi",
                value = config.postCatchAction.label,
                subtitle = config.minigameControlMode.label.substringBefore(" ("),
                icon = Icons.Outlined.Tune,
                accent = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun TelemetryCard(
    title: String,
    value: String,
    subtitle: String,
    icon: ImageVector,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = accent,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge.copy(fontFamily = JetBrainsMonoFamily),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun LiveVisionBarCard(state: BotDashboardState) {
    val barColor = MaterialTheme.colorScheme.secondary
    val fishColor = MaterialTheme.colorScheme.primary
    val trackBg = MaterialTheme.colorScheme.surfaceVariant

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Telemetry Visual Minigame",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = if (state.isDialogDetected) {
                        "DIALOG TERDETEKSI"
                    } else if (state.isMinigameDetected) {
                        "Ikan=${state.lastFishX?.let { "%.2f".format(it) } ?: "--"} | Bar=${state.lastBarX?.let { "%.2f".format(it) } ?: "--"}"
                    } else {
                        "Menunggu bar..."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (state.isMinigameDetected || state.isDialogDetected) barColor else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(24.dp)
            ) {
                drawRoundRect(
                    color = trackBg,
                    cornerRadius = CornerRadius(12f, 12f),
                    size = size
                )
                state.barXOrNull()?.let { bx ->
                    val barW = size.width * 0.22f
                    val left = (bx * size.width - barW / 2f).coerceIn(0f, size.width - barW)
                    drawRoundRect(
                        color = barColor.copy(alpha = 0.55f),
                        topLeft = Offset(left, 2f),
                        size = Size(barW, size.height - 4f),
                        cornerRadius = CornerRadius(10f, 10f)
                    )
                }
                state.fishXOrNull()?.let { fx ->
                    val cx = (fx * size.width).coerceIn(10f, size.width - 10f)
                    drawCircle(
                        color = fishColor,
                        radius = size.height * 0.34f,
                        center = Offset(cx, size.height / 2f)
                    )
                }
            }
        }
    }
}

private fun BotDashboardState.barXOrNull(): Float? = lastBarX
private fun BotDashboardState.fishXOrNull(): Float? = lastFishX

@Composable
private fun EmptyLogCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.Phishing,
                contentDescription = "Belum ada log",
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "Tekan START untuk memulai memancing otomatis di Gridmere. Aktivitas Cast, Minigame, dan Sell akan tercatat di sini.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ActivityLogRow(entry: ActivityLogEntry) {
    val accent = when (entry.level) {
        LogLevel.SUCCESS -> MaterialTheme.colorScheme.secondary
        LogLevel.WARNING -> MaterialTheme.colorScheme.tertiary
        LogLevel.ERROR -> MaterialTheme.colorScheme.error
        LogLevel.INFO -> MaterialTheme.colorScheme.primary
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.65f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (entry.level == LogLevel.ERROR || entry.level == LogLevel.WARNING) {
                Icon(
                    imageVector = Icons.Outlined.ErrorOutline,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(16.dp)
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(accent)
                )
            }
            Text(
                text = entry.timeText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = entry.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = (durationMs / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
}
