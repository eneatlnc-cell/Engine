package com.engine.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

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
 * ═══════════════════════════════════════════════════════════════════
 *  v3.6→v3.8 主题切换演进
 * ═══════════════════════════════════════════════════════════════════
 *  v3.6 (旧): animatedColorScheme 对 24 个角色各起一路
 *    animateColorAsState —— 漏掉了 surfaceContainerHigh 等 10 个角色。
 *    而 M3 DropdownMenu 容器色恰是 surfaceContainerHigh: 切主题时
 *    全屏渐变、菜单瞬变, 加上静止的 elevation 投影 → "影子/重影"。
 *  v3.8 (现): 委托 [EngineSyncedTheme] —— 单一 progress 驱动全部
 *    35 个角色 + 背景渐变端点 + 状态栏底色, 同源同钟。
 *    (实现与病灶分析见 ThemeSync.kt 头注释)
 */
/**
 * Material3 主题, 支持浅色/深色 + 动态取色 (Android 12+)
 */
@Composable
fun EngineTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val lightScheme: ColorScheme
    val darkScheme: ColorScheme
    if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val context = LocalContext.current
        lightScheme = dynamicLightColorScheme(context)
        darkScheme = dynamicDarkColorScheme(context)
    } else {
        lightScheme = LightColorScheme
        darkScheme = DarkColorScheme
    }

    EngineSyncedTheme(
        darkTheme = darkTheme,
        lightScheme = lightScheme,
        darkScheme = darkScheme,
        typography = RobotoTypography,
        shapes = EngineShapes,   // v3.7: M3E 表情化形状系统
        content = content
    )
}
