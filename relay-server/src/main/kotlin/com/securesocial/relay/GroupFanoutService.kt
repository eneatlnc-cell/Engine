package com.securesocial.relay

import com.securesocial.core.protocol.ProtocolConstants
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 群扇出服务 (v3.18)
 *
 * 职责: 维护 groupId → 订阅会话集 的内存映射, 并对扇出流量做
 * 双层令牌桶限流 (群级 + 全局)。
 *
 * 设计红线: 与 ConnectionRegistry 同性质 —— 纯会话态内存,
 * 断开即除名、进程重启即清零, **永不落盘** (中继零状态红线不破)。
 *
 * 鉴权模型: groupId 为不可猜测 UUID, 仅经 E2E 密钥分发通道扩散,
 * 能订阅即持有群秘密; 中继不验证成员身份, 只路由密文,
 * 非成员订阅者拿到的只是无法解密的密文。
 *
 * 限流模型 (100Mbps 中继带宽 ≈ 12.5MB/s):
 * - 群级消息桶: 10 tokens, 10/s 补充 —— 抑制刷屏/风暴
 * - 群级字节桶: 16MB 突发 / 2MB/s 持续 —— 单条满额贴纸扇出 (≈15.6MB)
 *   可整帧放行 (瞬时 ~1.25s 满管), 连续大贴纸被迫降速
 * - 全局字节桶: 32MB 突发 / 8MB/s 持续 —— 防多个大群饿死 1:1 流量
 */
class GroupFanoutService {

    private val logger = LoggerFactory.getLogger("GroupFanout")

    /** 扇出协程域: 投递异步执行, 不阻塞发送方的消息读取循环 */
    private val fanoutScope = CoroutineScope(SupervisorJob())

    /** groupId → 订阅会话集 (会话断开由 unsubscribeAll 除名) */
    private val subscribers = ConcurrentHashMap<String, MutableSet<DefaultWebSocketServerSession>>()

    /** 会话 → 订阅计数 (单连接订阅数护栏) */
    private val sessionCounts = ConcurrentHashMap<DefaultWebSocketServerSession, AtomicInteger>()

    /** groupId → 群级令牌桶 */
    private val buckets = ConcurrentHashMap<String, GroupBucket>()

    /** 全局字节令牌桶 */
    private val globalBucket = TokenBucket(
        capacity = ProtocolConstants.GLOBAL_FANOUT_BYTES_BURST,
        refillPerSecond = ProtocolConstants.GLOBAL_FANOUT_BYTES_PER_SECOND
    )

    /** 扇出准入结果 */
    enum class Admission { OK, NO_SUBSCRIBERS, GROUP_RATE_LIMITED, GLOBAL_LIMIT }

    /**
     * 订阅群 (幂等; GROUP_SUBSCRIBE 帧)
     *
     * @return false = 该连接订阅群数已达上限 (MAX_GROUP_SUBSCRIPTIONS_PER_CONNECTION)
     */
    fun subscribe(groupId: String, session: DefaultWebSocketServerSession): Boolean {
        val count = sessionCounts.computeIfAbsent(session) { AtomicInteger(0) }
        while (true) {
            val current = count.get()
            if (current >= ProtocolConstants.MAX_GROUP_SUBSCRIPTIONS_PER_CONNECTION) return false
            if (count.compareAndSet(current, current + 1)) break
        }
        subscribers.computeIfAbsent(groupId) { ConcurrentHashMap.newKeySet() }.add(session)
        return true
    }

    /**
     * 会话断开: 移除其全部订阅 (含订阅计数), 并回收空订阅集与闲置令牌桶
     */
    fun unsubscribeAll(session: DefaultWebSocketServerSession) {
        sessionCounts.remove(session)
        subscribers.values.removeAll { set -> set.remove(session) && set.isEmpty() }
    }

    /**
     * 扇出投递入口 (GROUP_MSG 扇出变体 / GROUP_FANOUT)
     *
     * @param frameText 原始帧全文 (中继不解密不重构, 原文透传)
     * @param frameBytes 帧字节数 (egress 计费按 帧字节 × 接收端数)
     * @param sender 发送方会话 (投递时排除, 不回显给自己)
     * @return 准入结果; OK 仅表示已受理异步投递, 不保证逐端送达;
     *         NO_SUBSCRIBERS 时由调用方按帧类型决定回报错误 (消息帧) 或静默 (心跳帧)
     */
    fun fanout(
        groupId: String,
        sender: DefaultWebSocketServerSession,
        frameText: String,
        frameBytes: Long
    ): Admission {
        val targets = subscribers[groupId]
        val recipients = targets?.filter { it != sender && !it.outgoing.isClosedForSend }
            ?: emptyList()

        if (recipients.isEmpty()) return Admission.NO_SUBSCRIBERS

        // egress 计费: 帧字节 × 实际接收端数
        val egressBytes = frameBytes * recipients.size
        val bucket = buckets.computeIfAbsent(groupId) { GroupBucket() }

        // 全局与群级双重字节准入 (先全局: 全局护栏更宽, 群级先查可少占全局锁)
        if (!globalBucket.tryConsume(egressBytes)) {
            logger.warn("Global fanout egress limit hit: gid=${groupId.take(8)}... egress=${egressBytes / 1024}KB")
            return Admission.GLOBAL_LIMIT
        }
        if (!bucket.tryConsume(egressBytes)) {
            // 群级拒绝: 归还全局配额 (未实际投递)
            globalBucket.refund(egressBytes)
            logger.debug("Group rate limit: gid=${groupId.take(8)}...")
            return Admission.GROUP_RATE_LIMITED
        }

        // 异步投递: 大群扇出 (200 端) 若同步执行会阻塞发送方读取循环数秒
        fanoutScope.launch {
            for (target in recipients) {
                try {
                    target.send(Frame.Text(frameText))
                } catch (e: Exception) {
                    // 单端投递失败 (对端刚断开等): 静默, 断开钩子会清订阅
                }
            }
        }
        return Admission.OK
    }

    /** 群级令牌桶: 消息速率 + 字节预算 双桶 */
    private class GroupBucket {
        private val msgBucket = TokenBucket(
            capacity = ProtocolConstants.GROUP_FANOUT_MSG_PER_SECOND.toLong(),
            refillPerSecond = ProtocolConstants.GROUP_FANOUT_MSG_PER_SECOND.toLong()
        )
        private val byteBucket = TokenBucket(
            capacity = ProtocolConstants.GROUP_FANOUT_BYTES_BURST,
            refillPerSecond = ProtocolConstants.GROUP_FANOUT_BYTES_PER_SECOND
        )

        @Synchronized
        fun tryConsume(egressBytes: Long): Boolean {
            if (!msgBucket.tryConsume(1)) return false
            if (!byteBucket.tryConsume(egressBytes)) {
                msgBucket.refund(1)
                return false
            }
            return true
        }
    }

    /** 字节令牌桶 (容量=突发上限, 恒速补充; 拒绝时不动桶) */
    private class TokenBucket(
        private val capacity: Long,
        private val refillPerSecond: Long
    ) {
        private var tokens = capacity.toDouble()
        private var lastRefill = System.nanoTime()

        @Synchronized
        fun tryConsume(amount: Long): Boolean {
            refill()
            if (tokens < amount) return false
            tokens -= amount
            return true
        }

        /** 准入失败回滚 (组合准入中后置桶拒绝时归还前置桶配额) */
        @Synchronized
        fun refund(amount: Long) {
            tokens = minOf(capacity.toDouble(), tokens + amount)
        }

        private fun refill() {
            val now = System.nanoTime()
            val elapsedSec = (now - lastRefill) / 1_000_000_000.0
            if (elapsedSec <= 0) return
            tokens = minOf(capacity.toDouble(), tokens + elapsedSec * refillPerSecond)
            lastRefill = now
        }
    }
}
