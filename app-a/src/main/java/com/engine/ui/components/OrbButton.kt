package com.engine.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.engine.ui.theme.EngineMotion

/**
 * ═══════════════════════════════════════════════════════════════════
 *  v3.11 · 变色球按钮 —— 顶栏左侧的设置入口
 * ═══════════════════════════════════════════════════════════════════
 *
 *  一个不断色彩变幻的球形按钮, 点击打开设置抽屉。
 *
 *  视觉: 四色圆锥渐变 (品牌紫 → 洋红 → 火焰橙 → 电青) 缓慢流转,
 *        8s 一圈 —— "能量球" 而非 "闪烁灯"。
 *        色相链与 GradientAvatar 的邻近色策略同源, 品牌一致。
 *
 *  性能 (FLAG_SECURE 下的硬约束):
 *    旋转动画的 angle 只在 graphicsLayer/draw lambda 里读取 ——
 *    状态变化仅 invalidate 绘制阶段, **不触发重组**。
 *    常驻动画的重组开销为零, 电量友好。
 *
 *  层次: 遵循 v3.10 无影语言 —— 无投影, 以 1dp 发丝描边提边界。
 */
@Composable
fun OrbButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    sizeDp: Int = 34,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (pressed) 0.88f else 1f,
        animationSpec = EngineMotion.PressSpring,
        label = "orbPress",
    )

    // ── 流转时钟: 只被 draw 层读取, 重组零开销 ──────────────────
    val transition = rememberInfiniteTransition(label = "orbFlow")
    val flowAngle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 8000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "orbAngle",
    )

    // 四色圆锥: 顶栏左侧的小体量上, 高饱和但不刺眼
    val orbColors = listOf(
        Color(0xFF7B5CF0), // 电流紫 (primary 邻域)
        Color(0xFFE85D9E), // 洋红
        Color(0xFFFF8A4C), // 火焰橙 (tertiary 邻域)
        Color(0xFF4CD0E1), // 电青
        Color(0xFF7B5CF0), // 闭环
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(48.dp) // 统一 48dp 触区, 与右侧 🔥 按钮对称
            .semantics { contentDescription = "设置" }
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            ),
    ) {
        Box(
            modifier = Modifier
                .size(sizeDp.dp)
                .graphicsLayer {
                    scaleX = pressScale
                    scaleY = pressScale
                    transformOrigin = TransformOrigin.Center
                }
                .drawBehind {
                    // 圆锥渐变 + 随流转角旋转, 全部发生在 draw 阶段
                    rotate(degrees = flowAngle) {
                        drawCircle(
                            brush = Brush.sweepGradient(
                                colors = orbColors,
                                center = Offset(size.width / 2f, size.height / 2f),
                            )
                        )
                    }
                    // 顶部一点高光, 球体感
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.45f),
                                Color.Transparent,
                            ),
                            center = Offset(size.width * 0.38f, size.height * 0.32f),
                            radius = size.minDimension * 0.55f,
                        )
                    )
                    // v3.10 无影语言: 发丝描边提边界
                    drawCircle(
                        color = Color.White.copy(alpha = 0.35f),
                        radius = size.minDimension / 2f - 0.5.dp.toPx(),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(
                            width = 1.dp.toPx(),
                        ),
                    )
                }
        )
    }
}
