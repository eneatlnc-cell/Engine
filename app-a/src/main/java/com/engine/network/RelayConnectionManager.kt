package com.engine.network

import com.securesocial.core.protocol.MessageType
import com.securesocial.core.protocol.MessageEnvelope
import com.securesocial.core.protocol.ProtocolConstants
import com.securesocial.core.protocol.ProtocolSerializer
import com.securesocial.core.protocol.SignalPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.atomic.AtomicLong

/**
 * 中继连接状态
 */
enum class ConnectionState {
    DISCONNECTED,  // 离线
    CONNECTING,    // 正在连接 (含挑战-应答认证阶段)
    CONNECTED,     // 已连接且已通过认证
    ERROR          // 连接错误
}

/**
 * 中继连接回调接口 (v2)
 */
interface RelayCallback {
    /**
     * 收到 MSG 消息 (加密密文透传)
     *
     * @param seq     发送方声明的序列号 (v2: 防重放用, 上层须做单调校验)
     * @param target  信封声明的接收方 (v2: 上层须校验为本机指纹)
     */
    fun onMessage(source: String, payload: String, seq: Long, target: String?)

    /** 收到 SIGNAL 信令 (v2: 携带身份签名的 ECDH 公钥, 上层验签后采纳) */
    fun onSignal(source: String, payload: String)

    /**
     * 收到中继注册挑战 (v2)
     *
     * 上层须用身份私钥对 RelayAuth.signingContent(fingerprint, nonce) 签名,
     * 再经 sendHelloAuth() 回送。签名由 Vault 完成 (Engine 不持有私钥)。
     */
    fun onChallenge(nonceBase64: String)

    /** 收到 ERROR 消息 */
    fun onError(code: String, message: String)

    /** 连接成功 (挑战-应答认证通过, 收到 HELLO ack) */
    fun onConnected()

    /** 连接断开 */
    fun onDisconnected()
}

/**
 * 中继 WebSocket 连接管理器 (v2)
 *
 * 职责:
 * - 建立 WebSocket 连接 + 发送 HELLO 注册 (携带身份公钥)
 * - 挑战-应答认证: CHALLENGE → (Vault 签名) → HELLO_AUTH → HELLO ack
 * - 发送加密消息 (seq 递增) / 签名信令
 * - 每 30 秒 PING 心跳保活 + 超时判定
 * - 自动重连 (指数退避)
 *
 * 认证状态机 (与服务端 v2 对齐):
 *   CONNECTING → [发 HELLO] → [收 CHALLENGE → onChallenge]
 *            → [sendHelloAuth] → [收 HELLO ack] → CONNECTED
 *   认证完成前发送的业务消息会被服务端拒绝 (UNAUTHORIZED)。
 *
 * 安全: 中继仅透传密文, 永不接触明文; 一切身份签名经 Vault。
 */
class RelayConnectionManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val okHttpClient = OkHttpClient.Builder()
        .pingInterval(0, java.util.concurrent.TimeUnit.MILLISECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var relayUrl: String? = null
    private var myFingerprint: String? = null
    private var myPubkeyBase64: String? = null

    private var callback: RelayCallback? = null

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    // 心跳
    private var heartbeatJob: Job? = null
    @Volatile
    private var lastPongTime: Long = 0L

    // 自动重连
    private var reconnectJob: Job? = null
    @Volatile
    private var shouldReconnect = false
    @Volatile
    private var reconnectDelayMs: Long = INITIAL_RECONNECT_DELAY_MS

    // 本地序列号 (仅用于 PING/SIGNAL 信封; MSG 的 seq 由上层 EngineApp 统一分配)
    private val seqCounter = AtomicLong(System.currentTimeMillis())

    /**
     * 设置回调
     */
    fun setCallback(cb: RelayCallback) {
        callback = cb
    }

    /**
     * 建立 WebSocket 连接并发起注册 (v2)
     *
     * @param url           中继服务器 WebSocket URL
     * @param fingerprint   己方公钥指纹 (节点 ID)
     * @param pubkeyBase64  身份公钥 X.509 编码 (Base64), 用于服务端验签挑战应答
     */
    fun connect(url: String, fingerprint: String, pubkeyBase64: String) {
        relayUrl = url
        myFingerprint = fingerprint
        myPubkeyBase64 = pubkeyBase64
        shouldReconnect = true
        reconnectDelayMs = INITIAL_RECONNECT_DELAY_MS
        doConnect()
    }

    /**
     * 主动断开连接 (不再自动重连)
     */
    fun disconnect() {
        shouldReconnect = false
        reconnectJob?.cancel()
        heartbeatJob?.cancel()
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    /**
     * 回送挑战应答 (v2)
     *
     * 注意: 此时连接仍处于 CONNECTING (未收到 HELLO ack),
     * 直接经底层 socket 发送而不检查连接状态。
     *
     * @param signatureBase64 身份私钥对挑战内容的 ECDSA 签名 (Vault 产出)
     */
    fun sendHelloAuth(signatureBase64: String) {
        val fp = myFingerprint ?: return
        val json = ProtocolSerializer.encodeHelloAuth(fp, signatureBase64)
        webSocket?.send(json)
    }

    /**
     * 发送加密消息 (v2: seq 由上层统一分配并纳入 GCM AAD)
     *
     * @param target  接收方公钥指纹
     * @param payload 加密密文 (Base64)
     * @param seq     序列号 (必须 > 0, 由 EngineApp 的单调计数器分配)
     */
    fun sendMsg(target: String, payload: String, seq: Long = 0): Boolean {
        val actualSeq = if (seq > 0) seq else seqCounter.incrementAndGet()
        val json = ProtocolSerializer.encodeMsg(
            source = myFingerprint ?: return false,
            target = target,
            payload = payload,
            seq = actualSeq
        )
        return send(json)
    }

    /**
     * 发送签名信令 (v2: ECDH 公钥 + 身份公钥 + 身份签名)
     *
     * @param target 接收方公钥指纹
     * @param signal 已完成身份签名的信令载荷
     */
    fun sendSignalPayload(target: String, signal: SignalPayload): Boolean {
        val json = ProtocolSerializer.encodeSignal(
            source = myFingerprint ?: return false,
            target = target,
            signal = signal
        )
        return send(json)
    }

    /**
     * 发送原始 JSON 字符串 (须已通过认证)
     */
    fun send(rawJson: String): Boolean {
        val ws = webSocket
        if (ws == null || _connectionState.value != ConnectionState.CONNECTED) {
            return false
        }
        return ws.send(rawJson)
    }

    /**
     * 发送 PING 心跳
     */
    private fun sendPing() {
        val ping = ProtocolSerializer.encodePing(seqCounter.incrementAndGet())
        // 认证完成后才心跳; 直接经 socket 发送避免状态检查阻塞
        webSocket?.send(ping)
    }

    // ---- 内部实现 ----

    private fun doConnect() {
        val url = relayUrl ?: return
        val fp = myFingerprint ?: return
        val pubkey = myPubkeyBase64 ?: return

        _connectionState.value = ConnectionState.CONNECTING

        val request = Request.Builder()
            .url(url)
            .build()

        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                // 发送 HELLO 注册 (v2: 携带身份公钥, 服务端校验指纹↔公钥后下发挑战)
                val hello = ProtocolSerializer.encodeHello(fp, pubkey)
                webSocket.send(hello)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleIncoming(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                onConnectionLost("Closed: $code $reason")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onConnectionLost("Failure: ${t.message}")
            }
        })
    }

    /**
     * 处理收到的消息 (v2 认证状态机)
     */
    private fun handleIncoming(raw: String) {
        val envelope = ProtocolSerializer.decode(raw) ?: return

        when (envelope.type) {
            MessageType.HELLO -> {
                // 收到 HELLO ack → 挑战-应答认证通过, 注册完成
                _connectionState.value = ConnectionState.CONNECTED
                reconnectDelayMs = INITIAL_RECONNECT_DELAY_MS
                lastPongTime = System.currentTimeMillis()
                startHeartbeat()
                callback?.onConnected()
            }

            MessageType.CHALLENGE -> {
                // 收到注册挑战 → 交给上层委托 Vault 签名 (Engine 不持有私钥)
                val payload = envelope.payload ?: return
                val challenge = ProtocolSerializer.decodeChallengePayload(payload) ?: return
                callback?.onChallenge(challenge.nonce)
            }

            MessageType.MSG -> {
                val source = envelope.source ?: return
                val payload = envelope.payload ?: return
                callback?.onMessage(source, payload, envelope.seq, envelope.target)
            }

            MessageType.SIGNAL -> {
                val source = envelope.source ?: return
                val payload = envelope.payload ?: return
                callback?.onSignal(source, payload)
            }

            MessageType.PONG -> {
                lastPongTime = System.currentTimeMillis()
            }

            MessageType.PING -> {
                val pong = ProtocolSerializer.encodePong(envelope.seq)
                webSocket?.send(pong)
            }

            MessageType.ERROR -> {
                val payload = envelope.payload ?: return
                try {
                    val err = ProtocolSerializer.json.decodeFromString(
                        com.securesocial.core.protocol.ErrorPayload.serializer(),
                        payload
                    )
                    callback?.onError(err.code, err.message)
                } catch (_: Exception) {
                    callback?.onError("UNKNOWN", "Unknown error")
                }
            }

            // 客户端不应收到对端 HELLO_AUTH; 忽略
            MessageType.HELLO_AUTH -> Unit
        }
    }

    /**
     * 启动心跳定时器
     */
    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (true) {
                delay(ProtocolConstants.HEARTBEAT_INTERVAL_MS)
                sendPing()

                // 检查 PONG 超时
                val elapsed = System.currentTimeMillis() - lastPongTime
                if (elapsed > ProtocolConstants.HEARTBEAT_TIMEOUT_MS) {
                    onConnectionLost("Heartbeat timeout")
                    break
                }
            }
        }
    }

    /**
     * 连接丢失处理
     */
    private fun onConnectionLost(reason: String) {
        heartbeatJob?.cancel()
        webSocket = null
        _connectionState.value = ConnectionState.ERROR
        callback?.onDisconnected()

        if (shouldReconnect) {
            scheduleReconnect()
        } else {
            _connectionState.value = ConnectionState.DISCONNECTED
        }
    }

    /**
     * 自动重连 (指数退避)
     */
    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        val delayMs = reconnectDelayMs
        reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(MAX_RECONNECT_DELAY_MS)

        reconnectJob = scope.launch {
            delay(delayMs)
            if (shouldReconnect) {
                doConnect()
            }
        }
    }

    companion object {
        private const val INITIAL_RECONNECT_DELAY_MS = 1000L
        private const val MAX_RECONNECT_DELAY_MS = 30_000L
    }
}
