package com.engine.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.engine.ui.theme.LocalEngineBackground

/**
 * ═══════════════════════════════════════════════════════════════════
 *  v3.8 · 全局背景 —— 状态栏/顶栏/内容连成一片的渐变底
 * ═══════════════════════════════════════════════════════════════════
 *
 *  背景笔刷取自 [LocalEngineBackground] (由 EngineSyncedTheme 从当前
 *  scheme 派生: 顶部掺品牌色 → 中部纯 surface → 底部微纵深),
 *  与所有组件同源同钟; 状态栏同款顶端色, 整个界面是一块连续的色场。
 *
 *  v3.9: 单层绘制 —— 去掉 v3.8 的 Surface 纯色兜底层。
 *  app 全局 FLAG_SECURE (防截屏) 下窗口只能 GPU 合成, 全屏每多
 *  叠一层就多一层逐像素混合; 渐变自身 fillMaxSize 全覆盖,
 *  兜底层本就用不上。
 */
@Composable
fun EngineBackground(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(LocalEngineBackground.current),
    ) {
        content()
    }
}
