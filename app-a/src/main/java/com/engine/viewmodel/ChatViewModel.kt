package com.engine.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.engine.EngineApp
import com.engine.data.ChatMessage
import com.engine.data.MessageStatus
import com.engine.network.ConnectionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 聊天页面 UI 状态
 */
data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val inputText: String = ""
)

/**
 * 聊天页面 ViewModel
 *
 * 职责:
 * - 管理与指定对端的会话消息
 * - 发送消息: 加密 → 中继发送 → 写入内存
 * - 重发失败消息
 * - 退出页面时释放该会话消息引用
 */
class ChatViewModel(
    val peerFingerprint: String
) : ViewModel() {

    private val app = EngineApp.get()
    private val messageStore = app.messageStore
    private val relayManager = app.relayManager
    private val cryptoManager = app.cryptoManager
    private val contactStore = app.contactStore

    private val _inputText = MutableStateFlow("")

    val uiState: StateFlow<ChatUiState> = combine(
        messageStore.getMessages(peerFingerprint),
        relayManager.connectionState,
        _inputText
    ) { messages, connState, inputText ->
        ChatUiState(messages = messages, connectionState = connState, inputText = inputText)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ChatUiState()
    )

    /**
     * 更新输入框文本
     */
    fun updateInputText(text: String) {
        _inputText.value = text
    }

    /**
     * 发送消息: 加密 → 中继发送 → 写入内存
     */
    fun sendMessage(text: String) {
        if (text.isBlank()) return

        val now = System.currentTimeMillis()
        val message = ChatMessage(
            id = UUID.randomUUID().toString(),
            peerFingerprint = peerFingerprint,
            text = text,
            isMine = true,
            timestamp = now,
            status = MessageStatus.PENDING
        )

        // 写入内存 + 更新联系人最后消息
        messageStore.addMessage(peerFingerprint, message)
        contactStore.updateLastMessage(peerFingerprint, text, now)

        // 清空输入框
        _inputText.value = ""

        // 异步加密 + 发送
        viewModelScope.launch {
            doSend(message)
        }
    }

    /**
     * 手动重发失败消息
     */
    fun resendMessage(messageId: String) {
        val msg = messageStore.getMessages(peerFingerprint).value
            .find { it.id == messageId }
            ?: return

        messageStore.updateMessageStatus(messageId, MessageStatus.PENDING)

        viewModelScope.launch {
            doSend(msg)
        }
    }

    /**
     * 实际发送逻辑 (v2: 统一委托 EngineApp.sendMessageToPeer)
     *
     * 统一入口保证:
     * - seq 由 App 级单调计数器分配并纳入 GCM AAD (防重放)
     * - 无会话密钥时自动发起签名信令交换, 消息标记失败待密钥建立后补发
     */
    private fun doSend(message: ChatMessage) {
        app.sendMessageToPeer(peerFingerprint, message.id, message.text)
    }

    /**
     * 退出页面时释放该会话消息引用
     */
    override fun onCleared() {
        super.onCleared()
        messageStore.clearSession(peerFingerprint)
    }

    companion object {
        /**
         * ViewModel 工厂 (需要 peerFingerprint 参数)
         */
        fun factory(peerFingerprint: String): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return ChatViewModel(peerFingerprint) as T
                }
            }
        }
    }
}
