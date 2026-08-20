package com.engine.ui.screen

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.engine.EngineApp
import com.engine.data.MarkerItem
import com.engine.data.StickerCatalog
import com.engine.ui.components.GradientAvatar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ═══════════════════════════════════════════════════════════════════
 *  v3.21 · 标记物列表 —— 设置抽屉 "标记物" 行的落地页
 * ═══════════════════════════════════════════════════════════════════
 *
 *  内容: 用户显式标记的消息快照 (应用唯一持久化数据)。
 *
 *  v3.21 管理:
 *    · 搜索: 按内容/对端昵称实时过滤
 *    · 分类: 全部 / 我发出的 / 收到的 (FilterChip)
 *    · 贴纸快照: v3.20 贴纸消息可标记, 卡片渲染缩略图 + 友好预览
 *    · 导出: 顶栏 IosShare → ShareSheet 明文导出 (用户主动行为)
 *    · 删除: 单条 ✕ / 全部清空 (AlertDialog 二次确认)
 *
 *  设计:
 *    · 与聊天页同款透明 Scaffold, 全局渐变背景一路铺到顶
 *    · v3.10 无影语言: 卡片纯平 surfaceVariant 底 + 20dp 圆角
 *    · 头像沿用指纹渐变 (GradientAvatar), 快照对端一目了然
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarksScreen(
    navController: NavController
) {
    val markerStore = EngineApp.get().markerStore
    val markers by markerStore.markers.collectAsState()
    val context = LocalContext.current

    var showClearConfirm by remember { mutableStateOf(false) }
    // v3.21: 搜索词 + 方向筛选 (0=全部 1=我发出的 2=收到)
    var query by remember { mutableStateOf("") }
    var directionFilter by remember { mutableStateOf(0) }

    // 过滤管线: 方向 → 关键词 (内容/对端昵称, 大小写不敏感)
    val filtered = remember(markers, query, directionFilter) {
        val q = query.trim()
        markers.filter { m ->
            val matchDir = when (directionFilter) {
                1 -> m.isMine
                2 -> !m.isMine
                else -> true
            }
            val matchQuery = q.isEmpty() ||
                m.displayText.contains(q, ignoreCase = true) ||
                m.peerName.contains(q, ignoreCase = true)
            matchDir && matchQuery
        }
    }

    /** v3.21: 明文导出 → 系统分享面板 (用户主动行为) */
    fun exportMarkers() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, markerStore.exportText())
            putExtra(Intent.EXTRA_TITLE, "Engine 标记物导出")
        }
        context.startActivity(Intent.createChooser(intent, "导出标记物"))
    }

    Scaffold(
        // 透明底 —— 全局渐变背景 (EngineBackground) 一路铺到顶, 同 ChatScreen
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("标记物", fontWeight = FontWeight.SemiBold)
                        Text(
                            text = "${markers.size} 条快照 · 仅存本机",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
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
                actions = {
                    if (markers.isNotEmpty()) {
                        // v3.21: 导出 (ShareSheet)
                        IconButton(onClick = { exportMarkers() }) {
                            Icon(
                                imageVector = Icons.Default.IosShare,
                                contentDescription = "导出标记物"
                            )
                        }
                        IconButton(onClick = { showClearConfirm = true }) {
                            Icon(
                                imageVector = Icons.Default.DeleteSweep,
                                contentDescription = "清空标记物",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
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
        ) {
            // ---- v3.21: 搜索 + 方向分类 (仅在有标记物时显示) ----
            if (markers.isNotEmpty()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    placeholder = { Text("搜索内容或对端昵称") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "清空搜索",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp)
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = directionFilter == 0,
                        onClick = { directionFilter = 0 },
                        label = { Text("全部 ${markers.size}") }
                    )
                    FilterChip(
                        selected = directionFilter == 1,
                        onClick = { directionFilter = 1 },
                        label = {
                            Text("我发出的 ${markers.count { it.isMine }}")
                        }
                    )
                    FilterChip(
                        selected = directionFilter == 2,
                        onClick = { directionFilter = 2 },
                        label = {
                            Text("收到 ${markers.count { !it.isMine }}")
                        }
                    )
                }
            }

            // ---- 列表 / 空态 ----
            if (markers.isEmpty()) {
                // 空态引导 (从未标记过)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.BookmarkBorder,
                            contentDescription = null,
                            modifier = Modifier.size(56.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "暂无标记物",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "长按聊天中的消息即可标记收藏",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                }
            } else if (filtered.isEmpty()) {
                // 有标记物但搜索/筛选无匹配
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.SearchOff,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "无匹配的标记物",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "换个关键词或切换分类试试",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    contentPadding = PaddingValues(
                        start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(filtered, key = { it.messageId }) { marker ->
                        MarkerCard(
                            marker = marker,
                            onRemove = { markerStore.remove(marker.messageId) }
                        )
                    }
                }
            }
        }
    }

    // 清空二次确认 (危险操作: 不可恢复)
    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.DeleteSweep,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { Text("清空全部标记物?") },
            text = {
                Text(
                    "将删除 ${markers.size} 条消息快照, 此操作不可恢复。\n\n" +
                        "聊天消息不受影响 (消息本身从不落盘)。"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        markerStore.clearAll()
                        showClearConfirm = false
                    }
                ) {
                    Text("清空", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text("取消")
                }
            }
        )
    }
}

/**
 * 单条标记物卡片
 *
 * 结构: [渐变头像] 昵称/@短码 ... [✕ 移除]
 *        快照内容 (文本: 最多 4 行引用样式; 贴纸: 缩略图 + 友好预览)
 *        消息时间 · 标记时间 · 方向
 */
@Composable
private fun MarkerCard(
    marker: MarkerItem,
    onRemove: () -> Unit
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .padding(16.dp)
    ) {
        // 头部: 头像 + 对端 + 移除
        Row(verticalAlignment = Alignment.CenterVertically) {
            GradientAvatar(
                seed = marker.peerFingerprint,
                label = marker.peerName,
                size = 36.dp
            )
            Spacer(modifier = Modifier.size(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = marker.peerName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "@${marker.peerFingerprint.take(8)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(
                onClick = onRemove,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "移除",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // ---- 快照内容 ----
        // 贴纸解析与缩略图均需无条件 remember (条件调用 remember 违反
        // Compose 规则), 条件逻辑移入 lambda 内部
        val sticker = remember(marker.stickerId) {
            marker.stickerId?.let { StickerCatalog.findById(it) }
        }
        val stickerThumb = remember(sticker?.id) {
            sticker?.let { StickerCatalog.thumbnail(context, it) }
        }
        if (sticker != null) {
            // v3.21: 贴纸快照 —— 静态缩略图 + 友好预览文本
            // (静态首帧: 列表滚动场景不跑 AnimatedImageDrawable; 主线程
            //  inSampleSize 解码为轻量操作, 详见 StickerCatalog.thumbnail 注释)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (stickerThumb != null) {
                    Image(
                        bitmap = stickerThumb.asImageBitmap(),
                        contentDescription = sticker.name,
                        modifier = Modifier.size(56.dp)
                    )
                    Spacer(modifier = Modifier.size(12.dp))
                }
                Text(
                    text = marker.displayText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        } else if (marker.isMine) {
            // 己方文本: 平铺
            Text(
                text = marker.text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )
        } else {
            // 对方文本: 引用竖线 (primary 色), 一眼区分收到的消息
            Row {
                Box(
                    modifier = Modifier
                        .size(width = 3.dp, height = 42.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.primary)
                )
                Spacer(modifier = Modifier.size(10.dp))
                Text(
                    text = marker.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 底部元信息: 消息时间 · 标记时间 · 方向
        Text(
            text = buildString {
                append(if (marker.isMine) "我发出的" else "收到")
                append(" · 消息 ")
                append(formatMarkerTime(marker.messageTimestamp))
                append(" · 标记于 ")
                append(formatMarkerTime(marker.markedAt))
            },
            style = MaterialTheme.typography.labelSmall,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
    }
}

/**
 * 标记物时间格式: 同日仅时分, 跨日含日期
 */
private fun formatMarkerTime(timestamp: Long): String {
    val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}
