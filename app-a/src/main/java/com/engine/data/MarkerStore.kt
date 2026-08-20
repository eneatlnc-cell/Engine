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
 * v3.21 快照类型:
 * - 文本: text 快照明文
 * - 贴纸 (v3.21): stickerId 快照引用 + text 保留线格式原文
 *   (资产随 APK 打包, 跨版本/跨设备均可解析; 版本错配未收录时
 *    回退为文本渲染, 不丢数据)
 * - 图片类支持评估结论: 当前协议无图片消息类型 (仅 text/sticker)。
 *   未来引入时的既定方案 —— 快照不存引用 (图片字节是内存态,
 *   引用必悬空), 而是**复制字节**到 filesDir/marks_assets/ 并在
 *   MarkerItem 增 assetFile 字段; 删除标记物时引用计数清理资产。
 *   在图片消息进入协议前不预置字段 (YAGNI)。
 *
 * 存储:
 * - filesDir/engine_marks.json (应用私有目录, 卸载即清除)
 * - 原子写: 先写 .tmp 再 rename, 崩溃不致文件损坏
 * - 全量读写: 标记物为低频小数据 (数百条以内), 无需数据库
 * - 前向兼容: 新增字段一律给默认值 + ignoreUnknownKeys,
 *   旧版本 JSON / 降级安装均可无损读取
 */
@Serializable
data class MarkerItem(
    val messageId: String,        // 原消息 ID (会话内去重键)
    val peerFingerprint: String,  // 对端指纹 (会话标识)
    val peerName: String,         // 对端昵称快照 (联系人表是内存态, 标记时定格)
    val text: String,             // 消息明文快照 (贴纸消息为线格式原文)
    val isMine: Boolean,          // 是否己方发送
    val messageTimestamp: Long,   // 原消息时间
    val markedAt: Long,           // 标记时间
    val stickerId: String? = null // v3.21: 贴纸引用快照 (null = 文本消息)
) {
    /**
     * v3.21: 列表/导出共用展示文本
     * 贴纸 → "👋 Spark 表情"; 目录未收录 (版本错配) 回退线格式原文
     * (与聊天页兜底一致: 不丢数据); 文本消息原样。
     */
    val displayText: String
        get() = stickerId?.let { StickerCatalog.previewOf(text) } ?: text
}

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
        // v3.16.1: 不设条数/长度上限 —— 标记物由用户自行管理,
        // UI 侧仅以 "仅存本机, 可随时清理" 简短提示交还控制权
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
     * 添加标记 (v3.16.1: 不截断, 原文全量快照; v3.21: 含贴纸引用)
     */
    @Synchronized
    private fun add(message: ChatMessage, peerName: String) {
        val item = MarkerItem(
            messageId = message.id,
            peerFingerprint = message.peerFingerprint,
            peerName = peerName.ifBlank { "用户 ${message.peerFingerprint.take(8)}" },
            text = message.text,
            isMine = message.isMine,
            messageTimestamp = message.timestamp,
            markedAt = System.currentTimeMillis(),
            stickerId = message.stickerId
        )
        // 头插: 最新标记在前
        val updated = listOf(item) + _markers.value
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
     * v3.21: 导出全部标记物为人类可读纯文本 (ShareSheet 分享用)
     *
     * 格式: 每条快照一段 —— 序号/对端/方向/消息时间/内容(贴纸渲染为
     * "👋 Spark 表情")。导出是用户主动行为, 明文快照离开设备由用户自担
     * (与"消息从不落盘"的隐私红线不冲突: 快照本就由用户显式创建)。
     */
    fun exportText(): String {
        val items = _markers.value
        if (items.isEmpty()) return "Engine 标记物导出\n(空)"
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
        return buildString {
            appendLine("Engine 标记物导出")
            appendLine("导出时间: ${sdf.format(java.util.Date())} · 共 ${items.size} 条")
            appendLine()
            items.forEachIndexed { i, m ->
                val direction = if (m.isMine) "我发出的" else "收到"
                appendLine("── ${i + 1} ──")
                appendLine(
                    "${m.peerName} (@${m.peerFingerprint.take(8)}) · $direction · " +
                        sdf.format(java.util.Date(m.messageTimestamp))
                )
                appendLine(m.displayText)
                appendLine()
            }
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
