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
import androidx.compose.foundation.border
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
import androidx.compose.material3.FloatingActionButtonDefaults
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.engine.EngineApp
import com.engine.data.BoundIdentityStore
import com.engine.data.FingerprintInput
import com.engine.ui.components.BreathingEmptyState
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
    // v3.13 修复 "绑定后抽屉仍显示未绑定": v3.11 用 remember{} 一次性读取,
    // 绑定完成回到本页时组合已缓存旧值 (null)。现改为:
    //   · ON_RESUME 重读 (与 LoginGate 同款生命周期刷新)
    //   · 每次抽屉打开前重读 (即时生效)
    var boundFingerprint by remember {
        mutableStateOf(BoundIdentityStore(app).getBoundFingerprint())
    }
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                boundFingerprint = BoundIdentityStore(app).getBoundFingerprint()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    androidx.compose.runtime.LaunchedEffect(showSettings) {
        if (showSettings) {
            boundFingerprint = BoundIdentityStore(app).getBoundFingerprint()
        }
    }

    // v3.22: 本机档案 (DID 页) —— 抽屉头部显示昵称 + 头像
    val profile by app.profileStore.profile.collectAsState()
    val drawerAvatar = remember(profile.avatarVersion) {
        app.profileStore.decodeAvatar()?.asImageBitmap()
    }

    // v3.8: 状态栏颜色与渐变顶端同色, 统一由 EngineSyncedTheme 管理
    // (原此处的 window.statusBarColor 手写块已删除, 避免双写跳变)

    EngineSettingsDrawer(
        isOpen = showSettings,
        onClose = { showSettings = false },
        isDark = app.isDarkTheme,
        onToggleTheme = { app.toggleTheme() },
        fingerprint = boundFingerprint,
        // v3.22: DID 身份页已落地 (头像设置 + 昵称修改)
        onOpenDid = { navController.navigate("did") },
        // v3.14: 群组管理页已落地
        onOpenGroups = { navController.navigate("groups") },
        // v3.16: 标记物列表页已落地
        onOpenMarks = { navController.navigate("marks") },
        // v3.22: 抽屉头部展示本机档案 (昵称 + 头像)
        nickname = profile.nickname,
        avatar = drawerAvatar,
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
                                    // v3.23: 搜索凭据唯一化 —— 仅指纹
                                    // (昵称/@短码语法移除, 用户决策)
                                    Text("输入指纹搜索 / 添加", style = MaterialTheme.typography.bodyMedium)
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
                                // v3.7 火焰橙品牌色 (tertiary); v3.12: 26→30dp 加大
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.size(30.dp)
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
            // v3.10 无影语言: 全部圆钮 elevation 全零; 药丸保留发丝描边
            // (描边与裁剪同形 18dp), 主 FAB 无描边 (v3.22.2 撤圆形描边)
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
                // v3.22.1 重影根修: BottomCenter → BottomEnd。
                // 真因 (弹簧/投影均不是): 药丸经 AnimatedVisibility 出入,
                // scaleIn/Out 只动画绘制层, 布局尺寸在可见首帧即全量占位 ——
                // Box 宽度 56dp→~144dp 瞬间跳变, BottomCenter 令主 FAB
                // 随之水平瞬移 ~44dp (展开左跳 / 收起在药丸淡出后才右弹),
                // 肉眼即 "重影"。锚定 BottomEnd 后 FAB 屏幕位置与药丸
                // 布局尺寸彻底解耦, 恒定不动; 药丸仍右对齐于 FAB 正上方。
                Box(contentAlignment = Alignment.BottomEnd) {
                    // v3.12: 展开列垫高到主 FAB 上方 (bottom = FAB 56dp + 16dp 间隙)。
                    //   v3.11 病灶: Column 与主 FAB 同底边对齐, 展开后 Column
                    //   底部 56dp 直接压在 FAB 圆面上 —— 视频确认的 "重叠展示"。
                    //   现在药丸严格从 FAB 顶缘向上生长, 右对齐贴边。
                    Column(
                        horizontalAlignment = Alignment.End,
                        modifier = Modifier.padding(bottom = 72.dp)
                    ) {
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
                    // LowBouncy 轻过冲纯打磨项; v3.22.1 确认重影真因
                    // 是父级 Box 的 BottomCenter 布局瞬移, 与弹簧无关
                    val fabRotate by animateFloatAsState(
                        targetValue = if (fabExpanded) 45f else 0f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioLowBouncy,
                            stiffness = Spring.StiffnessMedium,
                        ),
                        label = "fabRotate",
                    )
                    FloatingActionButton(
                        onClick = { fabExpanded = !fabExpanded },
                        // v3.10 无影语言: 四态 elevation 全零
                        elevation = FloatingActionButtonDefaults.elevation(
                            defaultElevation = 0.dp,
                            pressedElevation = 0.dp,
                            focusedElevation = 0.dp,
                            hoveredElevation = 0.dp,
                        ),
                        // v3.22.2 重影根修: 撤掉 CircleShape 发丝描边 (用户实拍定位)。
                        // 真凶: 容器是 M3E 超圆方形 (m3 1.2.0 默认
                        // FloatingActionButtonDefaults.shape → 主题 shapes.large
                        // = 24dp 圆角), 描边却是正圆 —— 圆内切于方, 四角弧瓣
                        // 各超出圆 ~6.6dp, 两套轮廓叠出 "重影"。
                        // 考古: v3.10 写的是 M2 式 border= 参数 (M3 FAB 无此参数,
                        // 从未编译过); v3.16 首次编译改成 Modifier+CircleShape
                        // 才真正上屏 —— 重影自首个可安装包即存在, 弹簧(v3.22.0)/
                        // 对齐(v3.22.1)两轮修复均未触及。M3E FAB 本就无描边,
                        // 容器色块自成层次。
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
                        },
                        // v3.23: 搜索框添加路径 —— 完整指纹且通讯录无此人时
                        // 结果卡可直达添加并开聊 (用户诉求 "搜索框能加联系人")
                        onAddContact = { fingerprint ->
                            app.contactStore.addContact(
                                fingerprint,
                                "用户 ${fingerprint.take(8)}"
                            )
                            searchMode = false
                            searchQuery = ""
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

    // 新建群组对话框 (v3.14: 真实流程 —— 群名/选成员/邀请码)
    if (showNewGroupDialog) {
        NewGroupDialog(onDismiss = { showNewGroupDialog = false })
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
            // LowBouncy 去过冲拖影 (打磨项)。重影真因在父级 Box 对齐方式
            // (v3.22.1 已根修), 弹簧参数对重影无贡献
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioLowBouncy,
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
                .padding(bottom = 12.dp)
                .clip(RoundedCornerShape(18.dp))
                // v3.12: 不透明 surfaceContainerHigh —— v3.11 的半透明 surface 底
                // 在夜间让渐变背景透进来, 文字对比度塌掉; 该角色已进主题统一时钟
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                // v3.10 无影语言: 发丝描边提边界
                .border(
                    BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                    RoundedCornerShape(18.dp)
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick,
                )
                .padding(end = 14.dp),
        ) {
            SmallFloatingActionButton(
                onClick = onClick,
                elevation = FloatingActionButtonDefaults.elevation(
                    defaultElevation = 0.dp,
                    pressedElevation = 0.dp,
                    focusedElevation = 0.dp,
                    hoveredElevation = 0.dp,
                ),
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
                // v3.12: 显式 onSurface —— 不再依赖继承, 夜间同步变色
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

/**
 * 聊天列表内容
 *
 * v3.11: 常驻搜索框移除 —— 搜索入口上移到顶栏 (点击 🔍 展开),
 * 列表多出一行内容高度
 * v3.23: 过滤仅按指纹 (凭据唯一化); 输入完整合法指纹且通讯录无
 * 此人时展示 "添加联系人" 结果卡 → 添加并开聊
 */
@Composable
private fun ChatListContent(
    uiState: com.engine.viewmodel.ChatListUiState,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onChatClick: (String) -> Unit,
    // v3.23: 搜索框添加路径 (完整指纹 + 通讯录无此人 → 结果卡)
    onAddContact: (String) -> Unit
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

        // v3.23: 搜索凭据唯一化 —— 仅按指纹过滤 (忽略大小写);
        // 昵称/@短码语法移除 (用户决策: 指纹是唯一搜索凭据)
        val filteredList = if (searchQuery.isBlank()) uiState.chatList
        else uiState.chatList.filter { contact ->
            contact.fingerprint.contains(searchQuery.trim(), ignoreCase = true)
        }

        // v3.23: 输入恰为完整合法指纹 (清洗后 32 位 hex) 且通讯录无此人
        // → 展示 "添加联系人" 结果卡。短码 (6 位) 数学上无法还原完整指纹,
        // 不提供添加路径 —— 添加请输入完整指纹。
        val cleanedQuery = FingerprintInput.clean(searchQuery)
        val addableFingerprint =
            if (FingerprintInput.isValid(cleanedQuery) &&
                uiState.chatList.none { it.fingerprint.equals(cleanedQuery, ignoreCase = true) }
            ) cleanedQuery
            else null

        if (filteredList.isEmpty()) {
            when {
                searchQuery.isBlank() -> {
                    // 空会话列表: 呼吸动画的帆船图标 (匹配 Engine 新 Logo)
                    BreathingEmptyState(
                        icon = Icons.Filled.Sailing,
                        title = "暂无会话, 请先添加联系人",
                        subtitle = "开始你的安全通讯之旅"
                    )
                }
                // 完整指纹未添加 → 可直接添加并开聊
                addableFingerprint != null -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        AddContactResultCard(
                            fingerprint = addableFingerprint,
                            onAdd = { onAddContact(addableFingerprint) }
                        )
                    }
                }
                else -> {
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
 * v3.23 · 搜索页 "添加联系人" 结果卡
 *
 * 主界面搜索框的添加路径: 输入为完整合法指纹且通讯录无此人时展示,
 * 点击即添加并进入聊天 (昵称默认 "用户 + 指纹前 8 位")。
 */
@Composable
private fun AddContactResultCard(
    fingerprint: String,
    onAdd: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .clickable(onClick = onAdd)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.PersonAdd,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(28.dp),
        )
        Spacer(modifier = Modifier.size(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "添加联系人",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = fingerprint,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 空会话列表状态已迁移至共享组件 [BreathingEmptyState] (v3.22):
 * 呼吸帆船 + 标题 + 副标题, 与联系人/群组/标记物四页统一规格。
 */
