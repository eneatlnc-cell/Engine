package com.engine.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.engine.ui.theme.EngineAvatarShape

/**
 * 指纹渐变头像 (v3.7)
 *
 * 玩法同 Telegram Premium 渐变头像: 用联系人指纹做哈希种子,
 * 确定性地映射到一对高饱和色相 → 135° 线性渐变。
 * 同一联系人永远拿到同一配色, 不同人色彩各异 —— 列表一眼
 * 即有 "每人一个专属色" 的鲜活感, 这比纯色头像年轻得多。
 *
 * 稳定性: 纯函数映射 (哈希→色相), 无随机数, 无状态,
 * 指纹不变则渐变不变。
 */
@Composable
fun GradientAvatar(
    seed: String,
    label: String,
    size: Dp = 52.dp,
    modifier: Modifier = Modifier
) {
    val brush = remember(seed) { avatarBrush(seed) }
    val shape = remember(size) { EngineAvatarShape(size.value.toInt()) }

    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(brush),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label.firstOrNull()?.uppercase() ?: "?",
            color = Color.White,
            fontSize = (size.value * 0.42f).sp,
            fontWeight = FontWeight.ExtraBold
        )
    }
}

/**
 * 哈希种子 → 135° 渐变笔刷 (确定性, 纯函数)
 *
 * 色相策略: 基准色相 h ∈ [0,360), 副色相取 h+50±30 (邻近色
 * 偏移) —— 保证渐变既鲜活又不脏。饱和度 0.72 / 明度 0.92 与
 * 0.68 / 0.82 错开, 制造斜向光感。
 */
internal fun avatarBrush(seed: String): Brush {
    var h = 1125899906842597L
    for (c in seed) h = 31L * h + c.code
    if (h == Long.MIN_VALUE) h = 42L

    val hue1 = Math.floorMod(h, 360L).toFloat()
    val hue2 = (hue1 + 40f + (Math.floorMod(h / 360L, 60L)).toFloat()) % 360f

    val c1 = Color.hsv(hue1, 0.72f, 0.94f)
    val c2 = Color.hsv(hue2, 0.68f, 0.80f)
    return Brush.linearGradient(colors = listOf(c1, c2))
}

/**
 * 兼容入口: 纯色主题色头像 (旧路径, 逐步迁移到渐变版)
 */
@Composable
fun SolidAvatar(
    label: String,
    size: Dp = 52.dp,
    shape: Shape = EngineAvatarShape((size.value).toInt()),
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label.firstOrNull()?.uppercase() ?: "?",
            color = MaterialTheme.colorScheme.onPrimary,
            fontSize = (size.value * 0.42f).sp,
            fontWeight = FontWeight.ExtraBold
        )
    }
}
