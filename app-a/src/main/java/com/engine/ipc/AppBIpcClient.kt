package com.engine.ipc

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.util.Log
import com.securesocial.core.crypto.EcdsaOperations
import com.securesocial.core.ipc.IpcCallback
import com.securesocial.core.ipc.IpcContract
import com.securesocial.core.ipc.IpcErrorCode
import java.security.MessageDigest
import java.security.PublicKey

/**
 * v3.23.3 IPC 唤起前置诊断结果。
 *
 * 背景: Vault 的全部 IPC 入口组件由 signature 级自定义权限
 * (com.vault.permission.VAULT_IPC) 保护 —— 只有与 Vault 同签名证书的
 * 应用才能唤起。两个工程默认均不配置签名 (各自用构建机 debug keystore),
 * 一旦 Engine 与 Vault 来自不同构建环境, 证书不一致 → 权限无法授予 →
 * startActivity 抛 SecurityException。旧实现把异常静默吞掉只返回
 * false, UI 误报 "请确认已安装", 真实原因完全不可见 (v3.23.3 根修)。
 */
sealed class VaultIpcDiagnostic {
    /** 通道就绪: Vault 已安装且签名一致, 唤起应能成功 */
    object Ready : VaultIpcDiagnostic()

    /** Vault 未安装 (或对本机不可见) */
    object VaultNotInstalled : VaultIpcDiagnostic()

    /**
     * 签名证书不一致: signature 级权限无法授予, 唤起必然 SecurityException。
     * [engineCertSha256] / [vaultCertSha256] 供 UI 展示, 便于现场比对定位。
     */
    data class SignatureMismatch(
        val engineCertSha256: String,
        val vaultCertSha256: String
    ) : VaultIpcDiagnostic()
}

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
 * v3.23.3 诊断增强 (修复: 唤起失败被静默吞掉, 误报 "未安装"):
 * - 全部 launch 方法记录真实异常 (lastLaunchError + logcat)
 * - diagnoseVaultChannel(): 唤起前比对两 App 签名证书 SHA-256
 * - describeLaunchFailure(): 供 UI 展示定向失败原因
 *
 * 安全约束:
 * - URI 中绝不携带私钥明文 (私钥仅经受保护的 EXTRA_PAYLOAD 单向导入)
 */
class AppBIpcClient(private val context: Context) {

    companion object {
        private const val TAG = "EngineIpc"
    }

    private val ecdsa = EcdsaOperations()
    private val b64d = java.util.Base64.getDecoder()

    /** 最近一次 launch 失败的真实异常摘要 (SecurityException / ActivityNotFound 等) */
    var lastLaunchError: String? = null
        private set

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
            recordLaunchFailure("launchImport", e)
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
            recordLaunchFailure("launchVerify", e)
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
            recordLaunchFailure("launchRestore", e)
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
            recordLaunchFailure("launchSign", e)
            false
        }
    }

    // ---- v3.23.3: 唤起失败诊断 (修复静默吞异常, 误报 "未安装") ----

    /** 记录 launch 真实异常: UI 与 logcat 双通道可见 */
    private fun recordLaunchFailure(where: String, e: Exception) {
        lastLaunchError = "${e.javaClass.simpleName}: ${e.message}"
        Log.e(TAG, "$where failed → $lastLaunchError", e)
    }

    /**
     * 读取指定包的 APK 签名证书 SHA-256 (十六进制)。
     *
     * API 28+ 走 SigningInfo (GET_SIGNING_CERTIFICATES), 26/27 回退
     * 废弃的 GET_SIGNATURES —— 取首个签名者证书摘要即可判断一致性
     * (单签名者场景, 与本工程签名形态一致)。
     */
    private fun signingCertSha256(pkg: String): String? {
        return try {
            val signatures: Array<Signature>? =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val info = context.packageManager.getPackageInfo(
                        pkg, PackageManager.GET_SIGNING_CERTIFICATES
                    )
                    if (info.signingInfo?.hasMultipleSigners() == true) {
                        info.signingInfo?.apkContentsSigners
                    } else {
                        info.signingInfo?.signingCertificateHistory
                    }
                } else {
                    @Suppress("DEPRECATION")
                    context.packageManager.getPackageInfo(pkg, PackageManager.GET_SIGNATURES)
                        .signatures
                }
            val first = signatures?.firstOrNull() ?: return null
            val digest = MessageDigest.getInstance("SHA-256").digest(first.toByteArray())
            digest.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Log.w(TAG, "signingCertSha256($pkg) failed: ${e.message}")
            null
        }
    }

    /**
     * IPC 通道前置诊断: Vault 是否安装 + 两 App 签名证书是否一致。
     *
     * 签名一致性是 signature 级权限 (VAULT_IPC / ENGINE_CALLBACK) 授予的
     * 充要条件 —— 不一致时唤起与回调双向全部失败, 且无法覆盖安装
     * (更新必须卸载重装 → 应用数据全清 → "更新丢身份" 的直接根源)。
     * 证书不可读时返回 Ready (尽力而为), 由 launch 异常日志兜底。
     */
    fun diagnoseVaultChannel(): VaultIpcDiagnostic {
        val installed = try {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(IpcContract.VAULT_PACKAGE, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
        if (!installed) return VaultIpcDiagnostic.VaultNotInstalled

        val engineCert = signingCertSha256(context.packageName) ?: return VaultIpcDiagnostic.Ready
        val vaultCert = signingCertSha256(IpcContract.VAULT_PACKAGE) ?: return VaultIpcDiagnostic.Ready

        return if (engineCert == vaultCert) {
            VaultIpcDiagnostic.Ready
        } else {
            Log.e(
                TAG,
                "Signature mismatch: engine=$engineCert vault=$vaultCert " +
                    "(signature-level IPC permission cannot be granted)"
            )
            VaultIpcDiagnostic.SignatureMismatch(engineCert, vaultCert)
        }
    }

    /**
     * 唤起失败的用户可读原因 (供 ViewModel 展示)。
     *
     * 定向文案取代旧版一律 "请确认已安装" —— 签名不一致时给出证书指纹
     * 与处置指引, 未安装时如实提示安装, 其余透出真实异常类名。
     *
     * v3.23.4: 处置指引指向 "同机构建 + Vault 迁移"。
     * 共享签名机制已撤销 (生成跨机构共享密钥违背项目密钥原则
     * —— 应用生成密钥对, Vault 离线保管, 不引入额外分发面)。
     */
    fun describeLaunchFailure(): String = when (val d = diagnoseVaultChannel()) {
        VaultIpcDiagnostic.VaultNotInstalled ->
            "未检测到 Vault 应用 (com.vault), 请先安装 Vault"
        is VaultIpcDiagnostic.SignatureMismatch ->
            "Engine 与 Vault 的签名证书不一致, signature 级 IPC 权限无法授予\n" +
                "处置:\n" +
                "① 在同一台机器重新构建并安装两个应用 (同机 debug 签名天然一致)\n" +
                "② 换机/重装后, 用 Vault 的「迁移」功能转移绑定身份后再恢复\n" +
                "Engine 证书: ${d.engineCertSha256.take(16)}…\n" +
                "Vault  证书: ${d.vaultCertSha256.take(16)}…"
        VaultIpcDiagnostic.Ready ->
            "唤起 Vault 失败: ${lastLaunchError ?: "未知原因"} (详见 logcat EngineIpc 标签)"
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
