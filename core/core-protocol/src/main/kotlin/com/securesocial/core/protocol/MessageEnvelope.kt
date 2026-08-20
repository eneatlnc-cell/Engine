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
    ERROR,        // 错误反馈
    ROOM_REGISTER, // v3.14: 群主向中继登记邀请码 → 群主指纹映射
    ROOM_LOOKUP,   // v3.14: 凭邀请码查询群主指纹 (中继目录服务)
    ROOM_INFO,     // v3.14: 中继对 ROOM_* 的统一应答
    GROUP_MSG,     // v3.14: 群聊消息 (群密钥加密, 按成员扇出, 中继同 MSG 透传)
    GROUP_CTRL     // v3.14: 群控制信令 (成对加密: 密钥分发/花名册/入群/退群/解散)
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
    val ts: Long = System.currentTimeMillis(),  // 时间戳
    val groupId: String? = null        // v3.14: 群消息路由用 (中继不解析, 仅透传)
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
 * ROOM_REGISTER 载荷 (v3.14) - 群主登记邀请码
 *
 * 中继仅维护 邀请码 → 群主指纹 的内存映射 (TTL 过期),
 * 不保存任何群成员/群密钥信息 —— 无状态红线不破。
 */
@Serializable
data class RoomRegisterPayload(
    val code: String,          // 邀请码 (8 位大写字母+数字)
    val fingerprint: String    // 群主指纹
)

/**
 * ROOM_LOOKUP 载荷 (v3.14) - 凭邀请码找群主
 */
@Serializable
data class RoomLookupPayload(
    val code: String
)

/**
 * ROOM_INFO 载荷 (v3.14) - 中继对 ROOM_REGISTER / ROOM_LOOKUP 的统一应答
 *
 * ok=true: 登记/查询成功; ownerFingerprint 非空 (LOOKUP)
 * ok=false: error 携带 GroupErrorCodes
 */
@Serializable
data class RoomInfoPayload(
    val ok: Boolean,
    val code: String = "",
    val ownerFingerprint: String? = null,
    val error: String? = null
)

/**
 * 群组规模常量 (v3.17.1)
 *
 * MAX_MEMBERS = 200 的推导 (100Mbps 中继带宽 ≈ 12.5MB/s):
 * - 群消息经中继向 N-1 在线成员扇出, 单条最坏 egress = (N-1) × 帧体。
 * - N=200: 40KB 文本 (帧 ≈54KB) → ~10.6MB ≈ 0.85s 满管;
 *   48KB 媒体 (帧 ≈86KB) → ~17.1MB ≈ 1.37s 满管 —— 单条消息瞬时打满
 *   但可被群级限流吸收, 可接受。
 * - N=500: 单条媒体 ≈ 3.4s 满管, 两个并发大群即互相饿死, 不可接受。
 * - 100Mbps ÷ 200 人 = 人均 ~62KB/s 扇出预算, 与聊天负载匹配。
 *
 * 群成员表仅存在于群主客户端 (中继零群组状态), 上限由群主侧在
 * 建群/入群批准两处强制执行。
 */
object GroupLimits {
    /** 单群成员上限 (含群主) */
    const val MAX_MEMBERS = 200
}

/**
 * 群组操作错误码 (v3.14)
 */
object GroupErrorCodes {
    const val INVALID_CODE = "INVALID_CODE"       // 邀请码格式不合法
    const val CODE_TAKEN = "CODE_TAKEN"           // 邀请码已被占用
    const val NOT_FOUND = "NOT_FOUND"             // 邀请码不存在/已过期
    const val RATE_LIMITED = "RATE_LIMITED"       // 查询限流 (防枚举)
    const val UNAUTHORIZED = "UNAUTHORIZED"       // 非群主操作他人映射
    const val GROUP_FULL = "GROUP_FULL"           // v3.17.1: 群成员已达上限 (200)
}

/**
 * 邀请码 (v3.14)
 *
 * - 8 位大写字母 + 数字, 剔除易混淆字符 (I O 0 1) —— 口播/截图友好
 * - 熵: log2(32^8) ≈ 40 bit; 配合中继查询限流 (10 次/分钟/指纹),
 *   枚举命中期望 > 10^10 年
 */
object InviteCode {
    const val LENGTH = 8
    const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    /** 生成随机邀请码 */
    fun generate(): String {
        val random = java.security.SecureRandom()
        return buildString {
            repeat(LENGTH) {
                append(ALPHABET[random.nextInt(ALPHABET.length)])
            }
        }
    }

    /** 格式校验: 8 位且全部在字母表内 */
    fun isValid(code: String): Boolean =
        code.length == LENGTH && code.all { it in ALPHABET }
}

// ==================== v3.14: 群组控制协议 ====================

/**
 * 群成员条目 (随花名册分发)
 */
@Serializable
data class GroupMemberData(
    val fp: String,                    // 成员身份指纹
    val nickname: String = "",         // 群内显示名
    val role: String = "MEMBER"        // GroupRoles: OWNER / ADMIN / MEMBER
)

/**
 * 群组三级角色
 *
 * - OWNER: 建群者; 唯一有权 分发/轮换群密钥、审批入群、解散
 * - ADMIN: 预留 (v3.14 未启用; 未来承担邀请/移人)
 * - MEMBER: 发言 / 退群
 */
object GroupRoles {
    const val OWNER = "OWNER"
    const val ADMIN = "ADMIN"
    const val MEMBER = "MEMBER"
}

/**
 * GROUP_CTRL 控制动作常量
 */
object GroupCtrlActions {
    /** 群主 → 成员: 群密钥 + 花名册 (入群批准 / 密钥轮换 / 离线补发) */
    const val KEY = "KEY"

    /** 群主 → 成员: 仅花名册更新 (无密钥变化) */
    const val ROSTER = "ROSTER"

    /** 申请者 → 群主: 凭邀请码申请入群 (groupId 为空, 群主按 code 匹配) */
    const val JOIN_REQ = "JOIN_REQ"

    /** 群主 → 申请者: 拒绝入群 (approved=false + reason; 通过以 KEY 落地) */
    const val JOIN_RESP = "JOIN_RESP"

    /** 成员 → 群主: 主动退群 (群主随之轮换密钥) */
    const val LEAVE = "LEAVE"

    /** 群主 → 被移除者 (预留, v3.14 未启用) */
    const val KICK = "KICK"

    /** 成员 → 群主: 群密钥过期/解密失败, 请求补发 */
    const val KEY_REQ = "KEY_REQ"

    /** 群主 → 全员: 解散群组 */
    const val DISSOLVE = "DISSOLVE"

    /**
     * 成员 → 成员: 在线心跳 (v3.14.1 主权顺移)
     *
     * 每 30s 一跳, 接收方据此维护 (groupId → 成员 → lastSeen) 在场表;
     * 群主失联超 90s 时由顺位首位在线成员接管群主权。
     */
    const val PRESENCE = "PRESENCE"
}

/**
 * GROUP_CTRL 载荷 (v3.14)
 *
 * 传输时整体经 1:1 ECDH 会话密钥加密 (与 MSG 同路径),
 * 中继全程只见密文 —— 群成员表/群密钥对中继零暴露。
 *
 * action=KEY 时 keyB64 携带 AES-256 群密钥原始字节 (Base64);
 * 群密钥每次成员减少时由群主轮换 (keyVersion 递增)。
 */
@Serializable
data class GroupCtrlPayload(
    val action: String,                        // GroupCtrlActions.*
    val groupId: String? = null,               // JOIN_REQ 为空 (凭 code 定位群)
    val groupName: String? = null,
    val ownerFp: String? = null,
    val code: String? = null,                  // 邀请码 (JOIN_REQ 携带)
    val requesterFp: String? = null,           // 申请人指纹 (JOIN_REQ)
    val approved: Boolean = true,              // JOIN_RESP: false = 拒绝
    val members: List<GroupMemberData> = emptyList(),  // 花名册 (KEY/ROSTER)
    val keyB64: String? = null,                // KEY: AES-256 群密钥 (Base64)
    val keyVersion: Int = 0,
    val reason: String? = null                 // 拒绝/解散原因
)

/**
 * 协议常量
 */
object ProtocolConstants {
    const val HEARTBEAT_INTERVAL_MS = 30_000L
    const val HEARTBEAT_TIMEOUT_MS = 60_000L
    /**
     * WebSocket 单帧 (JSON 信封全文) 字节数上限 (v2 服务端强制执行)。
     *
     * v3.17: 64KB → 128KB。v3.17.1 消息预算定稿 (文本 40KB / 媒体 48KB) 后核算:
     * - 40KB 文本 → 加密+Base64 后信封 ≈54KB
     * - 48KB 媒体文件 → Base64 文本 ~64KB → 加密+Base64 后信封 ≈86KB
     * 128KB 对最坏情况 (媒体) 仍有 ~33% 余量, 维持不变。
     */
    const val MAX_PAYLOAD_SIZE = 128 * 1024
    const val WEBSOCKET_PATH = "/relay"

    /** v2: 注册挑战应答超时 (未完成认证的连接将被断开) */
    const val AUTH_TIMEOUT_MS = 30_000L

    /** v2: 单 IP 最大并发连接数 */
    const val MAX_CONNECTIONS_PER_IP = 20

    /** v2: 单连接消息速率 (条/秒, 超限断开) */
    const val MAX_MSG_PER_SECOND = 20
}
