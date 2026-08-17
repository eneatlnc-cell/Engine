package com.engine.viewmodel

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.engine.EngineApp
import com.engine.crypto.KeyGenerationResult
import com.engine.data.BoundIdentityStore
import com.securesocial.core.ipc.IpcCallback
import kotlinx.coroutines.Dispatchers
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
            app.awaitingIpc = true
            _uiState.value = KeyBindingUiState.WaitingCallback
        } else {
            _uiState.value = KeyBindingUiState.Error("无法唤起 Vault, 请确认已安装")
        }
    }

    /**
     * 处理 Vault 回调 (仅当 sessionId 匹配时被调用; v2: 验签后才信任)
     *
     * 验签规则:
     * - 成功回调: 必须用 "本次生成的密钥对" 公钥验签通过 —— 证明 Vault 确实
     *   收到并持有了刚移交的私钥, 而非恶意 App 伪造的成功状态
     * - 失败回调: Vault 在导入完成前可能尚无密钥可签 (如用户取消), 允许无签名;
     *   回调入口本身已受 signature 权限保护
     */
    private fun handleCallback(callback: IpcCallback) {
        app.awaitingIpc = false

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

            // 绑定成功后连接中继
            app.connectRelay()
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
    }
}
