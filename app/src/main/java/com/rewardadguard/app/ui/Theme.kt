package com.rewardadguard.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Minimal Material 3 theme.
 *
 * The app intentionally ships no dynamic-color / custom-font stack: a plain
 * scheme keeps the APK small and the rendering identical on every OEM skin.
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF1B5E9E),
    onPrimary = Color.White,
    secondary = Color(0xFF3E6B4C),
    background = Color(0xFFF6F7F9),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFEDEFF3),
    error = Color(0xFFB00020)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9FC9F3),
    onPrimary = Color(0xFF00325A),
    secondary = Color(0xFFA6D3B4),
    background = Color(0xFF101215),
    surface = Color(0xFF1A1D21),
    surfaceVariant = Color(0xFF25292E),
    error = Color(0xFFFFB4AB)
)

@Composable
fun RewardAdGuardTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
