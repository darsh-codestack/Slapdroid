package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val AppColorScheme = lightColorScheme(
    primary = PrimaryBlack,
    secondary = TextSecondary,
    tertiary = PrimaryBlack,
    background = BackgroundWhite,
    surface = SurfaceLight,
    surfaceVariant = ActionBackground,
    onPrimary = BackgroundWhite,
    onSecondary = BackgroundWhite,
    onTertiary = BackgroundWhite,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    onSurfaceVariant = TextPrimary,
    outline = OutlineColor
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false, // Force custom theme
    content: @Composable () -> Unit,
) {
    MaterialTheme(colorScheme = AppColorScheme, typography = Typography, content = content)
}
