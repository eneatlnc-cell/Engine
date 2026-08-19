package com.engine.viewmodel

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.engine.EngineApp
import com.engine.crypto.KeyGenerationResult
import com.engine.data.BoundIdentityStore
import com.securesocial.core.ipc.IpcCallback
import com.securesocial.core.ipc.IpcErrorCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * 密钥绑定 UI 状态 (密封类)
 */
sealed class KeyBindingUiState {
    /** 空闲 (未绑定, 等待用户生成密钥对) */
    object Idle : KeyBindingUiState()
    /** 已绑定: 展示当前绑定指纹, 重新绑定需二次确认 */
    data class Bound(val fingerprint: String) : KeyBindingUiState()
    /** 正在生成密钥对 */
    object Generating : KeyBindingUiState()
    /** 展示二维码 */
    data class ShowingQR(val bitmap: Bitmap, val fingerprint: String) : KeyBindingUiState()
    /** 等待 Vault 回调 */
    object WaitingCallback : KeyBindingUiState()
    /** v3.13 等待 Vault 恢复回调 */
    object Restoring : KeyBindingUiState()
    /** 绑定成功 */
    object Success : KeyBindingUiState()
    /** 绑定失败 */
    data class Error(val message: String) : KeyBindingUiState()
}

/**
 * 密钥绑定 ViewModel
 *
 * 职责:
 * - init: 读取 BoundIdentityStore, 已绑定时直接进入 Bound 状态,
 *   避免绑定成功后界面仍显示"生成密钥对"造成误操作
 * - generateKeyPair(): 生成 ECDSA P-256 密钥对 + 渲染二维码
 * - launchAppB(): 通过 URI Scheme 唤起 Vault (记录 sessionId)
 * - handleCallback(): 仅消费与本次 sessionId 匹配的回调
 *
 * 安全约束:
 * - 私钥通过二维码光学通道传递, URI 中不携带私钥
 * - 重新绑定会使旧身份永久失效, UI 层必须二次确认
 */
class KeyBindingViewModel : ViewModel() {

    private val app = EngineApp.get()
    private val keyGenerator = app.keyGenerator
    private val sessionManager = app.sessionManager
    private val ipcClient = app.ipcClient

    private val _uiState = MutableStateFlow<KeyBindingUiState>(KeyBindingUiState.Idle)
    val uiState: StateFlow<KeyBindingUiState> = _uiState.asStateFlow()

    // 当前生成结果 (仅内存)
    private var currentResult: KeyGenerationResult? = null

    // 本次绑定的 sessionId; 仅消费与其匹配的回调, 防止误吞登录流程的回调
    private var pendingSessionId: String? = null

    // v3.13: 本次会话是否为 "身份恢复" (Engine 数据清除后从 Vault 取回公钥身份)
    private var pendingRestore = false

    init {
        // 已绑定时展示 Bound 状态, 而非可误触的"生成密钥对"入口
        val bound = BoundIdentityStore(app).getBoundFingerprint()
        if (bound != null) {
            _uiState.value = KeyBindingUiState.Bound(bound)
        }

        // 观察 Vault 回调, 按 sessionId 路由
        viewModelScope.launch {
            app.pendingCallback.collect { callback ->
                if (callback != null && callback.sessionId == pendingSessionId) {
                    handleCallback(callback)
                    app.consumeCallback()
                }
            }
        }
    }

    /**
     * 刷新绑定状态 (重新进入密钥 Tab 时调用)
     */
    fun refreshBoundState() {
        if (_uiState.value is KeyBindingUiState.Idle ||
            _uiState.value is KeyBindingUiState.Success
        ) {
            val bound = BoundIdentityStore(app).getBoundFingerprint()
            if (bound != null) {
                _uiState.value = KeyBindingUiState.Bound(bound)
            }
        }
    }

    /**
     * 用户在二次确认后触发重新绑定:
     * 清除旧绑定指纹, 生成新密钥对进入二维码流程
     */
    fun rebind() {
        BoundIdentityStore(app).clearBoundFingerprint()
        generateKeyPair()
    }

    /**
     * 生成密钥对 + 渲染二维码
     */
    fun generateKeyPair() {
        _uiState.value = KeyBindingUiState.Generating

        viewModelScope.launch {
            try {
                // 在 IO 线程生成密钥对和渲染二维码
                val result = withContext(Dispatchers.Default) {
                    keyGenerator.generate()
                }

                currentResult = result

                // v2: 进入绑定流程 (私钥仅存内存, 供二维码展示; 导入成功后销毁)
                sessionManager.beginBindingIdentity(result.keyPair)

                // 渲染二维码
                val bitmap = withContext(Dispatchers.Default) {
                    keyGenerator.renderQrCode(result.qrPayload, QR_CODE_SIZE)
                }

                _uiState.value = KeyBindingUiState.ShowingQR(
                    bitmap = bitmap,
                    fingerprint = result.fingerprint
                )
            } catch (e: Exception) {
                _uiState.value = KeyBindingUiState.Error(
                    message = e.message ?: "密钥生成失败"
                )
            }
        }
    }

    /**
     * 通过 URI Scheme 唤起 Vault, 同时传递 QR payload 使 Vault 自动识别密钥
     *
     * payload 作为 Intent Extra 传递, Vault 可直接解析而无需开启摄像头。
     */
    fun launchAppB() {
        val sessionId = UUID.randomUUID().toString()
        val payload = currentResult?.qrPayload
        val launched = ipcClient.launchImport(sessionId, payload)
        if (launched) {
            pendingSessionId = sessionId
            pendingRestore = false
            app.awaitingIpc = true
            _uiState.value = KeyBindingUiState.WaitingCallback
        } else {
            _uiState.value = KeyBindingUiState.Error("无法唤起 Vault, 请确认已安装")
        }
    }

    /**
     * v3.13 身份恢复: Engine 清除数据/换机后, 从 Vault 取回该应用绑定的公钥身份。
     *
     * 场景: Engine 本地 BoundIdentityStore 已空, 但 Vault 仍持有 com.engine
     * 的绑定私钥。若直接生成新密钥对 → 新 DID → 旧联系人全部失联;
     * 走恢复流程则拿回同一把公钥, DID 不变。
     *
     * Vault 侧: 无绑定 → NO_BINDING 失败回调 (UI 引导走新生成);
     * 有绑定 → 指纹门后回送公钥 (Base64 X.509) 于 result 参数。
     */
    fun restoreFromVault() {
        val sessionId = UUID.randomUUID().toString()
        val launched = ipcClient.launchRestore(sessionId)
        if (launched) {
            pendingSessionId = sessionId
            pendingRestore = true
            app.awaitingIpc = true
            _uiState.value = KeyBindingUiState.Restoring
        } else {
            _uiState.value = KeyBindingUiState.Error("无法唤起 Vault, 请确认已安装")
        }
    }

    /**
     * 处理 Vault 回调 (仅当 sessionId 匹配时被调用; v2: 验签后才信任)
     *
     * v3.13: 恢复流程走独立分支 —— 恢复时本地没有 "本次生成的密钥对"
     * (expectedPub 为 null), 信任链改为: 回调通道 signature 权限 +
     * "用返回公钥验签" 的私钥持有证明 + 指纹重算一致性校验。
     *
     * 绑定流程验签规则 (不变):
     * - 成功回调: 必须用 "本次生成的密钥对" 公钥验签通过 —— 证明 Vault 确实
     *   收到并持有了刚移交的私钥, 而非恶意 App 伪造的成功状态
     * - 失败回调: Vault 在导入完成前可能尚无密钥可签 (如用户取消), 允许无签名;
     *   回调入口本身已受 signature 权限保护
     */
    private fun handleCallback(callback: IpcCallback) {
        app.awaitingIpc = false

        if (pendingRestore) {
            handleRestoreCallback(callback)
            return
        }

        val expectedPub = sessionManager.identityPublicKey
        if (expectedPub == null) {
            _uiState.value = KeyBindingUiState.Error("状态异常: 本次密钥对不存在")
            return
        }

        if (callback.isSuccess) {
            val verifyError = ipcClient.verifyCallbackSignature(callback, expectedPub)
            if (verifyError != null) {
                _uiState.value = KeyBindingUiState.Error("回调验证失败: $verifyError")
                return
            }

            _uiState.value = KeyBindingUiState.Success

            // 持久化公钥 + 指纹 (仅公钥材料, 不含私钥)
            // 用于二次启动时恢复绑定身份
            val fingerprint = sessionManager.identityFingerprint
            val pubEncoded = sessionManager.getIdentityPublicKeyEncoded()
            if (fingerprint != null && pubEncoded != null) {
                val pubB64 = android.util.Base64.encodeToString(
                    pubEncoded, android.util.Base64.NO_WRAP
                )
                BoundIdentityStore(app).saveBoundIdentity(fingerprint, pubB64)
            }

            // v2: 私钥已移交 Vault, 立即销毁本地副本 —— Engine 对身份私钥零持有
            currentResult = null
            sessionManager.convertBoundToPublicKeyOnly()

            // v3.5: 绑定即登录 (修复 "绑定后跳回应用又弹验证陷入死循环")。
            //
            // 绑定回调已用本次密钥对验签通过 = Vault 刚完成了
            // 生物识别 (导入指纹门) 并确认持有私钥 —— 这本身就是
            // 一次完整的 "在场证明"。旧流程绑定完成后 LoginGate 检测
            // "已绑定未登录" 又要唤起 Vault 指纹验证, 紧接着中继挑战
            // 再弹一次签名指纹: 用户一分钟内被要求按 3 次指纹、
            // 跨应用切换 4 次, 表现为 "打开应用循环"。
            //
            // 现绑定成功直接置登录态, LoginGate 放行进主界面;
            // 中继连接延迟 1.5s 发起 (等 UI 切换稳定)。挑战签名到来时
            // Vault 侧仍在导入指纹的 30s 授权窗口内 → 静默签名,
            // 全程仅一次指纹。
            app.isLoggedIn = true
            viewModelScope.launch {
                delay(BINDING_RELAY_DELAY_MS)
                if (app.isLoggedIn) {
                    app.onLoginVerified()
                }
            }
        } else {
            // 失败回调: 有签名则严格验签 (防止跨流程篡改), 无签名则按失败处理
            if (callback.hasSignatureMaterial) {
                val verifyError = ipcClient.verifyCallbackSignature(callback, expectedPub)
                if (verifyError != null) {
                    _uiState.value = KeyBindingUiState.Error("回调验证失败: $verifyError")
                    return
                }
            }
            val msg = callback.errorCode?.description ?: "未知错误"
            _uiState.value = KeyBindingUiState.Error(msg)
        }
    }

    /**
     * v3.13 恢复回调处理:
     *
     * 成功: result = Base64(X.509 公钥)。
     *   ① 用该公钥对回调验签 (sig 由 "被恢复的绑定私钥" 签出) —— 私钥持有
     *      证明 + 公私钥自洽证明; 通道本身受 ENGINE_CALLBACK signature
     *      权限保护, 仅 Vault 可投递
     *   ② SessionManager.adoptBoundIdentity 重算指纹并装载身份 (含新 ECDH)
     *   ③ BoundIdentityStore 持久化 → DID 恢复如初
     *   ④ 复用 "绑定即登录" (恢复动作刚过 Vault 指纹门, 本身是在场证明)
     *
     * 失败: NO_BINDING → 引导走 "生成新密钥对"; 其余按错误码展示。
     */
    private fun handleRestoreCallback(callback: IpcCallback) {
        pendingRestore = false

        if (!callback.isSuccess) {
            val msg = when (callback.errorCode) {
                IpcErrorCode.NO_BINDING ->
                    "Vault 中没有本应用的可恢复身份 (可能从未绑定过), 请生成新密钥对"
                else -> callback.errorCode?.description ?: "恢复失败"
            }
            _uiState.value = KeyBindingUiState.Error(msg)
            return
        }

        val resultB64 = callback.result
            ?: run {
                _uiState.value = KeyBindingUiState.Error("恢复回调缺少公钥载荷")
                return
            }

        try {
            val pubBytes = android.util.Base64.decode(resultB64, android.util.Base64.NO_WRAP)
            val pub = java.security.KeyFactory.getInstance("EC")
                .generatePublic(java.security.spec.X509EncodedKeySpec(pubBytes))

            // ① 私钥持有证明: 用返回公钥验回调签名
            val verifyError = ipcClient.verifyCallbackSignature(callback, pub)
            if (verifyError != null) {
                _uiState.value = KeyBindingUiState.Error("恢复回调验证失败: $verifyError")
                return
            }

            // ② 装载身份 (内含指纹重算 + 新 ECDH 临时密钥)
            val pubB64 = android.util.Base64.encodeToString(pubBytes, android.util.Base64.NO_WRAP)
            val fingerprint = sessionManager.adoptBoundIdentity(pubB64)
            if (fingerprint == null) {
                _uiState.value = KeyBindingUiState.Error("恢复的公钥无法解析")
                return
            }

            // ③ 持久化 → 同一 DID 回到本机
            BoundIdentityStore(app).saveBoundIdentity(fingerprint, pubB64)
            _uiState.value = KeyBindingUiState.Bound(fingerprint)

            // ④ 恢复即登录: 刚过 Vault 指纹门, 不再重复验证 (同 v3.5 绑定即登录)
            app.isLoggedIn = true
            app.onLoginVerified()
        } catch (e: Exception) {
            _uiState.value = KeyBindingUiState.Error("身份恢复失败: ${e.message ?: "未知错误"}")
        }
    }

    /**
     * 重置到空闲状态
     */
    fun reset() {
        _uiState.value = KeyBindingUiState.Idle
    }

    override fun onCleared() {
        super.onCleared()
        currentResult = null
        // 注意: 不销毁 SessionManager 的身份, 因为 ChatViewModel 仍在使用
        // SessionManager.destroyPrivateKey() 由 EngineApp.onAppBackground() 统一调用
    }

    companion object {
        private const val QR_CODE_SIZE = 600

        /** 绑定成功后延迟发起中继连接 (v3.5: 等 UI 切换稳定) */
        private const val BINDING_RELAY_DELAY_MS = 1_500L
    }
}
