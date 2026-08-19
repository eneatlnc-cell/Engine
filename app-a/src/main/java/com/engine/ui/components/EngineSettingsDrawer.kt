package com.engine.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.engine.ui.theme.EngineMotion

/**
 * ═══════════════════════════════════════════════════════════════════
 *  v3.11 · X 式设置抽屉 —— 顶栏变色球的落地页
 * ═══════════════════════════════════════════════════════════════════
 *
 *  设计语言严格复刻 X 参考图:
 *    · 纯平无投影, 全出血背景 (v3.10 无影语言天然一致)
 *    · 56dp 行高 / 24dp 线性图标 / 图标-文字 16dp 间距
 *    · 行间无分割线, 靠留白分组
 *    · 按压仅微妙灰底 (surfaceVariant 40%)
 *
 *  手势: ModalNavigationDrawer 自带边缘左滑呼出 + 右滑关闭;
 *        右上角 ✕ 按产品要求叠加 (显式关闭的可达性兜底)。
 *
 *  内容:
 *    头部  渐变头像 + DID 短格式 + 指纹短码 (@短码 即搜索语法)
 *    行 1  DID 身份    → [onOpenDid]
 *    行 2  群组        → [onOpenGroups]
 *    行 3  标记物      → [onOpenMarks]
 *    行 4  白夜切换    → 仅图标, 点击即切, 太阳↔月亮形变动画
 */
@Composable
fun EngineSettingsDrawer(
    isOpen: Boolean,
    onClose: () -> Unit,
    isDark: Boolean,
    onToggleTheme: () -> Unit,
    fingerprint: String?,
    onOpenDid: () -> Unit,
    onOpenGroups: () -> Unit,
    onOpenMarks: () -> Unit,
    content: @Composable () -> Unit,
) {
    ModalNavigationDrawer(
        drawerState = rememberEngineDrawerState(isOpen, onClose),
        drawerContent = {
            ModalDrawerSheet(
                // 85% 屏宽 (X 比例), 纯平无影
                modifier = Modifier.width(340.dp),
                drawerShape = RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp),
                drawerContainerColor = MaterialTheme.colorScheme.surface,
            ) {
                Column(modifier = Modifier.padding(vertical = 12.dp)) {

                    // ── 头部: 头像 + DID + 指纹短码 + ✕ ─────────────
                    Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        Column(modifier = Modifier.padding(top = 20.dp, end = 40.dp)) {
                            GradientAvatar(
                                seed = fingerprint ?: "unbound",
                                label = "E",
                                size = 44.dp,
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(
                                text = "DID 身份",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = didShort(fingerprint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "@${fingerprintShort(fingerprint)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                        // 显式关闭兜底 (手势之外, 按产品要求)
                        IconButton(
                            onClick = onClose,
                            modifier = Modifier.align(Alignment.TopEnd),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "关闭",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    // 唯一一条极淡分割线 (X 仅在资料区下用它)
                    Spacer(Modifier.height(16.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .height(1.dp)
                            .background(
                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                            ),
                    )
                    Spacer(Modifier.height(8.dp))

                    // ── 菜单行 ─────────────────────────────────────
                    EngineDrawerRow(
                        icon = Icons.Outlined.Fingerprint,
                        filledIcon = Icons.Filled.Fingerprint,
                        label = "DID 身份",
                        onClick = onOpenDid,
                    )
                    EngineDrawerRow(
                        icon = Icons.Outlined.Groups,
                        filledIcon = Icons.Filled.Groups,
                        label = "群组",
                        onClick = onOpenGroups,
                    )
                    EngineDrawerRow(
                        icon = Icons.Outlined.BookmarkBorder,
                        filledIcon = Icons.Filled.Bookmark,
                        label = "标记物",
                        onClick = onOpenMarks,
                    )

                    Spacer(Modifier.height(16.dp))

                    // ── 白夜切换: 仅图标, 形变动画 ──────────────────
                    DayNightToggleRow(isDark = isDark, onToggle = onToggleTheme)
                }
            }
        },
        content = content,
    )
}

/**
 * X 式菜单行: 56dp 高 / 24dp 线性图标 / 16dp 间距 / 无分割线。
 * 按压: 微妙灰底 + 图标实心化的呼吸感。
 */
@Composable
private fun EngineDrawerRow(
    icon: ImageVector,
    filledIcon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (pressed) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                else Color.Transparent
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 4.dp),
    ) {
        Icon(
            imageVector = if (pressed) filledIcon else icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * 白夜切换行: 无文字, 只有居左的太阳↔月亮形变动画。
 * 点击即切 —— 抽屉的签名微交互。
 */
@Composable
private fun DayNightToggleRow(isDark: Boolean, onToggle: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (pressed) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                else Color.Transparent
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onToggle)
            .padding(horizontal = 4.dp),
    ) {
        // 太阳↔月亮: 旋转 + 缩放 + 淡入淡出三重过渡
        AnimatedContent(
            targetState = isDark,
            transitionSpec = {
                (fadeIn(tween(240)) + scaleIn(
                    initialScale = 0.5f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMedium,
                    ),
                )) togetherWith (fadeOut(tween(120)) + scaleOut(targetScale = 0.5f))
            },
            label = "dayNightIcon",
        ) { dark ->
            val rotate by animateFloatAsState(
                targetValue = if (dark) 180f else 0f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium,
                ),
                label = "dayNightRotate",
            )
            Icon(
                imageVector = if (dark) Icons.Filled.DarkMode else Icons.Filled.LightMode,
                contentDescription = if (dark) "切换到白天" else "切换到夜间",
                tint = if (dark) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.tertiary,
                modifier = Modifier
                    .size(24.dp)
                    .graphicsLayer { rotationZ = rotate },
            )
        }
        Spacer(Modifier.width(16.dp))
        // 占位: 保持与其他行图标-内容节奏一致 (无文字, 产品要求)
        Box(Modifier.weight(1f))
    }
}

// ── 工具 ──────────────────────────────────────────────────────────

/** DID 短格式: did:engine:前 8 位… */
internal fun didShort(fingerprint: String?): String =
    if (fingerprint.isNullOrBlank()) "did:engine:未绑定"
    else "did:engine:${fingerprint.take(8)}…"

/** 指纹短码: @搜索语法直接可用 */
internal fun fingerprintShort(fingerprint: String?): String =
    if (fingerprint.isNullOrBlank()) "未绑定"
    else fingerprint.take(6).uppercase()

/** 抽屉状态桥: isOpen 变化时动画开合, 手势/✕ 关闭时回调 onClose */
@Composable
private fun rememberEngineDrawerState(
    isOpen: Boolean,
    onClose: () -> Unit,
): androidx.compose.material3.DrawerState {
    val state = androidx.compose.material3.rememberDrawerState(
        initialValue = androidx.compose.material3.DrawerValue.Closed,
    )
    androidx.compose.runtime.LaunchedEffect(isOpen) {
        if (isOpen) state.animateTo(androidx.compose.material3.DrawerValue.Open)
        else state.animateTo(androidx.compose.material3.DrawerValue.Closed)
    }
    androidx.compose.runtime.LaunchedEffect(state.currentValue, state.isAnimationRunning) {
        // 手势滑到关闭 → 通知宿主同步状态
        if (state.currentValue == androidx.compose.material3.DrawerValue.Closed &&
            !state.isAnimationRunning
        ) onClose()
    }
    return state
}
