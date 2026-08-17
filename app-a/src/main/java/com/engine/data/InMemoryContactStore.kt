package com.engine.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 联系人数据类 (纯内存, 无持久化)
 *
 * @param fingerprint      对方公钥指纹 (全局唯一节点 ID)
 * @param nickname         昵称备注
 * @param lastMessage      最后一条消息预览
 * @param lastMessageTime  最后一条消息时间戳
 */
data class Contact(
    val fingerprint: String,
    val nickname: String,
    val lastMessage: String? = null,
    val lastMessageTime: Long? = null
)

/**
 * 纯内存联系人存储
 *
 * 使用 MutableStateFlow 管理联系人列表, 数据仅存内存, 无任何持久化。
 */
class InMemoryContactStore {

    private val _contacts = MutableStateFlow<List<Contact>>(emptyList())
    val contacts: StateFlow<List<Contact>> = _contacts.asStateFlow()

    /**
     * 添加联系人 (若指纹已存在则更新昵称)
     */
    @Synchronized
    fun addContact(fingerprint: String, nickname: String) {
        val current = _contacts.value.toMutableList()
        val idx = current.indexOfFirst { it.fingerprint == fingerprint }
        if (idx >= 0) {
            current[idx] = current[idx].copy(nickname = nickname)
        } else {
            current.add(Contact(fingerprint = fingerprint, nickname = nickname))
        }
        _contacts.value = current
    }

    /**
     * 移除联系人
     */
    @Synchronized
    fun removeContact(fingerprint: String) {
        _contacts.value = _contacts.value.filterNot { it.fingerprint == fingerprint }
    }

    /**
     * 更新最后消息预览
     */
    @Synchronized
    fun updateLastMessage(fingerprint: String, message: String, time: Long) {
        val current = _contacts.value.toMutableList()
        val idx = current.indexOfFirst { it.fingerprint == fingerprint }
        if (idx >= 0) {
            current[idx] = current[idx].copy(
                lastMessage = message,
                lastMessageTime = time
            )
            _contacts.value = current
        }
    }

    /**
     * 根据指纹获取联系人
     */
    fun getContact(fingerprint: String): Contact? {
        return _contacts.value.find { it.fingerprint == fingerprint }
    }

    /**
     * 清除全部联系人 (onTrimMemory 时调用)
     */
    @Synchronized
    fun clearAll() {
        _contacts.value = emptyList()
    }
}
