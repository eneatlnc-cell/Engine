package com.engine.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalIndication
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.engine.data.Contact
import com.engine.ui.theme.EngineMotion
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 聊天列表项组件 (Material Design 3 风格)
 *
 * 布局: 头像 + (昵称 + 最后消息) + (时间戳 + 未读角标)
 *
 * v3.6 动效:
 * - 错峰入场: 进入视口时淡入 + 上浮落定 (stagger, 由父列表按 index 派发延迟)
 * - 按压回弹: 按下轻微缩放, 松手弹簧回弹 —— 触觉 "活" 感
 */
@Composable
fun ChatListItem(
    contact: Contact,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enterDelayMs: Int = 0
) {
    // ---- v3.6: 错峰入场动画 ----
    val enterAlpha = remember { Animatable(0f) }
    val enterOffsetY = remember { Animatable(20f) }
    LaunchedEffect(Unit) {
        launch {
            enterAlpha.animateTo(
                1f,
                tween(
                    durationMillis = EngineMotion.ITEM_ENTER_MS,
                    delayMillis = enterDelayMs,
                    easing = EngineMotion.EaseOut
                )
            )
        }
        launch {
            enterOffsetY.animateTo(
                0f,
                tween(
                    durationMillis = EngineMotion.ITEM_ENTER_MS + 40,
                    delayMillis = enterDelayMs,
                    easing = EngineMotion.EaseOut
                )
            )
        }
    }

    // ---- v3.6: 按压弹性缩放 ----
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = 900f
        ),
        label = "pressScale"
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = enterAlpha.value
                translationY = enterOffsetY.value
                scaleX = pressScale
                scaleY = pressScale
            }
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current
            ) { onClick() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 头像 (v3.7: 指纹渐变 + 超圆 squircle, 替换纯色圆形)
            GradientAvatar(
                seed = contact.fingerprint,
                label = contact.nickname,
                size = 52.dp
            )

            Spacer(modifier = Modifier.width(14.dp))

            // 中间: 昵称 + 最后消息
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = contact.nickname,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = formatTime(contact.lastMessageTime),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.size(2.dp))

                Text(
                    text = contact.lastMessage ?: "暂无消息",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (contact.lastMessage != null)
                        MaterialTheme.colorScheme.onSurfaceVariant
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        HorizontalDivider(
            modifier = Modifier.padding(start = 82.dp),
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
        )
    }
}

/**
 * 格式化时间戳
 */
private fun formatTime(timestamp: Long?): String {
    if (timestamp == null) return ""
    val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}
