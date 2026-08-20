package com.engine.ui.components

import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.engine.data.StickerCatalog

/**
 * ═══════════════════════════════════════════════════════════════════
 *  v3.20 · 贴纸渲染 (动态 WebP)
 * ═══════════════════════════════════════════════════════════════════
 *
 *  渲染策略 (零图片库依赖, 平台原生能力):
 *  · API 28+: ImageDecoder → AnimatedImageDrawable 无限循环播放
 *    (AnimatedImageDrawable 自管理帧调度, start() 即播)
 *  · API 26/27: BitmapFactory 解码首帧静态显示
 *    (ImageDecoder 动画能力 28 起才可用; 首帧静态是可接受降级)
 *
 *  生命周期: 可见期间 start(), 离屏 DisposableEffect stop(),
 *  滚动离屏的贴纸动画即停, 长会话不积压后台解码线程。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StickerImage(
    sticker: StickerCatalog.Sticker,
    modifier: Modifier = Modifier,
    contentDescription: String = sticker.name,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null
) {
    val context = LocalContext.current
    var drawable by remember(sticker.id) {
        mutableStateOf<android.graphics.drawable.Drawable?>(null)
    }

    // 后台解码 (ImageDecoder.decodeDrawable 做 IO, 不得在主线程)
    LaunchedEffect(sticker.id) {
        drawable = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // createSource(AssetManager, path): 流式按需读取, 无需整包入内存
                val source = ImageDecoder.createSource(context.assets, sticker.assetPath)
                (ImageDecoder.decodeDrawable(source) as? AnimatedImageDrawable)?.apply {
                    repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                }
            } else {
                context.assets.open(sticker.assetPath).use {
                    BitmapFactory.decodeStream(it)
                }?.let { bmp ->
                    object : android.graphics.drawable.BitmapDrawable(
                        context.resources, bmp
                    ) {}
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    DisposableEffect(drawable) {
        (drawable as? AnimatedImageDrawable)?.start()
        onDispose {
            (drawable as? AnimatedImageDrawable)?.stop()
        }
    }

    val clickModifier = if (onClick != null || onLongClick != null) {
        Modifier.combinedClickable(
            onClick = { onClick?.invoke() },
            onLongClick = { onLongClick?.invoke() }
        )
    } else Modifier

    Box(
        modifier = modifier.then(clickModifier),
        contentAlignment = Alignment.Center
    ) {
        val d = drawable
        if (d != null) {
            Canvas(Modifier.fillMaxSize()) {
                drawIntoCanvas { c ->
                    val canvas: Canvas = c.nativeCanvas
                    // 按显示尺寸适配 (保持纵横比, 贴纸均 512×512 正方形)
                    val scale = minOf(
                        size.width / d.intrinsicWidth,
                        size.height / d.intrinsicHeight
                    ).coerceAtLeast(0f)
                    val w = d.intrinsicWidth * scale
                    val h = d.intrinsicHeight * scale
                    val dx = (size.width - w) / 2f
                    val dy = (size.height - h) / 2f
                    canvas.save()
                    canvas.translate(dx, dy)
                    canvas.scale(scale, scale)
                    d.setBounds(0, 0, d.intrinsicWidth, d.intrinsicHeight)
                    d.draw(canvas)
                    canvas.restore()
                }
            }
        }
        // 解码前占位: emoji 中心显示
        if (d == null) {
            Text(text = sticker.emoji, fontSize = 32.sp)
        }
    }
}

/**
 * 贴纸全屏预览 (聊天页点击贴纸消息弹出)
 *
 * 大尺寸播放 + 底部语义名, 点击任意处关闭。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StickerPreviewDialog(
    sticker: StickerCatalog.Sticker,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.72f))
                .combinedClickable(
                    onClick = onDismiss,
                    onLongClick = onDismiss
                ),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                StickerImage(
                    sticker = sticker,
                    modifier = Modifier.size(300.dp)
                )
                Spacer(Modifier.height(20.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.White.copy(alpha = 0.12f))
                        .padding(horizontal = 20.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "${sticker.emoji} ${sticker.name}",
                        color = Color.White,
                        fontSize = 15.sp
                    )
                }
                Spacer(Modifier.navigationBarsPadding())
            }
        }
    }
}
