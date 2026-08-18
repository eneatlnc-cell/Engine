package com.engine.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.engine.ui.theme.EngineLeafShape
import com.engine.ui.theme.EngineMotion

/**
 * ═══════════════════════════════════════════════════════════════════
 *  v3.8 · 底部导航 —— 点开变大 / 退出变小
 * ═══════════════════════════════════════════════════════════════════
 *
 *  v3.7 的缺口: 只有选中项 1.12x 放大, 未选中项不动 ——
 *  "退出时缩小" 这个方向缺失, 弹簧的对比感出不来。
 *
 *  三态缩放 (锚定底部中心, 像从导航条上 "长" 出来):
 *
 *      按压     → 0.88   (受压收缩)
 *      选中     → 1.16   (放大站定)
 *      未选中   → 0.85   (缩小待命)
 *
 *  · 选中项背后有不对称 "叶片" 药丸生长 (延续 v3.7 设计语言);
 *  · 导航条本体透明, 全局渐变直接透出 —— 与背景融合为一体。
 *
 *  用法 (Scaffold bottomBar):
 *      EngineNavBar(selected = selectedTab, onSelect = { selectedTab = it })
 */
@Composable
fun EngineNavBar(
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    tabs: List<Pair<String, ImageVector>> = listOf(
        "聊天" to Icons.Filled.Chat,
        "联系人" to Icons.Filled.Contacts,
        "密钥" to Icons.Filled.Key,
    ),
) {
    Column(modifier = modifier.fillMaxWidth()) {
        // 顶部发丝线: 40% 不透明度的 outlineVariant, 标出边界不抢戏
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            tabs.forEachIndexed { index, (label, icon) ->
                EngineNavItem(
                    label = label,
                    icon = icon,
                    selected = index == selected,
                    onClick = { onSelect(index) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun EngineNavItem(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    // ── 核心动效: 点开变大 / 退出变小 ──────────────────────────
    // MediumBouncy + MediumLow: 回弹明显、节奏稍慢 = 呼吸感
    val scale by animateFloatAsState(
        targetValue = when {
            pressed -> 0.88f   // 受压收缩
            selected -> 1.16f  // 放大站定 (弹簧过冲后回正)
            else -> 0.85f      // 退出后缩小待命
        },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "navItemScale",
    )

    // 选中时图标在整体放大的基础上再长一档, 强化层级
    val iconScale by animateFloatAsState(
        targetValue = if (selected) 1.15f else 1f,
        animationSpec = EngineMotion.EnterSpring,
        label = "navIconScale",
    )

    // 选中药丸: 宽度生长 + 淡入, 两个维度同时动
    val pillWidth by animateDpAsState(
        targetValue = if (selected) 56.dp else 44.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "navPillWidth",
    )
    val pillAlpha by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "navPillAlpha",
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                // 锚定底部中心: 按钮从导航条上 "长" 出来
                transformOrigin = TransformOrigin(0.5f, 0.8f)
            }
            .clickable(
                interactionSource = interaction,
                indication = null, // 缩放本身就是按压反馈, 不叠涟漪
            ) { onClick() }
            .padding(vertical = 8.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            // 不对称叶片药丸 —— v3.7 设计语言延续, 落笔在右下
            Box(
                modifier = Modifier
                    .graphicsLayer { alpha = pillAlpha }
                    .size(width = pillWidth, height = 32.dp)
                    .background(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = EngineLeafShape,
                    ),
            )
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(24.dp)
                    .graphicsLayer {
                        scaleX = iconScale
                        scaleY = iconScale
                    },
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
