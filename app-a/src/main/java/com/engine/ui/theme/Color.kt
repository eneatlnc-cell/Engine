package com.engine.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * M3 Expressive 色彩体系 (v3.7 "电流紫")
 *
 * 设计意图: 替换 Material 模板蓝为高饱和紫罗兰 —— Gen Z 视觉母语
 * (Discord / 加密社区同源), 深色为默认场景的年轻化基调。
 *
 * 配角策略:
 * - Secondary 品红: 渐变搭档色 (与 primary 拼 "电流渐变")
 * - Tertiary 火焰橙: 承接 Spark 加密货币的品牌火色
 *
 * 遵循 Material Design 3 色彩角色定义 (token 名与旧版一一对应):
 * - primary / onPrimary / primaryContainer / onPrimaryContainer
 * - secondary / onSecondary / secondaryContainer / onSecondaryContainer
 * - tertiary / onTertiary / tertiaryContainer / onTertiaryContainer
 * - error / onError / errorContainer / onErrorContainer
 * - background / onBackground / surface / onSurface / surfaceVariant / onSurfaceVariant / outline
 */

// ---- Primary (电流紫) ----
val EnginePrimary = Color(0xFF5A2EE8)
val EngineOnPrimary = Color(0xFFFFFFFF)
val EnginePrimaryContainer = Color(0xFFEADCFF)
val EngineOnPrimaryContainer = Color(0xFF23005C)

val EngineDarkPrimary = Color(0xFFA98FFF)
val EngineDarkOnPrimary = Color(0xFF2A1065)
val EngineDarkPrimaryContainer = Color(0xFF4A36A8)
val EngineDarkOnPrimaryContainer = Color(0xFFEADCFF)

// ---- Secondary (品红 · 渐变搭档) ----
val EngineSecondary = Color(0xFF9C4478)
val EngineOnSecondary = Color(0xFFFFFFFF)
val EngineSecondaryContainer = Color(0xFFFFD7EB)
val EngineOnSecondaryContainer = Color(0xFF3E0727)

val EngineDarkSecondary = Color(0xFFF4A6D8)
val EngineDarkOnSecondary = Color(0xFF5A1D44)
val EngineDarkSecondaryContainer = Color(0xFF7B3260)
val EngineDarkOnSecondaryContainer = Color(0xFFFFD7EB)

// ---- Tertiary (火焰橙 · Spark 品牌色) ----
val EngineTertiary = Color(0xFFA63D00)
val EngineOnTertiary = Color(0xFFFFFFFF)
val EngineTertiaryContainer = Color(0xFFFFDBC8)
val EngineOnTertiaryContainer = Color(0xFF3B0A00)

val EngineDarkTertiary = Color(0xFFFFB592)
val EngineDarkOnTertiary = Color(0xFF571F00)
val EngineDarkTertiaryContainer = Color(0xFF7D2E00)
val EngineDarkOnTertiaryContainer = Color(0xFFFFDBC8)

// ---- Error ----
val EngineError = Color(0xFFBA1A1A)
val EngineOnError = Color(0xFFFFFFFF)
val EngineErrorContainer = Color(0xFFFFDAD6)
val EngineOnErrorContainer = Color(0xFF410002)

val EngineDarkError = Color(0xFFFFB4AB)
val EngineDarkOnError = Color(0xFF690005)
val EngineDarkErrorContainer = Color(0xFF93000A)
val EngineDarkOnErrorContainer = Color(0xFFFFDAD6)

// ---- 浅色主题 Neutral (紫调灰) ----
val EngineLightBackground = Color(0xFFFDFBFF)
val EngineLightOnBackground = Color(0xFF1D1B24)
val EngineLightSurface = Color(0xFFFDFBFF)
val EngineLightOnSurface = Color(0xFF1D1B24)
val EngineLightSurfaceVariant = Color(0xFFE7E0F3)
val EngineLightOnSurfaceVariant = Color(0xFF48454E)
val EngineLightOutline = Color(0xFF797487)
val EngineLightSurfaceContainer = Color(0xFFEFEDF5)

// ---- 深色主题 Neutral (深空紫黑) ----
val EngineDarkBackground = Color(0xFF141120)
val EngineDarkOnBackground = Color(0xFFE6E1F0)
val EngineDarkSurface = Color(0xFF141120)
val EngineDarkOnSurface = Color(0xFFE6E1F0)
val EngineDarkSurfaceVariant = Color(0xFF494460)
val EngineDarkOnSurfaceVariant = Color(0xFFCAC4DC)
val EngineDarkOutline = Color(0xFF948F9C)
val EngineDarkSurfaceContainer = Color(0xFF211D33)

// ---- 消息气泡 ----
val EngineLightBubbleMine = Color(0xFF5A2EE8)
val EngineLightBubbleMineText = Color(0xFFFFFFFF)
val EngineLightBubblePeer = Color(0xFFEFEDF5)
val EngineLightBubblePeerText = Color(0xFF1D1B24)

val EngineDarkBubbleMine = Color(0xFFA98FFF)
val EngineDarkBubbleMineText = Color(0xFF2A1065)
val EngineDarkBubblePeer = Color(0xFF211D33)
val EngineDarkBubblePeerText = Color(0xFFE6E1F0)

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
