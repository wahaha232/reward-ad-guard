package com.rewardadguard.app.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

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

/**
 * Material 3 theme.
 *
 * On Android 12+ the open-source Material You palette follows the wallpaper
 * (dynamic colour); older devices keep the fixed navy/green scheme so the app
 * still looks consistent without pulling a custom font or theme engine.
 */
@Composable
fun RewardAdGuardTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
