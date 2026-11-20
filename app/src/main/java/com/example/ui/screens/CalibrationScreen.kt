package com.example.ui.screens

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.CalibrationConfig
import com.example.model.CalibrationTarget
import com.example.model.MinigameControlMode
import com.example.model.PostCatchAction
import com.example.model.RelativePoint
import com.example.ui.theme.JetBrainsMonoFamily

@Composable
fun CalibrationScreen(
    config: CalibrationConfig,
    selectedTarget: CalibrationTarget,
    onSelectTarget: (CalibrationTarget) -> Unit,
    onUpdatePoint: (CalibrationTarget, RelativePoint) -> Unit,
    onUpdateMinigameMode: (MinigameControlMode) -> Unit,
    onUpdatePostCatchAction: (PostCatchAction) -> Unit,
    onUpdateUseBaitSelection: (Boolean) -> Unit,
    onUpdateInvertSingle: (Boolean) -> Unit,
    onUpdateCastDelay: (Long) -> Unit,
    onUpdateRecastDelay: (Long) -> Unit,
    onResetDefaults: () -> Unit,
    modifier: Modifier = Modifier
) {
    val activePoint = config.pointFor(selectedTarget)
    val context = LocalContext.current
    var referenceScreenshot by remember { mutableStateOf<ImageBitmap?>(null) }

    val screenshotPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream)?.asImageBitmap()
                }
            }.getOrNull()?.let { img ->
                referenceScreenshot = img
            }
        }
    }

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
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Kalibrasi Posisi Layar",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "Ketuk atau geser pin di pratinjau landscape (atau muat screenshot Gridmere Anda) untuk menyetel Umpan Cacing, Blok Air, Panah Bawah (↓), Panah ←/→, dan Sell/Keep.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            item {
                TargetSelectorRow(
                    selectedTarget = selectedTarget,
                    onSelectTarget = onSelectTarget
                )
            }

            item {
                LandscapeCalibrationCanvas(
                    config = config,
                    selectedTarget = selectedTarget,
                    referenceScreenshot = referenceScreenshot,
                    onPickScreenshot = {
                        screenshotPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    onClearScreenshot = { referenceScreenshot = null },
                    onPointChange = { point -> onUpdatePoint(selectedTarget, point) }
                )
            }

            item {
                FineTuneCoordinatesCard(
                    selectedTarget = selectedTarget,
                    point = activePoint,
                    onUpdatePoint = { updated -> onUpdatePoint(selectedTarget, updated) }
                )
            }

            item {
                MinigameAndActionSettingsCard(
                    config = config,
                    onUpdateMinigameMode = onUpdateMinigameMode,
                    onUpdatePostCatchAction = onUpdatePostCatchAction,
                    onUpdateUseBaitSelection = onUpdateUseBaitSelection,
                    onUpdateInvertSingle = onUpdateInvertSingle,
                    onUpdateCastDelay = onUpdateCastDelay,
                    onUpdateRecastDelay = onUpdateRecastDelay
                )
            }

            item {
                OutlinedButton(
                    onClick = onResetDefaults,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("reset_calibration_button"),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Icon(
                        imageVector = Icons.Outlined.RestartAlt,
                        contentDescription = "Reset Kalibrasi",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text("Reset ke Default Gridmere")
                }
            }
        }
    }
}

@Composable
private fun TargetSelectorRow(
    selectedTarget: CalibrationTarget,
    onSelectTarget: (CalibrationTarget) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        CalibrationTarget.entries.forEach { target ->
            val selected = target == selectedTarget
            FilterChip(
                selected = selected,
                onClick = { onSelectTarget(target) },
                modifier = Modifier.testTag("target_chip_${target.name.lowercase()}"),
                label = {
                    Text(
                        text = target.label,
                        style = MaterialTheme.typography.labelLarge
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.primary
                )
            )
        }
    }
}

@Composable
private fun LandscapeCalibrationCanvas(
    config: CalibrationConfig,
    selectedTarget: CalibrationTarget,
    referenceScreenshot: ImageBitmap?,
    onPickScreenshot: () -> Unit,
    onClearScreenshot: () -> Unit,
    onPointChange: (RelativePoint) -> Unit
) {
    val latestOnPointChange by rememberUpdatedState(onPointChange)
    val textMeasurer: TextMeasurer = rememberTextMeasurer()

    val primaryColor = MaterialTheme.colorScheme.primary
    val emeraldColor = MaterialTheme.colorScheme.secondary
    val amberColor = MaterialTheme.colorScheme.tertiary
    val skyBackdrop = Color(0xFF0A2540)
    val trackColor = Color(0xFF1B75BC)
    val dialogBackdrop = Color(0xCC112A56)

    val canvasAspectRatio = if (referenceScreenshot != null && referenceScreenshot.height > 0) {
        (referenceScreenshot.width.toFloat() / referenceScreenshot.height.toFloat()).coerceIn(1.3f, 2.5f)
    } else {
        20f / 9f
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        colors = CardDefaults.cardColors(containerColor = skyBackdrop)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.TouchApp,
                        contentDescription = null,
                        tint = primaryColor,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "Edit: ${selectedTarget.label}",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFFE2E8F0)
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = onPickScreenshot,
                        modifier = Modifier.testTag("pick_screenshot_button"),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Image,
                            contentDescription = "Muat Screenshot",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.size(4.dp))
                        Text(
                            text = if (referenceScreenshot == null) "Muat Screenshot" else "Ganti",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                    if (referenceScreenshot != null) {
                        IconButton(
                            onClick = onClearScreenshot,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Close,
                                contentDescription = "Hapus Screenshot",
                                tint = Color(0xFFE2E8F0),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(canvasAspectRatio)
                    .clip(MaterialTheme.shapes.small)
                    .background(skyBackdrop)
            ) {
                if (referenceScreenshot != null) {
                    Image(
                        bitmap = referenceScreenshot,
                        contentDescription = "Screenshot Referensi Gridmere",
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("calibration_preview_canvas")
                        .pointerInput(selectedTarget) {
                            detectTapGestures { offset ->
                                val rx = (offset.x / size.width.toFloat()).coerceIn(0.01f, 0.99f)
                                val ry = (offset.y / size.height.toFloat()).coerceIn(0.01f, 0.99f)
                                latestOnPointChange(RelativePoint(rx, ry))
                            }
                        }
                        .pointerInput(selectedTarget) {
                            detectDragGestures { change, _ ->
                                change.consume()
                                val rx = (change.position.x / size.width.toFloat()).coerceIn(0.01f, 0.99f)
                                val ry = (change.position.y / size.height.toFloat()).coerceIn(0.01f, 0.99f)
                                latestOnPointChange(RelativePoint(rx, ry))
                            }
                        }
                ) {
                    // Subtle alignment grid
                    val gridLines = 4
                    for (i in 1 until gridLines) {
                        val x = size.width * (i.toFloat() / gridLines)
                        val y = size.height * (i.toFloat() / gridLines)
                        drawLine(
                            color = Color.White.copy(alpha = if (referenceScreenshot == null) 0.08f else 0.14f),
                            start = Offset(x, 0f),
                            end = Offset(x, size.height),
                            strokeWidth = 1.dp.toPx()
                        )
                        drawLine(
                            color = Color.White.copy(alpha = if (referenceScreenshot == null) 0.08f else 0.14f),
                            start = Offset(0f, y),
                            end = Offset(size.width, y),
                            strokeWidth = 1.dp.toPx()
                        )
                    }

                    // Draw schematic UI guides when no custom screenshot is loaded
                    if (referenceScreenshot == null) {
                        val barRect = config.minigameBarRegion
                        drawRoundRect(
                            color = trackColor.copy(alpha = 0.45f),
                            topLeft = Offset(barRect.left * size.width, barRect.top * size.height),
                            size = Size(
                                (barRect.right - barRect.left) * size.width,
                                (barRect.bottom - barRect.top) * size.height
                            ),
                            cornerRadius = CornerRadius(8f, 8f)
                        )
                        drawRoundRect(
                            color = Color(0xFF38BDF8).copy(alpha = 0.7f),
                            topLeft = Offset(barRect.left * size.width, barRect.top * size.height),
                            size = Size(
                                (barRect.right - barRect.left) * size.width,
                                (barRect.bottom - barRect.top) * size.height
                            ),
                            cornerRadius = CornerRadius(8f, 8f),
                            style = Stroke(width = 1.5.dp.toPx())
                        )

                        val dlg = config.dialogRegion
                        drawRoundRect(
                            color = dialogBackdrop,
                            topLeft = Offset(dlg.left * size.width, dlg.top * size.height),
                            size = Size(
                                (dlg.right - dlg.left) * size.width,
                                (dlg.bottom - dlg.top) * size.height
                            ),
                            cornerRadius = CornerRadius(10f, 10f)
                        )
                        drawRoundRect(
                            color = Color.White.copy(alpha = 0.25f),
                            topLeft = Offset(dlg.left * size.width, dlg.top * size.height),
                            size = Size(
                                (dlg.right - dlg.left) * size.width,
                                (dlg.bottom - dlg.top) * size.height
                            ),
                            cornerRadius = CornerRadius(10f, 10f),
                            style = Stroke(width = 1.dp.toPx())
                        )

                        // Bottom 5-slot hotbar guide
                        val hotbarLeft = size.width * 0.34f
                        val hotbarTop = size.height * 0.855f
                        val hotbarW = size.width * 0.28f
                        val hotbarH = size.height * 0.115f
                        drawRoundRect(
                            color = Color(0xFF1E40AF).copy(alpha = 0.55f),
                            topLeft = Offset(hotbarLeft, hotbarTop),
                            size = Size(hotbarW, hotbarH),
                            cornerRadius = CornerRadius(6f, 6f),
                            style = Stroke(width = 1.dp.toPx())
                        )
                    }

                    // Draw all calibration pins
                    CalibrationTarget.entries.forEach { target ->
                        val pt = config.pointFor(target)
                        val cx = pt.x * size.width
                        val cy = pt.y * size.height
                        val isSelected = target == selectedTarget
                        val pinColor = when (target) {
                            CalibrationTarget.BAIT_SLOT -> Color(0xFFF97316)
                            CalibrationTarget.CAST -> amberColor
                            CalibrationTarget.HOOK -> Color(0xFFEC4899)
                            CalibrationTarget.SELL -> emeraldColor
                            CalibrationTarget.KEEP -> Color(0xFF38BDF8)
                            CalibrationTarget.MINIGAME_PRIMARY -> primaryColor
                            CalibrationTarget.MINIGAME_LEFT,
                            CalibrationTarget.MINIGAME_RIGHT -> Color(0xFFA78BFA)
                        }

                        val radius = if (isSelected) 12.dp.toPx() else 8.dp.toPx()
                        if (isSelected) {
                            drawCircle(
                                color = pinColor.copy(alpha = 0.32f),
                                radius = radius * 1.65f,
                                center = Offset(cx, cy)
                            )
                        }
                        drawCircle(
                            color = pinColor,
                            radius = radius,
                            center = Offset(cx, cy)
                        )
                        drawCircle(
                            color = Color.White,
                            radius = radius,
                            center = Offset(cx, cy),
                            style = Stroke(width = if (isSelected) 2.2.dp.toPx() else 1.dp.toPx())
                        )

                        val tagLayout = textMeasurer.measure(
                            text = target.shortTag,
                            style = TextStyle(
                                color = Color.White,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = JetBrainsMonoFamily
                            )
                        )
                        val labelX = (cx - tagLayout.size.width / 2f)
                            .coerceIn(2f, size.width - tagLayout.size.width - 2f)
                        val labelY = (cy - radius - tagLayout.size.height - 3.dp.toPx())
                            .coerceAtLeast(2f)
                        drawText(
                            textLayoutResult = tagLayout,
                            topLeft = Offset(labelX, labelY)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FineTuneCoordinatesCard(
    selectedTarget: CalibrationTarget,
    point: RelativePoint,
    onUpdatePoint: (RelativePoint) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Koordinat Relatif • ${selectedTarget.label}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "X=${"%.3f".format(point.x)}  Y=${"%.3f".format(point.y)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Column {
                Text(
                    text = "Posisi Horizontal (X): ${(point.x * 100).toInt()}%",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = point.x,
                    onValueChange = { newX -> onUpdatePoint(point.copy(x = newX)) },
                    valueRange = 0.01f..0.99f,
                    modifier = Modifier.testTag("slider_x")
                )
            }

            Column {
                Text(
                    text = "Posisi Vertikal (Y): ${(point.y * 100).toInt()}%",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = point.y,
                    onValueChange = { newY -> onUpdatePoint(point.copy(y = newY)) },
                    valueRange = 0.01f..0.99f,
                    modifier = Modifier.testTag("slider_y")
                )
            }
        }
    }
}

@Composable
private fun MinigameAndActionSettingsCard(
    config: CalibrationConfig,
    onUpdateMinigameMode: (MinigameControlMode) -> Unit,
    onUpdatePostCatchAction: (PostCatchAction) -> Unit,
    onUpdateUseBaitSelection: (Boolean) -> Unit,
    onUpdateInvertSingle: (Boolean) -> Unit,
    onUpdateCastDelay: (Long) -> Unit,
    onUpdateRecastDelay: (Long) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "Pengaturan Aksi & Minigame",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Ketuk Umpan Cacing Sebelum Cast",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Otomatis pilih slot umpan cacing lalu ketuk blok air (Fase 1)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = config.useBaitSelection,
                    onCheckedChange = onUpdateUseBaitSelection
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Aksi Setelah Ikan Tertangkap (Dialog Fish Caught!)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PostCatchAction.entries.forEach { action ->
                        FilterChip(
                            selected = config.postCatchAction == action,
                            onClick = { onUpdatePostCatchAction(action) },
                            label = { Text(action.label) }
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Mode Kontrol Minigame",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                MinigameControlMode.entries.forEach { mode ->
                    FilterChip(
                        selected = config.minigameControlMode == mode,
                        onClick = { onUpdateMinigameMode(mode) },
                        label = { Text(mode.label) }
                    )
                }
            }

            if (config.minigameControlMode == MinigameControlMode.SINGLE_TAP_HOLD) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Balik Arah Kontrol Tunggal",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Aktifkan jika menahan tombol menggeser indikator ke kiri",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = config.invertSingleControl,
                        onCheckedChange = onUpdateInvertSingle
                    )
                }
            }

            Column {
                Text(
                    text = "Jeda Setelah Cast: ${config.castDelayMs} ms",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = config.castDelayMs.toFloat(),
                    onValueChange = { onUpdateCastDelay(it.toLong()) },
                    valueRange = 400f..3000f
                )
            }

            Column {
                Text(
                    text = "Jeda Sebelum Recast: ${config.recastDelayMs} ms",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = config.recastDelayMs.toFloat(),
                    onValueChange = { onUpdateRecastDelay(it.toLong()) },
                    valueRange = 300f..3000f
                )
            }
        }
    }
}
