package com.engine.ui.screen

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GroupAdd
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
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.engine.EngineApp
import com.engine.data.BoundIdentityStore
import com.engine.ui.components.ChatListItem
import com.engine.ui.components.EngineNavBar
import com.engine.ui.components.EngineSettingsDrawer
import com.engine.ui.components.OrbButton
import com.engine.ui.theme.EngineLeafShape
import com.engine.ui.theme.EngineMotion
import com.engine.viewmodel.ChatListViewModel

/**
 * 聊天列表主屏幕 (Telegram 2026 风格 + Material 3)
 *
 * - 顶部: TopAppBar 标题 "ENGINE" + 左变色球(设置) + 右 🔍/🔥
 * - v3.11: ⋮ 菜单移除; 搜索上移顶栏; FAB 双选项; Spark 三卡片
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
    var showNewGroupDialog by remember { mutableStateOf(false) }
    var showSparkSheet by remember { mutableStateOf(false) }
    // v3.11: ⋮ 菜单移除, 改为 🔥 直达 Spark / 变色球直达设置抽屉
    var showSettings by remember { mutableStateOf(false) }
    // v3.11: FAB 双选项展开态 (Telegram 式上滑)
    var fabExpanded by remember { mutableStateOf(false) }
    // v3.11: 顶栏搜索态 (常驻搜索框移除, 点击 🔍 展开)
    var searchMode by rememberSaveable { mutableStateOf(false) }
    // 绑定指纹: 设置抽屉 DID/短码数据源
    val boundFingerprint = remember {
        BoundIdentityStore(app).getBoundFingerprint()
    }

    // v3.8: 状态栏颜色与渐变顶端同色, 统一由 EngineSyncedTheme 管理
    // (原此处的 window.statusBarColor 手写块已删除, 避免双写跳变)

    EngineSettingsDrawer(
        isOpen = showSettings,
        onClose = { showSettings = false },
        isDark = app.isDarkTheme,
        onToggleTheme = { app.toggleTheme() },
        fingerprint = boundFingerprint,
        onOpenDid = { /* TODO(v3.12): DID 详情页 */ },
        onOpenGroups = { /* TODO(v3.12): 群组管理页 */ },
        onOpenMarks = { /* TODO(v3.12): 标记物列表 */ },
    ) {
    Scaffold(
        // v3.8: 透明底 —— 让 EngineBackground 的全局渐变一路铺到屏幕顶
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                // 顶栏透明: 状态栏 (同渐变顶端色) / 顶栏 / 内容连成一片
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    // v3.9: 滚动态也透明 —— 默认值是不透明 surfaceContainer,
                    // 亮色下滚动时会以灰块盖住渐变 (深色两色相近不显)
                    scrolledContainerColor = Color.Transparent
                ),
                // v3.11: 左变色球+右(🔍🔥) —— 左侧补 48dp 透明触区
                // 保持两侧 96dp 对称, ENGINE 标题绝对居中不被挤偏
                navigationIcon = {
                    Row {
                        OrbButton(onClick = { showSettings = true })
                        Box(modifier = Modifier.size(48.dp))
                    }
                },
                title = {
                    // v3.11: 搜索态 —— 标题与搜索框交叉过渡 (Telegram 式),
                    // 常驻搜索框移除, 列表多一行内容高度
                    AnimatedContent(
                        targetState = searchMode,
                        transitionSpec = {
                            (fadeIn(tween(180)) togetherWith fadeOut(tween(180)))
                        },
                        label = "topBarMode"
                    ) { searching ->
                        if (searching) {
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                placeholder = {
                                    Text("@指纹短码 或 昵称", style = MaterialTheme.typography.bodyMedium)
                                },
                                singleLine = true,
                                shape = RoundedCornerShape(22.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(end = 4.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    unfocusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                                    focusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                                    unfocusedBorderColor = Color.Transparent,
                                    focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                                ),
                                trailingIcon = {
                                    if (searchQuery.isNotEmpty()) {
                                        IconButton(onClick = { searchQuery = "" }) {
                                            Icon(Icons.Filled.Close, contentDescription = "清空")
                                        }
                                    }
                                }
                            )
                        } else {
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
                        }
                    }
                },
                actions = {
                    // v3.11: ⋮ 三点移除 → 🔍 进搜索态 + 🔥 直达 Spark
                    if (searchMode) {
                        TextButton(onClick = {
                            searchMode = false
                            searchQuery = ""
                        }) { Text("取消") }
                    } else {
                        IconButton(onClick = { searchMode = true }) {
                            Icon(
                                imageVector = Icons.Filled.Search,
                                contentDescription = "搜索"
                            )
                        }
                        IconButton(onClick = { showSparkSheet = true }) {
                            Icon(
                                imageVector = Icons.Filled.LocalFireDepartment,
                                contentDescription = "Spark",
                                // v3.7 火焰橙品牌色 (tertiary)
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.size(26.dp)
                            )
                        }
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
            // v3.11: FAB 双选项 (b 模式) —— 点击向上展开两个小圆钮
            //   · 👥 添加联系人 → 跳联系人 Tab
            //   · 👥+ 新建群组  → 群组对话框 (群组协议 TODO #2)
            // FAB 图标旋转 45° 变 ✕; 再点收起。展开态点遮罩也收起。
            // v3.10 无影语言延续: 全部圆钮 shadowElevation=0 + 发丝描边
            AnimatedVisibility(
                visible = selectedTab == 0,
                enter = scaleIn(
                    initialScale = 0.5f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioLowBouncy,
                        stiffness = Spring.StiffnessMedium
                    )
                ) + fadeIn(tween(120)),
                exit = scaleOut(
                    targetScale = 0.5f,
                    animationSpec = tween(120)
                ) + fadeOut(tween(120))
            ) {
                Box(contentAlignment = Alignment.BottomCenter) {
                    // 展开项: 自下而上错峰弹出
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        FabAction(
                            visible = fabExpanded,
                            label = "新建群组",
                            icon = Icons.Filled.GroupAdd,
                            onClick = {
                                fabExpanded = false
                                showNewGroupDialog = true
                            }
                        )
                        FabAction(
                            visible = fabExpanded,
                            label = "添加联系人",
                            icon = Icons.Filled.PersonAdd,
                            delayMillis = 40,
                            onClick = {
                                fabExpanded = false
                                selectedTab = 1
                            }
                        )
                    }
                    // 主 FAB: 展开时图标旋转 45° (+→✕)
                    val fabRotate by animateFloatAsState(
                        targetValue = if (fabExpanded) 45f else 0f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessMedium,
                        ),
                        label = "fabRotate",
                    )
                    FloatingActionButton(
                        onClick = { fabExpanded = !fabExpanded },
                        shadowElevation = 0.dp,
                        border = BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = if (fabExpanded) "收起" else "新建",
                            modifier = Modifier.graphicsLayer { rotationZ = fabRotate },
                        )
                    }
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

    // Spark 底部弹窗 (v3.11: 三卡片重构, 燃料讲解文字全部移除)
    // 卡片可上下滑动; 余额为本地 mock, 服务端 (VPS) 对接后接真数据
    if (showSparkSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showSparkSheet = false },
            sheetState = sheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 拖动条下的小标题行: 火焰 + Spark
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.LocalFireDepartment,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Spark",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold,
                    )
                }
                Spacer(Modifier.height(20.dp))

                // ── 卡片 ① 剩余额度 ─────────────────────────────
                // mock 余额; 每日凌晨自动领取 1000 (TODO #2 服务端)
                SparkCard(gradient = true) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "剩余额度",
                                style = MaterialTheme.typography.labelLarge,
                                color = Color.White.copy(alpha = 0.85f),
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "1,000",
                                style = MaterialTheme.typography.displaySmall,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color.White,
                            )
                            Text(
                                text = "\$SPARK",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White.copy(alpha = 0.85f),
                            )
                        }
                        Icon(
                            imageVector = Icons.Filled.LocalFireDepartment,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.9f),
                            modifier = Modifier.size(56.dp)
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))

                // ── 卡片 ② 充值 (占位: 钱包对接后启用) ───────────
                SparkCard(onClick = null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "充值",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = "即将支持钱包接入",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            text = "即将上线",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                )
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))

                // ── 卡片 ③ 官网 (占位: URL 待产品方提供) ─────────
                val sparkSiteUrl = "https://spark.engine.app" // TODO: 换正式域名
                val ctx = LocalContext.current
                SparkCard(onClick = {
                    ctx.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(sparkSiteUrl))
                    )
                }) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Spark 官网",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = sparkSiteUrl.removePrefix("https://"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
    } // EngineSettingsDrawer content
}

/**
 * v3.11 Spark 弹窗卡片容器: 品牌渐变 / 纯平两种模式。
 * 无投影 (v3.10 语言), 层次靠留白与色块对比。
 */
@Composable
private fun SparkCard(
    gradient: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val shape = EngineLeafShape
    val base = if (gradient) {
        Modifier.clip(shape).background(
            Brush.linearGradient(
                listOf(
                    MaterialTheme.colorScheme.primary,
                    MaterialTheme.colorScheme.tertiary,
                )
            )
        )
    } else {
        Modifier.clip(shape).background(
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        )
    }
    val clickable = if (onClick != null) {
        Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick,
        )
    } else Modifier
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(base)
            .then(clickable)
            .padding(horizontal = 20.dp, vertical = 18.dp)
    ) { content() }
}

/**
 * v3.11 FAB 展开项: 小圆钮 + 右侧标签, 弹簧弹出 (可错峰 delay)。
 */
@Composable
private fun FabAction(
    visible: Boolean,
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    delayMillis: Int = 0,
) {
    androidx.compose.animation.AnimatedVisibility(
        visible = visible,
        enter = scaleIn(
            initialScale = 0.4f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium,
                visibilityThreshold = 0.01f,
            ),
        ) + fadeIn(tween(120, delayMillis = delayMillis)),
        exit = scaleOut(targetScale = 0.4f, animationSpec = tween(120)) +
            fadeOut(tween(120)),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .padding(bottom = 14.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick,
                )
                .padding(end = 14.dp),
        ) {
            SmallFloatingActionButton(
                onClick = onClick,
                shadowElevation = 0.dp,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

/**
 * 聊天列表内容
 *
 * v3.11: 常驻搜索框移除 —— 搜索入口上移到顶栏 (点击 🔍 展开),
 * 列表多出一行内容高度; @指纹短码 / 昵称过滤逻辑不变
 */
@Composable
private fun ChatListContent(
    uiState: com.engine.viewmodel.ChatListUiState,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onChatClick: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // 非搜索态下顶栏不显示输入框, 但保留空态提示
        if (searchQuery.isNotBlank()) {
            Text(
                text = "搜索: $searchQuery",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )
        }

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
