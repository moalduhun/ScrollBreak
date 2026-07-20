package com.moalduhun.scrollbreak.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = Indigo80,
    onPrimary = Color20,
    primaryContainer = IndigoContainerDark,
    onPrimaryContainer = IndigoContainerLight,
    secondary = Teal80,
    onSecondary = Color20,
    error = Rose80,
    onError = Color20,
    background = NeutralBackgroundDark,
    onBackground = NeutralOnBackgroundDark,
    surface = NeutralSurfaceDark,
    onSurface = NeutralOnBackgroundDark,
    surfaceVariant = NeutralSurfaceVariantDark,
    onSurfaceVariant = NeutralOnBackgroundDark,
    outline = NeutralOutlineDark
)

private val LightColorScheme = lightColorScheme(
    primary = Indigo40,
    onPrimary = NeutralSurfaceLight,
    primaryContainer = IndigoContainerLight,
    onPrimaryContainer = Indigo40,
    secondary = Teal40,
    onSecondary = NeutralSurfaceLight,
    error = Rose40,
    onError = NeutralSurfaceLight,
    background = NeutralBackgroundLight,
    onBackground = NeutralOnBackgroundLight,
    surface = NeutralSurfaceLight,
    onSurface = NeutralOnBackgroundLight,
    surfaceVariant = NeutralSurfaceVariantLight,
    onSurfaceVariant = NeutralOnBackgroundLight,
    outline = NeutralOutlineLight
)

@Composable
fun ScrollBreakTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
