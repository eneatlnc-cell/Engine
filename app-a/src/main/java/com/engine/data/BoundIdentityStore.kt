package com.engine.data

import android.content.Context
import android.content.SharedPreferences

/**
 * 已绑定身份的持久化存储 (v2: 指纹 + 身份公钥)
 *
 * 安全约束:
 * - 存储身份公钥指纹 (32 字符十六进制) 与身份公钥本身 (X.509 Base64)
 * - 公钥与指纹均非秘密, 泄露不影响私钥安全
 * - 绝不存储任何私钥或会话密钥
 *
 * v2 变更 (修复: 身份漂移 / 绑定失效):
 * - 同时持久化身份公钥, 使 Engine 二次启动可恢复 "与 Vault 绑定的同一身份",
 *   不再静默生成新随机身份导致节点 ID 漂移
 * - 公钥用于: 登录回调验签、SIGNAL 信令验签、中继 HELLO 声明
 */
class BoundIdentityStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("engine_identity", Context.MODE_PRIVATE)

    /**
     * 保存绑定身份 (指纹 + 公钥)
     */
    fun saveBoundIdentity(fingerprint: String, publicKeyBase64: String) {
        prefs.edit()
            .putString(KEY_FINGERPRINT, fingerprint)
            .putString(KEY_PUBLIC_KEY, publicKeyBase64)
            .apply()
    }

    /**
     * 获取已绑定的公钥指纹
     */
    fun getBoundFingerprint(): String? = prefs.getString(KEY_FINGERPRINT, null)

    /**
     * 获取已绑定的身份公钥 (X.509 Base64); 未绑定时返回 null
     */
    fun getBoundPublicKeyBase64(): String? = prefs.getString(KEY_PUBLIC_KEY, null)

    /**
     * 清除绑定身份 (用于重新绑定场景)
     */
    fun clearBoundFingerprint() {
        prefs.edit().clear().apply()
    }

    // ---- v3.23.2: 身份恢复会话落盘 (修复进程死亡导致恢复静默失败) ----

    /**
     * 记录进行中的身份恢复会话。
     *
     * 恢复流程跨应用: Engine 退后台 → Vault 指纹门 (数秒~数十秒) → 回调。
     * 这期间 Engine 的后台进程随时可能被系统回收 —— 若会话只存
     * ViewModel 内存, 进程死亡后回调到达时新 ViewModel 的
     * pendingSessionId 为空, 回调无人认领, 恢复静默失败
     * (即 "Vault 的身份无法恢复" 的根因)。
     * 落盘后 EngineApp 可在冷启动的回调入口直接完成恢复。
     */
    fun savePendingRestore(sessionId: String) {
        prefs.edit()
            .putString(KEY_PENDING_RESTORE_SESSION, sessionId)
            .putLong(KEY_PENDING_RESTORE_AT, System.currentTimeMillis())
            .apply()
    }

    /**
     * 读取进行中的恢复会话; 超过 TTL 的陈旧会话自动清除
     * (Vault 永不回调 = 流程已死, 如用户在 Vault 侧直接退出)。
     */
    fun getPendingRestoreSessionId(): String? {
        val sessionId = prefs.getString(KEY_PENDING_RESTORE_SESSION, null) ?: return null
        val at = prefs.getLong(KEY_PENDING_RESTORE_AT, 0L)
        if (System.currentTimeMillis() - at > PENDING_RESTORE_TTL_MS) {
            clearPendingRestore()
            return null
        }
        return sessionId
    }

    fun clearPendingRestore() {
        prefs.edit()
            .remove(KEY_PENDING_RESTORE_SESSION)
            .remove(KEY_PENDING_RESTORE_AT)
            .apply()
    }

    companion object {
        private const val KEY_FINGERPRINT = "bound_fingerprint"
        private const val KEY_PUBLIC_KEY = "bound_public_key"

        // v3.23.2: 待恢复会话键 + TTL (覆盖用户从容完成一次指纹验证)
        private const val KEY_PENDING_RESTORE_SESSION = "pending_restore_session"
        private const val KEY_PENDING_RESTORE_AT = "pending_restore_at"
        private const val PENDING_RESTORE_TTL_MS = 10 * 60 * 1000L
    }
}
