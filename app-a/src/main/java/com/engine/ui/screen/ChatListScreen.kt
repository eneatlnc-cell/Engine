package com.engine.ui.screen

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sailing
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.engine.EngineApp
import com.engine.ui.components.ChatListItem
import com.engine.ui.components.EngineNavBar
import com.engine.ui.components.EngineTopMenu
import com.engine.ui.theme.EngineLeafShape
import com.engine.ui.theme.EngineMotion
import com.engine.viewmodel.ChatListViewModel

/**
 * 聊天列表主屏幕 (Telegram 2026 风格 + Material 3)
 *
 * - 顶部: TopAppBar 标题 "Engine" + 三点菜单 (夜间模式 / 新建群组 / Spark)
 * - 聊天列表上方: 全宽搜索框 (过滤聊天)
 * - 底部导航: 聊天 / 联系人 / 密钥
 * - 浮动按钮: 新建联系人 / 搜索
 *
 * v3.8:
 * - Scaffold/TopAppBar 透明化, 全局渐变背景 (EngineBackground) 一路铺到顶
 * - 三点菜单 → EngineTopMenu (不透明无投影, 修 "变色有影子")
 * - 底部导航 → EngineNavBar (点开变大 / 退出变小)
 * - 状态栏颜色统一由 EngineSyncedTheme 管理 (与渐变顶端同色)
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

    // v3.8: 状态栏颜色与渐变顶端同色, 统一由 EngineSyncedTheme 管理
    // (原此处的 window.statusBarColor 手写块已删除, 避免双写跳变)

    Scaffold(
        // v3.8: 透明底 —— 让 EngineBackground 的全局渐变一路铺到屏幕顶
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                // 顶栏透明: 状态栏 (同渐变顶端色) / 顶栏 / 内容连成一片
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                ),
                // X (Twitter) 式居中标识: 两侧各占 48dp (左占位 + 右菜单按钮),
                // 标题在剩余空间内绝对居中; 大写 + 加宽字距强化品牌感
                navigationIcon = {
                    Box(modifier = Modifier.size(48.dp))
                },
                title = {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "ENGINE",
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 3.sp,
                            style = MaterialTheme.typography.titleLarge
                        )
                    }
                },
                actions = {
                    // 三点菜单 + 自绘下拉面板 (v3.8)
                    Box {
                        IconButton(onClick = { showOverflowMenu = true }) {
                            Icon(
                                imageVector = Icons.Filled.MoreVert,
                                contentDescription = "菜单"
                            )
                        }
                        EngineTopMenu(
                            expanded = showOverflowMenu,
                            onDismiss = { showOverflowMenu = false },
                            isDark = app.isDarkTheme,
                            onToggleTheme = { app.toggleTheme() },
                            onNewGroup = { showNewGroupDialog = true },
                            onSpark = { showSparkSheet = true }
                        )
                    }
                }
            )
        },
        bottomBar = {
            // v3.8: 点开变大 / 退出变小 (三态弹簧缩放, 见 EngineNavBar)
            EngineNavBar(
                selected = selectedTab,
                onSelect = { selectedTab = it }
            )
        },
        floatingActionButton = {
            // v3.6: FAB 弹簧出入场 (旧版切 Tab 时瞬现瞬灭)
            AnimatedVisibility(
                visible = selectedTab == 0,
                enter = scaleIn(
                    initialScale = 0.6f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMedium
                    )
                ) + fadeIn(),
                exit = scaleOut(
                    targetScale = 0.6f,
                    animationSpec = tween(140)
                ) + fadeOut(tween(140))
            ) {
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
            // v3.6: Tab 内容交叉淡入 + 轻微缩放 (旧版 when 硬切)
            AnimatedContent(
                targetState = selectedTab,
                transitionSpec = {
                    (fadeIn(tween(EngineMotion.CONTENT_CROSSFADE_MS)) +
                        scaleIn(
                            initialScale = 0.98f,
                            animationSpec = tween(EngineMotion.CONTENT_CROSSFADE_MS)
                        )) togetherWith
                        (fadeOut(tween(EngineMotion.CONTENT_CROSSFADE_MS)) +
                            scaleOut(
                                targetScale = 0.98f,
                                animationSpec = tween(EngineMotion.CONTENT_CROSSFADE_MS)
                            ))
                },
                label = "tabContent"
            ) { tab ->
                when (tab) {
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

    // Spark 底部弹窗 (v3.7: 火焰橙品牌化 + 不对称叶片卡片)
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
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(48.dp)
                )
                Spacer(Modifier.size(12.dp))
                Text(
                    text = "Spark",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.tertiary
                )
                Spacer(Modifier.size(16.dp))

                // 计费卡片: 电流渐变 + 叶片形 (M3E 有机形状)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(EngineLeafShape)
                        .background(
                            Brush.linearGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.primary,
                                    MaterialTheme.colorScheme.tertiary
                                )
                            )
                        )
                        .padding(horizontal = 24.dp, vertical = 20.dp)
                ) {
                    Column {
                        Text(
                            text = "10 ⚡ / 条",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White
                        )
                        Spacer(Modifier.size(4.dp))
                        Text(
                            text = "单条 ≤ 1KB · 文字 / 图片 / 链接同价",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.9f)
                        )
                    }
                }

                Spacer(Modifier.size(16.dp))
                Text(
                    text = "Spark 是 Engine 的燃料\n每次发送消息需要消耗 Spark\n未来可用于激励节点中继和群组广播",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
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
                itemsIndexed(
                    filteredList,
                    key = { _, contact -> contact.fingerprint }
                ) { index, contact ->
                    ChatListItem(
                        contact = contact,
                        // v3.6: 错峰入场 —— 逐项淡入上浮 (stagger)
                        enterDelayMs = (index.coerceAtMost(EngineMotion.ITEM_STAGGER_MAX)) *
                            EngineMotion.ITEM_STAGGER_MS,
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
