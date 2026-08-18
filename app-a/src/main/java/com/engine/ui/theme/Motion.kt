package com.engine.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

/**
 * 全局动效系统 (v3.6) —— "呼吸感" 的节奏来源
 *
 * 设计参照: Telegram / CSS transition 的丝滑观感来自三件事
 *   1. 所有状态变化都有过渡 (绝不允许瞬变硬切)
 *   2. 位移类动效用弹簧物理 (spring), 有自然的加速减速与轻微回弹
 *   3. 时长克制: 快速响应 (<300ms) 优先, 不让动画拖累操作
 *
 * 用法: 所有动画规格一律引用本对象, 不允许在组件里散落魔法数字,
 * 保证全局节奏一致 —— 这是 "丝滑" 的前提: 节奏统一。
 */
object EngineMotion {

    // ---- 缓动曲线 ----

    /** 标准减速曲线: 快进慢停, 适合大多数入场 (近似 CSS ease-out) */
    val EaseOut: Easing = CubicBezierEasing(0.25f, 0.8f, 0.35f, 1f)

    /** 对称曲线: 适合交叉淡入淡出 (近似 CSS ease-in-out) */
    val EaseInOut: Easing = CubicBezierEasing(0.45f, 0f, 0.15f, 1f)

    // ---- 弹簧规格 (位移/缩放类) ----

    /** 按压反馈: 高刚度快速收缩, 松手带一点弹跳 —— 触觉"活"感 */
    val PressSpring = spring<Float>(
        dampingRatio = Spring.DampingRatioMediumBouncy,  // 0.5 → 松手轻微回弹
        stiffness = 900f                                  // 快速响应不拖沓
    )

    /** 控件入场: 轻微过冲后稳定, 类似 TG 元素出现时的 "落定" 感 */
    val EnterSpring = spring<Float>(
        dampingRatio = 0.75f,
        stiffness = Spring.StiffnessMediumLow             // 400 → 可感知但流畅
    )

    /** 选中态指示: 图标/角标放大, 中弹性 */
    val SelectSpring = spring<Float>(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessMedium
    )

    // ---- 时长规格 (淡入淡出类, 固定时长更利落) ----

    /** 内容交叉切换 (Tab 页切换) */
    const val CONTENT_CROSSFADE_MS = 240

    /** 列表项入场: 单项基础时长 */
    const val ITEM_ENTER_MS = 260

    /** 列表项入场: 相邻项错峰间隔 (stagger) */
    const val ITEM_STAGGER_MS = 28

    /** 列表项入场: 最大参与错峰的项数 (超出直接 0 延迟, 长列表不排队) */
    const val ITEM_STAGGER_MAX = 10

    /** 主题切换: 全屏色彩渐变时长 (TG 白夜切换的顺滑感) */
    const val THEME_CROSSFADE_MS = 420
}
