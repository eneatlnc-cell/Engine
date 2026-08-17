package com.securesocial.relay

import io.ktor.websocket.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 内存级连接注册表 (v2)
 *
 * 维护公钥指纹 -> WebSocket 会话的映射关系。
 * 纯内存实现, 服务器进程重启后所有连接信息清空。
 *
 * v2 安全增强:
 * - 注册仅能由通过挑战-应答认证的连接触发 (见 Application.kt waitForHelloAndAuth),
 *   未持有身份私钥的攻击者无法注册/顶替任意指纹
 * - 新增单 IP 并发连接配额, 缓解连接洪泛
 *
 * 线程安全: 使用 ConcurrentHashMap 保证并发安全。
 */
class ConnectionRegistry {

    private val connections = ConcurrentHashMap<String, DefaultWebSocketServerSession>()

    // 单 IP 并发连接计数 (v2)
    private val ipCounts = ConcurrentHashMap<String, AtomicInteger>()

    /**
     * 尝试为该 IP 占用一个连接槽位
     *
     * @return false 表示该 IP 连接数已达上限, 应拒绝新连接
     */
    fun tryAcquireIpSlot(ip: String, maxPerIp: Int): Boolean {
        val counter = ipCounts.computeIfAbsent(ip) { AtomicInteger(0) }
        while (true) {
            val current = counter.get()
            if (current >= maxPerIp) return false
            if (counter.compareAndSet(current, current + 1)) return true
        }
    }

    /**
     * 释放该 IP 的一个连接槽位
     */
    fun releaseIpSlot(ip: String) {
        ipCounts[ip]?.let { counter ->
            val current: Int
            do {
                current = counter.get()
            } while (current > 0 && !counter.compareAndSet(current, current - 1))
        }
    }

    /**
     * 注册节点 (仅在挑战-应答认证通过后调用)
     *
     * 同指纹重复注册: 顶号保留。v2 中顶号是安全的 ——
     * 注册前提是调用方完成了身份私钥挑战签名, 只有真实持有者才能顶替自己
     * (典型场景: 设备网络切换后重连)。
     *
     * @param fingerprint 公钥指纹 (全局唯一节点 ID)
     * @param session WebSocket 会话
     */
    fun register(fingerprint: String, session: DefaultWebSocketServerSession) {
        // 如果该指纹已有连接, 先关闭旧连接
        connections[fingerprint]?.let { oldSession ->
            runCatching {
                oldSession.close(CloseReason(CloseReason.Codes.Normal, "Replaced by new authenticated connection"))
            }
        }
        connections[fingerprint] = session
    }

    /**
     * 注销节点
     */
    fun unregister(fingerprint: String) {
        connections.remove(fingerprint)
    }

    /**
     * 条件注销: 仅当该指纹当前映射仍指向本会话时移除 (v2)
     *
     * 防止 "顶号后旧连接断开" 误删新会话的映射
     * (ConcurrentHashMap.remove(key, value) 原子语义)。
     */
    fun unregisterIfOwner(fingerprint: String, session: DefaultWebSocketServerSession) {
        connections.remove(fingerprint, session)
    }

    /**
     * 查找目标节点的 WebSocket 会话
     */
    fun findSession(fingerprint: String): DefaultWebSocketServerSession? {
        return connections[fingerprint]?.takeIf { it.isOpen }
    }

    /**
     * 检查节点是否在线
     */
    fun isOnline(fingerprint: String): Boolean {
        return connections[fingerprint]?.isOpen == true
    }

    /**
     * 获取当前在线节点数
     */
    fun onlineCount(): Int = connections.size

    /**
     * 获取所有在线节点指纹
     */
    fun getAllFingerprints(): Set<String> = connections.keys.toSet()
}
