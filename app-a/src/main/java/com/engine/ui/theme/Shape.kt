package com.engine.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * M3 Expressive 形状系统 (v3.7)
 *
 * 设计意图: Material 模板的小圆角克制保守; M3E 的年轻化语言是
 * "更大胆的圆角 + 有机不对称"。两档制保持克制:
 * - 常规控件: 12dp (卡片/输入框)
 * - 容器级: 24dp (弹窗/底部抽屉/大卡片)
 * - 控件全圆药丸由组件自身 pill 形状承担 (搜索框/按钮)
 *
 * EngineLeafShape: 不对称 "叶片" 形 —— 一个角特别大 (M3E 有机形状
 * 的克制版), 用于强调型卡片 (Spark 面板/空状态插图容器)。
 */
val EngineShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

/**
 * 不对称叶片形: 右下角 24dp, 其余 10dp。
 * 视觉重心落在右下的 "落笔" 处, 用于强调型容器。
 */
val EngineLeafShape = RoundedCornerShape(
    topStart = 10.dp,
    topEnd = 10.dp,
    bottomEnd = 24.dp,
    bottomStart = 10.dp
)

/**
 * 头像专用 "超圆" (squircle 观感): 圆角半径 = 尺寸的 35%。
 * 与正圆相比更接近 M3E 有机形状; 具体尺寸由使用处指定。
 */
fun EngineAvatarShape(sizeDp: Int): RoundedCornerShape =
    RoundedCornerShape((sizeDp * 0.35f).dp)
