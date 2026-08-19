package com.engine.ui.theme

import android.app.Activity
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * ═══════════════════════════════════════════════════════════════════
 *  v3.8 · 统一主题时钟 —— 修复 "色彩重影 / 顶部菜单影子"
 * ═══════════════════════════════════════════════════════════════════
 *
 *  v3.7 的病灶 (本轮从源码确认):
 *    animatedColorScheme 只插值了 24 个颜色角色, 漏掉了
 *    surfaceContainerHigh/Low/Highest/Lowest、surfaceBright/Dim、
 *    inverseSurface、inverseOnSurface、inversePrimary、scrim 共 10 个。
 *    而 M3 DropdownMenu 容器色恰恰是 surfaceContainerHigh ——
 *    切主题的 420ms 里全屏都在渐变, 唯独菜单面板瞬间跳变,
 *    叠上加重的 elevation 投影, 就成了视频里的 "影子 / 重影"。
 *
 *  本文件:
 *    1. 单一 progress ∈ [0,1] 同时驱动全部 35 个色彩角色 (统一时钟);
 *    2. 背景渐变端点直接取自插值后的 scheme (背景与组件同源同钟);
 *    3. 状态栏底色取渐变顶端同款颜色 (状态栏-顶栏-内容无缝一体)。
 *
 * ═══════════════════════════════════════════════════════════════════
 *  v3.9 · 白天模式掉帧修复 —— "夜间丝滑 / 白天一卡一卡"
 * ═══════════════════════════════════════════════════════════════════
 *
 *  病灶 ① 状态栏窗口事务每帧触发 (v3.8 引入的回归):
 *    SideEffect 在每次重组后执行 —— 不只主题切换的 420ms,
 *    列表滚动/呼吸动画期间也每帧执行 window.statusBarColor 写入。
 *    每次写入都是一次系统窗口事务 (binder → system_server);
 *    app 全局 FLAG_SECURE (防截屏) 下窗口只能 GPU 合成,
 *    系统栏事务最易掉帧。白天模式 = 亮色栏 + 深色图标翻转,
 *    事务代价与视觉可见度都最高。
 *    修复: 颜色量化 (每通道 16 级内不变不写, 整段过渡 ≤ 8 次),
 *          图标深浅仅在真正翻转时写一次。
 *
 *  病灶 ② 亮背景把帧抖 "放大" 了:
 *    深色相邻色明度差极小 (#141120→#211D33), 掉帧肉眼不可见;
 *    白天从纸白渐变到紫灰, 跨度大, 同样的帧抖全部显形
 *    (暗色 UI 天然藏帧抖的经典现象)。
 *    修复: glow 随主题时钟自适应 (白天 0.45× → 夜间 1×),
 *          白天的色彩场更 "静", 帧抖可见度随之下降。
 */

/**
 * 全局背景笔刷: 由 [EngineSyncedTheme] 派生, 屏幕经
 * `EngineBackground` 组件消费。
 */
val LocalEngineBackground = compositionLocalOf<Brush> {
    Brush.verticalGradient(listOf(Color.White, Color.White))
}

/**
 * 背景渐变配方 —— 端点全部取自当前 (已插值的) scheme:
 *
 *   · 顶部: surface 掺 glow 比例的 primary  (品牌微光, 深浅色都成立)
 *   · 中部: 纯 surface                     (与卡片/表面完全同色 → 融合)
 *   · 底部: surface 向 surfaceVariant 过半  (轻微纵深, 托住底部导航)
 *
 * @param glow 品牌色浓度。0.12 是克制值, 想更浓可调到 0.2~0.3
 */
fun engineBackgroundBrush(scheme: ColorScheme, glow: Float = 0.12f): Brush =
    Brush.verticalGradient(
        colors = listOf(
            lerp(scheme.surface, scheme.primary, glow),
            scheme.surface,
            lerp(scheme.surface, scheme.surfaceVariant, 0.6f),
        )
    )

/**
 * 全角色同步插值 (material3 1.2.0, 35 个颜色角色一个不漏)。
 * t 落在 0/1 端点时直接返回原 scheme —— 静止期零开销。
 */
fun lerpColorScheme(a: ColorScheme, b: ColorScheme, t: Float): ColorScheme {
    if (t <= 0f) return a
    if (t >= 1f) return b
    fun l(x: Color, y: Color): Color = lerp(x, y, t)
    return a.copy(
        primary = l(a.primary, b.primary),
        onPrimary = l(a.onPrimary, b.onPrimary),
        primaryContainer = l(a.primaryContainer, b.primaryContainer),
        onPrimaryContainer = l(a.onPrimaryContainer, b.onPrimaryContainer),
        inversePrimary = l(a.inversePrimary, b.inversePrimary),
        secondary = l(a.secondary, b.secondary),
        onSecondary = l(a.onSecondary, b.onSecondary),
        secondaryContainer = l(a.secondaryContainer, b.secondaryContainer),
        onSecondaryContainer = l(a.onSecondaryContainer, b.onSecondaryContainer),
        tertiary = l(a.tertiary, b.tertiary),
        onTertiary = l(a.onTertiary, b.onTertiary),
        tertiaryContainer = l(a.tertiaryContainer, b.tertiaryContainer),
        onTertiaryContainer = l(a.onTertiaryContainer, b.onTertiaryContainer),
        background = l(a.background, b.background),
        onBackground = l(a.onBackground, b.onBackground),
        surface = l(a.surface, b.surface),
        onSurface = l(a.onSurface, b.onSurface),
        surfaceVariant = l(a.surfaceVariant, b.surfaceVariant),
        onSurfaceVariant = l(a.onSurfaceVariant, b.onSurfaceVariant),
        surfaceTint = l(a.surfaceTint, b.surfaceTint),
        inverseSurface = l(a.inverseSurface, b.inverseSurface),
        inverseOnSurface = l(a.inverseOnSurface, b.inverseOnSurface),
        error = l(a.error, b.error),
        onError = l(a.onError, b.onError),
        errorContainer = l(a.errorContainer, b.errorContainer),
        onErrorContainer = l(a.onErrorContainer, b.onErrorContainer),
        outline = l(a.outline, b.outline),
        outlineVariant = l(a.outlineVariant, b.outlineVariant),
        scrim = l(a.scrim, b.scrim),
        surfaceBright = l(a.surfaceBright, b.surfaceBright),
        surfaceDim = l(a.surfaceDim, b.surfaceDim),
        surfaceContainer = l(a.surfaceContainer, b.surfaceContainer),
        surfaceContainerHigh = l(a.surfaceContainerHigh, b.surfaceContainerHigh),
        surfaceContainerHighest = l(a.surfaceContainerHighest, b.surfaceContainerHighest),
        surfaceContainerLow = l(a.surfaceContainerLow, b.surfaceContainerLow),
        surfaceContainerLowest = l(a.surfaceContainerLowest, b.surfaceContainerLowest),
    )
}

/**
 * v3.8 主题核心: 取代 v3.6 的 24 路 animateColorAsState。
 *
 * @param darkTheme   当前是否深色
 * @param lightScheme 浅色目标 scheme (可来自动态取色)
 * @param darkScheme  深色目标 scheme (可来自动态取色)
 */
@Composable
fun EngineSyncedTheme(
    darkTheme: Boolean,
    lightScheme: ColorScheme,
    darkScheme: ColorScheme,
    typography: Typography,
    shapes: Shapes,
    glow: Float = 0.12f,
    content: @Composable () -> Unit,
) {
    // ── 唯一的动画源: 0 (浅) ↔ 1 (深), 与 v3.6 同节奏 (420ms) ──
    val progress by animateFloatAsState(
        targetValue = if (darkTheme) 1f else 0f,
        animationSpec = tween(
            durationMillis = EngineMotion.THEME_CROSSFADE_MS,
            easing = EngineMotion.EaseInOut,
        ),
        label = "engineThemeProgress",
    )
    val scheme = lerpColorScheme(lightScheme, darkScheme, progress)

    // ── v3.9: glow 自适应 ─────────────────────────────────────
    // 亮背景色彩跨度大, 同样的帧抖全部显形 (暗色天然藏帧抖);
    // 白天收敛到 0.45×, 夜间保持 1×。随同一时钟插值, 无跳变。
    val glowNow = glow * (0.45f + 0.55f * progress)

    val background = engineBackgroundBrush(scheme, glowNow)

    // ── v3.9: 状态栏节流写入 ─────────────────────────────────
    // v3.8 病灶: SideEffect 每次重组后都执行 —— 滚动/呼吸动画期间
    // 也每帧写 window.statusBarColor (每次 = 一次系统窗口事务)。
    // 现按通道量化: 变化不足 1/16 不写, 整段过渡 ≤ 8 次;
    // 图标深浅仅在真正翻转时写一次。
    val view = LocalView.current
    var lastBarArgb by remember { mutableStateOf(0) }
    var lastLightIcons by remember { mutableStateOf<Boolean?>(null) }
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val argb = lerp(scheme.surface, scheme.primary, glowNow).toArgb()
            // 每通道只比较高 4 位: 不足一级视为没变, 不触发窗口事务
            if ((argb xor lastBarArgb) and 0xF0F0F0F0.toInt() != 0) {
                @Suppress("DEPRECATION")
                window.statusBarColor = argb
                lastBarArgb = argb
            }
            val lightIcons = !darkTheme
            if (lastLightIcons != lightIcons) {
                WindowCompat.getInsetsController(window, view)
                    .isAppearanceLightStatusBars = lightIcons
                lastLightIcons = lightIcons
            }
        }
    }

    CompositionLocalProvider(LocalEngineBackground provides background) {
        MaterialTheme(
            colorScheme = scheme,
            typography = typography,
            shapes = shapes,
            content = content,
        )
    }
}
