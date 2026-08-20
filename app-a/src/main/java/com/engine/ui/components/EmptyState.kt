package com.engine.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 呼吸动画空态大图 (v3.22 共享组件)
 *
 * 聊天列表的帆船 / 联系人 / 群组 / 标记物 四页空态统一为
 * "动态大图 + 标题 + 副标题" —— Telegram 空状态吉祥物式插图的玩法,
 * 营造 "鲜活" 的 UI 感受; 动画规格沿用 v3.6 呼吸系:
 *
 *   · 图标: 缩放 1.0 ↔ 1.1, 每段 1000ms + Reverse ⇒ 周期 2s
 *   · 副标题: 透明度 0.55 ↔ 1.0, 每段 1500ms + Reverse ⇒ 与图标错相呼吸
 *
 * 夜间可读性 (v3.22 修复): 旧空态在 onSurfaceVariant 上再叠
 * alpha 0.5~0.7, 夜间模式对比度塌掉看不清。现规格:
 *   · 图标 primary (品牌色, 深浅两套主题均高可见)
 *   · 标题 onSurface 全强度
 *   · 副标题 onSurfaceVariant 不叠加额外 alpha (该角色本身就是低强调)
 *
 * 记忆化稳定性: 动画逻辑独立为顶层 composable, 保证 remember 调用
 * 结构稳定, 避免在条件分支中调用 (与 EmptyChatListState 同款约束)。
 */
@Composable
fun BreathingEmptyState(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "emptyStateBreathing")
    val iconScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "emptyIconScale"
    )
    // 副标题错相呼吸: 半透明 ↔ 全亮, 周期 3s (与图标 2s 交错, 不机械同步)
    val subtitleAlpha by infiniteTransition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "emptySubtitleAlpha"
    )

    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .size(72.dp)
                .graphicsLayer(scaleX = iconScale, scaleY = iconScale)
        )
        Spacer(modifier = Modifier.size(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.size(8.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.graphicsLayer { alpha = subtitleAlpha }
        )
    }
}
