package com.securesocial.relay

import com.securesocial.core.crypto.EcdsaOperations
import com.securesocial.core.crypto.KeyFingerprint
import com.securesocial.core.protocol.ErrorCodes
import com.securesocial.core.protocol.MessageType
import com.securesocial.core.protocol.MessageEnvelope
import com.securesocial.core.protocol.ProtocolConstants
import com.securesocial.core.protocol.ProtocolSerializer
import com.securesocial.core.protocol.RelayAuth
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.security.PublicKey
import java.security.SecureRandom
import java.util.Base64

/**
 * 无状态 WebSocket 中继服务器 (v2)
 *
 * 设计原则:
 * - 只做搬运工: 不存储任何消息, 无账号体系, 不解密任何载荷
 * - 内存级实时转发: 消息仅在内存中短暂停留, 完成路由后立即释放
 * - 离线即丢弃: 目标节点离线时, 消息直接丢弃并通知发送方
 *
 * v2 安全增强 (修复: 中继自报身份注册 + 顶号 + 无资源限制):
 * 1. 注册采用挑战-应答: HELLO(指纹+公钥) → CHALLENGE(随机 nonce) → HELLO_AUTH(ECDSA 签名)。
 *    服务器验证 "持有该指纹对应私钥" 后才允许注册, 未认证连接一律拒绝。
 * 2. envelope.source 强制等于注册指纹 (MSG/SIGNAL), 冒充他人发送即断开。
 * 3. 认证前只接受 HELLO / HELLO_AUTH; 认证有 30s 超时。
 * 4. 强制执行载荷 64KB 上限、单连接 20 msg/s 速率限制、单 IP 并发连接上限。
 *
 * 协议: WebSocket (JSON 封皮 + 密文载荷), 建议部署于 TLS 反向代理之后 (wss://)
 */
fun main() {
    val port = System.getenv("RELAY_PORT")?.toIntOrNull() ?: 8080
    val host = System.getenv("RELAY_HOST") ?: "127.0.0.1"  // 默认仅本机监听, 由反向代理 (Caddy/Nginx) 暴露并终结 TLS
    embeddedServer(Netty, port = port, host = host) {
        install(WebSockets)
        configureRouting()
    }.start(wait = true)
}

/** 认证完成的节点信息 */
data class AuthenticatedNode(val fingerprint: String, val publicKey: PublicKey)

fun Application.configureRouting() {
    val logger = LoggerFactory.getLogger("RelayServer")
    val registry = ConnectionRegistry()
    val ecdsa = EcdsaOperations()
    val secureRandom = SecureRandom()
    val b64e = Base64.getEncoder()
    val b64d = Base64.getDecoder()

    routing {
        webSocket(ProtocolConstants.WEBSOCKET_PATH) {
            val remoteHost = call.request.local.remoteHost
            logger.info("New WebSocket connection from $remoteHost")

            // v2: 单 IP 并发连接配额
            if (!registry.tryAcquireIpSlot(remoteHost, ProtocolConstants.MAX_CONNECTIONS_PER_IP)) {
                logger.warn("Connection quota exceeded for $remoteHost")
                close(CloseReason(CloseReason.Codes.TryAgainLater, "Too many connections from this IP"))
                return@webSocket
            }

            // 已认证节点 (认证成功后赋值, 断开时条件注销)
            var node: AuthenticatedNode? = null

            try {
                // 步骤 1: 挑战-应答认证 (v2)
                node = withTimeoutOrNull(ProtocolConstants.AUTH_TIMEOUT_MS) {
                    waitForHelloAndAuth(ecdsa, secureRandom, b64e, b64d, logger)
                }
                if (node == null) {
                    send(Frame.Text(ProtocolSerializer.encodeError(
                        ErrorCodes.AUTH_FAILED, "Authentication failed or timed out"
                    )))
                    close(CloseReason(CloseReason.Codes.ViolatedPolicy, "Authentication required"))
                    return@webSocket
                }

                // 步骤 2: 注册节点 (已证明持有身份私钥)
                registry.register(node.fingerprint, this)
                logger.info("Node authenticated & registered: ${node.fingerprint.take(8)}... (${registry.onlineCount()} online)")

                // 步骤 3: 消息循环 (带速率限制)
                var rateWindowStart = System.currentTimeMillis()
                var rateWindowCount = 0

                for (frame in incoming) {
                    if (frame !is Frame.Text) continue
                    val raw = frame.readText()

                    // v2: 载荷大小上限强制执行
                    if (raw.length > ProtocolConstants.MAX_PAYLOAD_SIZE) {
                        send(Frame.Text(ProtocolSerializer.encodeError(
                            ErrorCodes.PAYLOAD_TOO_LARGE,
                            "Payload exceeds ${ProtocolConstants.MAX_PAYLOAD_SIZE} bytes"
                        )))
                        close(CloseReason(CloseReason.Codes.ViolatedPolicy, "Payload too large"))
                        break
                    }

                    // v2: 简单滑动窗口速率限制
                    val now = System.currentTimeMillis()
                    if (now - rateWindowStart >= 1_000L) {
                        rateWindowStart = now
                        rateWindowCount = 0
                    }
                    if (++rateWindowCount > ProtocolConstants.MAX_MSG_PER_SECOND) {
                        logger.warn("Rate limit exceeded for ${node.fingerprint.take(8)}...")
                        close(CloseReason(CloseReason.Codes.ViolatedPolicy, "Rate limit exceeded"))
                        break
                    }

                    val envelope = ProtocolSerializer.decode(raw) ?: continue

                    // v2: 发送方身份强校验 —— 消息只能以自己的注册身份发出
                    when (envelope.type) {
                        MessageType.MSG, MessageType.SIGNAL -> {
                            if (envelope.source != node.fingerprint) {
                                logger.warn("SOURCE_MISMATCH: conn=${node.fingerprint.take(8)}... claims=${envelope.source?.take(8)}...")
                                send(Frame.Text(ProtocolSerializer.encodeError(
                                    ErrorCodes.SOURCE_MISMATCH,
                                    "envelope.source does not match authenticated identity"
                                )))
                                close(CloseReason(CloseReason.Codes.ViolatedPolicy, "Identity mismatch"))
                                return@webSocket
                            }
                            val target = envelope.target ?: continue
                            val targetSession = registry.findSession(target)
                            if (targetSession != null) {
                                targetSession.send(Frame.Text(ProtocolSerializer.encode(envelope)))
                                logger.debug("${envelope.type} routed: ${envelope.source?.take(8)}... -> ${target.take(8)}...")
                            } else {
                                send(Frame.Text(ProtocolSerializer.encodeError(
                                    ErrorCodes.TARGET_OFFLINE, "Target node is offline", target
                                )))
                            }
                        }

                        MessageType.PING -> {
                            send(Frame.Text(ProtocolSerializer.encodePong(envelope.seq)))
                        }

                        MessageType.PONG -> { /* 心跳响应, 无需处理 */ }
                        MessageType.HELLO,
                        MessageType.CHALLENGE,
                        MessageType.HELLO_AUTH,
                        MessageType.ERROR -> { /* 认证后不再接受, 忽略 */ }
                    }
                }
            } finally {
                registry.releaseIpSlot(remoteHost)
                // 条件注销: 仅当映射仍指向本会话时移除 (防止误删顶号后的新会话)
                node?.let { registry.unregisterIfOwner(it.fingerprint, this) }
                logger.info("Connection closed from $remoteHost (${registry.onlineCount()} online)")
            }
        }
    }
}

/**
 * 挑战-应答认证 (v2)
 *
 * 状态机: HELLO(指纹+公钥) → [服务器校验指纹↔公钥] → CHALLENGE(nonce)
 *        → HELLO_AUTH(签名) → [服务器验签] → 注册确认 (HELLO ack)
 *
 * 任何一步失败/超时/乱序都返回 null, 连接将被断开。
 */
private suspend fun DefaultWebSocketServerSession.waitForHelloAndAuth(
    ecdsa: EcdsaOperations,
    secureRandom: SecureRandom,
    b64e: Base64.Encoder,
    b64d: Base64.Decoder,
    logger: org.slf4j.Logger
): AuthenticatedNode? {
    var fingerprint: String? = null
    var publicKey: PublicKey? = null
    var nonce: ByteArray? = null

    for (frame in incoming) {
        if (frame !is Frame.Text) continue
        val raw = frame.readText()
        if (raw.length > ProtocolConstants.MAX_PAYLOAD_SIZE) return null
        val envelope = ProtocolSerializer.decode(raw) ?: continue

        when (envelope.type) {
            MessageType.HELLO -> {
                // 重复 HELLO 视为协议违规
                if (fingerprint != null) return null

                val source = envelope.source ?: return null
                val hello = envelope.payload?.let { ProtocolSerializer.decodeHelloPayload(it) }
                if (hello == null || hello.pubkey.isBlank() || hello.fingerprint != source) {
                    logger.warn("Malformed HELLO from ${call.request.local.remoteHost}")
                    return null
                }

                // 公钥可解码 且 指纹与公钥匹配
                val pub = try {
                    ecdsa.decodePublicKey(b64d.decode(hello.pubkey))
                } catch (e: Exception) {
                    logger.warn("Undecodable pubkey in HELLO: ${e.message}")
                    return null
                }
                if (!KeyFingerprint.matches(pub, source)) {
                    logger.warn("FINGERPRINT_MISMATCH in HELLO")
                    send(Frame.Text(ProtocolSerializer.encodeError(
                        ErrorCodes.FINGERPRINT_MISMATCH, "Claimed fingerprint does not match pubkey"
                    )))
                    return null
                }

                fingerprint = source
                publicKey = pub
                nonce = ByteArray(32).also { secureRandom.nextBytes(it) }
                send(Frame.Text(ProtocolSerializer.encodeChallenge(source, b64e.encodeToString(nonce))))
            }

            MessageType.HELLO_AUTH -> {
                val fp = fingerprint ?: return null  // 未 HELLO 先 AUTH: 违规
                val auth = envelope.payload?.let { ProtocolSerializer.decodeHelloAuthPayload(it) }
                if (auth == null || auth.fingerprint != fp) return null

                val signature = try {
                    b64d.decode(auth.signature)
                } catch (e: Exception) {
                    return null
                }
                val content = RelayAuth.signingContent(fp, nonce!!)
                if (!ecdsa.verify(publicKey!!, content, signature)) {
                    logger.warn("AUTH_FAILED: signature invalid for ${fp.take(8)}...")
                    send(Frame.Text(ProtocolSerializer.encodeError(
                        ErrorCodes.AUTH_FAILED, "Challenge signature verification failed"
                    )))
                    return null
                }

                // 认证成功 → 注册确认 (沿用 HELLO 作为 ack)
                send(Frame.Text(ProtocolSerializer.encode(
                    MessageEnvelope(type = MessageType.HELLO, target = fp)
                )))
                return AuthenticatedNode(fp, publicKey!!)
            }

            // 认证完成前收到其他类型消息: 一律拒绝
            else -> return null
        }
    }
    return null
}
