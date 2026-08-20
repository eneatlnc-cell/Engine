package com.securesocial.relay

import com.securesocial.core.crypto.EcdsaOperations
import com.securesocial.core.crypto.KeyFingerprint
import com.securesocial.core.protocol.ErrorCodes
import com.securesocial.core.protocol.GroupErrorCodes
import com.securesocial.core.protocol.MessageType
import com.securesocial.core.protocol.MessageEnvelope
import com.securesocial.core.protocol.ProtocolConstants
import com.securesocial.core.protocol.ProtocolSerializer
import com.securesocial.core.protocol.RelayAuth
import com.securesocial.core.protocol.RoomInfoPayload
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
 * 4. 强制执行载荷 128KB 上限 (v3.17: 64KB → 128KB, 支撑 40KB 文本 / 48KB 媒体消息)、
 *    单连接 20 msg/s 速率限制、单 IP 并发连接上限。
 *
 * v3.18 群扇出 (修复: >21 人群客户端逐成员扇出撞单连接限流, 心跳 199 帧/tick 打死大群):
 * 5. GROUP_SUBSCRIBE: 认证后订阅群扇出 (groupId 即鉴权, 会话态订阅表, 断开即除名)。
 * 6. GROUP_MSG 无 target → 中继向订阅集单帧扇出 (双层令牌桶: 群级 10 msg/s +
 *    16MB 突发/2MB/s 持续, 全局 32MB 突发/8MB/s 持续); 有 target 保留 v3.14 逐成员路径。
 * 7. GROUP_FANOUT: PRESENCE 等群密钥控制帧扇出 (尽力投递)。
 *    中继仍零落盘零群组语义 (不解密任何载荷, 不验证群成员身份)。
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

/** v3.14.2: 认证阶段最大帧数 (正常流程 ≤3 帧: HELLO + HELLO_AUTH + 冗余) */
private const val AUTH_FRAME_BUDGET = 8

fun Application.configureRouting() {
    val logger = LoggerFactory.getLogger("RelayServer")
    val registry = ConnectionRegistry()
    val roomRegistry = RoomRegistry()   // v3.14: 邀请码目录 (内存 + TTL + 限流)
    val fanoutService = GroupFanoutService()  // v3.18: 群扇出 (订阅表 + 双层令牌桶, 纯会话态)
    val ecdsa = EcdsaOperations()
    val secureRandom = SecureRandom()
    val b64e = Base64.getEncoder()
    val b64d = Base64.getDecoder()

    routing {
        webSocket(ProtocolConstants.WEBSOCKET_PATH) {
            // v3.14.2: 真实客户端 IP 解析 (审计修复: local.remoteHost 在反代后恒为代理地址,
            // 单 IP 配额形同虚设)
            // - 直连: 使用 local.remoteHost
            // - 反代部署 (直连对端为环回): 信任 X-Forwarded-For 首个地址
            //   (仅环回信任 —— 外部客户端伪造的 XFF 头不会生效)
            val directPeer = call.request.local.remoteHost
            val clientIp = if (directPeer == "127.0.0.1" || directPeer == "::1" || directPeer == "0:0:0:0:0:0:0:1") {
                call.request.headers["X-Forwarded-For"]
                    ?.split(',')?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
                    ?: directPeer
            } else directPeer
            logger.info("New WebSocket connection from $clientIp (direct=$directPeer)")

            // v2: 单 IP 并发连接配额
            if (!registry.tryAcquireIpSlot(clientIp, ProtocolConstants.MAX_CONNECTIONS_PER_IP)) {
                logger.warn("Connection quota exceeded for $clientIp")
                close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "Too many connections from this IP"))
                return@webSocket
            }

            // 已认证节点 (认证成功后赋值, 断开时条件注销)
            var node: AuthenticatedNode? = null

            try {
                // 步骤 1: 挑战-应答认证 (v2; v3.14.2: 认证阶段帧预算, 防验签 CPU DoS)
                node = withTimeoutOrNull(ProtocolConstants.AUTH_TIMEOUT_MS) {
                    waitForHelloAndAuth(ecdsa, secureRandom, b64e, b64d, logger)
                }
                if (node == null) {
                    send(Frame.Text(ProtocolSerializer.encodeError(
                        ErrorCodes.AUTH_FAILED, "Authentication failed or timed out"
                    )))
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Authentication required"))
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
                        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Payload too large"))
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
                        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Rate limit exceeded"))
                        break
                    }

                    val envelope = ProtocolSerializer.decode(raw) ?: continue

                    // v2: 发送方身份强校验 —— 消息只能以自己的注册身份发出
                    // v3.14: GROUP_CTRL 与 MSG 同路径透传 (中继零群组状态)
                    // v3.18: GROUP_MSG 按有无 target 分流 —— 有 target 走 v3.14
                    //        逐成员路径 (兼容回退), 无 target 走订阅集扇出
                    when (envelope.type) {
                        MessageType.MSG,
                        MessageType.SIGNAL,
                        MessageType.GROUP_CTRL -> {
                            if (envelope.source != node.fingerprint) {
                                logger.warn("SOURCE_MISMATCH: conn=${node.fingerprint.take(8)}... claims=${envelope.source?.take(8)}...")
                                send(Frame.Text(ProtocolSerializer.encodeError(
                                    ErrorCodes.SOURCE_MISMATCH,
                                    "envelope.source does not match authenticated identity"
                                )))
                                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Identity mismatch"))
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

                        // v3.18: 群消息单帧上行 → 中继向订阅集扇出
                        // (>21 人群的客户端逐成员扇出会撞 20 msg/s 限流, 见 TODO.md P0)
                        MessageType.GROUP_MSG -> {
                            if (envelope.source != node.fingerprint) {
                                send(Frame.Text(ProtocolSerializer.encodeError(
                                    ErrorCodes.SOURCE_MISMATCH,
                                    "envelope.source does not match authenticated identity"
                                )))
                                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Identity mismatch"))
                                return@webSocket
                            }
                            val gid = envelope.groupId
                            if (envelope.target == null && gid != null) {
                                // 扇出路径: 客户端单帧上行, 中继 N 端下行
                                when (fanoutService.fanout(gid, this, raw, raw.toByteArray().size.toLong())) {
                                    GroupFanoutService.Admission.OK -> {}
                                    GroupFanoutService.Admission.NO_SUBSCRIBERS ->
                                        // 订阅集为空 = 其余成员全离线 (断开即除名);
                                        // 回错误信封 (target=gid), 客户端可据此降级/标失败
                                        send(Frame.Text(ProtocolSerializer.encodeError(
                                            ErrorCodes.GROUP_NO_SUBSCRIBERS, "No subscribers for group", gid
                                        )))
                                    GroupFanoutService.Admission.GROUP_RATE_LIMITED ->
                                        send(Frame.Text(ProtocolSerializer.encodeError(
                                            ErrorCodes.GROUP_RATE_LIMITED, "Group fanout rate limited", gid
                                        )))
                                    GroupFanoutService.Admission.GLOBAL_LIMIT ->
                                        send(Frame.Text(ProtocolSerializer.encodeError(
                                            ErrorCodes.GROUP_RATE_LIMITED, "Global fanout egress limit", gid
                                        )))
                                }
                            } else {
                                // v3.14 逐成员路径 (兼容回退: 订阅未建立/旧客户端定向帧)
                                val target = envelope.target ?: continue
                                val targetSession = registry.findSession(target)
                                if (targetSession != null) {
                                    targetSession.send(Frame.Text(ProtocolSerializer.encode(envelope)))
                                    logger.debug("GROUP_MSG routed: ${envelope.source?.take(8)}... -> ${target.take(8)}...")
                                } else {
                                    send(Frame.Text(ProtocolSerializer.encodeError(
                                        ErrorCodes.TARGET_OFFLINE, "Target node is offline", target
                                    )))
                                }
                            }
                        }

                        // v3.18: 群密钥控制帧扇出 (PRESENCE 心跳;
                        // 原逐成员 1:1 扇出在 200 人群 = 单 tick 199 帧突发撞限流)
                        MessageType.GROUP_FANOUT -> {
                            if (envelope.source != node.fingerprint) {
                                send(Frame.Text(ProtocolSerializer.encodeError(
                                    ErrorCodes.SOURCE_MISMATCH,
                                    "envelope.source does not match authenticated identity"
                                )))
                                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Identity mismatch"))
                                return@webSocket
                            }
                            val gid = envelope.groupId ?: continue
                            // 心跳为尽力投递: 无订阅者 (全员离线) 静默丢弃, 限流不回错误
                            fanoutService.fanout(gid, this, raw, raw.toByteArray().size.toLong())
                        }

                        // v3.18: 订阅群扇出 (groupId 即鉴权: 不可猜测 UUID,
                        // 仅经 E2E 密钥分发通道扩散, 中继不验证成员身份)
                        MessageType.GROUP_SUBSCRIBE -> {
                            if (envelope.source != node.fingerprint) {
                                send(Frame.Text(ProtocolSerializer.encodeError(
                                    ErrorCodes.SOURCE_MISMATCH,
                                    "envelope.source does not match authenticated identity"
                                )))
                                return@webSocket
                            }
                            val gid = envelope.groupId
                            if (gid == null) {
                                send(Frame.Text(ProtocolSerializer.encodeError(
                                    ErrorCodes.INVALID_FORMAT, "GROUP_SUBSCRIBE requires groupId"
                                )))
                            } else if (!fanoutService.subscribe(gid, this)) {
                                send(Frame.Text(ProtocolSerializer.encodeError(
                                    ErrorCodes.GROUP_SUBSCRIBE_LIMIT,
                                    "Subscription limit per connection exceeded", gid
                                )))
                            } else {
                                logger.debug("Subscribed ${node.fingerprint.take(8)}... -> group ${gid.take(8)}...")
                            }
                        }

                        MessageType.PING -> {
                            send(Frame.Text(ProtocolSerializer.encodePong(envelope.seq)))
                        }

                        // v3.14: 群组目录服务 (登记/查询邀请码 → 群主指纹)
                        MessageType.ROOM_REGISTER -> {
                            if (envelope.source != node.fingerprint) {
                                send(Frame.Text(ProtocolSerializer.encodeRoomInfo(
                                    node.fingerprint,
                                    RoomInfoPayload(false, error = GroupErrorCodes.UNAUTHORIZED)
                                )))
                                return@webSocket
                            }
                            val req = envelope.payload?.let { ProtocolSerializer.decodeRoomRegisterPayload(it) }
                            // v3.14.2: 载荷指纹必须与认证身份一致 (防目录污染: 冒用他人指纹登记)
                            if (req == null || req.fingerprint != node.fingerprint) {
                                send(Frame.Text(ProtocolSerializer.encodeRoomInfo(
                                    node.fingerprint,
                                    RoomInfoPayload(false, error = ErrorCodes.INVALID_FORMAT)
                                )))
                            } else {
                                val err = roomRegistry.register(req.code, req.fingerprint)
                                send(Frame.Text(ProtocolSerializer.encodeRoomInfo(
                                    node.fingerprint,
                                    if (err == null) RoomInfoPayload(true, code = req.code, ownerFingerprint = req.fingerprint)
                                    else RoomInfoPayload(false, code = req.code, error = err)
                                )))
                            }
                        }

                        MessageType.ROOM_LOOKUP -> {
                            // v3.14.2: 与 MSG 同级的身份校验
                            if (envelope.source != node.fingerprint) {
                                send(Frame.Text(ProtocolSerializer.encodeRoomInfo(
                                    node.fingerprint,
                                    RoomInfoPayload(false, error = GroupErrorCodes.UNAUTHORIZED)
                                )))
                                return@webSocket
                            }
                            val req = envelope.payload?.let { ProtocolSerializer.decodeRoomLookupPayload(it) }
                            if (req == null) {
                                send(Frame.Text(ProtocolSerializer.encodeRoomInfo(
                                    node.fingerprint,
                                    RoomInfoPayload(false, error = ErrorCodes.INVALID_FORMAT)
                                )))
                            } else {
                                // v3.14.2: 限流叠加 IP 维度 (指纹可批量伪造, IP 不可; 双窗口取更严)
                                val result = roomRegistry.lookup(req.code, node.fingerprint, clientIp)
                                send(Frame.Text(ProtocolSerializer.encodeRoomInfo(
                                    node.fingerprint,
                                    if (result.error == null) RoomInfoPayload(true, code = req.code, ownerFingerprint = result.ownerFingerprint)
                                    else RoomInfoPayload(false, code = req.code, error = result.error)
                                )))
                            }
                        }

                        MessageType.PONG -> { /* 心跳响应, 无需处理 */ }
                        MessageType.ROOM_INFO,
                        MessageType.HELLO,
                        MessageType.CHALLENGE,
                        MessageType.HELLO_AUTH,
                        MessageType.ERROR -> { /* 认证后不再接受, 忽略 */ }
                    }
                }
            } finally {
                registry.releaseIpSlot(clientIp)
                // 条件注销: 仅当映射仍指向本会话时移除 (防止误删顶号后的新会话)
                node?.let { registry.unregisterIfOwner(it.fingerprint, this) }
                // v3.18: 会话断开即移除其全部群订阅 (扇出订阅表零残留)
                fanoutService.unsubscribeAll(this)
                logger.info("Connection closed from $clientIp (${registry.onlineCount()} online)")
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

    // v3.14.2: 认证阶段帧预算 (纵深防御; 状态机本身对乱序/畸形一律断连,
    // 此处兜底恶意畸形帧在超时窗口内的空转消耗)
    var authFrames = 0

    for (frame in incoming) {
        if (frame !is Frame.Text) continue
        if (++authFrames > AUTH_FRAME_BUDGET) return null
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
