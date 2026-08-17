package com.engine.ipc

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.engine.EngineApp

/**
 * IPC 回调入口 (v2, 修复: 登录回调伪造)
 *
 * 职责:
 * - 接收 Vault 投递的 myvault://callback (sessionId / status / ts / sig / result)
 * - 解析后直接交给 EngineApp.deliverCallback() 路由, 自身立即 finish()
 *
 * 三重防护:
 * 1. 组件级: android:permission (signature) — 未持有 Engine 证书的 App 无法投递
 * 2. 类别级: intent-filter 无 BROWSABLE — 浏览器/网页链接无法唤起
 * 3. 数据级: 回调必须带 ECDSA 签名, 由 EngineApp / ViewModel 用绑定公钥验签后才被信任
 *
 * 无 UI (透明主题), 不进入最近任务 (excludeFromRecents + noHistory)。
 */
class CallbackActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val app = application as EngineApp
        val callback = AppBIpcClient(this).handleCallbackIntent(intent)
        if (callback != null) {
            app.deliverCallback(callback)
        }
        // 无论解析成功与否都立即结束, 不留任何界面痕迹
        finish()
    }

    /**
     * 单实例回调重复投递保护: 每次回调都经由新实例处理
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val app = application as EngineApp
        val callback = AppBIpcClient(this).handleCallbackIntent(intent)
        if (callback != null) {
            app.deliverCallback(callback)
        }
        finish()
    }
}
