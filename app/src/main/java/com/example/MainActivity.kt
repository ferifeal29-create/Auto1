package com.example

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.automation.AutoFishAccessibilityService
import com.example.ui.AutoFishTab
import com.example.ui.AutoFishViewModel
import com.example.ui.screens.CalibrationScreen
import com.example.ui.screens.DashboardScreen
import com.example.ui.screens.GuideScreen
import com.example.ui.theme.AutoFishTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AutoFishTheme {
                AutoFishApp()
            }
        }
    }
}

@Composable
fun AutoFishApp(
    viewModel: AutoFishViewModel = viewModel()
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val dashboardState by viewModel.dashboardState.collectAsStateWithLifecycle()
    val calibrationConfig by viewModel.calibrationConfig.collectAsStateWithLifecycle()
    val lifetimeStats by viewModel.lifetimeStats.collectAsStateWithLifecycle()
    val selectedTab by viewModel.selectedTab.collectAsStateWithLifecycle()
    val selectedTarget by viewModel.selectedTarget.collectAsStateWithLifecycle()

    var showAccessibilityDisclosure by remember { mutableStateOf(false) }

    // Refresh accessibility state whenever user returns to the app from system Settings
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshAccessibilityStatus()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val mediaProjectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            viewModel.onMediaProjectionGranted(result.resultCode, result.data!!)
        }
    }

    val launchMediaProjectionPrompt = {
        val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        // Proceed to MediaProjection prompt regardless of notification decision so automation remains usable
        launchMediaProjectionPrompt()
    }

    val handleStartRequested = {
        viewModel.refreshAccessibilityStatus()
        val accReady = AutoFishAccessibilityService.isServiceEnabled(context) ||
            AutoFishAccessibilityService.instance != null

        if (!accReady) {
            showAccessibilityDisclosure = true
        } else if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            launchMediaProjectionPrompt()
        }
    }

    val openAccessibilitySettings = {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    if (selectedTab != AutoFishTab.DASHBOARD) {
        BackHandler {
            viewModel.selectTab(AutoFishTab.DASHBOARD)
        }
    }

    if (showAccessibilityDisclosure) {
        AlertDialog(
            onDismissRequest = { showAccessibilityDisclosure = false },
            title = { Text("Aktifkan Accessibility Service") },
            text = {
                Text(
                    "AutoFish membutuhkan layanan Accessibility (AutoFish Touch Controller) untuk menekan tombol Cast, menggerakkan kontrol minigame, dan menekan tombol Sell/Keep di Gridmere. Layanan ini hanya mengirim ketukan saat Anda menekan START."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showAccessibilityDisclosure = false
                        openAccessibilitySettings()
                    },
                    modifier = Modifier.testTag("confirm_accessibility_dialog")
                ) {
                    Text("Buka Pengaturan")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAccessibilityDisclosure = false }) {
                    Text("Batal")
                }
            }
        )
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useNavigationRail = maxWidth >= 600.dp

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            bottomBar = {
                if (!useNavigationRail) {
                    NavigationBar {
                        AutoFishTab.entries.forEach { tab ->
                            val selected = selectedTab == tab
                            NavigationBarItem(
                                selected = selected,
                                onClick = { viewModel.selectTab(tab) },
                                modifier = Modifier.testTag("nav_${tab.name.lowercase()}"),
                                icon = {
                                    Icon(
                                        imageVector = tabIcon(tab, selected),
                                        contentDescription = tab.label
                                    )
                                },
                                label = { Text(tab.label) }
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useNavigationRail) {
                    NavigationRail {
                        AutoFishTab.entries.forEach { tab ->
                            val selected = selectedTab == tab
                            NavigationRailItem(
                                selected = selected,
                                onClick = { viewModel.selectTab(tab) },
                                modifier = Modifier.testTag("rail_${tab.name.lowercase()}"),
                                icon = {
                                    Icon(
                                        imageVector = tabIcon(tab, selected),
                                        contentDescription = tab.label
                                    )
                                },
                                label = { Text(tab.label) }
                            )
                        }
                    }
                }

                Box(modifier = Modifier.weight(1f)) {
                    when (selectedTab) {
                        AutoFishTab.DASHBOARD -> DashboardScreen(
                            state = dashboardState,
                            config = calibrationConfig,
                            lifetimeStats = lifetimeStats,
                            onStartClick = handleStartRequested,
                            onStopClick = { viewModel.stopAutomation() },
                            onOpenAccessibilitySettings = { showAccessibilityDisclosure = true },
                            onOpenCalibration = { viewModel.selectTab(AutoFishTab.CALIBRATION) }
                        )
                        AutoFishTab.CALIBRATION -> CalibrationScreen(
                            config = calibrationConfig,
                            selectedTarget = selectedTarget,
                            onSelectTarget = viewModel::selectCalibrationTarget,
                            onUpdatePoint = viewModel::updatePoint,
                            onUpdateMinigameMode = viewModel::updateMinigameMode,
                            onUpdatePostCatchAction = viewModel::updatePostCatchAction,
                            onUpdateUseBaitSelection = viewModel::updateUseBaitSelection,
                            onUpdateAutoDetectWaterBlock = viewModel::updateAutoDetectWaterBlock,
                            onUpdateInvertSingle = viewModel::updateInvertSingleControl,
                            onUpdateCastDelay = viewModel::updateCastDelay,
                            onUpdateRecastDelay = viewModel::updateRecastDelay,
                            onResetDefaults = viewModel::resetCalibration
                        )
                        AutoFishTab.GUIDE -> GuideScreen(
                            isAccessibilityEnabled = dashboardState.isAccessibilityEnabled,
                            onOpenAccessibilitySettings = { showAccessibilityDisclosure = true }
                        )
                    }
                }
            }
        }
    }
}

private fun tabIcon(tab: AutoFishTab, selected: Boolean) = when (tab) {
    AutoFishTab.DASHBOARD -> if (selected) Icons.Filled.Dashboard else Icons.Outlined.Dashboard
    AutoFishTab.CALIBRATION -> if (selected) Icons.Filled.Tune else Icons.Outlined.Tune
    AutoFishTab.GUIDE -> if (selected) Icons.AutoMirrored.Filled.MenuBook else Icons.AutoMirrored.Outlined.MenuBook
}
