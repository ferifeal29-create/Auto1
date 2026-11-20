package com.example.model

enum class MinigameControlMode(val label: String, val description: String) {
    DUAL_DIRECTION(
        label = "Dual Arrows (Left / Right)",
        description = "Menekan panah Kiri / Kanan di kiri bawah secara adaptif mengikuti arah ikan"
    ),
    SINGLE_TAP_HOLD(
        label = "Single Control (Tap / Hold)",
        description = "Menekan satu tombol kontrol saat ikan bergerak melewati kotak indikator"
    )
}

enum class PostCatchAction(val label: String) {
    SELL("Auto Sell"),
    KEEP("Auto Keep")
}

enum class CalibrationTarget(val label: String, val shortTag: String) {
    BAIT_SLOT("Umpan Cacing", "BAIT"),
    CAST("Blok Air", "AIR"),
    HOOK("Panah Bawah (↓)", "HOOK"),
    MINIGAME_LEFT("Panah Kiri (←)", "LEFT"),
    MINIGAME_RIGHT("Panah Kanan (→)", "RIGHT"),
    SELL("Tombol Sell", "SELL"),
    KEEP("Tombol Keep", "KEEP"),
    MINIGAME_PRIMARY("Kontrol Tunggal", "CTRL")
}

data class RelativePoint(
    val x: Float,
    val y: Float
) {
    fun clamped(): RelativePoint = RelativePoint(
        x = x.coerceIn(0.01f, 0.99f),
        y = y.coerceIn(0.01f, 0.99f)
    )
}

data class RelativeRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    fun clamped(): RelativeRect {
        val l = left.coerceIn(0.01f, 0.95f)
        val t = top.coerceIn(0.01f, 0.95f)
        val r = right.coerceIn(l + 0.04f, 0.99f)
        val b = bottom.coerceIn(t + 0.04f, 0.99f)
        return RelativeRect(l, t, r, b)
    }
}

data class CalibrationConfig(
    val baitPoint: RelativePoint = DEFAULT_BAIT_POINT,
    val castPoint: RelativePoint = DEFAULT_CAST_POINT,
    val hookPoint: RelativePoint = DEFAULT_HOOK_POINT,
    val sellPoint: RelativePoint = DEFAULT_SELL_POINT,
    val keepPoint: RelativePoint = DEFAULT_KEEP_POINT,
    val minigamePrimaryPoint: RelativePoint = DEFAULT_MINIGAME_PRIMARY_POINT,
    val minigameLeftPoint: RelativePoint = DEFAULT_MINIGAME_LEFT_POINT,
    val minigameRightPoint: RelativePoint = DEFAULT_MINIGAME_RIGHT_POINT,
    val minigameBarRegion: RelativeRect = DEFAULT_MINIGAME_BAR_REGION,
    val dialogRegion: RelativeRect = DEFAULT_DIALOG_REGION,
    val minigameControlMode: MinigameControlMode = MinigameControlMode.DUAL_DIRECTION,
    val postCatchAction: PostCatchAction = PostCatchAction.SELL,
    val useBaitSelection: Boolean = true,
    val invertSingleControl: Boolean = false,
    val castDelayMs: Long = 1000L,
    val recastDelayMs: Long = 900L,
    val biteTimeoutMs: Long = 35000L
) {
    fun pointFor(target: CalibrationTarget): RelativePoint = when (target) {
        CalibrationTarget.BAIT_SLOT -> baitPoint
        CalibrationTarget.CAST -> castPoint
        CalibrationTarget.HOOK -> hookPoint
        CalibrationTarget.SELL -> sellPoint
        CalibrationTarget.KEEP -> keepPoint
        CalibrationTarget.MINIGAME_PRIMARY -> minigamePrimaryPoint
        CalibrationTarget.MINIGAME_LEFT -> minigameLeftPoint
        CalibrationTarget.MINIGAME_RIGHT -> minigameRightPoint
    }

    fun withUpdatedPoint(target: CalibrationTarget, point: RelativePoint): CalibrationConfig {
        val safe = point.clamped()
        return when (target) {
            CalibrationTarget.BAIT_SLOT -> copy(baitPoint = safe)
            CalibrationTarget.CAST -> copy(castPoint = safe)
            CalibrationTarget.HOOK -> copy(hookPoint = safe)
            CalibrationTarget.SELL -> copy(sellPoint = safe)
            CalibrationTarget.KEEP -> copy(keepPoint = safe)
            CalibrationTarget.MINIGAME_PRIMARY -> copy(minigamePrimaryPoint = safe)
            CalibrationTarget.MINIGAME_LEFT -> copy(minigameLeftPoint = safe)
            CalibrationTarget.MINIGAME_RIGHT -> copy(minigameRightPoint = safe)
        }
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 2

        val DEFAULT_BAIT_POINT = RelativePoint(0.422f, 0.910f)
        val DEFAULT_CAST_POINT = RelativePoint(0.270f, 0.530f)
        val DEFAULT_HOOK_POINT = RelativePoint(0.880f, 0.840f)
        val DEFAULT_SELL_POINT = RelativePoint(0.432f, 0.700f)
        val DEFAULT_KEEP_POINT = RelativePoint(0.568f, 0.700f)
        val DEFAULT_MINIGAME_PRIMARY_POINT = RelativePoint(0.880f, 0.840f)
        val DEFAULT_MINIGAME_LEFT_POINT = RelativePoint(0.058f, 0.840f)
        val DEFAULT_MINIGAME_RIGHT_POINT = RelativePoint(0.168f, 0.840f)
        val DEFAULT_MINIGAME_BAR_REGION = RelativeRect(0.240f, 0.065f, 0.725f, 0.170f)
        val DEFAULT_DIALOG_REGION = RelativeRect(0.330f, 0.210f, 0.670f, 0.770f)
    }
}
