package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape

private val DarkColorScheme = darkColorScheme(
    primary = BioCyan,
    onPrimary = AbyssalNavy,
    primaryContainer = BioCyanContainer,
    onPrimaryContainer = BioCyan,
    secondary = CatchEmerald,
    onSecondary = AbyssalNavy,
    secondaryContainer = CatchEmeraldContainer,
    onSecondaryContainer = CatchEmerald,
    tertiary = AmberBeacon,
    onTertiary = AbyssalNavy,
    tertiaryContainer = AmberContainer,
    onTertiaryContainer = AmberBeacon,
    error = CoralRed,
    onError = Color.White,
    errorContainer = CoralContainer,
    onErrorContainer = CoralRed,
    background = AbyssalNavy,
    onBackground = TextPrimaryDark,
    surface = OceanSurface,
    onSurface = TextPrimaryDark,
    surfaceVariant = OceanSurfaceHigh,
    onSurfaceVariant = TextSecondaryDark,
    outline = OceanOutline
)

private val LightColorScheme = lightColorScheme(
    primary = DeepCyanLight,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0F2FE),
    onPrimaryContainer = Color(0xFF0C4A6E),
    secondary = DeepEmeraldLight,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCFCE7),
    onSecondaryContainer = Color(0xFF14532D),
    tertiary = DeepAmberLight,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFEF3C7),
    onTertiaryContainer = Color(0xFF78350F),
    error = Color(0xFFDC2626),
    onError = Color.White,
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D),
    background = LightOceanBg,
    onBackground = TextPrimaryLight,
    surface = LightOceanSurface,
    onSurface = TextPrimaryLight,
    surfaceVariant = LightOceanSurfaceVar,
    onSurfaceVariant = TextSecondaryLight,
    outline = Color(0xFFCBD5E1)
)

val AutoFishShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp)
)

@Composable
fun AutoFishTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AutoFishTypography,
        shapes = AutoFishShapes,
        content = content
    )
}
