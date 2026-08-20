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
    val inputText: String = "",
    /** v3.16: 已标记消息 ID 集 (驱动气泡书签角标) */
    val markedMessageIds: Set<String> = emptySet()
)

/**
 * 聊天页面 ViewModel
 *
 * 职责:
 * - 管理与指定对端的会话消息
 * - 发送消息: 加密 → 中继发送 → 写入内存
 * - 重发失败消息
 * - v3.16: 标记/取消标记消息 (快照落盘)
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
    private val markerStore = app.markerStore

    /** v3.14: 群会话 (peerFingerprint 形如 "grp:<id>"); 1:1 会话为 null */
    val isGroup = com.engine.data.EngineGroup.isGroupConversation(peerFingerprint)
    private val groupId = com.engine.data.EngineGroup.groupIdOf(peerFingerprint)

    /** v3.14: 群信息 (群名/成员数驱动顶栏) */
    val group = if (isGroup) app.groupStore.groups else null

    private val _inputText = MutableStateFlow("")

    val uiState: StateFlow<ChatUiState> = combine(
        messageStore.getMessages(peerFingerprint),
        relayManager.connectionState,
        _inputText,
        markerStore.markers
    ) { messages, connState, inputText, marks ->
        ChatUiState(
            messages = messages,
            connectionState = connState,
            inputText = inputText,
            markedMessageIds = marks.map { it.messageId }.toSet()
        )
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

        // 写入内存 + 更新会话预览 (群会话由 EngineApp.sendGroupMessage 更新群仓)
        messageStore.addMessage(peerFingerprint, message)
        if (!isGroup) {
            contactStore.updateLastMessage(peerFingerprint, text, now)
        }

        // 清空输入框
        _inputText.value = ""

        // 异步加密 + 发送
        viewModelScope.launch {
            doSend(message)
        }
    }

    /**
     * v3.20: 发送 Spark 贴纸
     *
     * 线格式引用经既有加密链路发送 (与文本同价, 17 字节):
     * 会话列表预览写 "👋 Spark 表情", 消息体 text 存线格式原文。
     */
    fun sendSticker(sticker: com.engine.data.StickerCatalog.Sticker) {
        val now = System.currentTimeMillis()
        val message = ChatMessage(
            id = UUID.randomUUID().toString(),
            peerFingerprint = peerFingerprint,
            text = sticker.wire,
            isMine = true,
            timestamp = now,
            status = MessageStatus.PENDING,
            stickerId = sticker.id
        )

        messageStore.addMessage(peerFingerprint, message)
        if (!isGroup) {
            contactStore.updateLastMessage(peerFingerprint, sticker.preview, now)
        }

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
     * v3.16: 标记/取消标记消息
     *
     * 标记 = 将消息快照 (文本+对端+时间) 写入应用私有文件;
     * 取消 = 从标记物文件中移除。昵称以标记时刻的联系人表/群信息为准。
     */
    fun toggleMark(message: ChatMessage) {
        val peerName = if (isGroup) {
            group?.value?.find { it.id == groupId }?.name ?: "群组"
        } else {
            contactStore.getContact(peerFingerprint)?.nickname
                ?: "用户 ${peerFingerprint.take(8)}"
        }
        markerStore.toggle(message, peerName)
    }

    /**
     * 实际发送逻辑 (统一委托 EngineApp):
     * - 1:1: sendMessageToPeer (成对密钥 + seq AAD)
     * - v3.14 群: sendGroupMessage (群密钥加密一次 + 按成员扇出)
     */
    private fun doSend(message: ChatMessage) {
        if (isGroup) {
            app.sendGroupMessage(groupId, message.id, message.text)
        } else {
            app.sendMessageToPeer(peerFingerprint, message.id, message.text)
        }
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
