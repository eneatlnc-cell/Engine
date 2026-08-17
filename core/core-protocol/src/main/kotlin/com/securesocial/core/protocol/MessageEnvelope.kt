package com.securesocial.core.protocol

import kotlinx.serialization.Serializable

/**
 * 消息类型枚举 - 中继服务器仅解析此字段做路由与认证状态机
 *
 * v2 新增:
 * - CHALLENGE: 服务器 → 客户端的注册挑战 (随机 nonce)
 * - HELLO_AUTH: 客户端 → 服务器的挑战应答 (身份私钥对 nonce 的 ECDSA 签名)
 */
@Serializable
enum class MessageType {
    HELLO,        // 节点注册声明 (携带身份公钥)
    CHALLENGE,    // 服务器注册挑战 (v2: 32 字节随机 nonce)
    HELLO_AUTH,   // 挑战应答 (v2: 身份私钥对 nonce 的 ECDSA 签名)
    SIGNAL,       // ECDH 密钥交换信令 (v2: 携带身份公钥 + 签名)
    MSG,          // 加密消息透传
    PING,         // 心跳保活
    PONG,         // 心跳响应
    ERROR         // 错误反馈
}

/**
 * WebSocket 信令协议消息封皮
 *
 * 中继服务器仅解析 type/source/target 字段做路由,
 * payload 字段永不解析, 始终以 Base64/JSON 字符串透传。
 *
 * JSON 结构示例:
 * {"type":"MSG","source":"a1b2...","target":"d4e5...","payload":"base64...","seq":42,"ts":1723737600000}
 */
@Serializable
data class MessageEnvelope(
    val type: MessageType,
    val source: String? = null,        // 发送方公钥指纹
    val target: String? = null,        // 接收方公钥指纹
    val payload: String? = null,       // 加密密文/信令载荷, 中继永不解析
    val seq: Long = 0,                 // 序列号
    val ts: Long = System.currentTimeMillis()  // 时间戳
)

/**
 * HELLO 消息载荷 - 节点注册声明 (v2)
 *
 * v2 安全增强: 必须携带身份公钥 (X.509 Base64)。
 * 服务器校验 fingerprint(pub) == source 后才下发挑战,
 * 注册从 "自报 ID" 升级为 "公钥持有证明"。
 */
@Serializable
data class HelloPayload(
    val fingerprint: String,   // 公钥指纹, 作为全局唯一节点 ID
    val pubkey: String = ""    // 身份公钥 X.509 编码 (Base64), v2 必填
)

/**
 * CHALLENGE 消息载荷 - 服务器注册挑战 (v2)
 *
 * 服务器生成 32 字节 SecureRandom nonce, 客户端须在超时前
 * 用身份私钥对 "RELAY-AUTH-V1|fingerprint|nonce" 完成 ECDSA 签名
 * 并以 HELLO_AUTH 回送。
 */
@Serializable
data class ChallengePayload(
    val fingerprint: String,   // 被挑战的节点指纹
    val nonce: String          // 挑战随机数 (32 字节, Base64)
)

/**
 * HELLO_AUTH 消息载荷 - 挑战应答 (v2)
 *
 * signature = Sign_identityPrivateKey("RELAY-AUTH-V1" ‖ fingerprint ‖ nonce_bytes)
 * 服务器用 HELLO 中声明的公钥验签, 通过后才允许注册与收发消息。
 */
@Serializable
data class HelloAuthPayload(
    val fingerprint: String,   // 应答方指纹
    val signature: String      // ECDSA 签名 (DER, Base64)
)

/**
 * SIGNAL 消息载荷 - ECDH 密钥交换信令 (v2)
 *
 * v2 安全增强: ECDH 公钥必须由发送方的身份私钥签名,
 * 接收方验证签名且指纹匹配后才采纳, 消除中间人替换公钥的攻击面。
 *
 * 签名内容: "ENGINE-SIGNAL-V1" ‖ ecdh_pub_bytes ‖ sender_fp(utf8) ‖ receiver_fp(utf8)
 * 验证条件: ECDSA.verify(idpub, sig, content) && fingerprint(idpub) == envelope.source
 */
@Serializable
data class SignalPayload(
    val ecdh: String,          // 发送方 ECDH 公钥 (X.509, Base64)
    val idpub: String,         // 发送方身份公钥 (X.509, Base64)
    val sig: String            // 身份私钥对绑定内容的 ECDSA 签名 (DER, Base64)
)

/**
 * 挑战应答签名内容的域分隔符
 */
object RelayAuth {
    const val DOMAIN = "RELAY-AUTH-V1"

    /** 构建中继注册挑战的签名内容: "RELAY-AUTH-V1" ‖ fingerprint ‖ nonce */
    fun signingContent(fingerprint: String, nonce: ByteArray): ByteArray {
        return (DOMAIN + fingerprint).toByteArray(Charsets.UTF_8) + nonce
    }
}

/**
 * SIGNAL 签名内容的域分隔符
 */
object SignalAuth {
    const val DOMAIN = "ENGINE-SIGNAL-V1"

    /**
     * 构建 ECDH 信号公钥的签名内容:
     * "ENGINE-SIGNAL-V1" ‖ ecdh_pub_bytes ‖ sender_fp(utf8) ‖ receiver_fp(utf8)
     *
     * 双向绑定: 既绑定 ECDH 公钥本身, 也绑定收发双方身份,
     * 中继无法将签名移植到其他上下文。
     */
    fun signingContent(ecdhPubBytes: ByteArray, senderFp: String, receiverFp: String): ByteArray {
        return DOMAIN.toByteArray(Charsets.UTF_8) + ecdhPubBytes +
                senderFp.toByteArray(Charsets.UTF_8) + receiverFp.toByteArray(Charsets.UTF_8)
    }
}

/**
 * ERROR 消息 - 错误反馈
 */
@Serializable
data class ErrorPayload(
    val code: String,
    val message: String,
    val target: String? = null  // 导致错误的目标节点指纹
)

/**
 * 错误码常量
 */
object ErrorCodes {
    const val TARGET_OFFLINE = "TARGET_OFFLINE"
    const val INVALID_FORMAT = "INVALID_FORMAT"
    const val UNAUTHORIZED = "UNAUTHORIZED"
    const val PAYLOAD_TOO_LARGE = "PAYLOAD_TOO_LARGE"
    const val AUTH_FAILED = "AUTH_FAILED"          // v2: 挑战应答失败/超时/签名不合法
    const val FINGERPRINT_MISMATCH = "FINGERPRINT_MISMATCH"  // v2: 声称指纹与公钥不匹配
    const val SOURCE_MISMATCH = "SOURCE_MISMATCH"  // v2: envelope.source 与注册身份不符
}

/**
 * 协议常量
 */
object ProtocolConstants {
    const val HEARTBEAT_INTERVAL_MS = 30_000L
    const val HEARTBEAT_TIMEOUT_MS = 60_000L
    const val MAX_PAYLOAD_SIZE = 64 * 1024       // 64KB (v2: 服务端强制执行)
    const val WEBSOCKET_PATH = "/relay"

    /** v2: 注册挑战应答超时 (未完成认证的连接将被断开) */
    const val AUTH_TIMEOUT_MS = 30_000L

    /** v2: 单 IP 最大并发连接数 */
    const val MAX_CONNECTIONS_PER_IP = 20

    /** v2: 单连接消息速率 (条/秒, 超限断开) */
    const val MAX_MSG_PER_SECOND = 20
}
