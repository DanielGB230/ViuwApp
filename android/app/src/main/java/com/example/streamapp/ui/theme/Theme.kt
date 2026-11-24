package com.example.streamapp.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColorScheme = lightColorScheme(
    primary = Emerald,
    onPrimary = White,
    primaryContainer = EmeraldContainer,
    onPrimaryContainer = OnEmeraldContainer,
    secondary = Blue,
    onSecondary = White,
    secondaryContainer = BlueContainer,
    onSecondaryContainer = OnBlueContainer,
    tertiary = EmeraldDark,
    onTertiary = White,
    error = Red,
    onError = White,
    errorContainer = RedContainer,
    onErrorContainer = OnRedContainer,
    background = Slate50,
    onBackground = Slate900,
    surface = White,
    onSurface = Slate900,
    surfaceVariant = Slate100,
    onSurfaceVariant = Slate600,
    outline = Slate400,
    outlineVariant = Slate200
)

private val DarkColorScheme = darkColorScheme(
    primary = EmeraldLight,
    onPrimary = Slate950,
    primaryContainer = OnEmeraldContainer,
    onPrimaryContainer = EmeraldContainer,
    secondary = BlueLight,
    onSecondary = Slate950,
    secondaryContainer = OnBlueContainer,
    onSecondaryContainer = BlueContainer,
    tertiary = Emerald,
    onTertiary = Slate950,
    error = Red,
    onError = White,
    errorContainer = OnRedContainer,
    onErrorContainer = RedContainer,
    background = Slate950,
    onBackground = Slate100,
    surface = Slate900,
    onSurface = Slate100,
    surfaceVariant = Slate800,
    onSurfaceVariant = Slate400,
    outline = Slate500,
    outlineVariant = Slate700
)

@Composable
fun ViuwAppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = ViuwTypography,
        content = content
    )
}
