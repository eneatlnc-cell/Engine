package com.engine.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.engine.ui.theme.LocalEngineBackground

/**
 * ═══════════════════════════════════════════════════════════════════
 *  v3.8 · 全局背景 —— 状态栏/顶栏/内容连成一片的渐变底
 * ═══════════════════════════════════════════════════════════════════
 *
 *  之前: 背景是纯色 surface, 组件各自用 container 色 —— 两套颜色
 *        "叠" 在一起, 深色下尤其像重影。
 *  现在: 背景笔刷取自 [LocalEngineBackground] (由 EngineSyncedTheme
 *        从当前 scheme 派生: 顶部掺品牌色 → 中部纯 surface → 底部
 *        微纵深), 与所有组件同源同钟。状态栏同款顶端色,
 *        整个界面是一块连续流动的颜色场。
 *
 *  用法: MainActivity 根部 Surface(color = background) 换成本组件;
 *        各屏幕 Scaffold 记得 containerColor = Color.Transparent。
 */
@Composable
fun EngineBackground(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    // 纯色兜底层: 即使局部重组漏刷渐变, 底色也永远与主题一致
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier.fillMaxSize(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(LocalEngineBackground.current),
        ) {
            content()
        }
    }
}
