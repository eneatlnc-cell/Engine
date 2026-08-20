package com.engine.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

/**
 * ═══════════════════════════════════════════════════════════════════
 *  v3.20 · Spark 表情包目录 (数据层)
 * ═══════════════════════════════════════════════════════════════════
 *
 *  设计决策 —— 引用式贴纸 (v3.19 TODO 中的 48KB 媒体轨方案弃用):
 *  · 表情包随 APK 打包 (60 张动态 WebP, 2.47MB), 收发双方目录必然一致;
 *  · 消息线上只传 `[spark:st001_hello]` 引用 (17 字节 vs 59KB Base64 密文),
 *    单条贴纸消息与普通文本同价 —— 群扇出 egress (帧字节 × 接收端数)
 *    从最坏 15.6MB 降至 ~100 字节级, 令牌桶与带宽压力归零;
 *  · 复用 1:1 / 群消息 / 扇出三条既有加密链路, 协议零改动;
 *  · 引用仍经 E2E 加密, 中继只见密文, 隐私语义与文本消息一致。
 *
 *  兼容性: 收到引用后校验 ID 是否在本机目录中, 未收录 (未来自定义包/
 *  版本错配) 则按普通文本渲染 —— 无静默丢消息。
 */
object StickerCatalog {

    /** 线格式前缀 (足够独特, 用户手打误碰撞概率可忽略) */
    private const val WIRE_PREFIX = "[spark:"
    private const val WIRE_SUFFIX = "]"

    @Serializable
    data class StickerDef(
        val id: String,      // 如 "st001_hello" (与资产文件名一致)
        val name: String,    // 语义名 "hello"
        val emoji: String,   // 联想 emoji "👋"
        val file: String     // 资产相对路径 "stickers/st001_hello.webp"
    )

    @Serializable
    private data class CatalogIndex(
        val pack: String = "Spark",
        val version: Int = 1,
        val stickers: List<StickerDef> = emptyList()
    )

    /** 单张贴纸定义 (UI 消费) */
    data class Sticker(val def: StickerDef) {
        val id: String get() = def.id
        val emoji: String get() = def.emoji
        val name: String get() = def.name
        val assetPath: String get() = def.file
        /** 线格式全文: [spark:st001_hello] */
        val wire: String get() = WIRE_PREFIX + def.id + WIRE_SUFFIX
        /** 会话列表预览: "👋 Spark 表情" */
        val preview: String get() = "$emoji Spark 表情"
    }

    private val json = Json { ignoreUnknownKeys = true }

    private val _stickers = MutableStateFlow<List<Sticker>>(emptyList())
    /** 目录流 (load 后填充; 未加载时为空, UI 显示占位) */
    val stickers: StateFlow<List<Sticker>> = _stickers.asStateFlow()

    /** id → 定义 (load 后可用; 未收录 id 返回 null → 按文本渲染) */
    private val byId = ConcurrentHashMap<String, Sticker>()

    /**
     * 缩略图 LRU (面板网格静态解码, inSampleSize 降到 ~128px)
     * 60 张 × 128px RGBA ≈ 4MB 上限, 恰为 12.5% 堆预算的一部分
     */
    private val thumbCache = object : LruCache<String, Bitmap>(48) {
        override fun sizeOf(key: String, value: Bitmap): Int = 1  // 按张数计
    }

    @Volatile
    private var loaded = false

    /**
     * 从 APK assets 加载目录索引 (EngineApp 启动时后台调用一次)
     *
     * 失败 (索引缺失/损坏) 时保持空目录: 面板显示空态, 贴纸引用按文本渲染。
     */
    fun load(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            try {
                val text = context.assets.open("stickers.json")
                    .bufferedReader().use { it.readText() }
                val index = json.decodeFromString(CatalogIndex.serializer(), text)
                index.stickers.forEach { def ->
                    byId[def.id] = Sticker(def)
                }
                _stickers.value = index.stickers.map { Sticker(it) }
                loaded = true
            } catch (e: Exception) {
                // 目录加载失败不致命: 贴纸功能退化为不可用, 消息链路不受影响
                android.util.Log.w("StickerCatalog", "catalog load failed: ${e.message}")
            }
        }
    }

    /** 按 ID 查找 (未收录返回 null) */
    fun findById(id: String): Sticker? = byId[id]

    /** 目录是否已就绪 */
    val isReady: Boolean get() = loaded

    // ---------------- 线格式 ----------------

    /**
     * 解析线格式引用: "[spark:st001_hello]" → Sticker
     *
     * 仅当整条消息恰为一个引用且 ID 已收录时返回非空;
     * 未收录引用 (版本错配/自定义包) 返回 null, 调用方按普通文本渲染。
     */
    fun parseWire(text: String): Sticker? {
        if (!text.startsWith(WIRE_PREFIX) || !text.endsWith(WIRE_SUFFIX)) return null
        if (text.length <= WIRE_PREFIX.length + WIRE_SUFFIX.length) return null
        val id = text.substring(WIRE_PREFIX.length, text.length - WIRE_SUFFIX.length)
        return findById(id)
    }

    /** 由 ID 构造线格式 (发送方; 未收录返回 null 拒发) */
    fun wireOf(id: String): String? = findById(id)?.wire

    /**
     * 会话列表/标记物快照预览: 引用 → "👋 Spark 表情", 普通文本原样
     */
    fun previewOf(text: String): String = parseWire(text)?.preview ?: text

    // ---------------- 缩略图 ----------------

    /**
     * 面板缩略图 (静态首帧, inSampleSize 解码至 ≤[targetPx], LRU 缓存)
     *
     * 主线程调用: BitmapFactory + inSampleSize 对 44KB webp 是轻量操作
     * (解码后 ≤128px 位图), 60 张网格滚动实测无卡顿; 不引图片库依赖。
     * API 26+: BitmapFactory 对动画 WebP 解码首帧, 全版本可用。
     */
    fun thumbnail(context: Context, sticker: Sticker, targetPx: Int = 128): Bitmap? {
        thumbCache.get(sticker.id)?.let { return it }
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.assets.open(sticker.assetPath).use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= targetPx &&
                bounds.outHeight / (sample * 2) >= targetPx
            ) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = context.assets.open(sticker.assetPath).use {
                BitmapFactory.decodeStream(it, null, opts)
            }
            if (bmp != null) thumbCache.put(sticker.id, bmp)
            bmp
        } catch (e: Exception) {
            null
        }
    }
}
