package com.andlab.doctas.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorPalette = darkColorScheme(
    primary = PrimaryMainDark,
    onPrimary = TextOnPrimaryDark,
    primaryContainer = PrimarySoftGlowDark,
    onPrimaryContainer = PrimaryMainDark,
    inversePrimary = PrimaryMainLight,
    secondary = SecondaryIconDark,
    onSecondary = SecondaryTextDark,
    secondaryContainer = SecondarySurfaceDark,
    onSecondaryContainer = PrimaryTextDark,
    background = AppBackgroundDark,
    onBackground = PrimaryTextDark,
    surface = PrimaryCardDark,
    onSurface = PrimaryTextDark,
    surfaceVariant = SecondarySurfaceDark,
    onSurfaceVariant = SecondaryTextDark,
    outline = DividerBorderDark,
    outlineVariant = HintPlaceholderDark,
    error = Red,
    onError = TextOnPrimaryLight
)

private val LightColorPalette = lightColorScheme(
    primary = PrimaryMainLight,
    onPrimary = TextOnPrimaryLight,
    primaryContainer = PrimarySoftLight,
    onPrimaryContainer = PrimaryMainLight,
    inversePrimary = PrimaryMainDark,
    secondary = SecondaryIconLight,
    onSecondary = SecondaryTextLight,
    secondaryContainer = SecondarySurfaceLight,
    onSecondaryContainer = PrimaryTextLight,
    background = AppBackgroundLight,
    onBackground = PrimaryTextLight,
    surface = PrimaryCardLight,
    onSurface = PrimaryTextLight,
    surfaceVariant = SecondarySurfaceLight,
    onSurfaceVariant = SecondaryTextLight,
    outline = DividerBorderLight,
    outlineVariant = HintPlaceholderLight,
    error = Red,
    onError = TextOnPrimaryLight
)

@Composable
fun DoctasTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) {
        DarkColorPalette
    } else {
        LightColorPalette
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
