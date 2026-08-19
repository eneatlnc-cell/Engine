package com.securesocial.relay

import com.securesocial.core.protocol.InviteCode
import java.util.concurrent.ConcurrentHashMap

/**
 * 邀请码目录服务 (v3.14)
 *
 * 中继侧唯一"群组状态": 邀请码 → 群主指纹 的内存映射。
 * - 纯内存 + TTL 过期 (24h), 进程重启即清空 —— 群主重连时重新登记
 * - 不保存任何群成员/群密钥/群名 (端上自持, E2EE)
 * - 查询限流: 单指纹 10 次/分钟 (滑动窗口), 阻断邀请码枚举
 *
 * 线程安全: ConcurrentHashMap; 计数窗口用同步块 (低频路径)。
 */
class RoomRegistry {

    companion object {
        /** 邀请码登记有效期: 24 小时 (群主每日重连刷新) */
        const val REGISTRY_TTL_MS = 24 * 60 * 60 * 1000L

        /** 查询限流: 每分钟最大次数 / 指纹 */
        const val LOOKUP_LIMIT_PER_MIN = 10
    }

    private data class Entry(
        val ownerFingerprint: String,
        val expiresAt: Long
    )

    private val rooms = ConcurrentHashMap<String, Entry>()

    // 查询限流: fingerprint → 最近查询时间戳列表 (滑动窗口)
    private val lookupWindow = ConcurrentHashMap<String, MutableList<Long>>()

    /**
     * 登记邀请码
     *
     * - 码被他人占用 → CODE_TAKEN
     * - 同一码已是自己的映射 → 刷新 TTL (幂等)
     * - 群主可有多张码 (旧码自然过期)
     */
    fun register(code: String, ownerFingerprint: String): String? {
        if (!InviteCode.isValid(code)) return com.securesocial.core.protocol.GroupErrorCodes.INVALID_CODE

        val now = System.currentTimeMillis()
        expireStale(now)

        val existing = rooms[code]
        if (existing != null && existing.ownerFingerprint != ownerFingerprint) {
            return com.securesocial.core.protocol.GroupErrorCodes.CODE_TAKEN
        }

        rooms[code] = Entry(ownerFingerprint, now + REGISTRY_TTL_MS)
        return null
    }

    /**
     * 查询邀请码 → 群主指纹
     *
     * 返回:
     * - null 之外字符串 = 错误码 (INVALID_CODE / NOT_FOUND / RATE_LIMITED)
     * - null = 成功, ownerFingerprint 出参
     */
    fun lookup(code: String, byFingerprint: String): LookupResult {
        if (!InviteCode.isValid(code)) {
            return LookupResult(error = com.securesocial.core.protocol.GroupErrorCodes.INVALID_CODE)
        }

        val now = System.currentTimeMillis()
        expireStale(now)

        if (!tryAcquireLookupSlot(byFingerprint, now)) {
            return LookupResult(error = com.securesocial.core.protocol.GroupErrorCodes.RATE_LIMITED)
        }

        val entry = rooms[code]
            ?: return LookupResult(error = com.securesocial.core.protocol.GroupErrorCodes.NOT_FOUND)

        return LookupResult(ownerFingerprint = entry.ownerFingerprint)
    }

    data class LookupResult(
        val ownerFingerprint: String? = null,
        val error: String? = null
    )

    /** 滑动窗口限流: 每指纹每分钟最多 LOOKUP_LIMIT_PER_MIN 次 */
    private fun tryAcquireLookupSlot(fp: String, now: Long): Boolean {
        val list = lookupWindow.computeIfAbsent(fp) { mutableListOf() }
        synchronized(list) {
            list.removeAll { now - it > 60_000L }
            if (list.size >= LOOKUP_LIMIT_PER_MIN) return false
            list.add(now)
            return true
        }
    }

    /** 惰性清理过期映射 (调用低频, 遍历成本可忽略) */
    private fun expireStale(now: Long) {
        rooms.entries.removeIf { it.value.expiresAt < now }
        if (lookupWindow.size > 10_000) {
            lookupWindow.entries.removeIf { e ->
                synchronized(e.value) { e.value.all { now - it > 60_000L } }
            }
        }
    }

    /** 当前登记数 (监控用) */
    fun size(): Int = rooms.size

    /** 群主主动释放 (解散群时): 仅当映射仍属于该群主 */
    fun release(code: String, ownerFingerprint: String) {
        rooms[code]?.let { entry ->
            if (entry.ownerFingerprint == ownerFingerprint) rooms.remove(code)
        }
    }
}
