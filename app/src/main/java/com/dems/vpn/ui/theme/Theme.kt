package com.dems.vpn.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8E8EFF),
    onPrimary = Color(0xFF0B1020),
    primaryContainer = BrandIndigo,
    onPrimaryContainer = Color.White,
    secondary = BrandCyan,
    onSecondary = Color(0xFF00131A),
    tertiary = BrandMint,
    onTertiary = Color(0xFF002014),
    background = NightBackground,
    onBackground = NightText,
    surface = NightSurface,
    onSurface = NightText,
    surfaceVariant = NightSurfaceHigh,
    onSurfaceVariant = NightTextMuted,
    outline = NightOutline,
    error = BrandDanger,
    onError = Color(0xFF250404)
)

private val LightColors = lightColorScheme(
    primary = BrandIndigo,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE4E4FF),
    onPrimaryContainer = Color(0xFF1B1B6B),
    secondary = Color(0xFF0891B2),
    onSecondary = Color.White,
    tertiary = Color(0xFF047857),
    onTertiary = Color.White,
    background = LightBackground,
    onBackground = LightText,
    surface = LightSurface,
    onSurface = LightText,
    surfaceVariant = LightSurfaceHigh,
    onSurfaceVariant = LightTextMuted,
    outline = Color(0xFFD3D8E4),
    error = Color(0xFFDC2626),
    onError = Color.White
)

@Composable
fun DemsVpnTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
