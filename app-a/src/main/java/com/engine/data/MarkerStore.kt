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
 * 标记物 (v3.16) — 用户显式收藏的消息快照
 *
 * 隐私模型定位 (本应用唯一持久化数据):
 * - 消息本身严格内存态, 退出页面 / 内存回收即消失 (隐私红线)
 * - 标记物是用户主动的、显式的例外: 长按消息选择"标记"时,
 *   将该消息**快照**(文本 + 对端 + 时间)写入应用私有文件
 * - 快照独立于消息生命周期 —— 原消息从内存清除后标记物仍可查看
 *
 * 存储:
 * - filesDir/engine_marks.json (应用私有目录, 卸载即清除)
 * - 原子写: 先写 .tmp 再 rename, 崩溃不致文件损坏
 * - 全量读写: 标记物为低频小数据 (数百条以内), 无需数据库
 */
@Serializable
data class MarkerItem(
    val messageId: String,        // 原消息 ID (会话内去重键)
    val peerFingerprint: String,  // 对端指纹 (会话标识)
    val peerName: String,         // 对端昵称快照 (联系人表是内存态, 标记时定格)
    val text: String,             // 消息明文快照
    val isMine: Boolean,          // 是否己方发送
    val messageTimestamp: Long,   // 原消息时间
    val markedAt: Long            // 标记时间
)

/**
 * 标记物仓库 — 文件式持久化 + 内存 StateFlow 响应式视图
 *
 * 线程安全: 所有写操作 synchronized; 读走无锁 StateFlow。
 */
class MarkerStore(context: Context) {

    companion object {
        private const val TAG = "MarkerStore"
        private const val FILE_NAME = "engine_marks.json"
        private const val TMP_SUFFIX = ".tmp"

        /** 单条标记文本上限 (字符): 防异常长消息撑爆文件 */
        const val MAX_TEXT_CHARS = 2000

        /** 标记物总数上限: 防无限增长 (FIFO 淘汰最旧) */
        const val MAX_MARKS = 500
    }

    private val file = context.filesDir.resolve(FILE_NAME)
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(MarkerItem.serializer())

    private val _markers = MutableStateFlow<List<MarkerItem>>(emptyList())
    val markers: StateFlow<List<MarkerItem>> = _markers.asStateFlow()

    init {
        _markers.value = load()
    }

    /**
     * 标记/取消标记 (幂等切换)
     *
     * @return 切换后的状态: true = 已标记
     */
    fun toggle(message: ChatMessage, peerName: String): Boolean {
        val existing = _markers.value.any { it.messageId == message.id }
        return if (existing) {
            remove(message.id)
            false
        } else {
            add(message, peerName)
            true
        }
    }

    /**
     * 消息是否已标记
     */
    fun isMarked(messageId: String): Boolean =
        _markers.value.any { it.messageId == messageId }

    /**
     * 添加标记 (文本截断 + FIFO 上限淘汰)
     */
    @Synchronized
    private fun add(message: ChatMessage, peerName: String) {
        val item = MarkerItem(
            messageId = message.id,
            peerFingerprint = message.peerFingerprint,
            peerName = peerName.ifBlank { "用户 ${message.peerFingerprint.take(8)}" },
            text = if (message.text.length > MAX_TEXT_CHARS)
                message.text.take(MAX_TEXT_CHARS) + "…"
            else message.text,
            isMine = message.isMine,
            messageTimestamp = message.timestamp,
            markedAt = System.currentTimeMillis()
        )
        // 头插: 最新标记在前; 超限时淘汰末尾 (最旧)
        val updated = (listOf(item) + _markers.value).take(MAX_MARKS)
        if (persist(updated)) {
            _markers.value = updated
        }
    }

    /**
     * 移除单条标记
     */
    @Synchronized
    fun remove(messageId: String) {
        val updated = _markers.value.filterNot { it.messageId == messageId }
        if (updated.size != _markers.value.size && persist(updated)) {
            _markers.value = updated
        }
    }

    /**
     * 清空全部标记 (设置页危险操作, 需二次确认)
     */
    @Synchronized
    fun clearAll() {
        if (persist(emptyList())) {
            _markers.value = emptyList()
        }
    }

    /**
     * 冷启动加载; 文件不存在/损坏时返回空表 (损坏自动自愈)
     */
    private fun load(): List<MarkerItem> = try {
        if (file.exists()) {
            json.decodeFromString(serializer, file.readText())
                .sortedByDescending { it.markedAt }
        } else emptyList()
    } catch (e: Exception) {
        Log.w(TAG, "Marks file corrupted, starting fresh: ${e.message}")
        emptyList()
    }

    /**
     * 原子落盘: 写临时文件 → rename 覆盖
     *
     * @return true 表示写入成功 (失败时内存态不更新, 保数据一致)
     */
    private fun persist(items: List<MarkerItem>): Boolean = try {
        val payload = json.encodeToString(serializer, items)
        val tmp = file.resolveSibling(FILE_NAME + TMP_SUFFIX)
        tmp.writeText(payload)
        if (tmp.renameTo(file)) {
            true
        } else {
            // rename 失败 (被占用等): 退化为直接写
            file.writeText(payload)
            true
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to persist marks: ${e.message}")
        false
    }
}
