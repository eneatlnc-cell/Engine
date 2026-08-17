package com.engine.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.engine.EngineApp
import com.securesocial.core.ipc.IpcCallback
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 登录页 UI 状态 (密封类)
 */
sealed class LoginUiState {
    /** 空闲 (等待用户触发验证) */
    object Idle : LoginUiState()
    /** 正在验证 (已唤起 Vault, 等待指纹确认回调) */
    object Loading : LoginUiState()
    /** 验证成功 */
    object Success : LoginUiState()
    /** 验证失败 */
    data class Error(val message: String) : LoginUiState()
}

/**
 * 登录 ViewModel
 *
 * 职责:
 * - verify(): 通过 IPC 唤起 Vault 弹出系统指纹验证 (无动态码输入)
 * - 按 sessionId 过滤 Vault 回调, 只消费属于本次验证的结果
 * - 验证成功 → EngineApp.isLoggedIn = true, 门控自动放行
 *
 * 安全约束:
 * - URI 中不携带任何密钥或验证码
 * - 证明因子是 Vault 侧的生物识别 (设备指纹), 而非可转抄的数字码
 */
class LoginViewModel : ViewModel() {

    private val app = EngineApp.get()
    private val ipcClient = app.ipcClient

    private val _uiState = MutableStateFlow<LoginUiState>(LoginUiState.Idle)
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    // 本次验证的 sessionId; 仅消费与其匹配的回调, 防止误吞绑定流程的回调
    private var pendingSessionId: String? = null

    init {
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
     * 通过 IPC 唤起 Vault 指纹验证
     */
    fun verify() {
        if (_uiState.value is LoginUiState.Loading) return
        val sessionId = UUID.randomUUID().toString()
        val launched = ipcClient.launchVerify(sessionId)
        if (launched) {
            pendingSessionId = sessionId
            app.awaitingIpc = true
            _uiState.value = LoginUiState.Loading
        } else {
            _uiState.value = LoginUiState.Error("无法唤起 Vault, 请确认已安装")
        }
    }

    /**
     * 处理 Vault 验证回调 (v2: 验签后才信任)
     *
     * 验签规则:
     * - 成功回调: 必须携带有效 ECDSA 签名 (用绑定身份公钥验), 否则视为伪造拒绝
     * - 失败回调: 有签名则验签; 无签名则接受 (回调入口已受 signature 权限保护,
     *   仅 Vault 本尊可投递; 伪造失败回调最多造成重试提示, 无安全收益)
     */
    private fun handleCallback(callback: IpcCallback) {
        app.awaitingIpc = false

        val boundPub = app.sessionManager.identityPublicKey
        if (boundPub != null) {
            if (callback.isSuccess || callback.hasSignatureMaterial) {
                val verifyError = ipcClient.verifyCallbackSignature(callback, boundPub)
                if (verifyError != null) {
                    _uiState.value = LoginUiState.Error("回调验证失败: $verifyError")
                    return
                }
            }
        } else if (callback.isSuccess) {
            // 无绑定公钥却收到成功回调: 状态异常, 一律拒绝
            _uiState.value = LoginUiState.Error("回调验证失败: 无绑定公钥")
            return
        }

        if (callback.isSuccess) {
            app.isLoggedIn = true
            _uiState.value = LoginUiState.Success
        } else {
            _uiState.value = LoginUiState.Error(
                callback.errorCode?.description ?: "验证失败, 请重试"
            )
        }
    }

    /**
     * 回到空闲状态 (错误后重试)
     */
    fun reset() {
        _uiState.value = LoginUiState.Idle
    }
}
