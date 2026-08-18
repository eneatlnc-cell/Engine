package com.engine.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brightness4
import androidx.compose.material.icons.filled.Brightness7
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.engine.ui.theme.EngineMotion

/**
 * ═══════════════════════════════════════════════════════════════════
 *  v3.8 · 右上角下拉菜单 —— 修复 "顶部的文本框变色有影子"
 * ═══════════════════════════════════════════════════════════════════
 *
 *  v3.7 的病灶: M3 DropdownMenu 的容器色是 surfaceContainerHigh ——
 *  恰好是 v3.7 animatedColorScheme 漏插值的角色之一。切主题的
 *  420ms 里全屏在渐变, 菜单却瞬变; 叠上它自带的 3dp elevation
 *  投影 (不随颜色动), 就是视频里的 "影子"。
 *
 *  修复:
 *    ① 自绘面板: surfaceContainerHigh 已纳入统一时钟 (ThemeSync),
 *       颜色与全屏同步渐变;
 *    ② 投影归零, 改 1dp 细描边 (outlineVariant) 表达层次;
 *    ③ 入场从右上角锚点 (⋮ 按钮) 弹性展开, 与点击位置呼应;
 *    ④ 菜单项自带 0.96x 按压收缩, 与底部导航的触觉语言一致。
 *
 *  用法 (与 DropdownMenu 同位替换, 放在 ⋮ 按钮的 Box 内):
 *      Box {
 *          IconButton(onClick = { showMenu = true }) { Icon(MoreVert) }
 *          EngineTopMenu(
 *              expanded = showMenu,
 *              onDismiss = { showMenu = false },
 *              isDark = app.isDarkTheme,
 *              onToggleTheme = { app.toggleTheme() },
 *              onNewGroup = { ... },
 *              onSpark = { ... },
 *          )
 *      }
 */
@Composable
fun EngineTopMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    isDark: Boolean,
    onToggleTheme: () -> Unit,
    onNewGroup: () -> Unit,
    onSpark: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!expanded) return

    // 锚定父容器 (⋮ 按钮所在 Box) 的右上角, 向下向左展开
    Popup(
        alignment = Alignment.TopEnd,
        onDismissRequest = onDismiss,
        properties = PopupProperties(
            focusable = true,
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
        ),
    ) {
        // 入场: 从右上角锚点弹性展开
        var appeared by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { appeared = true }
        val menuScale by animateFloatAsState(
            targetValue = if (appeared) 1f else 0.72f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMediumLow,
            ),
            label = "menuScale",
        )
        val menuAlpha by animateFloatAsState(
            targetValue = if (appeared) 1f else 0f,
            animationSpec = tween(durationMillis = 140),
            label = "menuAlpha",
        )

        Surface(
            shape = RoundedCornerShape(22.dp),
            // ① 完全不透明, 颜色只来自统一主题时钟
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            // ② 投影归零 —— 层次改由细描边表达
            shadowElevation = 0.dp,
            tonalElevation = 0.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = modifier
                // 锚定父容器 (48dp IconButton) 右上角: 面板从 ⋮ 按钮
                // 下缘展开, 右缘内收 8dp —— 与 DropdownMenu 的落位习惯一致
                .padding(top = 48.dp, end = 8.dp)
                .graphicsLayer {
                    scaleX = menuScale
                    scaleY = menuScale
                    alpha = menuAlpha
                    // 右上角锚点 = ⋮ 按钮位置
                    transformOrigin = TransformOrigin(1f, 0f)
                },
        ) {
            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                EngineMenuItem(
                    icon = if (isDark) Icons.Filled.Brightness7 else Icons.Filled.Brightness4,
                    label = if (isDark) "白天模式" else "夜间模式",
                    onClick = {
                        onDismiss()
                        onToggleTheme()
                    },
                )
                EngineMenuItem(
                    icon = Icons.Filled.GroupAdd,
                    label = "新建群组",
                    onClick = {
                        onDismiss()
                        onNewGroup()
                    },
                )
                EngineMenuItem(
                    icon = Icons.Filled.LocalFireDepartment,
                    label = "Spark",
                    tint = MaterialTheme.colorScheme.tertiary, // 火焰橙品牌色 (v3.7)
                    onClick = {
                        onDismiss()
                        onSpark()
                    },
                )
            }
        }
    }
}

@Composable
private fun EngineMenuItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.onSurface,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val itemScale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = EngineMotion.PressSpring,
        label = "menuItemScale",
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .graphicsLayer {
                scaleX = itemScale
                scaleY = itemScale
            }
            .clip(RoundedCornerShape(14.dp))
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 18.dp, vertical = 12.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(22.dp),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = tint,
        )
    }
}
