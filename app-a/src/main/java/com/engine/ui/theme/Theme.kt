package com.engine.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * M3 浅色主题色板
 */
private val LightColorScheme = lightColorScheme(
    primary = EnginePrimary,
    onPrimary = EngineOnPrimary,
    primaryContainer = EnginePrimaryContainer,
    onPrimaryContainer = EngineOnPrimaryContainer,
    secondary = EngineSecondary,
    onSecondary = EngineOnSecondary,
    secondaryContainer = EngineSecondaryContainer,
    onSecondaryContainer = EngineOnSecondaryContainer,
    tertiary = EngineTertiary,
    onTertiary = EngineOnTertiary,
    tertiaryContainer = EngineTertiaryContainer,
    onTertiaryContainer = EngineOnTertiaryContainer,
    error = EngineError,
    onError = EngineOnError,
    errorContainer = EngineErrorContainer,
    onErrorContainer = EngineOnErrorContainer,
    background = EngineLightBackground,
    onBackground = EngineLightOnBackground,
    surface = EngineLightSurface,
    onSurface = EngineLightOnSurface,
    surfaceVariant = EngineLightSurfaceVariant,
    onSurfaceVariant = EngineLightOnSurfaceVariant,
    outline = EngineLightOutline,
    surfaceContainer = EngineLightSurfaceContainer
)

/**
 * M3 深色主题色板
 */
private val DarkColorScheme = darkColorScheme(
    primary = EngineDarkPrimary,
    onPrimary = EngineDarkOnPrimary,
    primaryContainer = EngineDarkPrimaryContainer,
    onPrimaryContainer = EngineDarkOnPrimaryContainer,
    secondary = EngineDarkSecondary,
    onSecondary = EngineDarkOnSecondary,
    secondaryContainer = EngineDarkSecondaryContainer,
    onSecondaryContainer = EngineDarkOnSecondaryContainer,
    tertiary = EngineDarkTertiary,
    onTertiary = EngineDarkOnTertiary,
    tertiaryContainer = EngineDarkTertiaryContainer,
    onTertiaryContainer = EngineDarkOnTertiaryContainer,
    error = EngineDarkError,
    onError = EngineDarkOnError,
    errorContainer = EngineDarkErrorContainer,
    onErrorContainer = EngineDarkOnErrorContainer,
    background = EngineDarkBackground,
    onBackground = EngineDarkOnBackground,
    surface = EngineDarkSurface,
    onSurface = EngineDarkOnSurface,
    surfaceVariant = EngineDarkSurfaceVariant,
    onSurfaceVariant = EngineDarkOnSurfaceVariant,
    outline = EngineDarkOutline,
    surfaceContainer = EngineDarkSurfaceContainer
)

/**
 * Material3 主题, 支持浅色/深色 + 动态取色 (Android 12+)
 */
@Composable
fun EngineTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = RobotoTypography,
        content = content
    )
}
