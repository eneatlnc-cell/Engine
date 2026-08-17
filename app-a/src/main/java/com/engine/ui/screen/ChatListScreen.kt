package com.engine.ui.screen

import android.app.Activity
import androidx.compose.animation.core.*
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brightness4
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.Brightness7
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sailing
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.engine.EngineApp
import com.engine.ui.components.ChatListItem
import com.engine.viewmodel.ChatListViewModel

/**
 * 底部导航项
 */
private data class BottomNavItem(
    val label: String,
    val icon: ImageVector
)

private val bottomNavItems = listOf(
    BottomNavItem("聊天", Icons.Filled.Chat),
    BottomNavItem("联系人", Icons.Filled.Contacts),
    BottomNavItem("密钥", Icons.Filled.Key)
)

/**
 * 聊天列表主屏幕 (Telegram 2026 风格 + Material 3)
 *
 * - 顶部: TopAppBar 标题 "Engine" + 三点菜单 (夜间模式 / 新建群组 / Spark)
 * - 聊天列表上方: 全宽搜索框 (过滤聊天)
 * - 底部导航: 聊天 / 联系人 / 密钥
 * - 浮动按钮: 新建联系人 / 搜索
 *
 * 设计参考:
 * - Telegram v12.4 (2026): 全宽搜索框置顶, 滚动隐藏
 * - M3 Expressive: 圆角容器, 药丸形控件
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(
    navController: NavController,
    chatListViewModel: ChatListViewModel = viewModel()
) {
    val app = LocalContext.current.applicationContext as EngineApp
    val uiState by chatListViewModel.uiState.collectAsState()
    // 登录卡片 "重新绑定密钥" 确认后直达密钥绑定 Tab, 避免用户迷失在聊天页
    var selectedTab by rememberSaveable {
        mutableIntStateOf(if (app.pendingRebindToKeys) 2 else 0)
    }
    LaunchedEffect(Unit) {
        if (app.pendingRebindToKeys) app.pendingRebindToKeys = false
        chatListViewModel.ensureIdentity()
    }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var showNewGroupDialog by remember { mutableStateOf(false) }
    var showSparkSheet by remember { mutableStateOf(false) }

    // 状态栏跟随主题
    val view = LocalView.current
    val darkTheme = isSystemInDarkTheme()
    val surfaceColor = MaterialTheme.colorScheme.surface
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = surfaceColor.toArgb()
            WindowCompat.getInsetsController(window, view)
                .isAppearanceLightStatusBars = !darkTheme
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Engine",
                        fontWeight = FontWeight.Bold
                    )
                },
                actions = {
                    // 三点菜单
                    Box {
                        IconButton(onClick = { showOverflowMenu = true }) {
                            Icon(
                                imageVector = Icons.Filled.MoreVert,
                                contentDescription = "菜单"
                            )
                        }
                        DropdownMenu(
                            expanded = showOverflowMenu,
                            onDismissRequest = { showOverflowMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Text(if (app.isDarkTheme) "切换白天模式" else "切换夜间模式")
                                },
                                onClick = {
                                    showOverflowMenu = false
                                    app.toggleTheme()
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = if (app.isDarkTheme) Icons.Filled.Brightness7 else Icons.Filled.Brightness4,
                                        contentDescription = null
                                    )
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("新建群组") },
                                onClick = {
                                    showOverflowMenu = false
                                    showNewGroupDialog = true
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Filled.GroupAdd,
                                        contentDescription = null
                                    )
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "Spark",
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                },
                                onClick = {
                                    showOverflowMenu = false
                                    showSparkSheet = true
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Filled.LocalFireDepartment,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            )
                        }
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                bottomNavItems.forEachIndexed { index, item ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        icon = { Icon(item.icon, contentDescription = item.label) },
                        label = { Text(item.label) }
                    )
                }
            }
        },
        floatingActionButton = {
            if (selectedTab == 0) {
                FloatingActionButton(
                    onClick = { selectedTab = 1 }
                ) {
                    Icon(Icons.Filled.PersonAdd, contentDescription = "添加联系人")
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (selectedTab) {
                0 -> ChatListContent(
                    uiState = uiState,
                    searchQuery = searchQuery,
                    onSearchQueryChange = { searchQuery = it },
                    onChatClick = { fingerprint ->
                        navController.navigate("chat/$fingerprint")
                    }
                )
                1 -> ContactsScreen()
                2 -> KeyBindingScreen(
                    onSuccess = {
                        selectedTab = 0
                    }
                )
            }
        }
    }

    // 新建群组对话框 (占位)
    if (showNewGroupDialog) {
        AlertDialog(
            onDismissRequest = { showNewGroupDialog = false },
            title = { Text("新建群组") },
            text = {
                Text(
                    "群组功能即将上线\n通过群组标签 (RoomTag) 创建临时广播频道",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                TextButton(onClick = { showNewGroupDialog = false }) {
                    Text("知道了")
                }
            }
        )
    }

    // Spark 底部弹窗
    if (showSparkSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showSparkSheet = false },
            sheetState = sheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Filled.LocalFireDepartment,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(48.dp)
                )
                Spacer(Modifier.size(12.dp))
                Text(
                    text = "Spark",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    text = "Spark 是 Engine 的燃料\n每次发送消息需要消耗 Spark\n未来可用于激励节点中继和群组广播",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                Spacer(Modifier.size(24.dp))
                TextButton(onClick = { showSparkSheet = false }) {
                    Text("关闭")
                }
            }
        }
    }
}

/**
 * 聊天列表内容 (含搜索框)
 *
 * 参考 Telegram 2026 UI: 全宽搜索框固定在列表顶部
 */
@Composable
private fun ChatListContent(
    uiState: com.engine.viewmodel.ChatListUiState,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onChatClick: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // 搜索框 (Telegram 风格: 全宽, 圆角, 置顶)
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text("@用户名") },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchQueryChange("") }) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "清除",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(24.dp)
        )

        // 聊天列表 (过滤后)
        // Telegram 风格搜索:
        // - 以 "@" 开头: 按昵称模糊搜索 (去除 "@" 前缀, 忽略大小写)
        // - 否则: 按指纹模糊搜索 (忽略大小写)
        // - 空白查询: 显示全部
        val filteredList = when {
            searchQuery.isBlank() -> uiState.chatList
            searchQuery.startsWith("@") -> {
                val usernameQuery = searchQuery.removePrefix("@")
                uiState.chatList.filter { contact ->
                    contact.nickname.contains(usernameQuery, ignoreCase = true)
                }
            }
            else -> {
                uiState.chatList.filter { contact ->
                    contact.fingerprint.contains(searchQuery, ignoreCase = true)
                }
            }
        }

        if (filteredList.isEmpty()) {
            if (searchQuery.isBlank()) {
                // 空会话列表: 呼吸动画的帆船图标 (匹配 Engine 新 Logo)
                EmptyChatListState()
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "未找到匹配的会话",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize()
            ) {
                items(filteredList) { contact ->
                    ChatListItem(
                        contact = contact,
                        onClick = { onChatClick(contact.fingerprint) }
                    )
                }
            }
        }
    }
}

/**
 * 空会话列表状态: 呼吸动画的帆船图标 (匹配 Engine 新 Logo)
 *
 * 灵感来自 Telegram 截图: 空状态使用带动画的吉祥物插图, 营造"鲜活"的 UI 感受。
 * 将动画逻辑独立为 composable, 保证 remember 调用结构稳定, 避免在条件分支中调用。
 */
@Composable
private fun EmptyChatListState() {
    // 呼吸动画: 图标缩放 1.0 ↔ 1.1, 每段 1000ms + Reverse => 完整呼吸周期 2 秒
    val infiniteTransition = rememberInfiniteTransition(label = "chatListBreathing")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "sailScale"
    )

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Filled.Sailing,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(72.dp)
                    .graphicsLayer(scaleX = scale, scaleY = scale)
            )
            Spacer(modifier = Modifier.size(16.dp))
            Text(
                text = "暂无会话, 请先添加联系人",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.size(8.dp))
            Text(
                text = "开始你的安全通讯之旅",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
