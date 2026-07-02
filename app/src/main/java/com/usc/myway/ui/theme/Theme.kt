package com.usc.myway.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Teal = Color(0xFF00A77D)
private val TealDeep = Color(0xFF00795A)

private val LightColors = lightColorScheme(
    primary = Teal,
    onPrimary = Color.White,
    secondary = TealDeep,
    background = Color(0xFFF0FDFE),
    surface = Color.White,
    onSurface = Color(0xFF1E293B),
)

private val DarkColors = darkColorScheme(
    primary = Teal,
    onPrimary = Color.White,
    secondary = Teal,
    background = Color(0xFF0F172A),
    surface = Color(0xFF1E293B),
    onSurface = Color(0xFFF1F5F9),
)

@Composable
fun MyWayTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
