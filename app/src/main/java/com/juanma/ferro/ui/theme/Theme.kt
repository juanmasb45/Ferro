package com.juanma.ferro.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = FerroGreen,
    onPrimary = Color.Black,
    primaryContainer = FerroLightBlue,
    onPrimaryContainer = Color.White,
    secondary = FerroAccent,
    onSecondary = Color.Black,
    background = FerroDarkBlue,
    onBackground = Color.White,
    surface = FerroDarkBlue,
    onSurface = Color.White,
    surfaceVariant = FerroLightBlue,
    onSurfaceVariant = Color.White,
    tertiary = FerroRed,
    onTertiary = Color.White
)

private val LightColorScheme = lightColorScheme(
    primary = FerroGreen,
    onPrimary = Color.White,
    primaryContainer = FerroLightBlue,
    onPrimaryContainer = Color.White,
    secondary = FerroLightBlue,
    onSecondary = Color.White,
    background = FerroDarkBlue,
    onBackground = Color.White,
    surface = FerroDarkBlue,
    onSurface = Color.White,
    surfaceVariant = FerroLightBlue,
    onSurfaceVariant = Color.White,
    tertiary = FerroRed,
    onTertiary = Color.White
)

@Composable
fun FerroTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    // Forzamos el esquema oscuro de Ferro para que coincida con el logo y la cabina
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}