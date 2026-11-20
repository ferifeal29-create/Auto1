package com.example.automation

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.capture.MediaProjectionHolder
import com.example.capture.ScreenCaptureManager
import com.example.data.CalibrationRepository
import com.example.model.CalibrationConfig
import com.example.vision.BiteDetector
import com.example.vision.DialogDetector
import com.example.vision.MinigameAnalyzer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground Service (mediaProjection type) running the ScreenCaptureManager
 * and AutoFishEngine reliably in the background while Gridmere is in foreground.
 */
class AutoFishForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var calibrationRepository: CalibrationRepository
    private lateinit var screenCaptureManager: ScreenCaptureManager
    private lateinit var notificationManager: NotificationManager

    @Volatile
    private var latestConfig: CalibrationConfig = CalibrationConfig()

    private var automationJob: Job? = null
    private var tickerJob: Job? = null
    private var configCollectorJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        calibrationRepository = CalibrationRepository(applicationContext)
        screenCaptureManager = ScreenCaptureManager(applicationContext)
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()

        configCollectorJob = serviceScope.launch {
            calibrationRepository.calibrationConfig.collectLatest { cfg ->
                latestConfig = cfg
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopAutomationAndSelf("Dihentikan oleh pengguna")
                return START_NOT_STICKY
            }
            ACTION_START, null -> {
                startForegroundWithProjectionType()
                startAutomationSession()
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundWithProjectionType() {
        val notification = buildNotification(
            title = "AutoFish Aktif",
            content = "Mempersiapkan tangkapan layar untuk Gridmere..."
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startAutomationSession() {
        if (automationJob?.isActive == true) {
            return
        }

        if (!AutoFishAccessibilityService.isServiceEnabled(this) &&
            AutoFishAccessibilityService.instance == null
        ) {
            AutoFishStateStore.setError("Accessibility Service belum aktif. Aktifkan di Pengaturan.")
            stopSelfSafely()
            return
        }

        val grant = MediaProjectionHolder.consumeGrant()
        if (grant == null || grant.resultCode != Activity.RESULT_OK) {
            AutoFishStateStore.setError("Izin Screen Capture belum diberikan.")
            stopSelfSafely()
            return
        }

        val started = screenCaptureManager.start(
            resultCode = grant.resultCode,
            data = grant.data,
            onStopped = {
                serviceScope.launch {
                    stopAutomationAndSelf("Screen Capture dihentikan oleh sistem")
                }
            }
        )

        if (!started) {
            AutoFishStateStore.setError("Gagal memulai MediaProjection Screen Capture.")
            stopSelfSafely()
            return
        }

        AutoFishStateStore.startSession()
        AutoFishStateStore.setMediaProjectionActive(true)

        val sessionStartMs = System.currentTimeMillis()
        tickerJob?.cancel()
        tickerJob = serviceScope.launch {
            while (isActive) {
                val elapsed = System.currentTimeMillis() - sessionStartMs
                AutoFishStateStore.updateDuration(elapsed)
                val snapshot = AutoFishStateStore.state.value
                notificationManager.notify(
                    NOTIFICATION_ID,
                    buildNotification(
                        title = "AutoFish • ${snapshot.status.label}",
                        content = "Tertangkap: ${snapshot.fishCaughtCount} | Terjual: ${snapshot.fishSoldCount}"
                    )
                )
                delay(1000L)
            }
        }

        val engine = AutoFishEngine(
            captureManager = screenCaptureManager,
            biteDetector = BiteDetector(),
            minigameAnalyzer = MinigameAnalyzer(),
            dialogDetector = DialogDetector(),
            calibrationRepository = calibrationRepository,
            gestureProvider = { AutoFishAccessibilityService.instance }
        )

        automationJob = serviceScope.launch {
            try {
                engine.runLoop(configProvider = { latestConfig })
            } finally {
                if (AutoFishStateStore.state.value.isRunning) {
                    stopAutomationAndSelf("Sesi otomatis selesai")
                }
            }
        }
    }

    private fun stopAutomationAndSelf(reason: String) {
        automationJob?.cancel()
        automationJob = null
        tickerJob?.cancel()
        tickerJob = null
        screenCaptureManager.stop()
        AutoFishStateStore.stopSession(reason)
        stopSelfSafely()
    }

    private fun stopSelfSafely() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        automationJob?.cancel()
        tickerJob?.cancel()
        configCollectorJob?.cancel()
        screenCaptureManager.stop()
        if (AutoFishStateStore.state.value.isRunning) {
            AutoFishStateStore.stopSession("Layanan dihentikan")
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, content: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this,
            100,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, AutoFishForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            101,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(content)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openPendingIntent)
            .addAction(
                android.R.drawable.ic_media_pause,
                "STOP",
                stopPendingIntent
            )
            .build()
    }

    companion object {
        const val ACTION_START = "com.example.autofish.ACTION_START"
        const val ACTION_STOP = "com.example.autofish.ACTION_STOP"
        private const val CHANNEL_ID = "autofish_automation_channel"
        private const val NOTIFICATION_ID = 4042

        fun start(context: Context) {
            val intent = Intent(context, AutoFishForegroundService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, AutoFishForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
