package com.engine.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * 消息发送状态
 */
enum class MessageStatus {
    PENDING,     // 发送中
    SENT,        // 已发送到中继
    DELIVERED,   // 已送达对方
    FAILED       // 发送失败
}

/**
 * 聊天消息数据类 (纯内存, 无持久化)
 *
 * @param id              消息唯一 ID
 * @param peerFingerprint 对方公钥指纹 (会话标识; 群会话为 "grp:<groupId>")
 * @param text            消息明文 (贴纸为线格式引用 "[spark:st001_hello]", 与快照/预览共用)
 * @param isMine          是否为己方发送
 * @param timestamp       毫秒时间戳
 * @param status          发送状态
 * @param senderName      v3.14: 群消息发送者显示名 (1:1 会话为 null)
 * @param stickerId       v3.20: 贴纸 ID (接收/发送时由线格式解析; null = 普通文本)
 */
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val peerFingerprint: String,
    val text: String,
    val isMine: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val status: MessageStatus = MessageStatus.PENDING,
    val senderName: String? = null,
    val stickerId: String? = null
)

/**
 * 纯内存消息存储
 *
 * 使用 MutableStateFlow 管理所有会话消息, 数据仅存内存, 无任何持久化。
 * 页面销毁时调用 clearSession 释放该会话消息引用;
 * onTrimMemory 时调用 clearAll 清理全部缓存。
 */
class InMemoryMessageStore {

    // peerFingerprint -> 该会话的消息 StateFlow
    private val peerMessageFlows = mutableMapOf<String, MutableStateFlow<List<ChatMessage>>>()

    // 全部消息的扁平视图 (供 ChatListViewModel 获取最后消息)
    private val _allMessages = MutableStateFlow<Map<String, List<ChatMessage>>>(emptyMap())
    val allMessages: StateFlow<Map<String, List<ChatMessage>>> = _allMessages.asStateFlow()

    /**
     * 获取指定会话的消息流 (线程安全)
     */
    @Synchronized
    fun getMessages(peerFingerprint: String): StateFlow<List<ChatMessage>> {
        return peerMessageFlows.getOrPut(peerFingerprint) {
            MutableStateFlow(emptyList())
        }.asStateFlow()
    }

    /**
     * 添加消息到指定会话
     */
    @Synchronized
    fun addMessage(peerFingerprint: String, message: ChatMessage) {
        val flow = peerMessageFlows.getOrPut(peerFingerprint) {
            MutableStateFlow(emptyList())
        }
        val updated = flow.value + message
        flow.value = updated
        _allMessages.value = _allMessages.value.toMutableMap().apply {
            this[peerFingerprint] = updated
        }
    }

    /**
     * 更新消息状态
     */
    @Synchronized
    fun updateMessageStatus(messageId: String, status: MessageStatus) {
        for ((peer, flow) in peerMessageFlows) {
            val idx = flow.value.indexOfFirst { it.id == messageId }
            if (idx >= 0) {
                val updated = flow.value.toMutableList()
                updated[idx] = updated[idx].copy(status = status)
                flow.value = updated
                _allMessages.value = _allMessages.value.toMutableMap().apply {
                    this[peer] = updated
                }
                break
            }
        }
    }

    /**
     * 清除指定会话的消息 (页面销毁时调用)
     */
    @Synchronized
    fun clearSession(peerFingerprint: String) {
        peerMessageFlows.remove(peerFingerprint)?.let { flow ->
            _allMessages.value = _allMessages.value.toMutableMap().apply {
                remove(peerFingerprint)
            }
            flow.value = emptyList()
        }
    }

    /**
     * 清除全部消息 (onTrimMemory 时调用)
     */
    @Synchronized
    fun clearAll() {
        for ((_, flow) in peerMessageFlows) {
            flow.value = emptyList()
        }
        peerMessageFlows.clear()
        _allMessages.value = emptyMap()
    }
}
