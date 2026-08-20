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
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.engine.EngineApp
import com.engine.data.ChatMessage
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

    // 会话标题: 群会话显示 群名+成员数 (v3.14); 1:1 显示对方昵称
    val app = EngineApp.get()
    val groups by app.groupStore.groups.collectAsState()
    val groupInfo = if (viewModel.isGroup) groups.find { it.id == viewModel.peerFingerprint.removePrefix("grp:") } else null
    val peerName = groupInfo?.name
        ?: app.contactStore.getContact(peerFingerprint)?.nickname
        ?: "用户 ${peerFingerprint.take(8)}"

    val listState = rememberLazyListState()

    // v3.16: 长按弹出的标记菜单目标消息 (null = 菜单收起)
    var markMenuMessage by remember { mutableStateOf<ChatMessage?>(null) }

    // v3.20: 表情面板开合 + 贴纸全屏预览
    var showStickerPanel by remember { mutableStateOf(false) }
    var previewSticker by remember {
        mutableStateOf<com.engine.data.StickerCatalog.Sticker?>(null)
    }

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
                    title = {
                        if (groupInfo != null) {
                            // v3.19: 群标题点击 → 群详情 (成员/门禁/审批/邀请码)
                            Column(
                                modifier = Modifier.clip(RoundedCornerShape(10.dp))
                                    .clickable {
                                        navController.navigate("groupDetail/${groupInfo.id}")
                                    }
                                    .padding(horizontal = 4.dp, vertical = 2.dp)
                            ) {
                                Text(groupInfo.name, fontWeight = FontWeight.SemiBold)
                                Text(
                                    text = "${groupInfo.members.size} 名成员 ›",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            Text(peerName, fontWeight = FontWeight.SemiBold)
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回"
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        // v3.9: 滚动态也透明 (同 ChatListScreen, 见注释)
                        scrolledContainerColor = Color.Transparent
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

                    MessageBubble(
                        message = message,
                        isMarked = message.id in uiState.markedMessageIds,
                        onLongPress = { markMenuMessage = message },
                        onStickerClick = {
                            message.stickerId?.let { id ->
                                com.engine.data.StickerCatalog.findById(id)
                                    ?.let { previewSticker = it }
                            }
                        }
                    )
                }
            }

            // 底部输入栏 (v3.20: + 表情面板开合)
            InputBar(
                text = uiState.inputText,
                onTextChange = {
                    viewModel.updateInputText(it)
                    if (showStickerPanel) showStickerPanel = false
                },
                onSend = { viewModel.sendMessage(it) },
                enabled = uiState.connectionState == ConnectionState.CONNECTED,
                stickerMode = showStickerPanel,
                onToggleSticker = { showStickerPanel = !showStickerPanel }
            )

            // v3.20: Spark 表情面板 (替换键盘)
            androidx.compose.animation.AnimatedVisibility(
                visible = showStickerPanel,
                enter = androidx.compose.animation.slideInVertically { it } +
                    androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.slideOutVertically { it } +
                    androidx.compose.animation.fadeOut()
            ) {
                com.engine.ui.components.StickerPickerPanel(
                    onPick = { sticker ->
                        viewModel.sendSticker(sticker)
                        showStickerPanel = false
                    }
                )
            }
        }
    }

    // v3.20: 贴纸全屏预览 (气泡点击)
    previewSticker?.let { sticker ->
        com.engine.ui.components.StickerPreviewDialog(
            sticker = sticker,
            onDismiss = { previewSticker = null }
        )
    }

    // v3.16: 标记菜单 (长按消息弹出)
    markMenuMessage?.let { target ->
        val marked = target.id in uiState.markedMessageIds
        ModalBottomSheet(
            onDismissRequest = { markMenuMessage = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 32.dp)
            ) {
                // 消息预览 (最多两行, 交代操作对象; v3.21: 贴纸渲染为友好预览)
                Text(
                    text = com.engine.data.StickerCatalog.previewOf(target.text),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.size(20.dp))

                // 标记/取消标记 (整行可点)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .clickable {
                            viewModel.toggleMark(target)
                            markMenuMessage = null
                        }
                        .padding(horizontal = 20.dp, vertical = 16.dp)
                ) {
                    Icon(
                        imageVector = if (marked) Icons.Default.BookmarkBorder
                        else Icons.Default.Bookmark,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.size(16.dp))
                    Text(
                        text = if (marked) "取消标记" else "标记",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = if (marked) "从标记物移除" else "保存消息快照",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
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
    enabled: Boolean,
    stickerMode: Boolean = false,
    onToggleSticker: () -> Unit = {}
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
        // v3.20: 表情面板开合键 (面板展开时高亮)
        IconButton(
            onClick = onToggleSticker,
            modifier = Modifier.size(44.dp)
        ) {
            Icon(
                imageVector = Icons.Default.EmojiEmotions,
                contentDescription = "表情",
                tint = if (stickerMode) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
        }

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
