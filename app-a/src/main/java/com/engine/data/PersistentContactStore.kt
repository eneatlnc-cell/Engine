package com.engine.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * 联系人数据类
 *
 * @param fingerprint      对方公钥指纹 (全局唯一节点 ID)
 * @param nickname         昵称备注
 * @param lastMessage      最后一条消息预览 (纯内存, 不落盘)
 * @param lastMessageTime  最后一条消息时间戳 (纯内存, 不落盘)
 */
data class Contact(
    val fingerprint: String,
    val nickname: String,
    val lastMessage: String? = null,
    val lastMessageTime: Long? = null
)

/** 落盘形态: 仅指纹 + 昵称 */
@Serializable
private data class PersistedContact(
    val fp: String,
    val nickname: String
)

/**
 * 持久化联系人仓库 (v3.14.1)
 *
 * 设计决策 (用户明确授权的持久化边界):
 * - 落盘: 指纹 + 昵称 —— 联系人通过指纹添加, 持久存在于联系人列表
 * - 不落盘: lastMessage 预览文本/时间 —— 任何消息内容绝不写盘 (红线),
 *   重启后预览为空, 新消息到达自然恢复
 * - 存储: filesDir/engine_contacts.json, 原子写 (tmp + rename), 崩溃自愈
 * - 群组/消息与联系人不同: 严格内存态, 关闭即消散
 */
class PersistentContactStore(context: Context) {

    companion object {
        private const val TAG = "ContactStore"
        private const val FILE_NAME = "engine_contacts.json"
        private const val TMP_SUFFIX = ".tmp"
    }

    private val file = context.filesDir.resolve(FILE_NAME)
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(PersistedContact.serializer())

    private val _contacts = MutableStateFlow<List<Contact>>(emptyList())
    val contacts: StateFlow<List<Contact>> = _contacts.asStateFlow()

    init {
        _contacts.value = load()
    }

    /**
     * 添加联系人 (若指纹已存在则更新昵称) — 落盘
     *
     * v3.23: 指纹入库前统一小写 —— 线上指纹恒为小写 hex
     * (KeyFingerprint "%02x"), 大写条目在消息精确匹配 (==) 中永远
     * 命不中, 首条消息会触发自动再加重复条目。
     */
    @Synchronized
    fun addContact(fingerprint: String, nickname: String) {
        val fp = fingerprint.trim().lowercase()
        val current = _contacts.value.toMutableList()
        val idx = current.indexOfFirst { it.fingerprint == fp }
        if (idx >= 0) {
            current[idx] = current[idx].copy(nickname = nickname)
        } else {
            current.add(Contact(fingerprint = fp, nickname = nickname))
        }
        _contacts.value = current
        persist(current)
    }

    /**
     * 移除联系人 — 落盘
     */
    @Synchronized
    fun removeContact(fingerprint: String) {
        val updated = _contacts.value.filterNot { it.fingerprint == fingerprint }
        if (updated.size != _contacts.value.size) {
            _contacts.value = updated
            persist(updated)
        }
    }

    /**
     * 更新最后消息预览 — 仅内存 (消息文本零落盘红线)
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
     * 清除全部联系人 — 用户显式操作 (同时清空落盘文件)
     */
    @Synchronized
    fun clearAll() {
        _contacts.value = emptyList()
        persist(emptyList())
    }

    /** 冷启动加载; 文件损坏时自愈为空表
     *
     * v3.23 迁移: 旧版本对话框以大写形态落盘的指纹统一归一为小写,
     * 并按指纹去重 (旧大小写双条目合并, 防列表 key 冲突崩溃)。
     */
    private fun load(): List<Contact> = try {
        if (file.exists()) {
            json.decodeFromString(serializer, file.readText())
                .map { Contact(fingerprint = it.fp.trim().lowercase(), nickname = it.nickname) }
                .distinctBy { it.fingerprint }
        } else emptyList()
    } catch (e: Exception) {
        Log.w(TAG, "Contacts file corrupted, starting fresh: ${e.message}")
        emptyList()
    }

    /** 原子落盘 (仅指纹+昵称) */
    private fun persist(contacts: List<Contact>): Boolean = try {
        val payload = json.encodeToString(
            serializer,
            contacts.map { PersistedContact(it.fingerprint, it.nickname) }
        )
        val tmp = file.resolveSibling(FILE_NAME + TMP_SUFFIX)
        tmp.writeText(payload)
        if (!tmp.renameTo(file)) {
            file.writeText(payload)
        }
        true
    } catch (e: Exception) {
        Log.e(TAG, "Failed to persist contacts: ${e.message}")
        false
    }
}
