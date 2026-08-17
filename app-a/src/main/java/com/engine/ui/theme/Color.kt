package com.engine.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * M3 色彩体系 (基于蓝色种子生成)
 *
 * 遵循 Material Design 3 色彩角色定义:
 * - primary / onPrimary / primaryContainer / onPrimaryContainer
 * - secondary / onSecondary / secondaryContainer / onSecondaryContainer
 * - tertiary / onTertiary / tertiaryContainer / onTertiaryContainer
 * - error / onError / errorContainer / onErrorContainer
 * - background / onBackground / surface / onSurface / surfaceVariant / onSurfaceVariant / outline
 */

// ---- Primary (蓝色系) ----
val EnginePrimary = Color(0xFF0061A4)
val EngineOnPrimary = Color(0xFFFFFFFF)
val EnginePrimaryContainer = Color(0xFFD1E4FF)
val EngineOnPrimaryContainer = Color(0xFF001D36)

val EngineDarkPrimary = Color(0xFF9ACBFF)
val EngineDarkOnPrimary = Color(0xFF00344C)
val EngineDarkPrimaryContainer = Color(0xFF004C6C)
val EngineDarkOnPrimaryContainer = Color(0xFFD1E4FF)

// ---- Secondary (青色系) ----
val EngineSecondary = Color(0xFF535F70)
val EngineOnSecondary = Color(0xFFFFFFFF)
val EngineSecondaryContainer = Color(0xFFD7E3F7)
val EngineOnSecondaryContainer = Color(0xFF101C2B)

val EngineDarkSecondary = Color(0xFFB0C9DE)
val EngineDarkOnSecondary = Color(0xFF193441)
val EngineDarkSecondaryContainer = Color(0xFF314A59)
val EngineDarkOnSecondaryContainer = Color(0xFFD2E5F4)

// ---- Tertiary (紫色系) ----
val EngineTertiary = Color(0xFF6B5778)
val EngineOnTertiary = Color(0xFFFFFFFF)
val EngineTertiaryContainer = Color(0xFFF2DAFF)
val EngineOnTertiaryContainer = Color(0xFF251431)

val EngineDarkTertiary = Color(0xFFC3C4EB)
val EngineDarkOnTertiary = Color(0xFF2D2E50)
val EngineDarkTertiaryContainer = Color(0xFF434468)
val EngineDarkOnTertiaryContainer = Color(0xFFE0E1FF)

// ---- Error ----
val EngineError = Color(0xFFBA1A1A)
val EngineOnError = Color(0xFFFFFFFF)
val EngineErrorContainer = Color(0xFFFFDAD6)
val EngineOnErrorContainer = Color(0xFF410002)

val EngineDarkError = Color(0xFFFFB4AB)
val EngineDarkOnError = Color(0xFF690005)
val EngineDarkErrorContainer = Color(0xFF93000A)
val EngineDarkOnErrorContainer = Color(0xFFFFDAD6)

// ---- 浅色主题 Neutral ----
val EngineLightBackground = Color(0xFFFDFCFF)
val EngineLightOnBackground = Color(0xFF191C1E)
val EngineLightSurface = Color(0xFFFDFCFF)
val EngineLightOnSurface = Color(0xFF191C1E)
val EngineLightSurfaceVariant = Color(0xFFDDE3EA)
val EngineLightOnSurfaceVariant = Color(0xFF41484D)
val EngineLightOutline = Color(0xFF72787D)
val EngineLightSurfaceContainer = Color(0xFFEEF1F4)

// ---- 深色主题 Neutral ----
val EngineDarkBackground = Color(0xFF191C1E)
val EngineDarkOnBackground = Color(0xFFE1E2E5)
val EngineDarkSurface = Color(0xFF191C1E)
val EngineDarkOnSurface = Color(0xFFE1E2E5)
val EngineDarkSurfaceVariant = Color(0xFF41484D)
val EngineDarkOnSurfaceVariant = Color(0xFFC0C7CD)
val EngineDarkOutline = Color(0xFF8B9196)
val EngineDarkSurfaceContainer = Color(0xFF2A2F33)

// ---- 消息气泡 (保留用于聊天页面特殊配色) ----
val EngineLightBubbleMine = Color(0xFF0061A4)
val EngineLightBubbleMineText = Color(0xFFFFFFFF)
val EngineLightBubblePeer = Color(0xFFE1E2E5)
val EngineLightBubblePeerText = Color(0xFF191C1E)

val EngineDarkBubbleMine = Color(0xFF9ACBFF)
val EngineDarkBubbleMineText = Color(0xFF00344C)
val EngineDarkBubblePeer = Color(0xFF2A2F33)
val EngineDarkBubblePeerText = Color(0xFFE1E2E5)

// ---- 功能色 (连接状态) ----
val StatusConnected = Color(0xFF4CAF50)
val StatusConnecting = Color(0xFFFFC107)
val StatusDisconnected = Color(0xFF9E9E9E)
val StatusError = Color(0xFFEF5350)

val MessageFailed = Color(0xFFEF5350)
val MessagePending = Color(0xFFFFC107)
val MessageSent = Color(0xFF4CAF50)
val MessageDelivered = Color(0xFF2196F3)

// ---- 兼容性: 保留 TelegramBlue 别名 (逐步迁移) ----
@Deprecated("Use MaterialTheme.colorScheme.primary instead", ReplaceWith("MaterialTheme.colorScheme.primary"))
val TelegramBlue = EnginePrimary
