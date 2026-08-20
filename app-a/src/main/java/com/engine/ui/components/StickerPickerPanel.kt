package com.engine.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.engine.data.StickerCatalog

/**
 * ═══════════════════════════════════════════════════════════════════
 *  v3.20 · Spark 表情面板 (替换键盘)
 * ═══════════════════════════════════════════════════════════════════
 *
 *  结构 (Telegram 风格):
 *  · 5 列网格, 静态缩略图 (LRU 缓存, 128px) —— 60 张动态全播会解码
 *    出 ~60MB 位图, 面板一律静态, 动画留给气泡与全屏预览
 *  · 长按网格项 → 全屏动态预览 (确认后仍需点击发送)
 *  · 点击 = 直接发送 (无中间确认, 与微信/TG 手势一致)
 *  · 底栏: 表情包标识 + 最近使用 (v3.21 可扩展)
 *
 *  空态: 目录未加载/加载失败 → 居中提示。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StickerPickerPanel(
    onPick: (StickerCatalog.Sticker) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val stickers by StickerCatalog.stickers.collectAsState()
    var preview by remember { mutableStateOf<StickerCatalog.Sticker?>(null) }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp)
                .navigationBarsPadding()
        ) {
            // ---- 顶栏: 包标识 ----
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.EmojiEmotions,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    "Spark 表情包",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "${stickers.size} 张",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }

            if (stickers.isEmpty()) {
                // 目录空态 (未加载/失败)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "表情包加载中…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                // ---- 网格 (静态缩略图) ----
                LazyVerticalGrid(
                    columns = GridCells.Fixed(5),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 12.dp, vertical = 8.dp
                    ),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    items(stickers, key = { it.id }) { sticker ->
                        val thumb = remember(sticker.id) {
                            StickerCatalog.thumbnail(context, sticker)
                        }
                        Box(
                            modifier = Modifier
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                )
                                .combinedClickable(
                                    onClick = { onPick(sticker) },
                                    onLongClick = { preview = sticker }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            val bmp = thumb
                            if (bmp != null) {
                                Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = sticker.name,
                                    modifier = Modifier.size(52.dp)
                                )
                            } else {
                                // 缩略图解码失败: emoji 兜底
                                Text(text = sticker.emoji, fontSize = 28.sp)
                            }
                        }
                    }
                }
            }
        }
    }

    // 长按全屏预览
    preview?.let { sticker ->
        StickerPreviewDialog(sticker = sticker, onDismiss = { preview = null })
    }
}
