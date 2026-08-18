package com.engine.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
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
 * 主题切换全屏渐变 (v3.6): 对 ColorScheme 的每个颜色做插值动画。
 *
 * 旧实现: darkTheme 布尔翻转 → 整棵色板树瞬间替换 → 全屏硬切,
 * 这就是 "生硬" 的直接来源。
 *
 * 本实现参照 Telegram 白夜切换: 切换时所有界面颜色从旧值向新值
 * 同时插值过渡 (~420ms), 背景/卡片/文字/导航条整体平滑渐变,
 * 状态栏颜色因读取动画值也随之同步渐变。
 *
 * 技术要点: animateColorAsState 逐帧驱动重组, Compose 只重绘颜色
 * 变化的层, 布局树不重建, 开销与一次普通动画相同。
 */
/**
 * 单色插值辅助 (v3.6): 主题切换时每个颜色从旧值平滑过渡到新值
 */
@Composable
private fun animColor(target: Color, spec: TweenSpec<Color>): Color =
    animateColorAsState(target, spec).value

@Composable
private fun animatedColorScheme(target: ColorScheme): ColorScheme {
    val spec = tween<Color>(durationMillis = EngineMotion.THEME_CROSSFADE_MS, easing = EngineMotion.EaseInOut)

    return target.copy(
        primary = animColor(target.primary, spec),
        onPrimary = animColor(target.onPrimary, spec),
        primaryContainer = animColor(target.primaryContainer, spec),
        onPrimaryContainer = animColor(target.onPrimaryContainer, spec),
        secondary = animColor(target.secondary, spec),
        onSecondary = animColor(target.onSecondary, spec),
        secondaryContainer = animColor(target.secondaryContainer, spec),
        onSecondaryContainer = animColor(target.onSecondaryContainer, spec),
        tertiary = animColor(target.tertiary, spec),
        onTertiary = animColor(target.onTertiary, spec),
        tertiaryContainer = animColor(target.tertiaryContainer, spec),
        onTertiaryContainer = animColor(target.onTertiaryContainer, spec),
        error = animColor(target.error, spec),
        onError = animColor(target.onError, spec),
        errorContainer = animColor(target.errorContainer, spec),
        onErrorContainer = animColor(target.onErrorContainer, spec),
        background = animColor(target.background, spec),
        onBackground = animColor(target.onBackground, spec),
        surface = animColor(target.surface, spec),
        onSurface = animColor(target.onSurface, spec),
        surfaceVariant = animColor(target.surfaceVariant, spec),
        onSurfaceVariant = animColor(target.onSurfaceVariant, spec),
        outline = animColor(target.outline, spec),
        outlineVariant = animColor(target.outlineVariant, spec),
        surfaceContainer = animColor(target.surfaceContainer, spec)
    )
}

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

    // v3.6: 主题切换全屏渐变 (见 animatedColorScheme 注释)
    val smoothScheme = animatedColorScheme(colorScheme)

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = smoothScheme,
        typography = RobotoTypography,
        content = content
    )
}
