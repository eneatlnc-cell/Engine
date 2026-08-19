package com.engine.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.engine.ui.theme.EngineMotion
import kotlin.math.cos
import kotlin.math.sin

/**
 * ═══════════════════════════════════════════════════════════════════
 *  v3.12 · 梦幻球按钮 —— 极光在球内流动
 * ═══════════════════════════════════════════════════════════════════
 *
 *  v3.11 的病灶: 圆锥渐变(sweepGradient)整体旋转 —— 硬边缘绕轴转,
 *  视觉读作 "陀螺/表盘", 与 "梦幻" 完全相反。
 *
 *  v3.12 新配方 —— 球内极光, 三层叠加 (全部 draw 阶段, 零重组):
 *
 *    ① 深空底色   深梅紫径向渐变 (球体暗部基调)
 *    ② 极光色团×3 品牌紫 / 洋红 / 电青三团柔色, 各自以不同速度与
 *      椭圆轨道在球内缓慢游移, 半径大而羽化, 透明度随相位呼吸
 *      —— 色彩在球里 "呼吸流动", 没有任何东西在 "旋转"
 *    ③ 玻璃质感   下缘暗角收拢球体 + 左上高光点 + 右下缘光,
 *      读作一颗被内光照亮的玻璃球
 *
 *  主时钟 16s 一周期 (v3.11 是 8s) —— 慢到不会被当成 "动画",
 *  快到每隔几秒色调就有可感知的变化。
 */
@Composable
fun OrbButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    sizeDp: Int = 34,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.88f else 1f,
        animationSpec = EngineMotion.PressSpring,
        label = "orbPress",
    )

    // ── 梦幻时钟: t ∈ [0,1) 循环, 只被 draw 层读取, 重组零开销 ──
    val transition = rememberInfiniteTransition(label = "orbDream")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 16_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "orbT",
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(48.dp) // 统一 48dp 触区, 与右侧图标按钮对称
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
                .clip(CircleShape)          // 先裁圆, 极光只活在球内
                .drawBehind {
                    val r = size.minDimension / 2f
                    val c = Offset(size.width / 2f, size.height / 2f)
                    val tau = (2f * Math.PI).toFloat()

                    // ① 深空底色: 中心稍亮的深梅紫
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(Color(0xFF33205C), Color(0xFF160D2E)),
                            center = c,
                            radius = r,
                        )
                    )

                    // ② 极光色团 ×3: 各自椭圆轨道 + 独立速度/相位 + 呼吸
                    val blobs = listOf(
                        Color(0xFF8B5CF6), // 电流紫
                        Color(0xFFEC6FB4), // 洋红
                        Color(0xFF4CD0E1), // 电青
                    )
                    blobs.forEachIndexed { i, col ->
                        val phase = t * tau + i * 2.1f
                        // 速度各异 (0.8/1.05/1.3) → 永不形成整体旋转感
                        val speed = 0.8f + 0.25f * i
                        val orbitX = r * 0.42f * cos(phase * speed)
                        val orbitY = r * 0.42f * sin(phase * speed * 1.18f - i * 0.7f)
                        val bc = Offset(c.x + orbitX, c.y + orbitY)
                        // 呼吸: 透明度 0.35↔0.65, 相位错开
                        val breathe = 0.5f + 0.15f * sin(phase * 2f + i)
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    col.copy(alpha = breathe),
                                    col.copy(alpha = 0f),
                                ),
                                center = bc,
                                radius = r * 0.9f, // 大半径羽化 → 无硬边
                            )
                        )
                    }

                    // ③ 玻璃质感: 下缘暗角收拢 + 左上高光 + 右下缘光
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.4f)),
                            center = c,
                            radius = r,
                        )
                    )
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.55f),
                                Color.Transparent,
                            ),
                            center = Offset(c.x - r * 0.38f, c.y - r * 0.42f),
                            radius = r * 0.5f,
                        )
                    )
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.22f),
                                Color.Transparent,
                            ),
                            center = Offset(c.x + r * 0.45f, c.y + r * 0.4f),
                            radius = r * 0.35f,
                        )
                    )

                    // v3.10 无影语言: 发丝描边收边
                    drawCircle(
                        color = Color.White.copy(alpha = 0.28f),
                        radius = r - 0.5.dp.toPx(),
                        style = Stroke(width = 1.dp.toPx()),
                    )
                }
        )
    }
}
