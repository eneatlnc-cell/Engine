package com.engine

import android.app.Application
import android.content.ComponentCallbacks2
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.engine.crypto.E2ECryptoManager
import com.engine.crypto.TemporaryKeyGenerator
import com.engine.data.BoundIdentityStore
import com.engine.data.PersistentContactStore
import com.engine.data.InMemoryMessageStore
import com.engine.data.MessageStatus
import com.engine.data.ChatMessage
import com.engine.data.SessionManager
import com.engine.data.toMember
import com.engine.ipc.AppBIpcClient
import com.engine.network.ConnectionState
import com.engine.network.RelayCallback
import com.engine.network.RelayConnectionManager
import com.securesocial.core.crypto.AesGcmCipher
import com.securesocial.core.protocol.ErrorCodes
import com.securesocial.core.protocol.ProtocolSerializer
import com.securesocial.core.protocol.RelayAuth
import com.securesocial.core.protocol.SignalPayload
import com.securesocial.core.protocol.SparkEconomy
import com.securesocial.core.ipc.IpcCallback
import com.securesocial.core.ipc.IpcErrorCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.security.PublicKey
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Application 类 — 应用级单例容器 (v2)
 *
 * 信任模型:
 * - 身份私钥仅存于 Vault; Engine 持有公钥 + 指纹
 * - 一切身份签名 (中继挑战应答 / ECDH 信令) 经 IPC 委托 Vault 完成
 *
 * v2 安全增强:
 * - 中继注册: HELLO(指纹+公钥) → CHALLENGE(nonce) → 经 Vault 签名 → HELLO_AUTH
 * - SIGNAL 信令: ECDH 公钥必须携带身份签名, 接收端验签后才采纳 (防 MITM)
 * - 消息: GCM AAD 绑定 (source, target, seq) + 接收端 seq 单调检查 (防重放)
 * - IPC 回调: 签名请求回调在 App 层直接消费, UI 回调交由 ViewModel 验签
 */
class EngineApp : Application() {

    val sessionManager = SessionManager()
    val messageStore = InMemoryMessageStore()

    // v3.14.1: 联系人持久化 (指纹+昵称落盘; 消息预览仅内存)
    // lazy: Application 构造期 Context 未附体, 首次访问在运行期
    val contactStore by lazy { PersistentContactStore(this) }
    val relayManager = RelayConnectionManager()
    val cryptoManager = E2ECryptoManager(sessionManager)
    val keyGenerator = TemporaryKeyGenerator()
    val ipcClient = AppBIpcClient(this)
    val boundIdentityStore by lazy { BoundIdentityStore(this) }

    // v3.16: 标记物仓库 — 用户显式收藏的消息快照 (应用唯一持久化数据)
    val markerStore by lazy { com.engine.data.MarkerStore(this) }

    // v3.14: 群组 (纯内存 —— 与消息/联系人同为易失态, 退出即焚)
    val groupStore = com.engine.data.GroupStore()
    private val groupCrypto = com.engine.crypto.GroupCryptoManager()

    // ---- v3.14: 群消息防重放 ((groupId:source) → 已见最大 seq) ----
    private val lastGroupSeq = SeqGuard()

    // ---- v3.18: 扇出控制帧 (PRESENCE) 防重放, 独立于 lastGroupSeq ----
    // 中继 GROUP_FANOUT 为异步投递、GROUP_MSG 为同步路由, 两者对同一发送者
    // 无顺序保证 —— 共享计数空间会出现 "心跳 seq 先到 → 后到消息被误判重放"。
    // seq 同源 (msgSeqCounter), 各计数空间内仍严格单调, 分离后互不干扰。
    private val lastFanoutSeq = SeqGuard()

    /**
     * v3.17 防重放记录器: source → 已见最大 seq, 带条目上限。
     *
     * 原裸 ConcurrentHashMap 无淘汰策略, 长期运行下 (大量对端 + 群组)
     * 条目只增不减 —— 诊断发现的内存无限增长风险。超出上限按插入序
     * 淘汰最旧记录; 被淘汰 source 的防重放保护重置 (接受极旧重放),
     * 以有界内存换取长期稳定性。
     */
    private class SeqGuard(private val maxEntries: Int = 2048) {
        private val map = LinkedHashMap<String, Long>(64, 0.75f, false)

        @Synchronized
        fun isReplay(key: String, seq: Long): Boolean = seq <= (map[key] ?: 0L)

        @Synchronized
        fun record(key: String, seq: Long) {
            map[key] = seq
            if (map.size > maxEntries) {
                val it = map.entries.iterator()
                var toDrop = map.size - maxEntries
                while (toDrop-- > 0 && it.hasNext()) {
                    it.next()
                    it.remove()
                }
            }
        }
    }

    // ---- v3.14: 待发群控制信令 (peer → 队列; 会话密钥建立后冲刷) ----
    private val pendingCtrlQueue = ConcurrentHashMap<String, MutableList<com.securesocial.core.protocol.GroupCtrlPayload>>()

    /** v3.14: 邀请码查询结果 (入群对话框消费; null = 无待处理结果) */
    val roomLookupResult = MutableStateFlow<com.securesocial.core.protocol.RoomInfoPayload?>(null)

    /** v3.14: 当前待入群的邀请码 (用于匹配 ROOM_INFO 回执) */
    @Volatile
    private var pendingJoinCode: String? = null

    /** v3.14: KEY_REQ 节流 (groupId → 上次请求时间) */
    private val lastKeyReqAt = ConcurrentHashMap<String, Long>()

    /** v3.14: 邀请码登记冲突重试计数 (groupId → 已重试次数) */
    private val inviteRetryCount = ConcurrentHashMap<String, Int>()

    // ---- v3.14.1: 群在场表与主权顺移 ----

    /** 群在场表 (groupId → (成员fp → 最近心跳时间)) */
    private val presenceSeen = ConcurrentHashMap<String, ConcurrentHashMap<String, Long>>()

    /** 本轮中继连接建立时间 (0 = 未连接); 判定他人失联前须连续在线满一个超时窗口 */
    @Volatile
    private var relayConnectedSince = 0L

    private val aesGcm = AesGcmCipher()
    private val b64e = Base64.getEncoder()
    private val b64d = Base64.getDecoder()

    // 主题状态
    var isDarkTheme by mutableStateOf(false)

    /**
     * 显式登录状态: 是否已通过 Vault 指纹验证 (签名回调)
     *
     * v3: 进程级登录态 —— 冷启动 (进程被杀后重启) 需重新验证;
     * 应用切换 (退后台/回前台) 不再复位, 由进程内存天然兜底:
     * 进程死亡时本字段随之清零, 下次启动自然回到未登录态。
     */
    var isLoggedIn by mutableStateOf(false)

    /** 是否正在等待 Vault 的 IPC 回调 */
    @Volatile
    var awaitingIpc: Boolean = false

    /** 登录卡片 "重新绑定密钥" 确认后置 true */
    var pendingRebindToKeys by mutableStateOf(false)

    // ---- v2: 签名请求登记 (中继挑战 / ECDH 信令, 回调在 App 层直接消费) ----
    // v3.4: 值为 (签名结果, 失败错误码); error 非 null 表示 Vault 明确拒绝,
    //       两者皆 null 表示无响应 (超时/Vault 被杀/唤起被拦截)
    private val pendingSignRequests =
        ConcurrentHashMap<String, (String?, IpcErrorCode?) -> Unit>()

    /** 签名请求超时看护任务 (sessionId → 看护 Job) */
    private val signTimeoutJobs = ConcurrentHashMap<String, Job>()

    /** App 级协程域 (签名超时看护 / 挑战重试) */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 最近一次 launchSign 的发起时间 (v3: 挑战风暴节流) */
    @Volatile
    private var lastSignLaunchAt: Long = 0L

    // ---- v3.4: 中继挑战自愈状态 ----

    /** 当前待应答的中继挑战 nonce (Base64); null 表示无待处理挑战 */
    @Volatile
    private var pendingRelayChallenge: String? = null

    /** 最近一次挑战签名失败时间 (失败退避, 防指纹框风暴) */
    @Volatile
    private var lastChallengeFailAt: Long = 0L

    // ---- v2: 消息防重放 (source → 已见最大 seq) ----
    // v3.17: 由裸 ConcurrentHashMap 换为带上限的 SeqGuard, 防
    // lastSeqBySource 无淘汰策略导致长期运行内存无限增长 (诊断发现的低危风险)。
    private val lastSeqBySource = SeqGuard()

    // ---- v3.14.2: 群控制信令防重放 (source → 已见最大 seq; 审计修复: 与 1:1 MSG 分离) ----
    // 原: GROUP_CTRL 与 MSG 共用 lastSeqBySource —— 两类消息 seq 各自独立推进,
    // 共享映射会把对方类型的合法新消息误判为重放 (可用性缺陷), 且语义混淆。
    // AAD 不同已阻断跨类型密文移植, 分离后两条路径各自严格单调。
    private val lastCtrlSeqBySource = SeqGuard()

    // ---- v2: 发送序列号 (时间戳初始化: 进程重启不回落) ----
    private val msgSeqCounter = AtomicLong(System.currentTimeMillis())

    /**
     * App 退到后台时调用 (v3: no-op)
     *
     * v2 曾在此处复位登录态并销毁会话密钥 (每次切回前台都需重新验证),
     * v3 按需求改为进程级登录: 切换应用不再重新验证, 仅进程结束后
     * 下次启动需重新通过 Vault 指纹验证。内存回收由 [onTrimMemory] 兜底。
     */
    fun onAppBackground() {
        // v3: 进程级登录态, 后台切换不清除
    }

    fun toggleTheme() {
        isDarkTheme = !isDarkTheme
    }

    // UI 回调通道 (Vault → Engine), 供 LoginViewModel / KeyBindingViewModel 消费
    private val _pendingCallback = MutableStateFlow<IpcCallback?>(null)
    val pendingCallback: StateFlow<IpcCallback?> = _pendingCallback.asStateFlow()

    /**
     * 投递 Vault 回调 (MainActivity 入口)
     *
     * v2 路由规则:
     * 1. sessionId 命中 pendingSignRequests → 验签后直接派发签名结果 (不经 UI)
     * 2. 其余 → 交给 pendingCallback flow, 由发起 ViewModel 验签消费
     */
    fun deliverCallback(callback: IpcCallback) {
        val sessionId = callback.sessionId

        // 签名请求回调: App 层直接消费
        if (sessionId != null) {
            val waiter = pendingSignRequests.remove(sessionId)
            if (waiter != null) {
                // v3.4: 回调已到达, 取消超时看护; 仅当再无在途请求才复位 IPC 等待标志
                signTimeoutJobs.remove(sessionId)?.cancel()
                if (pendingSignRequests.isEmpty()) awaitingIpc = false

                val pubKey = sessionManager.identityPublicKey
                if (pubKey == null) {
                    Log.w(TAG, "Sign callback rejected: no bound public key")
                    waiter(null, IpcErrorCode.UNKNOWN_ERROR)
                    return
                }
                val verifyError = ipcClient.verifyCallbackSignature(callback, pubKey)
                if (verifyError != null) {
                    Log.w(TAG, "Sign callback rejected: $verifyError")
                    waiter(null, IpcErrorCode.UNKNOWN_ERROR)
                    return
                }
                if (callback.isSuccess) {
                    waiter(callback.result, null)
                } else {
                    waiter(null, callback.errorCode ?: IpcErrorCode.UNKNOWN_ERROR)
                }
                return
            }
        }

        // UI 流程回调 (登录 / 绑定): 交由 ViewModel 验签
        _pendingCallback.value = callback
    }

    fun consumeCallback() {
        _pendingCallback.value = null
    }

    companion object {
        private const val TAG = "EngineApp"

        /** Vault 签名请求节流窗口 (v3: 挑战/信令风暴防连环弹框) */
        private const val SIGN_LAUNCH_THROTTLE_MS = 1_000L

        /**
         * 签名请求超时 (v3.4): Vault 被杀/唤起被拦截/用户在 Vault 侧
         * 退出时回调永不到达, 超时后清理登记并通知失败。
         * 取值需覆盖用户从容完成一次指纹验证 (含重试) 的时间。
         */
        private const val SIGN_REQUEST_TIMEOUT_MS = 45_000L

        /** 挑战应答被节流后的重试间隔 (v3.4) */
        private const val CHALLENGE_RETRY_DELAY_MS = 2_000L

        /** 挑战签名失败后的退避窗口 (v3.4: 防 Vault 不可用时指纹框风暴) */
        private const val CHALLENGE_FAIL_BACKOFF_MS = 15_000L

        /** v3.14: 群密钥补发请求节流 (防解密失败风暴) */
        private const val KEY_REQ_THROTTLE_MS = 10_000L

        /** v3.14.1: 群心跳间隔 */
        private const val PRESENCE_INTERVAL_MS = 30_000L

        /** v3.18: 扇出关联表条目有效期 (超时视为已送达/不再关联错误) */
        private const val FANOUT_PENDING_TTL_MS = 60_000L

        /** v3.18: 大群心跳隔拍阈值 (≥100 人 → 心跳间隔 30s → 60s) */
        private const val PRESENCE_BIG_GROUP_MEMBERS = 100

        /** v3.14.1: 成员失联判定窗口 (3 个心跳周期无信号 = 失联) */
        private const val MEMBER_TIMEOUT_MS = 90_000L

        /**
         * 中继服务器地址 (v2: 经 BuildConfig 配置, 支持生产 VPS wss:// 地址)
         * 构建时以 -PrelayUrl=... 覆盖, 详见 README 部署章节
         */
        val RELAY_URL: String = BuildConfig.RELAY_URL

        @Volatile
        private var instance: EngineApp? = null

        fun get(): EngineApp = instance!!
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // v2: 恢复绑定身份 (公钥 + 指纹; 不涉及私钥)
        boundIdentityStore.getBoundPublicKeyBase64()?.let { pubB64 ->
            sessionManager.adoptBoundIdentity(pubB64)?.let { fp ->
                Log.i(TAG, "Bound identity restored: ${fp.take(8)}...")
            }
        }

        setupRelayCallback()
        startGroupPresenceLoop()
    }

    /**
     * 登录验证成功后调用: 恢复绑定身份并连接中继
     *
     * v3: 唯一合法的中继连接入口 —— 仅登录后调用。
     * 绑定流程 (KeyBindingViewModel) 不再触发连接, 消除
     * "未登录即连接 → 挑战指纹框与登录验证框互抢" 的循环。
     */
    fun onLoginVerified() {
        if (sessionManager.identityFingerprint == null) {
            boundIdentityStore.getBoundPublicKeyBase64()?.let {
                sessionManager.adoptBoundIdentity(it)
            }
        }
        connectRelay()
    }

    /**
     * 统一的 Vault 签名请求入口 (v3: 挑战风暴节流 + 登录门控; v3.4: 超时看护)
     *
     * 中继断线重连风暴时 CHALLENGE/ECDH 信令可能密集到达, 若每次都唤起
     * Vault 指纹框会造成连环弹框 (表现为指纹框抖动/闪现)。此处:
     * 0. 未登录 → 一律拒绝 (登录验证期间绝不允许签名请求弹框互抢)
     * 1. 已有签名请求在途 → 跳过 (旧请求对应旧连接, 结果已无意义)
     * 2. 1 秒节流窗口 → 跳过 (防瞬间重复)
     * 3. v3.4 超时看护: Vault 被杀/唤起被拦截/用户在 Vault 侧直接退出时
     *    回调永远不会到达。若不清理, 在途门控会永久拒绝后续所有签名请求
     *    ("杀进程后无法完成签名" 的直接根因)。超时后移除登记并通知失败。
     *
     * @param content  待签内容
     * @param onResult 结果回调: (Base64 签名, null)=成功; (null, 错误码)=失败
     * @return true 表示已发起
     */
    private fun launchSignRequest(
        content: ByteArray,
        onResult: (String?, IpcErrorCode?) -> Unit
    ): Boolean {
        // v3: 登录门控 —— 登录指纹框 (VerifyActivity) 进行中绝不弹签名框
        if (!isLoggedIn) {
            Log.w(TAG, "Sign request rejected: not logged in")
            return false
        }
        if (pendingSignRequests.isNotEmpty()) {
            Log.w(TAG, "Sign request throttled: another request in flight")
            return false
        }
        val now = System.currentTimeMillis()
        if (now - lastSignLaunchAt < SIGN_LAUNCH_THROTTLE_MS) {
            Log.w(TAG, "Sign request throttled: within cooldown window")
            return false
        }

        val sessionId = UUID.randomUUID().toString()
        pendingSignRequests[sessionId] = onResult
        lastSignLaunchAt = now
        awaitingIpc = true

        // v3.4: 部署超时看护 (Vault 无响应时自动清理, 保证系统可自愈)
        signTimeoutJobs[sessionId] = appScope.launch {
            delay(SIGN_REQUEST_TIMEOUT_MS)
            val expired = pendingSignRequests.remove(sessionId)
            if (expired != null) {
                signTimeoutJobs.remove(sessionId)
                if (pendingSignRequests.isEmpty()) awaitingIpc = false
                Log.w(TAG, "Sign request timed out (${SIGN_REQUEST_TIMEOUT_MS}ms): $sessionId")
                expired(null, IpcErrorCode.SIGN_TIMEOUT)
            }
        }

        val launched = ipcClient.launchSign(sessionId, content)
        if (!launched) {
            signTimeoutJobs.remove(sessionId)?.cancel()
            pendingSignRequests.remove(sessionId)
            awaitingIpc = false
            return false
        }
        return true
    }

    // ==================== 中继连接 (挑战-应答) ====================

    private fun setupRelayCallback() {
        relayManager.setCallback(object : RelayCallback {
            override fun onMessage(source: String, payload: String, seq: Long, target: String?) {
                handleIncomingMessage(source, payload, seq, target)
            }

            override fun onSignal(source: String, payload: String) {
                handleIncomingSignal(source, payload)
            }

            override fun onChallenge(nonceBase64: String) {
                handleRelayChallenge(nonceBase64)
            }

            override fun onError(code: String, message: String, target: String?) {
                Log.w(TAG, "Relay error: $code - $message (target=${target?.take(8)}...)")
                // v3.18: 扇出消息无订阅者 (其余成员全离线) → 关联消息标失败
                if (code == com.securesocial.core.protocol.ErrorCodes.GROUP_NO_SUBSCRIBERS && target != null) {
                    failPendingFanoutMessages(target)
                }
                // v3.18.1: 扇出被令牌桶拒绝 (群级/全局限流, 审计 R6) →
                // 该群最新一条待决消息标失败 (原实现忽略此错误, 被拒消息
                // 永远停留在 SENT 状态, 用户以为已送达)
                if (code == com.securesocial.core.protocol.ErrorCodes.GROUP_RATE_LIMITED && target != null) {
                    failNewestPendingFanout(target)
                }
            }

            // v3.14: 群消息/群控制/目录回执
            override fun onGroupMessage(source: String, target: String?, groupId: String, payload: String, seq: Long) {
                handleIncomingGroupMsg(source, target, groupId, payload, seq)
            }

            override fun onGroupCtrl(source: String, target: String?, groupId: String?, payload: String, seq: Long) {
                handleIncomingGroupCtrl(source, target, payload, seq)
            }

            override fun onRoomInfo(info: com.securesocial.core.protocol.RoomInfoPayload) {
                handleRoomInfo(info)
            }

            override fun onConnected() {
                relayConnectedSince = System.currentTimeMillis()
                Log.i(TAG, "Relay connected (authenticated)")
                // v3.18: 重连后全群重订阅 (中继订阅表为会话态, 断开即除名)
                resubscribeAllGroups()
            }

            // v3.18: 群密钥控制帧扇出 (PRESENCE) → 群密钥解密后分发
            override fun onGroupFanout(source: String, groupId: String, payload: String, seq: Long) {
                handleIncomingGroupFanout(source, groupId, payload, seq)
            }

            override fun onDisconnected() {
                relayConnectedSince = 0L
                Log.i(TAG, "Relay disconnected")
            }
        })
    }

    /**
     * 连接中继 (需要已绑定身份 + 已登录)
     *
     * v3 双门控:
     * - 未登录 → 拒绝 (防绑定完成后未登录就连中继, 挑战签名框
     *   与登录验证框互抢导致 "绑定陷入循环")
     * - 已连接/连接中 → 跳过 (幂等, 防并发重复建链)
     */
    fun connectRelay() {
        if (!isLoggedIn) {
            Log.w(TAG, "connectRelay skipped: not logged in (connect after login only)")
            return
        }
        when (relayManager.connectionState.value) {
            ConnectionState.CONNECTING, ConnectionState.CONNECTED -> {
                Log.d(TAG, "connectRelay skipped: already connected/connecting")
                return
            }
            else -> Unit
        }
        val fp = sessionManager.identityFingerprint ?: run {
            Log.w(TAG, "connectRelay skipped: no bound identity")
            return
        }
        val pubEncoded = sessionManager.getIdentityPublicKeyEncoded() ?: return
        relayManager.connect(RELAY_URL, fp, b64e.encodeToString(pubEncoded))
    }

    /**
     * 处理中继注册挑战 (v2): 委托 Vault 用身份私钥签名后应答
     *
     * 挑战内容: "RELAY-AUTH-V1" ‖ fingerprint ‖ nonce
     *
     * v3.4 重构: 旧实现在被节流/签名失败时直接 disconnect() ——
     * 而 disconnect 会关闭自动重连 (shouldReconnect=false), 连接一旦
     * 因签名不可用中断就永久沉默 ("断线后再也连不上")。新实现:
     * - 挑战 nonce 持久保存, 被节流时延迟重试而非放弃
     * - 签名失败时不主动断开, 交由 RelayConnectionManager 的握手看门狗
     *   判死连接并自动重连 (新连接带来新挑战, 再次尝试)
     * - 失败后进入退避窗口, 防止 Vault 不可用时连环唤起指纹框 (抖动回归)
     */
    private fun handleRelayChallenge(nonceBase64: String) {
        if (sessionManager.identityFingerprint == null) {
            // 无绑定身份无法注册: 放弃 (用户层引导去绑定)
            pendingRelayChallenge = null
            relayManager.disconnect()
            return
        }
        pendingRelayChallenge = nonceBase64
        attemptRelayChallenge()
    }

    /**
     * 尝试应答当前挑战 (v3.4): 节流退避 + 失败退避 + 延迟重试
     */
    private fun attemptRelayChallenge() {
        val nonceBase64 = pendingRelayChallenge ?: return
        val fp = sessionManager.identityFingerprint ?: return
        val nonce = try {
            b64d.decode(nonceBase64)
        } catch (e: Exception) {
            pendingRelayChallenge = null
            relayManager.disconnect()
            return
        }
        val content = RelayAuth.signingContent(fp, nonce)

        // 失败退避: Vault 刚刚签名失败 (被杀/超时), 冷却期内不再唤起指纹框
        val sinceFail = System.currentTimeMillis() - lastChallengeFailAt
        if (lastChallengeFailAt > 0L && sinceFail < CHALLENGE_FAIL_BACKOFF_MS) {
            Log.d(TAG, "Challenge backoff: ${CHALLENGE_FAIL_BACKOFF_MS - sinceFail}ms remaining")
            appScope.launch {
                delay(CHALLENGE_FAIL_BACKOFF_MS - sinceFail)
                if (pendingRelayChallenge != null && isLoggedIn) attemptRelayChallenge()
            }
            return
        }

        val launched = launchSignRequest(content) { sigB64, _ ->
            if (sigB64 != null) {
                pendingRelayChallenge = null
                relayManager.sendHelloAuth(sigB64)
            } else {
                // Vault 不可用/签名失败/超时: 不 disconnect (旧实现会永久断连)。
                // 握手看门狗将判死本连接并自动重连, 新挑战到来时再次尝试;
                // 退避窗口防止指纹框风暴。
                lastChallengeFailAt = System.currentTimeMillis()
                Log.w(TAG, "Relay auth: Vault signing unavailable, backing off")
            }
        }
        if (!launched) {
            // 被节流 (在途请求/冷却窗口): 在途请求结束后必有空档, 稍后重试
            appScope.launch {
                delay(CHALLENGE_RETRY_DELAY_MS)
                if (pendingRelayChallenge != null && isLoggedIn) attemptRelayChallenge()
            }
        }
    }

    // ==================== 消息收发 (AAD + 防重放) ====================

    /**
     * 处理收到的加密消息 (v2):
     * 1. target 必须是本机指纹 (防路由混淆)
     * 2. seq 必须严格递增 (防重放/乱序)
     * 3. GCM AAD 绑定 (source, target, seq) 解密
     */
    private fun handleIncomingMessage(source: String, encryptedPayload: String, seq: Long, target: String?) {
        try {
            val myFp = sessionManager.identityFingerprint ?: return
            if (target != null && target != myFp) {
                Log.w(TAG, "Dropped message with foreign target")
                return
            }

            // 防重放: seq 单调递增检查
            if (lastSeqBySource.isReplay(source, seq)) {
                Log.w(TAG, "Dropped replayed/out-of-order message from ${source.take(8)}...")
                return
            }

            val aad = aesGcm.buildMessageAad(source, myFp, seq)
            val plaintext = cryptoManager.decryptMessage(encryptedPayload, source, aad)
            lastSeqBySource.record(source, seq)

            val msg = ChatMessage(
                peerFingerprint = source,
                text = plaintext,
                isMine = false,
                timestamp = System.currentTimeMillis(),
                status = MessageStatus.DELIVERED
            )
            messageStore.addMessage(source, msg)
            contactStore.updateLastMessage(source, plaintext, msg.timestamp)

            if (contactStore.getContact(source) == null) {
                contactStore.addContact(source, "用户 ${source.take(8)}")
            }
        } catch (e: javax.crypto.AEADBadTagException) {
            // v3.14.2: GCM tag 校验失败 ≠ 一般格式错误 —— 意味着密钥不同步
            // (对端已重置 ECDH 临时密钥) 或密文被篡改。中继强制 source == 认证身份,
            // 篡改者只能是对端自身, 故按密钥失步处理: 清除本端缓存,
            // 待对方 SIGNAL 重握手 (或本端发送时触发重新协商)。不推进 seq,
            // 密钥恢复后同 seq 报文可重新解密成功。
            Log.w(TAG, "GCM tag mismatch from ${source.take(8)}... (key desync?), clearing session key")
            sessionManager.clearSessionKey(source)
        } catch (e: Exception) {
            // 格式/解码类错误: 不影响会话密钥状态
            Log.e(TAG, "Failed to decrypt message from ${source.take(8)}...")
        }
    }

    /**
     * 处理收到的信令 (v2): ECDH 公钥必须经对方身份私钥签名, 验签通过才采纳
     */
    private fun handleIncomingSignal(source: String, payload: String) {
        try {
            val myFp = sessionManager.identityFingerprint ?: return
            val signal = ProtocolSerializer.decodeSignalPayload(payload) ?: run {
                Log.w(TAG, "Malformed signal from ${source.take(8)}...")
                return
            }

            val peerEcdhPub = sessionManager.verifyIncomingSignal(signal, source, myFp)
            if (peerEcdhPub == null) {
                Log.w(TAG, "Signal verification FAILED from ${source.take(8)}... (possible MITM), ignored")
                return
            }

            sessionManager.ensureSessionKey(peerEcdhPub, source)
            Log.i(TAG, "Verified signal accepted: session key established with ${source.take(8)}...")

            // 会话密钥建立后自动补发失败消息 (首条消息体验)
            resendFailedMessages(source)
            // v3.14: 冲刷该对端的待发群控制信令 (入群申请/密钥请求等)
            flushPendingCtrls(source)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process signal from $source")
        }
    }

    /**
     * 发起与对端的密钥交换 (v2): 生成 ECDH 对 → 委托 Vault 签名 → 发送 SIGNAL
     *
     * @return false 表示无法发起 (无身份/未登录)
     */
    fun initiateKeyExchange(peerFingerprint: String): Boolean {
        // 门控: 无身份/未登录直接失败 (v3.17: 原变量 myFp 从未使用, 仅保留守卫)
        sessionManager.identityFingerprint ?: return false
        val myIdPub = sessionManager.getIdentityPublicKeyEncoded() ?: return false

        if (sessionManager.getSessionKey(peerFingerprint) == null) {
            sessionManager.rotateEcdhKeyPair()
        }
        val ecdhPub = sessionManager.getMyEcdhPublicKeyEncoded() ?: return false
        val content = sessionManager.buildOutgoingSignalSigningContent(ecdhPub, peerFingerprint)
            ?: return false

        // v3: 走节流入口, 防信令风暴连环弹指纹框
        return launchSignRequest(content) { sigB64, _ ->
            if (sigB64 != null) {
                val signal = SignalPayload(
                    ecdh = b64e.encodeToString(ecdhPub),
                    idpub = b64e.encodeToString(myIdPub),
                    sig = sigB64
                )
                relayManager.sendSignalPayload(peerFingerprint, signal)
                Log.i(TAG, "Signed signal sent to ${peerFingerprint.take(8)}...")
            } else {
                Log.w(TAG, "Signal signing unavailable (Vault offline)")
            }
        }
    }

    /**
     * v3.17: 本地消息体护栏 — UTF-8 字节数 ≤ SparkEconomy.MAX_MESSAGE_BYTES。
     *
     * 按 UTF-8 字节而非字符数计算 (中英文/Emoji 同权), 与服务端
     * (中继按信封全文长度) 及计费口径一致。图片/表情/贴纸等媒体
     * 载荷应在上游压缩至限额内或转为引用发送。
     *
     * v3.17.1: 文本上限 60KB → 40KB; 媒体消息 (贴纸) 按
     * SparkEconomy.MAX_MEDIA_BYTES (48KB, 解码后字节) 单独计限,
     * 不走本文本护栏 —— 表情面板接入时须区分两轨。
     */
    private fun exceedsMessageLimit(text: String): Boolean =
        text.toByteArray(Charsets.UTF_8).size > SparkEconomy.MAX_MESSAGE_BYTES

    /**
     * 统一发送入口 (v2): 加密 (AAD 绑定 seq) → 中继发送 → 更新状态
     *
     * v3.17: 发送前按 SparkEconomy.MAX_MESSAGE_BYTES 做本地护栏,
     * 超限消息直接判失败, 不再依赖服务端拒绝 —— 图片/表情/贴纸等
     * 媒体载荷同价计费, 超限应在上游压缩或转引用。
     */
    fun sendMessageToPeer(peerFingerprint: String, messageId: String, text: String) {
        val myFp = sessionManager.identityFingerprint ?: return

        if (exceedsMessageLimit(text)) {
            Log.w(TAG, "Message rejected: exceeds ${SparkEconomy.MAX_MESSAGE_BYTES}B limit")
            messageStore.updateMessageStatus(messageId, MessageStatus.FAILED)
            return
        }

        if (cryptoManager.hasSessionKey(peerFingerprint)) {
            val seq = msgSeqCounter.incrementAndGet()
            val aad = aesGcm.buildMessageAad(myFp, peerFingerprint, seq)
            val encrypted = try {
                cryptoManager.encryptMessage(text, peerFingerprint, aad)
            } catch (e: Exception) {
                messageStore.updateMessageStatus(messageId, MessageStatus.FAILED)
                return
            }
            val sent = relayManager.sendMsg(peerFingerprint, encrypted, seq)
            messageStore.updateMessageStatus(
                messageId,
                if (sent) MessageStatus.SENT else MessageStatus.FAILED
            )
        } else {
            // 无会话密钥: 发起签名信令交换, 消息标记失败等待密钥建立后自动补发
            initiateKeyExchange(peerFingerprint)
            messageStore.updateMessageStatus(messageId, MessageStatus.FAILED)
        }
    }

    /**
     * 会话密钥建立后, 自动补发该对端的失败消息
     */
    private fun resendFailedMessages(peerFingerprint: String) {
        val failed = messageStore.getMessages(peerFingerprint).value
            .filter { it.isMine && it.status == MessageStatus.FAILED }
        for (msg in failed) {
            messageStore.updateMessageStatus(msg.id, MessageStatus.PENDING)
            sendMessageToPeer(peerFingerprint, msg.id, msg.text)
        }
    }

    // ==================== v3.14: 群组编排 ====================

    /**
     * 建群 (群主侧)
     *
     * 1. 生成群 ID + AES-256 群密钥 (v1) + 8 位邀请码
     * 2. 向中继登记 邀请码 → 群主指纹 (回执异步, 冲突自动换码重试)
     * 3. 向每位被邀成员发送 KEY 控制信令 (经 1:1 会话密钥加密;
     *    无会话者自动排队并发起 ECDH 交换, 建立后冲刷)
     *
     * @return 群 ID
     */
    fun createGroup(name: String, memberFps: List<String>): String {
        val myFp = sessionManager.identityFingerprint
            ?: throw IllegalStateException("No identity")
        val gid = groupStore.newGroupId()
        val key = groupCrypto.generateGroupKey()
        val me = com.engine.data.GroupMember(myFp, "我", com.securesocial.core.protocol.GroupRoles.OWNER)
        // v3.17.1: 200 人上限 (含群主) —— 中继带宽推导见 GroupLimits;
        // 防御性截断 (UI 正常不会超), 超出部分丢弃并记日志
        val others = memberFps.filter { it != myFp }.distinct()
            .take(com.securesocial.core.protocol.GroupLimits.MAX_MEMBERS - 1)
            .map { fp ->
                com.engine.data.GroupMember(
                    fp = fp,
                    nickname = contactStore.getContact(fp)?.nickname ?: "用户 ${fp.take(8)}",
                    role = com.securesocial.core.protocol.GroupRoles.MEMBER
                )
            }
        if (memberFps.distinct().size > com.securesocial.core.protocol.GroupLimits.MAX_MEMBERS) {
            Log.w(TAG, "createGroup: member list truncated to ${com.securesocial.core.protocol.GroupLimits.MAX_MEMBERS} (GroupLimits.MAX_MEMBERS)")
        }
        val group = com.engine.data.EngineGroup(
            id = gid,
            name = name.ifBlank { "新群组" },
            ownerFp = myFp,
            myRole = com.securesocial.core.protocol.GroupRoles.OWNER,
            members = listOf(me) + others,
            groupKey = key,
            keyVersion = 1,
            inviteCode = com.securesocial.core.protocol.InviteCode.generate(),
            inviteConfirmed = false,
            createdAt = System.currentTimeMillis()
        )
        groupStore.upsert(group)
        // v3.14.1: 建群播种在场表 (全员宽限一个失联窗口)
        seedPresence(gid, group.members.map { it.fp })

        // v3.18: 建群即订阅扇出 (群主第一个订阅; 成员在 KEY 落地时订阅)
        relayManager.sendGroupSubscribe(gid)

        // 中继目录登记 (异步回执; CODE_TAKEN 自动换码重试)
        group.inviteCode?.let { relayManager.sendRoomRegister(it) }

        // 密钥分发 (无会话密钥的对端自动排队 + ECDH)
        group.members.map { it.fp }.filter { it != myFp }.forEach { fp ->
            sendGroupCtrlTo(fp, keyCtrlFor(group))
        }
        return gid
    }

    /**
     * 凭邀请码申请入群 (成员侧)
     *
     * ROOM_LOOKUP → 群主指纹 → JOIN_REQ 控制信令;
     * 群主在线收到即自动批准 (KEY 分发落地)。
     * 结果经 [roomLookupResult] 异步暴露给 UI。
     */
    fun joinByInviteCode(rawCode: String): Boolean {
        val code = rawCode.trim().uppercase()
        val myFp = sessionManager.identityFingerprint ?: return false
        if (!com.securesocial.core.protocol.InviteCode.isValid(code)) {
            roomLookupResult.value = com.securesocial.core.protocol.RoomInfoPayload(
                ok = false, code = code, error = com.securesocial.core.protocol.GroupErrorCodes.INVALID_CODE
            )
            return false
        }
        roomLookupResult.value = null
        pendingJoinCode = code
        val sent = relayManager.sendRoomLookup(code)
        if (!sent) {
            pendingJoinCode = null
            roomLookupResult.value = com.securesocial.core.protocol.RoomInfoPayload(
                ok = false, code = code, error = com.securesocial.core.protocol.ErrorCodes.UNAUTHORIZED
            )
        }
        Log.i(TAG, "Room lookup sent for code $code by ${myFp.take(8)}...")
        return sent
    }

    /**
     * 中继目录回执: 入群查询结果 / 邀请码登记结果
     */
    private fun handleRoomInfo(info: com.securesocial.core.protocol.RoomInfoPayload) {
        val myFp = sessionManager.identityFingerprint ?: return

        // 分支 1: 入群查询回执
        val pending = pendingJoinCode
        if (pending != null && info.code == pending) {
            pendingJoinCode = null
            roomLookupResult.value = info
            if (info.ok && info.ownerFingerprint != null && info.ownerFingerprint != myFp) {
                sendGroupCtrlTo(
                    info.ownerFingerprint!!,
                    com.securesocial.core.protocol.GroupCtrlPayload(
                        action = com.securesocial.core.protocol.GroupCtrlActions.JOIN_REQ,
                        code = pending,
                        requesterFp = myFp
                    )
                )
            }
            return
        }

        // 分支 2: 群主侧登记回执
        val group = groupStore.findByInviteCode(info.code) ?: return
        if (group.inviteConfirmed) return
        if (info.ok) {
            groupStore.setInviteConfirmed(group.id, true)
            Log.i(TAG, "Invite code registered: ${info.code}")
        } else if (info.error == com.securesocial.core.protocol.GroupErrorCodes.CODE_TAKEN) {
            // 冲突: 换码重登记 (最多 3 次)
            val attempts = inviteRetryCount[group.id] ?: 0
            if (attempts < 3) {
                inviteRetryCount[group.id] = attempts + 1
                val newCode = com.securesocial.core.protocol.InviteCode.generate()
                groupStore.updateInviteCode(group.id, newCode, false)
                relayManager.sendRoomRegister(newCode)
            }
        }
    }

    /**
     * 发送群消息 (成员侧)
     *
     * 群密钥加密一次 (AAD 与接收者无关) → 按成员扇出同一份密文;
     * 任一投递成功即视为已发送 (中继离线即丢弃, 无离线补投)。
     *
     * v3.17: 与 1:1 消息同口径的文本护栏 (群密文按成员扇出 N 份,
     * 超限媒体消息在群场景会放大中继流量, 更应在上游压缩)。
     * v3.17.1: 上限 60KB → 40KB; 200 人群单条 40KB 文本扇出 egress
     * ≈10.6MB (100Mbps ≈0.85s 满管), 上限收紧直接压缩群场景最坏流量。
     */
    fun sendGroupMessage(groupId: String, messageId: String, text: String) {
        val myFp = sessionManager.identityFingerprint ?: return
        val group = groupStore.getGroup(groupId) ?: return

        if (exceedsMessageLimit(text)) {
            Log.w(TAG, "Group message rejected: exceeds ${SparkEconomy.MAX_MESSAGE_BYTES}B limit")
            messageStore.updateMessageStatus(messageId, MessageStatus.FAILED)
            return
        }

        val key = group.groupKey ?: run {
            messageStore.updateMessageStatus(messageId, MessageStatus.FAILED)
            return
        }
        val seq = msgSeqCounter.incrementAndGet()
        val aad = groupCrypto.buildGroupAad(groupId, myFp, seq)
        val ciphertext = try {
            groupCrypto.encrypt(text, key, aad)
        } catch (e: Exception) {
            messageStore.updateMessageStatus(messageId, MessageStatus.FAILED)
            return
        }

        // v3.18: 单帧上行扇出 —— 原逐成员 N-1 帧在 >21 人群直接撞中继
        // 20 msg/s 单连接限流 (199 帧突发 = 立即断连), 且 40KB 满额文本
        // × 199 ≈ 10.6MB 上行远超手机上行带宽。现由中继向订阅集扇出,
        // 发送方上行恒为 1 帧 (AAD 绑定 gid+发送者+seq, 密文与接收者无关,
        // 扇出各方解密同一份密文)。
        val others = group.members.any { it.fp != myFp }
        val sent = if (!others) {
            true // 单人群: 无投递对象, 视作成功
        } else {
            pendingFanout[messageId] = groupId to System.currentTimeMillis()
            relayManager.sendGroupMsgFanout(groupId, ciphertext, seq)
        }
        messageStore.updateMessageStatus(
            messageId,
            if (sent) MessageStatus.SENT else MessageStatus.FAILED
        )
        groupStore.updateLastMessage(groupId, text, System.currentTimeMillis())
    }

    /**
     * v3.18: 扇出消息的中继回执关联表 (messageId → groupId/发送时刻)
     *
     * 中继对无订阅者的扇出消息回 GROUP_NO_SUBSCRIBERS (target=groupId),
     * 据此把仍处 SENT 状态的关联消息改判 FAILED (语义 = 其余成员全离线,
     * 与 v3.14 逐成员路径 "全员离线即失败" 一致)。条目由心跳 tick 定期清理。
     */
    private val pendingFanout = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Long>>()

    /** GROUP_NO_SUBSCRIBERS 到达: 关联消息标失败 */
    private fun failPendingFanoutMessages(groupId: String) {
        val now = System.currentTimeMillis()
        pendingFanout.entries.removeIf { (msgId, entry) ->
            val (gid, sentAt) = entry
            val stale = now - sentAt > FANOUT_PENDING_TTL_MS
            if (!stale && gid == groupId) {
                messageStore.updateMessageStatus(msgId, MessageStatus.FAILED)
            }
            stale || gid == groupId
        }
    }

    /** 心跳 tick 顺带清理过期的扇出关联条目 */
    private fun prunePendingFanout() {
        val now = System.currentTimeMillis()
        pendingFanout.entries.removeIf { it.value.second < now - FANOUT_PENDING_TTL_MS }
    }

    /**
     * v3.18.1: GROUP_RATE_LIMITED 到达 → 该群最新一条待决扇出消息标失败
     *
     * 令牌桶拒绝不回执具体帧 (错误信封仅携带 groupId)。突发连发场景下
     * 令牌耗尽后新到帧被拒 —— 被拒的是后发的, 故按 LIFO 关联最新一条;
     * 慢速逐条发送不触限, 此路径仅在快速连发时出现, 关联偏差至多一条。
     */
    private fun failNewestPendingFanout(groupId: String) {
        var newestId: String? = null
        var newestAt = 0L
        for ((msgId, entry) in pendingFanout) {
            if (entry.first != groupId) continue
            if (newestId == null || entry.second > newestAt) {
                newestId = msgId
                newestAt = entry.second
            }
        }
        val id = newestId ?: return
        pendingFanout.remove(id)
        messageStore.updateMessageStatus(id, MessageStatus.FAILED)
    }

    /**
     * 收到群消息: 群密钥解密 + (groupId, source) 防重放
     */
    private fun handleIncomingGroupMsg(source: String, target: String?, groupId: String, payload: String, seq: Long) {
        try {
            val myFp = sessionManager.identityFingerprint ?: return
            if (target != null && target != myFp) return
            val group = groupStore.getGroup(groupId) ?: return
            val key = group.groupKey ?: return

            val seqKey = "$groupId:$source"
            if (lastGroupSeq.isReplay(seqKey, seq)) return

            val aad = groupCrypto.buildGroupAad(groupId, source, seq)
            val text = try {
                groupCrypto.decrypt(payload, key, aad)
            } catch (e: Exception) {
                // 密钥可能落后于群主轮换 → 请求补发 (节流)
                maybeRequestGroupKey(group)
                return
            }
            lastGroupSeq.record(seqKey, seq)

            val senderName = group.members.find { it.fp == source }?.nickname
                ?: "用户 ${source.take(8)}"
            val convKey = com.engine.data.EngineGroup.conversationKey(groupId)
            val msg = ChatMessage(
                peerFingerprint = convKey,
                text = text,
                isMine = false,
                timestamp = System.currentTimeMillis(),
                status = MessageStatus.DELIVERED,
                senderName = senderName
            )
            messageStore.addMessage(convKey, msg)
            groupStore.updateLastMessage(groupId, "$senderName: $text", msg.timestamp)
        } catch (e: Exception) {
            Log.e(TAG, "Group message handling failed: ${e.message}")
        }
    }

    /**
     * 收到扇出控制帧 (v3.18, GROUP_FANOUT): 群密钥解密后分发
     *
     * 当前仅承载 PRESENCE 心跳 (原 1:1 逐成员路径)。密文为
     * GroupCtrlPayload JSON 经群密钥加密 (AAD 绑定 gid+source+seq,
     * 与群消息同构), 防重放走独立计数空间 (见 lastFanoutSeq 注释)。
     */
    private fun handleIncomingGroupFanout(source: String, groupId: String, payload: String, seq: Long) {
        try {
            val group = groupStore.getGroup(groupId) ?: return
            val key = group.groupKey ?: return

            val seqKey = "$groupId:$source"
            if (lastFanoutSeq.isReplay(seqKey, seq)) return

            val aad = groupCrypto.buildGroupAad(groupId, source, seq)
            val plaintext = try {
                groupCrypto.decrypt(payload, key, aad)
            } catch (e: Exception) {
                return  // 密钥落后 (轮换间隙): 本拍丢弃, 心跳周期性重发
            }
            lastFanoutSeq.record(seqKey, seq)

            val ctrl = ProtocolSerializer.decodeGroupCtrlPayload(plaintext) ?: return
            if (ctrl.action == com.securesocial.core.protocol.GroupCtrlActions.PRESENCE) {
                presenceSeen.getOrPut(groupId) { ConcurrentHashMap() }[source] =
                    System.currentTimeMillis()
            }
            // 其余 action 防御性忽略: 当前协议仅 PRESENCE 走扇出,
            // 后续若扩展 (如群级广播信令) 在此分发
        } catch (e: Exception) {
            Log.e(TAG, "Group fanout handling failed: ${e.message}")
        }
    }

    /**
     * 收到群控制信令: 成对解密后按 action 分发
     */
    private fun handleIncomingGroupCtrl(source: String, target: String?, payload: String, seq: Long) {
        val myFp = sessionManager.identityFingerprint ?: return
        if (target != null && target != myFp) return

        // v3.14.2: 独立计数器 (原与 MSG 共享 → 类型间互相顶号, 合法信令被误拒)
        if (lastCtrlSeqBySource.isReplay(source, seq)) return
        val aad = aesGcm.buildMessageAad(source, myFp, seq)
        val plaintext = try {
            cryptoManager.decryptMessage(payload, source, aad)
        } catch (e: Exception) {
            return
        }
        lastCtrlSeqBySource.record(source, seq)

        val ctrl = ProtocolSerializer.decodeGroupCtrlPayload(plaintext) ?: return
        val A = com.securesocial.core.protocol.GroupCtrlActions

        when (ctrl.action) {
            // 密钥分发: 首次到达 = 入群成功; 再次 = 轮换/补发/主权接管
            A.KEY -> applyGroupKey(source, ctrl)

            // 花名册更新 (无密钥变化)
            A.ROSTER -> {
                val gid = ctrl.groupId ?: return
                val group = groupStore.getGroup(gid) ?: return
                val members = ctrl.members.map { it.toMember() }
                groupStore.updateRoster(gid, ctrl.groupName ?: group.name, members)
                // v3.14.1: 新花名册成员播入在场表 (宽限窗口)
                seedPresence(gid, members.map { it.fp })
            }

            // v3.14.1: 在线心跳 → 刷新在场表
            A.PRESENCE -> {
                val gid = ctrl.groupId ?: return
                if (groupStore.getGroup(gid) != null) {
                    presenceSeen.getOrPut(gid) { ConcurrentHashMap() }[source] =
                        System.currentTimeMillis()
                }
            }

            // 群主侧: 入群申请 (凭邀请码匹配群, v3.14 自动批准)
            A.JOIN_REQ -> {
                val code = ctrl.code ?: return
                val group = groupStore.findByInviteCode(code) ?: return
                if (!group.isOwner) return
                if (group.members.any { it.fp == source }) return

                // v3.17.1: 200 人上限 —— 满员拒绝 (JOIN_RESP approved=false),
                // 申请者侧不落地 KEY 即未入群
                if (group.members.size >= com.securesocial.core.protocol.GroupLimits.MAX_MEMBERS) {
                    Log.i(TAG, "Group ${group.id.take(8)}... join rejected: full (${group.members.size} members)")
                    sendGroupCtrlTo(source, com.securesocial.core.protocol.GroupCtrlPayload(
                        action = A.JOIN_RESP,
                        groupId = group.id,
                        approved = false,
                        reason = com.securesocial.core.protocol.GroupErrorCodes.GROUP_FULL
                    ))
                    return
                }

                val nickname = contactStore.getContact(source)?.nickname
                    ?: ctrl.requesterFp?.let { "用户 ${it.take(8)}" }
                    ?: "用户 ${source.take(8)}"
                val updated = group.members +
                        com.engine.data.GroupMember(source, nickname, com.securesocial.core.protocol.GroupRoles.MEMBER)
                groupStore.setMembers(group.id, updated)

                // 新成员: 全量 KEY; 既有成员: ROSTER
                val fresh = groupStore.getGroup(group.id) ?: return
                seedPresence(group.id, listOf(source) + fresh.members.map { it.fp })
                sendGroupCtrlTo(source, keyCtrlFor(fresh))
                val rosterCtrl = ctrlFor(fresh, A.ROSTER)
                fresh.members.map { it.fp }
                    .filter { it != myFp && it != source }
                    .forEach { sendGroupCtrlTo(it, rosterCtrl) }
                Log.i(TAG, "Group ${group.id.take(8)}... member joined: ${source.take(8)}...")
            }

            // 群主侧: 成员退群 → 轮换密钥
            A.LEAVE -> {
                val gid = ctrl.groupId ?: return
                val group = groupStore.getGroup(gid) ?: return
                if (!group.isOwner) return
                val remaining = group.members.filterNot { it.fp == source }
                if (remaining.size == group.members.size) return
                groupStore.setMembers(gid, remaining)
                rotateGroupKey(gid)
                Log.i(TAG, "Group $gid member left: ${source.take(8)}...")
            }

            // 群主侧: 密钥补发请求 (离线成员追上轮换)
            A.KEY_REQ -> {
                val gid = ctrl.groupId ?: return
                val group = groupStore.getGroup(gid) ?: return
                if (!group.isOwner) return
                if (group.members.any { it.fp == source }) {
                    sendGroupCtrlTo(source, keyCtrlFor(group))
                }
            }

            // 成员侧: 解散
            A.DISSOLVE -> {
                val gid = ctrl.groupId ?: return
                val group = groupStore.getGroup(gid) ?: return
                if (group.ownerFp == source) {
                    groupStore.remove(gid)
                    messageStore.clearSession(com.engine.data.EngineGroup.conversationKey(gid))
                    Log.i(TAG, "Group $gid dissolved by owner")
                }
            }

            // 成员侧: 被移除 (预留; 协议先行)
            A.KICK -> {
                val gid = ctrl.groupId ?: return
                groupStore.getGroup(gid)?.let {
                    if (it.ownerFp == source && !it.isOwner) {
                        groupStore.remove(gid)
                        messageStore.clearSession(com.engine.data.EngineGroup.conversationKey(gid))
                    }
                }
            }

            // 成员侧: 入群被拒 (v3.14 自动批准, 此路径预留)
            A.JOIN_RESP -> {
                Log.i(TAG, "Join rejected: ${ctrl.reason}")
            }
        }
    }

    /**
     * KEY 分发落地: 首次 = 入群; 再次 = 轮换/补发/主权接管 (v3.14.1)
     *
     * 守卫:
     * 1. 版本单调: 旧版本拒绝; 同版本仅接受同群主 (幂等重放), 异主拒绝
     * 2. 所有权变更仅当: 我非群主 + 声明者自称群主 + 其为在册成员 +
     *    旧群主在我的在场视图已失联 —— 防在册恶意成员在群主健在时抢权
     */
    private fun applyGroupKey(source: String, ctrl: com.securesocial.core.protocol.GroupCtrlPayload) {
        val gid = ctrl.groupId ?: return
        val myFp = sessionManager.identityFingerprint ?: return
        val existing = groupStore.getGroup(gid)

        if (existing != null) {
            if (ctrl.keyVersion < existing.keyVersion) return
            if (ctrl.keyVersion == existing.keyVersion && ctrl.ownerFp != existing.ownerFp) return
            if (ctrl.ownerFp != null && ctrl.ownerFp != existing.ownerFp) {
                if (existing.isOwner) return
                if (source != ctrl.ownerFp) return
                if (existing.members.none { it.fp == source }) return
                val now = System.currentTimeMillis()
                val ownerAlive = (presenceSeen[gid]?.get(existing.ownerFp) ?: 0L) > now - MEMBER_TIMEOUT_MS
                if (ownerAlive) return
            }
        }

        val members = ctrl.members.map { it.toMember() }
        if (members.isEmpty()) return
        val key = ctrl.keyB64?.let { groupCrypto.base64ToKey(it) }
        val R = com.securesocial.core.protocol.GroupRoles
        val myRole = if (ctrl.ownerFp == myFp) R.OWNER else R.MEMBER

        if (existing == null) {
            if (ctrl.ownerFp == null || key == null) return
            groupStore.upsert(
                com.engine.data.EngineGroup(
                    id = gid,
                    name = ctrl.groupName ?: "群组",
                    ownerFp = ctrl.ownerFp!!,
                    myRole = myRole,
                    members = members,
                    groupKey = key,
                    keyVersion = ctrl.keyVersion,
                    inviteCode = ctrl.code,
                    inviteConfirmed = false,
                    createdAt = System.currentTimeMillis()
                )
            )
            Log.i(TAG, "Joined group ${gid.take(8)}... (${members.size} members)")
        } else {
            val codeChanged = ctrl.code != null && ctrl.code != existing.inviteCode
            val ownershipChanged = ctrl.ownerFp != null && ctrl.ownerFp != existing.ownerFp
            groupStore.upsert(
                existing.copy(
                    name = ctrl.groupName ?: existing.name,
                    ownerFp = ctrl.ownerFp ?: existing.ownerFp,
                    myRole = myRole,
                    members = members,
                    groupKey = key ?: existing.groupKey,
                    keyVersion = maxOf(ctrl.keyVersion, existing.keyVersion),
                    inviteCode = ctrl.code ?: existing.inviteCode,
                    inviteConfirmed = if (codeChanged) false else existing.inviteConfirmed
                )
            )
            if (ownershipChanged) {
                Log.i(TAG, "Group ${gid.take(8)}... ownership migrated to ${ctrl.ownerFp?.take(8)}...")
            }
        }
        // v3.14.1: 花名册全体播入在场表 (宽限窗口, 防刚入群即被判失联)
        seedPresence(gid, members.map { it.fp })

        // v3.18: KEY 落地即订阅扇出 —— 首次到达 = 入群成功;
        // 轮换/补发/主权接管到达时订阅已存在, 幂等无害 (KEY 是群秘密
        // 到手的唯一时刻, groupId 仅随 KEY 扩散, 订阅时机与之绑定)
        relayManager.sendGroupSubscribe(gid)
    }

    /**
     * v3.18: 全群重订阅 (重连后调用)
     *
     * 中继订阅表为会话态 (断开即除名), 每次 HELLO ack 后对持有群密钥的
     * 群各发一帧 GROUP_SUBSCRIBE; 未持钥的群不订阅 (拿不到密文也无意义)。
     * 单连接订阅上限 64 群 (中继侧 GROUP_SUBSCRIBE_LIMIT), 超限群退化为
     * 无法扇出 —— 重度用户场景后续再议 (分批订阅/上限提升)。
     */
    private fun resubscribeAllGroups() {
        val count = groupStore.groups.value.count { g ->
            g.groupKey != null && relayManager.sendGroupSubscribe(g.id)
        }
        if (count > 0) {
            Log.d(TAG, "Resubscribed $count group(s) for fanout after reconnect")
        }
    }

    /** 群主: 轮换群密钥并对余员重发 KEY */
    private fun rotateGroupKey(groupId: String) {
        val myFp = sessionManager.identityFingerprint ?: return
        groupStore.rotateKey(groupId, groupCrypto.generateGroupKey())
        val fresh = groupStore.getGroup(groupId) ?: return
        fresh.members.map { it.fp }
            .filter { it != myFp }
            .forEach { sendGroupCtrlTo(it, keyCtrlFor(fresh)) }
    }

    /** 构造群主侧 KEY 控制信令 (携带密钥 + 花名册) */
    private fun keyCtrlFor(group: com.engine.data.EngineGroup): com.securesocial.core.protocol.GroupCtrlPayload =
        ctrlFor(group, com.securesocial.core.protocol.GroupCtrlActions.KEY, includeKey = true)

    private fun ctrlFor(
        group: com.engine.data.EngineGroup,
        action: String,
        includeKey: Boolean = false
    ): com.securesocial.core.protocol.GroupCtrlPayload {
        return com.securesocial.core.protocol.GroupCtrlPayload(
            action = action,
            groupId = group.id,
            groupName = group.name,
            ownerFp = group.ownerFp,
            // v3.14.1: 邀请码随 KEY 分发 —— 主权顺移后新群主可据此重新登记
            code = group.inviteCode,
            members = group.members.map {
                com.securesocial.core.protocol.GroupMemberData(it.fp, it.nickname, it.role)
            },
            keyB64 = if (includeKey) group.groupKey?.let { groupCrypto.keyToBase64(it) } else null,
            keyVersion = group.keyVersion
        )
    }

    /**
     * 发送群控制信令 (1:1 会话密钥加密)
     *
     * 无会话密钥时排队并自动发起 ECDH 交换, 建立后由
     * flushPendingCtrls 冲刷 —— 入群申请等异步流程不丢信令。
     */
    private fun sendGroupCtrlTo(peerFp: String, ctrl: com.securesocial.core.protocol.GroupCtrlPayload): Boolean {
        val myFp = sessionManager.identityFingerprint ?: return false
        if (cryptoManager.hasSessionKey(peerFp)) {
            val seq = msgSeqCounter.incrementAndGet()
            val aad = aesGcm.buildMessageAad(myFp, peerFp, seq)
            val ciphertext = try {
                cryptoManager.encryptMessage(ProtocolSerializer.encodeGroupCtrlJson(ctrl), peerFp, aad)
            } catch (e: Exception) {
                return false
            }
            return relayManager.sendGroupCtrl(peerFp, ctrl.groupId, ciphertext, seq)
        }
        pendingCtrlQueue.getOrPut(peerFp) { mutableListOf() }.add(ctrl)
        initiateKeyExchange(peerFp)
        return false
    }

    /** 会话密钥建立后冲刷该对端的待发群控制信令 */
    private fun flushPendingCtrls(peerFp: String) {
        val queue = pendingCtrlQueue.remove(peerFp) ?: return
        if (!cryptoManager.hasSessionKey(peerFp)) {
            pendingCtrlQueue[peerFp] = queue
            return
        }
        queue.forEach { sendGroupCtrlTo(peerFp, it) }
    }

    /** 解密失败时向群主请求补发密钥 (10s 节流) */
    private fun maybeRequestGroupKey(group: com.engine.data.EngineGroup) {
        val now = System.currentTimeMillis()
        if (now - (lastKeyReqAt[group.id] ?: 0L) < KEY_REQ_THROTTLE_MS) return
        lastKeyReqAt[group.id] = now
        sendGroupCtrlTo(
            group.ownerFp,
            com.securesocial.core.protocol.GroupCtrlPayload(
                action = com.securesocial.core.protocol.GroupCtrlActions.KEY_REQ,
                groupId = group.id
            )
        )
    }

    // ==================== v3.14.1: 在场心跳与主权顺移 ====================

    /**
     * 启动群在场心跳循环 (Application onCreate 起, 常驻)
     *
     * 每 30s 一拍 (大群隔拍 → 实际 60s):
     * 1. 向各群广播 PRESENCE (v3.18: GROUP_FANOUT 单帧上行, 中继向订阅集扇出;
     *    原 1:1 逐成员在 200 人群 = 单 tick 199 帧突发, 直撞 20 msg/s 限流)
     * 2. 判定 (须本轮中继连接连续在线 ≥ 失联窗口, 防重连后全员"失联"误判):
     *    - 我是群主: 清退失联成员 (视同退群 → 轮换密钥)
     *    - 我非群主: 群主失联且我是顺位首位在线成员 → 接管群主权
     *
     * 群生命周期语义: 只要还有一人在线群即存活 (主权顺移接力);
     * 最后一人关闭应用 → 内存态消散, 群彻底消失, 再沟通需凭新邀请码重建。
     */
    private fun startGroupPresenceLoop() {
        appScope.launch {
            while (true) {
                delay(PRESENCE_INTERVAL_MS)
                try {
                    groupPresenceTick()
                } catch (e: Exception) {
                    Log.w(TAG, "Presence tick failed: ${e.message}")
                }
            }
        }
    }

    /** 心跳拍计数 (大群隔拍用) */
    private var presenceTickCount = 0L

    private fun groupPresenceTick() {
        if (relayManager.connectionState.value != ConnectionState.CONNECTED) return
        val myFp = sessionManager.identityFingerprint ?: return
        val now = System.currentTimeMillis()
        val A = com.securesocial.core.protocol.GroupCtrlActions

        presenceTickCount++
        prunePendingFanout()

        for (group in groupStore.groups.value.toList()) {
            if (group.groupKey == null) continue
            val hasOthers = group.members.any { it.fp != myFp }
            if (!hasOthers) continue

            // 1. 心跳广播 (v3.18: 单帧扇出)
            //
            // 原 1:1 逐成员路径每 tick 突发 N-1 帧 (200 人群 = 199 帧),
            // 撞中继 20 msg/s 单连接限流, 全员在线聚合 ≈1,327 帧/s ——
            // 大群无人说话也会被心跳打死。现 GROUP_FANOUT 单帧上行,
            // 中继向订阅集扇出 (尽力投递, 全员离线时静默丢弃)。
            // 大群 (≥100 人) 隔拍: 60s 间隔, 200 人 presence 下行 ≈1MB/s (占管 8%)。
            val everyN = if (group.members.size >= PRESENCE_BIG_GROUP_MEMBERS) 2L else 1L
            if (presenceTickCount % everyN == 0L) {
                val beat = com.securesocial.core.protocol.GroupCtrlPayload(
                    action = A.PRESENCE, groupId = group.id
                )
                val seq = msgSeqCounter.incrementAndGet()
                val aad = groupCrypto.buildGroupAad(group.id, myFp, seq)
                try {
                    val ciphertext = groupCrypto.encrypt(
                        ProtocolSerializer.encodeGroupCtrlJson(beat),
                        group.groupKey!!,
                        aad
                    )
                    relayManager.sendGroupFanout(group.id, ciphertext, seq)
                } catch (e: Exception) {
                    // 群密钥异常 (轮换间隙等): 本拍跳过, 下拍重试
                }
            }

            // 2. 失联判定 (须连续在线满一个窗口)
            if (now - relayConnectedSince < MEMBER_TIMEOUT_MS) continue

            if (group.isOwner) {
                ownerPruneExpiredMembers(group, now)
            } else {
                val seen = presenceSeen[group.id]
                val ownerAlive = (seen?.get(group.ownerFp) ?: 0L) > now - MEMBER_TIMEOUT_MS
                if (!ownerAlive) {
                    // 顺位: 花名册序中, 我之前的在线成员均不在线 → 由我接管
                    val successor = group.members.map { it.fp }
                        .filter { it != group.ownerFp }
                        .firstOrNull { fp ->
                            fp == myFp || (seen?.get(fp) ?: 0L) > now - MEMBER_TIMEOUT_MS
                        }
                    if (successor == myFp) executeTakeover(group, now)
                }
            }
        }
    }

    /**
     * 群主侧: 清退失联成员 (隐式退群)
     *
     * 成员关闭应用 = 本地群态已焚, 无从告别; 群主按在场表清退,
     * 轮换密钥并对余员重发 KEY —— 失联者即使重启也已不在册。
     */
    private fun ownerPruneExpiredMembers(group: com.engine.data.EngineGroup, now: Long) {
        val seen = presenceSeen[group.id] ?: return
        val expired = group.members.map { it.fp }
            .filter { it != group.ownerFp }
            .filter { (seen[it] ?: 0L) <= now - MEMBER_TIMEOUT_MS }
        if (expired.isEmpty()) return

        groupStore.setMembers(group.id, group.members.filterNot { it.fp in expired })
        rotateGroupKey(group.id)
        Log.i(TAG, "Group ${group.id.take(8)}... pruned ${expired.size} absent member(s)")
    }

    /**
     * 主权顺移: 顺位成员接管群 (v3.14.1 核心)
     *
     * 1. 依在场表收缩花名册 (仅保留在线者), 自任群主
     * 2. 轮换群密钥 (keyVersion+1) —— 旧群主/失联者持有的旧钥即刻作废
     * 3. 生成全新邀请码并向中继登记 (旧码仍映射旧群主, 自然作废)
     * 4. 向余员重发 KEY (携带新主/新册/新钥/新码)
     *
     * 接收端经 applyGroupKey 守卫校验 (版本单调 + 旧主失联 + 声明者在册)
     * 后落地; 若多方同时接管, 同版本异主信令先到先得, 后到被拒。
     */
    private fun executeTakeover(group: com.engine.data.EngineGroup, now: Long) {
        val myFp = sessionManager.identityFingerprint ?: return
        val seen = presenceSeen[group.id]
        val R = com.securesocial.core.protocol.GroupRoles

        val keptFps = group.members.map { it.fp }
            .filter { it != group.ownerFp && it != myFp }
            .filter { fp -> (seen?.get(fp) ?: 0L) > now - MEMBER_TIMEOUT_MS }
        val meOld = group.members.first { it.fp == myFp }
        val newRoster = listOf(meOld.copy(role = R.OWNER)) +
                group.members.filter { it.fp in keptFps }
        val dropped = group.members.size - newRoster.size

        val newCode = com.securesocial.core.protocol.InviteCode.generate()
        val newGroup = group.copy(
            ownerFp = myFp,
            myRole = R.OWNER,
            members = newRoster,
            groupKey = groupCrypto.generateGroupKey(),
            keyVersion = group.keyVersion + 1,
            inviteCode = newCode,
            inviteConfirmed = false
        )
        groupStore.upsert(newGroup)
        seedPresence(newGroup.id, newRoster.map { it.fp })

        newGroup.members.map { it.fp }
            .filter { it != myFp }
            .forEach { sendGroupCtrlTo(it, keyCtrlFor(newGroup)) }
        relayManager.sendRoomRegister(newCode)

        Log.i(
            TAG, "Takeover: group ${group.id.take(8)}... owner migrated to me, " +
                    "${keptFps.size} kept, $dropped dropped, key v${newGroup.keyVersion}"
        )
    }

    /** 在场表播种 (新群/入群/接管/花名册更新时, 给予失联判定宽限) */
    private fun seedPresence(groupId: String, fps: List<String>) {
        val now = System.currentTimeMillis()
        val seen = presenceSeen.getOrPut(groupId) { ConcurrentHashMap() }
        fps.forEach { fp -> seen.putIfAbsent(fp, now) }
    }

    /** 退群 (成员) / 解散 (群主) */
    fun leaveGroup(groupId: String) {
        val group = groupStore.getGroup(groupId) ?: return
        if (group.isOwner) {
            dissolveGroup(groupId)
            return
        }
        sendGroupCtrlTo(
            group.ownerFp,
            com.securesocial.core.protocol.GroupCtrlPayload(
                action = com.securesocial.core.protocol.GroupCtrlActions.LEAVE,
                groupId = groupId
            )
        )
        groupStore.remove(groupId)
        messageStore.clearSession(com.engine.data.EngineGroup.conversationKey(groupId))
    }

    /** 解散群组 (仅群主): 通知全员后移除 */
    fun dissolveGroup(groupId: String) {
        val myFp = sessionManager.identityFingerprint ?: return
        val group = groupStore.getGroup(groupId) ?: return
        if (!group.isOwner) return
        val ctrl = com.securesocial.core.protocol.GroupCtrlPayload(
            action = com.securesocial.core.protocol.GroupCtrlActions.DISSOLVE,
            groupId = groupId,
            groupName = group.name,
            ownerFp = myFp
        )
        group.members.map { it.fp }
            .filter { it != myFp }
            .forEach { sendGroupCtrlTo(it, ctrl) }
        groupStore.remove(groupId)
        messageStore.clearSession(com.engine.data.EngineGroup.conversationKey(groupId))
    }

    /**
     * 内存不足时清理非活跃会话缓存 (严禁持久化)
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        when (level) {
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> messageStore.clearAll()

            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND,
            ComponentCallbacks2.TRIM_MEMORY_MODERATE,
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> messageStore.clearAll()
        }
    }
}
