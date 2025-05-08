package com.example.llama.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.google.accompanist.systemuicontroller.rememberSystemUiController

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF6200EE),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEDE7F6),
    onPrimaryContainer = Color(0xFF3700B3),
    surface = Color(0xFFFAFAFA),
    onSurface = Color(0xFF212121),
    surfaceVariant = Color(0xFFE0E0E0),
    onSurfaceVariant = Color(0xFF424242),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF212121),
    error = Color(0xFFB00020),
    onError = Color.White
)

@Composable
fun LlamaAndroidTheme(content: @Composable () -> Unit) {
    val systemUiController = rememberSystemUiController()

    // Set the status bar to transparent
    systemUiController.setStatusBarColor(
        color = Color.Transparent,
        darkIcons = true // Use dark icons for visibility on a transparent (likely light) background
    )

    // Optionally, set the navigation bar to transparent as well
    systemUiController.setNavigationBarColor(
        color = Color.Transparent,
        darkIcons = true
    )

    MaterialTheme(
        colorScheme = LightColorScheme,
        typography = Typography,
        content = content
    )
}
