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

    companion object {
        private const val KEY_FINGERPRINT = "bound_fingerprint"
        private const val KEY_PUBLIC_KEY = "bound_public_key"
    }
}
