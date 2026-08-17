package com.engine.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.engine.EngineApp
import com.securesocial.core.ipc.IpcCallback
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

    // v3.3: 验证超时任务 (Vault 未被成功唤起时的死锁兜底)
    private var timeoutJob: Job? = null

    companion object {
        /** 等待 Vault 回调的超时 (毫秒): 正常指纹流程 20s 绰绰有余 */
        private const val VERIFY_TIMEOUT_MS = 20_000L
    }

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
     *
     * v3.3: 加 20s 超时兜底 —— 若 Vault 唤起被 ROM 拦截/指纹框未展示,
     * 回调永远不会到达, 旧版 Loading 态按钮永久禁用 = 死锁
     * ("即使手动启动 Vault 也无法完成验证")。超时后自动复位可重试。
     */
    fun verify() {
        if (_uiState.value is LoginUiState.Loading) return
        val sessionId = UUID.randomUUID().toString()
        val launched = ipcClient.launchVerify(sessionId)
        if (launched) {
            pendingSessionId = sessionId
            app.awaitingIpc = true
            _uiState.value = LoginUiState.Loading
            armTimeout(sessionId)
        } else {
            _uiState.value = LoginUiState.Error("无法唤起 Vault, 请确认已安装")
        }
    }

    /**
     * 部署验证超时: 到期仍未收到匹配回调则复位到可重试状态
     */
    private fun armTimeout(sessionId: String) {
        timeoutJob?.cancel()
        timeoutJob = viewModelScope.launch {
            delay(VERIFY_TIMEOUT_MS)
            if (_uiState.value is LoginUiState.Loading && pendingSessionId == sessionId) {
                app.awaitingIpc = false
                pendingSessionId = null
                _uiState.value = LoginUiState.Error(
                    "验证超时: 未收到 Vault 回调 (唤起可能被系统拦截), 请重试"
                )
            }
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
        timeoutJob?.cancel()  // v3.3: 回调已到达, 取消超时兜底
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

            // v3: 登录成功是中继连接的唯一合法入口。
            // 修复 "杀进程后登录无法完成签名": 旧逻辑登录成功后无人连接中继,
            // 首次绑定场景靠 KeyBindingViewModel 连接 (未登录即连 → 指纹框互抢),
            // 二次启动场景则彻底无人连接 → 挑战签名永不触发。
            app.onLoginVerified()
        } else {
            _uiState.value = LoginUiState.Error(
                callback.errorCode?.description ?: "验证失败, 请重试"
            )
        }
    }

    /**
     * 回到空闲状态 (错误后重试 / 重新绑定)
     */
    fun reset() {
        timeoutJob?.cancel()
        timeoutJob = null
        pendingSessionId = null
        app.awaitingIpc = false
        _uiState.value = LoginUiState.Idle
    }
}
