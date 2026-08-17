package com.engine.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.engine.network.ConnectionState
import com.engine.ui.theme.StatusConnected
import com.engine.ui.theme.StatusConnecting
import com.engine.ui.theme.StatusDisconnected
import com.engine.ui.theme.StatusError

/**
 * 连接状态栏 (显示中继/P2P/离线)
 *
 * 在页面顶部显示当前连接状态, 使用 Crossfade 淡入淡出切换。
 */
@Composable
fun ConnectionStatusBar(
    state: ConnectionState,
    modifier: Modifier = Modifier
) {
    val (color, text) = when (state) {
        ConnectionState.CONNECTED -> StatusConnected to "中继已连接"
        ConnectionState.CONNECTING -> StatusConnecting to "正在连接中继…"
        ConnectionState.DISCONNECTED -> StatusDisconnected to "离线"
        ConnectionState.ERROR -> StatusError to "连接错误"
    }

    Crossfade(
        targetState = state,
        label = "connectionStatus"
    ) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .background(color.copy(alpha = 0.12f))
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            // 状态指示圆点
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = text,
                color = color,
                fontSize = 13.sp,
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}
