package com.engine.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.engine.data.ChatMessage
import com.engine.data.MessageStatus
import com.engine.ui.theme.MessageFailed
import com.engine.ui.theme.MessagePending
import com.engine.ui.theme.MessageDelivered
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 消息气泡 (Material Design 3 风格)
 *
 * - 己方: primary 底 onPrimary 字, 右对齐
 * - 对方: 浅灰底黑字, 左对齐
 * - 圆角矩形, animateContentSize 弹簧动画
 * - v3.16: 长按触发 [onLongPress] (标记菜单); 已标记消息
 *   时间行前显示书签角标 (primary 色, 与发送状态图标同排)
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    message: ChatMessage,
    modifier: Modifier = Modifier,
    isMarked: Boolean = false,
    onLongPress: (() -> Unit)? = null
) {
    val isMine = message.isMine

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp),
        horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start
    ) {
        // 对方消息: 左侧头像占位
        if (!isMine) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "?",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.size(6.dp))
        }

        Column(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .wrapContentWidth(if (isMine) Alignment.End else Alignment.Start)
                .clip(
                    RoundedCornerShape(
                        topStart = 12.dp,
                        topEnd = 12.dp,
                        bottomStart = if (isMine) 12.dp else 4.dp,
                        bottomEnd = if (isMine) 4.dp else 12.dp
                    )
                )
                .background(
                    if (isMine) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant
                )
                .combinedClickable(
                    onClick = {},
                    onLongClick = { onLongPress?.invoke() }
                )
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .animateContentSize(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessLow
                    )
                )
        ) {
            // 消息文本
            Text(
                text = message.text,
                color = if (isMine) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurface,
                fontSize = 15.sp,
                maxLines = Int.MAX_VALUE,
                overflow = TextOverflow.Visible
            )

            Spacer(modifier = Modifier.size(4.dp))

            // 时间戳 + 状态图标 (v3.16: + 书签角标)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.fillMaxWidth()
            ) {
                // v3.16: 已标记书签角标 (时间行首位)
                if (isMarked) {
                    Icon(
                        imageVector = Icons.Default.Bookmark,
                        contentDescription = "已标记",
                        modifier = Modifier.size(13.dp),
                        tint = if (isMine) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.size(3.dp))
                }
                Text(
                    text = formatMessageTime(message.timestamp),
                    color = if (isMine) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    fontSize = 11.sp
                )
                if (isMine) {
                    Spacer(modifier = Modifier.size(4.dp))
                    StatusIcon(message.status)
                }
            }
        }
    }
}

/**
 * 消息状态图标
 */
@Composable
private fun StatusIcon(status: MessageStatus) {
    when (status) {
        MessageStatus.PENDING -> {
            Icon(
                imageVector = Icons.Default.Schedule,
                contentDescription = "发送中",
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
            )
        }
        MessageStatus.SENT -> {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "已发送",
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
            )
        }
        MessageStatus.DELIVERED -> {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = "已送达",
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
            )
        }
        MessageStatus.FAILED -> {
            Icon(
                imageVector = Icons.Default.Error,
                contentDescription = "发送失败",
                modifier = Modifier.size(14.dp),
                tint = MessageFailed
            )
        }
    }
}

/**
 * 格式化消息时间
 */
private fun formatMessageTime(timestamp: Long): String {
    val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}
