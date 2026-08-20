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
 *
 * v3.18.1 审计修复 (定向增量审计 2026-08-20):
 * - 订阅幂等修复: 原实现对同一 (会话, 群) 重复订阅会重复累加计数器
 *   (KEY 轮换/重发即触发), 64 群上限被虚耗提前触顶。现以会话级
 *   gid 集合为准, 重复订阅为无副作用幂等操作。
 * - 索引化断连除名: 原实现每次断连全表扫描 subscribers (O(所有群));
 *   现经会话索引直取本会话订阅的群, O(本会话群数)。
 * - 空群回收: 订阅集清空时同步移除订阅集与群级令牌桶 (原桶永不
 *   回收, 群解散后条目永久驻留 = 缓慢内存泄漏)。
 * - 扇出投递改每端独立协程: 原单协程顺序 send, 一个慢消费端
 *   (outgoing 缓冲满则挂起) 会拖住同群其余全部接收端的投递。
 */
class GroupFanoutService {

    private val logger = LoggerFactory.getLogger("GroupFanout")

    /** 扇出协程域: 投递异步执行, 不阻塞发送方的消息读取循环 */
    private val fanoutScope = CoroutineScope(SupervisorJob())

    /** groupId → 订阅会话集 (会话断开由 unsubscribeAll 除名) */
    private val subscribers = ConcurrentHashMap<String, MutableSet<DefaultWebSocketServerSession>>()

    /**
     * 会话 → 已订阅群集合 (订阅幂等与断连索引的真值源)
     *
     * 计数即集合大小, 不再单独维护计数器 —— 杜绝 "重复订阅虚增计数"
     * 的不一致窗口 (审计 R1)。
     */
    private val sessionSubscriptions = ConcurrentHashMap<DefaultWebSocketServerSession, MutableSet<String>>()

    /** groupId → 群级令牌桶 (订阅集清空时回收, 见 unsubscribeAll) */
    private val buckets = ConcurrentHashMap<String, GroupBucket>()

    /** 全局字节令牌桶 */
    private val globalBucket = TokenBucket(
        capacity = ProtocolConstants.GLOBAL_FANOUT_BYTES_BURST,
        refillPerSecond = ProtocolConstants.GLOBAL_FANOUT_BYTES_PER_SECOND
    )

    /** 群订阅总数 (观测用) */
    fun subscriptionCount(): Int = subscribers.size

    /** 扇出准入结果 */
    enum class Admission { OK, NO_SUBSCRIBERS, GROUP_RATE_LIMITED, GLOBAL_LIMIT }

    /**
     * 订阅群 (GROUP_SUBSCRIBE 帧; 幂等)
     *
     * 重复订阅同一群为无副作用操作 (KEY 轮换/补发/入群重试会多次发送);
     * 以 sessionSubscriptions 为准, 计数天然一致。
     *
     * @return false = 该连接订阅群数已达上限 (MAX_GROUP_SUBSCRIPTIONS_PER_CONNECTION)
     */
    fun subscribe(groupId: String, session: DefaultWebSocketServerSession): Boolean {
        val gids = sessionSubscriptions.computeIfAbsent(session) { ConcurrentHashMap.newKeySet() }
        // 会话级互斥: gids 集合的 "查限-加入" 与计数一致 (同一会话的订阅帧
        // 来自同一条连接读取循环, 天然串行; 锁仅防御非常规并发调用)
        synchronized(gids) {
            if (!gids.add(groupId)) return true  // 已订阅: 幂等无副作用
            if (gids.size > ProtocolConstants.MAX_GROUP_SUBSCRIPTIONS_PER_CONNECTION) {
                gids.remove(groupId)
                return false
            }
        }
        // compute 原子挂载: 与 unsubscribeAll 的 computeIfPresent 同键互斥,
        // 不存在 "挂到已被回收的集" 的窗口
        subscribers.compute(groupId) { _, set ->
            val s = set ?: ConcurrentHashMap.newKeySet()
            s.add(session)
            s
        }
        return true
    }

    /**
     * 会话断开: 移除其全部订阅, 并回收空订阅集与群级令牌桶
     *
     * 经会话索引直取 (O(本会话群数)), 不再全表扫描;
     * subscribers.computeIfPresent 与 subscribe 的 compute 同键原子,
     * 并发订阅/退订不会互相孤儿化。
     */
    fun unsubscribeAll(session: DefaultWebSocketServerSession) {
        val gids = sessionSubscriptions.remove(session) ?: return
        for (gid in gids) {
            subscribers.computeIfPresent(gid) { _, set ->
                set.remove(session)
                if (set.isEmpty()) {
                    // 末位离场: 回收订阅集与群级令牌桶 (桶随空闲群重建,
                    // 速率预算重置无正确性影响 —— 空群本无扇出流量)
                    buckets.remove(gid)
                    null
                } else set
            }
        }
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

        // 异步投递, 每端独立协程: 大群扇出 (200 端) 若同步执行会阻塞发送方
        // 读取循环数秒; 单协程顺序 send 则一个慢消费端 (outgoing 满则挂起)
        // 会拖住同群其余全部接收端 (审计 R5) —— 独立协程隔离各端背压
        for (target in recipients) {
            fanoutScope.launch {
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
