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
import com.engine.data.InMemoryContactStore
import com.engine.data.InMemoryMessageStore
import com.engine.data.MessageStatus
import com.engine.data.ChatMessage
import com.engine.data.SessionManager
import com.engine.ipc.AppBIpcClient
import com.engine.network.ConnectionState
import com.engine.network.RelayCallback
import com.engine.network.RelayConnectionManager
import com.securesocial.core.crypto.AesGcmCipher
import com.securesocial.core.protocol.ErrorCodes
import com.securesocial.core.protocol.ProtocolSerializer
import com.securesocial.core.protocol.RelayAuth
import com.securesocial.core.protocol.SignalPayload
import com.securesocial.core.ipc.IpcCallback
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    val contactStore = InMemoryContactStore()
    val relayManager = RelayConnectionManager()
    val cryptoManager = E2ECryptoManager(sessionManager)
    val keyGenerator = TemporaryKeyGenerator()
    val ipcClient = AppBIpcClient(this)
    val boundIdentityStore by lazy { BoundIdentityStore(this) }

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
    private val pendingSignRequests = ConcurrentHashMap<String, (String?) -> Unit>()

    /** 最近一次 launchSign 的发起时间 (v3: 挑战风暴节流) */
    @Volatile
    private var lastSignLaunchAt: Long = 0L

    // ---- v2: 消息防重放 (source → 已见最大 seq) ----
    private val lastSeqBySource = ConcurrentHashMap<String, Long>()

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
                awaitingIpc = false
                val pubKey = sessionManager.identityPublicKey
                if (pubKey == null) {
                    waiter(null)
                    return
                }
                val verifyError = ipcClient.verifyCallbackSignature(callback, pubKey)
                if (verifyError != null) {
                    Log.w(TAG, "Sign callback rejected: $verifyError")
                    waiter(null)
                    return
                }
                waiter(if (callback.isSuccess) callback.result else null)
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
     * 统一的 Vault 签名请求入口 (v3: 挑战风暴节流 + 登录门控)
     *
     * 中继断线重连风暴时 CHALLENGE/ECDH 信令可能密集到达, 若每次都唤起
     * Vault 指纹框会造成连环弹框 (表现为指纹框抖动/闪现)。此处:
     * 0. 未登录 → 一律拒绝 (登录验证期间绝不允许签名请求弹框互抢)
     * 1. 已有签名请求在途 → 跳过 (旧请求对应旧连接, 结果已无意义)
     * 2. 1 秒节流窗口 → 跳过 (防瞬间重复)
     *
     * @param content  待签内容
     * @param onResult 签名结果回调 (Base64 签名或 null=失败)
     * @return true 表示已发起
     */
    private fun launchSignRequest(content: ByteArray, onResult: (String?) -> Unit): Boolean {
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

        val launched = ipcClient.launchSign(sessionId, content)
        if (!launched) {
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

            override fun onError(code: String, message: String) {
                Log.w(TAG, "Relay error: $code - $message")
            }

            override fun onConnected() {
                Log.i(TAG, "Relay connected (authenticated)")
            }

            override fun onDisconnected() {
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
     */
    private fun handleRelayChallenge(nonceBase64: String) {
        val fp = sessionManager.identityFingerprint ?: run {
            relayManager.disconnect()
            return
        }
        val nonce = try {
            b64d.decode(nonceBase64)
        } catch (e: Exception) {
            relayManager.disconnect()
            return
        }
        val content = RelayAuth.signingContent(fp, nonce)

        // v3: 走节流入口; 被跳过时放弃本次注册 (重连后新挑战会再次触发)
        if (!launchSignRequest(content) { sigB64 ->
                if (sigB64 != null) {
                    relayManager.sendHelloAuth(sigB64)
                } else {
                    // Vault 不可用/签名失败: 放弃本次注册
                    Log.w(TAG, "Relay auth: Vault signing unavailable")
                    relayManager.disconnect()
                }
            }
        ) {
            relayManager.disconnect()
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
            val lastSeq = lastSeqBySource[source] ?: 0L
            if (seq <= lastSeq) {
                Log.w(TAG, "Dropped replayed/out-of-order message from ${source.take(8)}...")
                return
            }

            val aad = aesGcm.buildMessageAad(source, myFp, seq)
            val plaintext = cryptoManager.decryptMessage(encryptedPayload, source, aad)
            lastSeqBySource[source] = seq

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
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decrypt message from $source")
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
        val myFp = sessionManager.identityFingerprint ?: return false
        val myIdPub = sessionManager.getIdentityPublicKeyEncoded() ?: return false

        if (sessionManager.getSessionKey(peerFingerprint) == null) {
            sessionManager.rotateEcdhKeyPair()
        }
        val ecdhPub = sessionManager.getMyEcdhPublicKeyEncoded() ?: return false
        val content = sessionManager.buildOutgoingSignalSigningContent(ecdhPub, peerFingerprint)
            ?: return false

        // v3: 走节流入口, 防信令风暴连环弹指纹框
        return launchSignRequest(content) { sigB64 ->
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
     * 统一发送入口 (v2): 加密 (AAD 绑定 seq) → 中继发送 → 更新状态
     */
    fun sendMessageToPeer(peerFingerprint: String, messageId: String, text: String) {
        val myFp = sessionManager.identityFingerprint ?: return

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
