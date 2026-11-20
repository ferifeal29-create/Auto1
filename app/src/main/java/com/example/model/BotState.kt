package com.example.model

enum class BotStatus(val label: String) {
    IDLE("Siaga"),
    STARTING("Memulai..."),
    CASTING("Pilih Umpan & Cast"),
    WAITING_BITE("Menunggu Umpan Dimakan..."),
    HOOKING("Menarik Kail (↓)"),
    MINIGAME("Minigame Catch The Fish"),
    DIALOG_DETECTED("Dialog Fish Caught!"),
    SELLING("Menjual / Menyimpan Ikan"),
    VERIFYING_CLOSED("Verifikasi Dialog Tertutup"),
    RECASTING("Bersiap Cast Ulang"),
    ERROR("Perlu Perhatian")
}

enum class LogLevel {
    INFO,
    SUCCESS,
    WARNING,
    ERROR
}

data class ActivityLogEntry(
    val id: Long,
    val timeText: String,
    val message: String,
    val level: LogLevel = LogLevel.INFO
)

data class BotDashboardState(
    val status: BotStatus = BotStatus.IDLE,
    val currentActivity: String = "Menunggu pengguna menekan START",
    val isRunning: Boolean = false,
    val fishCaughtCount: Int = 0,
    val fishSoldCount: Int = 0,
    val runningDurationMs: Long = 0L,
    val isAccessibilityEnabled: Boolean = false,
    val isMediaProjectionActive: Boolean = false,
    val lastFishX: Float? = null,
    val lastBarX: Float? = null,
    val isMinigameDetected: Boolean = false,
    val isDialogDetected: Boolean = false,
    val lastError: String? = null,
    val logs: List<ActivityLogEntry> = emptyList()
)
