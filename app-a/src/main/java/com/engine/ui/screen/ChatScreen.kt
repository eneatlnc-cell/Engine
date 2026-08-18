package com.engine.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.engine.EngineApp
import com.engine.data.MessageStatus
import com.engine.network.ConnectionState
import com.engine.ui.components.ConnectionStatusBar
import com.engine.ui.components.MessageBubble
import com.engine.viewmodel.ChatViewModel

/**
 * 聊天页面 (Material Design 3 风格)
 *
 * - 顶部: 对方昵称 + 连接状态
 * - 消息流: 消息气泡列表 (LazyColumn)
 * - 底部: 输入框 + 发送按钮 (M3 primary 圆形图标)
 * - 离线提示: "对方离线, 消息未送达" + 重发按钮
 * - 退出页面时释放消息引用 (ViewModel.onCleared)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    navController: NavController,
    peerFingerprint: String
) {
    val viewModel: ChatViewModel = viewModel(
        factory = ChatViewModel.factory(peerFingerprint)
    )
    val uiState by viewModel.uiState.collectAsState()

    // 获取对方昵称
    val contact = EngineApp.get().contactStore.getContact(peerFingerprint)
    val peerName = contact?.nickname ?: "用户 ${peerFingerprint.take(8)}"

    val listState = rememberLazyListState()

    // 新消息到达时自动滚动到底部
    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.size - 1)
        }
    }

    Scaffold(
        // v3.8: 透明底 —— 全局渐变背景 (EngineBackground) 一路铺到顶
        containerColor = Color.Transparent,
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(peerName, fontWeight = FontWeight.SemiBold) },
                    navigationIcon = {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回"
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent
                    )
                )
                ConnectionStatusBar(state = uiState.connectionState)
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // 消息列表
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                state = listState,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    top = 8.dp,
                    bottom = 8.dp
                )
            ) {
                items(uiState.messages) { message ->
                    // 离线提示 + 重发按钮
                    if (message.isMine && message.status == MessageStatus.FAILED) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "对方离线, 消息未送达",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.size(4.dp))
                            TextButton(
                                onClick = { viewModel.resendMessage(message.id) }
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Refresh,
                                    contentDescription = "重发",
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.size(4.dp))
                                Text("重发", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }

                    MessageBubble(message = message)
                }
            }

            // 底部输入栏
            InputBar(
                text = uiState.inputText,
                onTextChange = { viewModel.updateInputText(it) },
                onSend = { viewModel.sendMessage(it) },
                enabled = uiState.connectionState == ConnectionState.CONNECTED
            )
        }
    }
}

/**
 * 底部输入栏 (Material Design 3 风格)
 *
 * - 底部固定
 * - 发送按钮为 M3 primary 圆形图标
 */
@Composable
private fun InputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: (String) -> Unit,
    enabled: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 8.dp, vertical = 8.dp)
            .navigationBarsPadding()
            .imePadding(),
        verticalAlignment = Alignment.Bottom
    ) {
        // 输入框 (默认 M3 颜色)
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(24.dp)),
            placeholder = { Text("输入消息…", color = MaterialTheme.colorScheme.onSurfaceVariant) },
            maxLines = 4,
            shape = RoundedCornerShape(24.dp)
        )

        Spacer(modifier = Modifier.size(8.dp))

        // 发送按钮 (M3 primary 圆形)
        IconButton(
            onClick = { onSend(text) },
            enabled = enabled && text.isNotBlank(),
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(
                    if (enabled && text.isNotBlank()) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant
                )
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Send,
                contentDescription = "发送",
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}
