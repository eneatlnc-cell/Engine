package com.engine.ui.screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.navigation.NavController
import com.engine.EngineApp
import com.engine.data.ProfileStore
import com.engine.ui.components.GradientAvatar
import com.engine.ui.theme.EngineAvatarShape
import kotlinx.coroutines.launch

/**
 * ═══════════════════════════════════════════════════════════════════
 *  v3.22 · DID 身份页 —— 设置抽屉 "DID 身份" 行的落地页
 * ═══════════════════════════════════════════════════════════════════
 *
 *  职责 (用户故事: "设置图像 + 修改昵称"):
 *  · 头像: 点击大头像 → 系统相册选图 (EXIF 纠向 + 中心裁剪 + 512px 落盘);
 *          已设置时可移除, 回退指纹渐变头像
 *  · 昵称: 行内 ✎ → 对话框修改 (≤24 字, 空串 = 恢复默认 "我")
 *  · DID/指纹: 只读展示 (did:engine:{指纹} 由密钥派生, 不可修改),
 *          点击复制; @短码 即聊天页搜索语法
 *
 *  设计:
 *  · 与群组/标记物同款透明 Scaffold, 全局渐变一路铺到顶
 *  · v3.10 无影语言: 卡片纯平 surfaceVariant 底 + 20dp 圆角
 *  · 隐私文案明示: 昵称/头像仅存本机, 不上行中继
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DidScreen(
    navController: NavController
) {
    val app = EngineApp.get()
    val profile by app.profileStore.profile.collectAsState()
    val clipboard = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // 绑定指纹: ON_RESUME 重读 (重新绑定后直达本页也能即时刷新, 同 ChatList 模式)
    var fingerprint by remember {
        mutableStateOf(app.boundIdentityStore.getBoundFingerprint())
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                fingerprint = app.boundIdentityStore.getBoundFingerprint()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 头像解码: 以 avatarVersion 为键 (换图/移除均自增) —— 变更后自动重解码
    val avatarBitmap = remember(profile.avatarVersion) {
        app.profileStore.decodeAvatar()?.asImageBitmap()
    }

    var showNickDialog by remember { mutableStateOf(false) }

    // 相册选图 (ACTION_GET_CONTENT, 全版本免权限)
    val pickImage = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            val context = app.applicationContext
            // 大图采样/裁剪走 IO 线程, 不卡主线程动画
            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val ok = app.profileStore.updateAvatar(context, uri)
                if (!ok) {
                    snackbarHostState.showSnackbar("无法读取该图片, 请换一张试试")
                }
            }
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("DID 身份", fontWeight = FontWeight.SemiBold)
                        Text(
                            "did:engine · 密钥即身份",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ── 头像卡 ────────────────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                    .padding(vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(96.dp)
                        .clip(EngineAvatarShape(96))
                        .clickable { pickImage.launch("image/*") },
                    contentAlignment = Alignment.Center
                ) {
                    if (avatarBitmap != null) {
                        Image(
                            bitmap = avatarBitmap,
                            contentDescription = "头像, 点击更换",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        GradientAvatar(
                            seed = fingerprint ?: "unbound",
                            label = profile.nickname.ifBlank { "我" },
                            size = 96.dp
                        )
                    }
                    // 右下角相机角标: 可更换的直观暗示
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(30.dp)
                            .clip(EngineAvatarShape(30))
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PhotoCamera,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = if (profile.hasAvatar) "点击更换头像" else "点击设置头像",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (profile.hasAvatar) {
                    TextButton(onClick = { app.profileStore.clearAvatar() }) {
                        Icon(
                            Icons.Filled.RemoveCircleOutline,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.size(6.dp))
                        Text("移除头像", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // ── 昵称卡 ────────────────────────────────────────
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                    .clickable { showNickDialog = true }
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Badge,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.size(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "昵称",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        profile.nickname.ifBlank { "我 (默认)" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Icon(
                    imageVector = Icons.Filled.Edit,
                    contentDescription = "修改昵称",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(14.dp))

            // ── DID 卡 (只读) ─────────────────────────────────
            InfoCard(
                icon = { Icon(Icons.Filled.Fingerprint, null, tint = MaterialTheme.colorScheme.primary) },
                title = "DID",
                value = "did:engine:${fingerprint ?: "未绑定"}",
                hint = "由密钥指纹派生 · 永久不变 · 点击复制",
                onClick = {
                    fingerprint?.let {
                        clipboard.setText(AnnotatedString("did:engine:$it"))
                        scope.launch { snackbarHostState.showSnackbar("DID 已复制") }
                    }
                }
            )

            Spacer(Modifier.height(14.dp))

            // ── 指纹卡 (只读) ─────────────────────────────────
            InfoCard(
                icon = { Icon(Icons.Filled.Fingerprint, null, tint = MaterialTheme.colorScheme.primary) },
                title = "公钥指纹",
                value = fingerprint ?: "未绑定 — 请先完成密钥绑定",
                hint = "@${(fingerprint ?: "??????").take(6).uppercase()} 即聊天页搜索语法 · 点击复制",
                monospace = fingerprint != null,
                onClick = {
                    fingerprint?.let {
                        clipboard.setText(AnnotatedString(it))
                        scope.launch { snackbarHostState.showSnackbar("指纹已复制") }
                    }
                }
            )

            Spacer(Modifier.height(14.dp))

            // ── 隐私说明卡 ────────────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
                    .padding(16.dp)
            ) {
                Text(
                    "仅存本机",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "昵称与头像只保存在这台设备上, 不会上传中继, " +
                        "也不会同步给任何联系人。DID 与指纹由你绑定的密钥唯一派生, " +
                        "任何人无法冒充; 换绑密钥即换身份。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(28.dp))
        }
    }

    // 昵称修改对话框
    if (showNickDialog) {
        NicknameDialog(
            initial = profile.nickname,
            onDismiss = { showNickDialog = false },
            onConfirm = { name ->
                app.profileStore.updateNickname(name)
                showNickDialog = false
            }
        )
    }
}

/**
 * 只读信息卡: 图标 + 标题 + 值 + 提示, 整卡可点 (复制类操作)
 */
@Composable
private fun InfoCard(
    icon: @Composable () -> Unit,
    title: String,
    value: String,
    hint: String,
    onClick: () -> Unit,
    monospace: Boolean = false,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            icon()
            Spacer(Modifier.size(14.dp))
            Text(
                title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            fontFamily = if (monospace) FontFamily.Monospace else null,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(4.dp))
        Text(
            hint,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 昵称修改对话框: 单行输入, ≤24 字, 空串 = 恢复默认
 */
@Composable
private fun NicknameDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    val trimmed = text.trim()
    val valid = trimmed.length <= ProfileStore.NICKNAME_MAX

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("修改昵称") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it.take(ProfileStore.NICKNAME_MAX + 8) // 留出超限可视余量
                    },
                    singleLine = true,
                    label = { Text("昵称 (留空恢复默认)") },
                    supportingText = {
                        Text(
                            if (valid) "${trimmed.length}/${ProfileStore.NICKNAME_MAX}"
                            else "最多 ${ProfileStore.NICKNAME_MAX} 个字符",
                            color = if (valid) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.error
                        )
                    }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text) },
                enabled = valid
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
