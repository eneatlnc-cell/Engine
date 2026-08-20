package com.engine.ipc

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.securesocial.core.crypto.EcdsaOperations
import com.securesocial.core.ipc.IpcCallback
import com.securesocial.core.ipc.IpcContract
import com.securesocial.core.ipc.IpcErrorCode
import java.security.PublicKey

/**
 * Vault IPC 客户端 (v2)
 *
 * 通过轻量级 URI Scheme (myvault://) 与 Vault 通信:
 * - launchImport(): 唤起 Vault 执行密钥导入
 * - launchVerify(): 唤起 Vault 指纹验证 (登录)
 * - launchSign():   请求 Vault 用身份私钥签名 (中继挑战应答 / ECDH 信令)
 * - handleCallbackIntent(): 解析 myvault://callback 回调
 *
 * v2 安全增强 (修复: 隐式 Intent 广播式投递):
 * - 全部请求 Intent 使用 setPackage(VAULT_PACKAGE) 显式锁定目标,
 *   系统直接投递给 Vault, 彻底消除 chooser 误选与恶意 App 抢答
 * - 回调验签: Vault 的每个回调都带 ECDSA 签名 (覆盖 sessionId‖status‖ts),
 *   verifyCallbackSignature() 用绑定公钥完成验证 —— 回调从 "状态字符串"
 *   升级为 "Vault 私钥持有证明", 恶意 App 无法伪造
 *
 * 安全约束:
 * - URI 中绝不携带私钥明文 (私钥仅经受保护的 EXTRA_PAYLOAD 单向导入)
 */
class AppBIpcClient(private val context: Context) {

    private val ecdsa = EcdsaOperations()
    private val b64d = java.util.Base64.getDecoder()

    /**
     * 通过 myvault://import URI 唤起 Vault
     *
     * @param sessionId 会话标识 (Engine 生成, 用于关联请求与回调)
     * @param payload 密钥二维码载荷 (JSON 字符串); 仅经 signature 权限保护的
     *                 显式 Intent 传递, 恶意 App 无法截获
     * @return true 表示成功唤起, false 表示未找到目标 App
     */
    fun launchImport(sessionId: String, payload: String? = null): Boolean {
        val uriString = IpcContract.buildImportUri(sessionId, context.packageName)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uriString)).apply {
            // v2: 显式锁定 Vault 包名, 杜绝隐式 Intent 广播式投递
            setPackage(IpcContract.VAULT_PACKAGE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            if (payload != null) {
                putExtra(IpcContract.EXTRA_PAYLOAD, payload)
            }
        }
        return try {
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 通过 myvault://verify URI 唤起 Vault 指纹验证
     *
     * @param sessionId 会话标识 (Engine 生成, 用于关联请求与回调)
     * @return true 表示成功唤起, false 表示未找到目标 App
     */
    fun launchVerify(sessionId: String): Boolean {
        val uriString = IpcContract.buildVerifyUri(sessionId, context.packageName)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uriString)).apply {
            setPackage(IpcContract.VAULT_PACKAGE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return try {
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * v3.13 通过 myvault://restore URI 唤起 Vault 身份恢复。
     *
     * Engine 清除数据/换机重装后本地绑定身份丢失时调用; Vault 在指纹门后
     * 将该应用绑定的公钥经回调 result 送回 (私钥不出 Vault)。
     */
    fun launchRestore(sessionId: String): Boolean {
        val uriString = IpcContract.buildRestoreUri(sessionId, context.packageName)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uriString)).apply {
            setPackage(IpcContract.VAULT_PACKAGE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return try {
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 请求 Vault 用绑定的身份私钥签名 (v2 新增)
     *
     * v3.17: 待签字节经 Intent Extra (EXTRA_PAYLOAD) 投递, URI 只保留
     * session/app 路由字段 —— Intent data 会随 ActivityTaskManager 的
     * "START u0" 日志行整体进 logcat, 挑战 nonce / ECDH 公钥不得进 URI。
     *
     * @param sessionId      会话标识
     * @param payloadBytes   待签字节
     * @return true 表示请求已发出 (结果经 callback 异步返回, result 字段为 Base64 签名)
     */
    fun launchSign(sessionId: String, payloadBytes: ByteArray): Boolean {
        val payloadBase64 = java.util.Base64.getEncoder().encodeToString(payloadBytes)
        val uriString = IpcContract.buildSignUri(sessionId, context.packageName)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uriString)).apply {
            setPackage(IpcContract.VAULT_PACKAGE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            // v3.17: 载荷走 Extra, 不进 URI (防系统日志泄露)
            putExtra(IpcContract.EXTRA_PAYLOAD, payloadBase64)
        }
        return try {
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 解析 myvault://callback 回调 Intent
     *
     * v3.17: sig/result 优先读 Intent Extra (新版 Vault 投递方式),
     * Extra 缺失时由模型层回退 URI 查询参数 (兼容旧版 Vault)。
     */
    fun handleCallbackIntent(intent: Intent?): IpcCallback? {
        if (intent == null) return null
        return IpcCallback.fromIntent(intent)
    }

    /**
     * 验证回调签名 (v2, 修复: 登录回调伪造)
     *
     * 检查项:
     * 1. 回调携带完整签名材料 (sessionId / ts / sig)
     * 2. 时间戳在容忍窗口内 (防重放)
     * 3. ECDSA 签名验证通过 (用 Vault 中绑定的身份公钥)
     *
     * @param callback    待验证的回调
     * @param boundPublicKey 已绑定的身份公钥 (来自 BoundIdentityStore)
     * @return null 表示验证通过; 否则返回失败原因
     */
    fun verifyCallbackSignature(
        callback: IpcCallback,
        boundPublicKey: PublicKey
    ): String? {
        val sessionId = callback.sessionId
            ?: return "回调缺少 sessionId"
        val sigB64 = callback.signature
            ?: return "回调缺少签名"
        if (callback.timestamp <= 0L) return "回调缺少时间戳"

        // 时间戳窗口 (防重放)
        val now = System.currentTimeMillis()
        if (kotlin.math.abs(now - callback.timestamp) > IpcContract.CALLBACK_TS_TOLERANCE_MS) {
            return "回调时间戳超出容忍窗口"
        }

        // 签名验证
        val content = IpcContract.callbackSigningContent(
            sessionId,
            if (callback.isSuccess) IpcContract.STATUS_SUCCESS else IpcContract.STATUS_FAIL,
            callback.timestamp.toString()
        )
        val sigBytes = try {
            b64d.decode(sigB64)
        } catch (e: Exception) {
            return "签名编码非法"
        }
        if (!ecdsa.verify(boundPublicKey, content, sigBytes)) {
            return "回调签名验证失败 (非 Vault 本尊)"
        }
        return null
    }
}
