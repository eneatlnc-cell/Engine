package com.securesocial.core.protocol

import kotlinx.serialization.Serializable
import java.security.MessageDigest

/**
 * Spark 账本协议定义 (v3.15)
 *
 * 架构定位:
 * - Spark 是产品方发行的计费货币; 账本为**独立有状态服务** (spark-ledger,
 *   部署于 VPS), 与无状态中继并行 —— 中继零状态红线不破
 * - 账户 = 身份指纹 (与中继/E2EE 同一套 ECDSA P-256 身份体系)
 * - 所有写操作经身份私钥签名 (客户端经 Vault IPC 完成签名,
 *   服务端用开户时登记的公钥验签)
 *
 * 计费模型 (本地预扣 + 批量结算 + 服务端权威):
 * - 发一条消息 = 1 Spark (文字/图片/表情/贴纸同价), 单条消息 ≤ 60KB (UTF-8 字节,
 *   v3.17: 由 1KB 上调, 支持图片/表情/贴纸内联发送, 参考 Telegram 贴纸量级)
 * - 客户端本地预扣余额视图 (零额外延迟), 每累计 N 条 / 定时批量上报
 *   totalSent (单调递增), 服务端按差值扣减并回传权威余额, 客户端校正本地视图
 * - 防作弊边界: 客户端篡改少报 → 服务端余额与上报值同步锁定,
 *   透支上限 = 一次结算周期的条数; 正式版可升级为逐条签名结算
 */
object SparkAuth {

    /** 签名域分隔符 */
    const val DOMAIN = "SPARK-V1"

    // HTTP 签名头
    const val HEADER_FP = "X-Spark-FP"
    const val HEADER_TS = "X-Spark-TS"
    const val HEADER_NONCE = "X-Spark-Nonce"
    const val HEADER_SIG = "X-Spark-Sig"
    const val HEADER_ADMIN = "X-Spark-Admin"

    /** 时间戳容忍窗口 (±5 分钟, 防重放窗口与 nonce 缓存 TTL 一致) */
    const val TIMESTAMP_TOLERANCE_MS = 5 * 60 * 1000L

    /**
     * 构建签名内容: "SPARK-V1" ‖ fp ‖ ts ‖ nonce ‖ SHA-256(body)
     *
     * 签名 = ECDSA(SHA256withECDSA, DER) 由身份私钥完成。
     * ts/nonce 以十进制/原文 UTF-8 拼接, 与 body 哈希一并绑定,
     * 请求体不可篡改、不可重放。
     */
    fun signingContent(fp: String, ts: Long, nonce: String, body: ByteArray): ByteArray {
        val bodyHash = MessageDigest.getInstance("SHA-256").digest(body)
        return (DOMAIN + fp + ts + nonce).toByteArray(Charsets.UTF_8) + bodyHash
    }
}

/**
 * Spark 计费经济常量 (客户端拦截 / 服务端结算共用)
 */
object SparkEconomy {

    /** 发送一条消息消耗的 Spark 数 */
    const val MESSAGE_COST = 1L

    /** 每日登录领取额 */
    const val DAILY_CLAIM = 1000L

    /** 单条消息字节上限 (UTF-8): 60KB, 覆盖图片/表情/贴纸 (v3.17 由 1KB 上调) */
    const val MAX_MESSAGE_BYTES = 60 * 1024

    /** 批量结算阈值: 未上报条数达到此值立即结算 */
    const val SETTLE_BATCH = 10

    /** 批量结算定时 (兜底) */
    const val SETTLE_INTERVAL_MS = 30_000L

    /** 交易明细保留条数 (每账户, 服务端) */
    const val TX_KEEP_PER_ACCOUNT = 100

    /** 交易明细返回条数 (单次查询) */
    const val TX_QUERY_LIMIT = 50
}

/**
 * Spark 错误码
 */
object SparkErrorCodes {
    const val BAD_REQUEST = "BAD_REQUEST"           // 参数不合法
    const val NO_ACCOUNT = "NO_ACCOUNT"             // 账户不存在 (未开户)
    const val ALREADY_OPEN = "ALREADY_OPEN"         // 重复开户 (幂等成功)
    const val ALREADY_CLAIMED = "ALREADY_CLAIMED"   // 今日已领取 (幂等成功)
    const val BAD_SIGNATURE = "BAD_SIGNATURE"       // 签名缺失/伪造/不可解码
    const val REPLAY = "REPLAYED_NONCE"             // nonce 重放
    const val EXPIRED_TS = "EXPIRED_TS"             // 时间戳超出容忍窗口
    const val INSUFFICIENT = "INSUFFICIENT"         // 余额不足
    const val SETTLE_REGRESSION = "SETTLE_REGRESSION" // totalSent 回退
    const val RATE_LIMITED = "RATE_LIMITED"         // 请求过频
    const val FORBIDDEN = "FORBIDDEN"               // 管理接口令牌错误
}

// ==================== API 模型 ====================

/** 开户: 登记身份公钥 */
@Serializable
data class SparkOpenRequest(
    val fingerprint: String,
    val pubkey: String            // X.509 Base64 (验签用)
)

/** 每日领取 (幂等) */
@Serializable
data class SparkClaimRequest(
    val date: String = ""         // 客户端日期 (服务端以东八区为准, 仅记录)
)

/** 批量结算: 上报累计发送条数 (单调递增) */
@Serializable
data class SparkSettleRequest(
    val totalSent: Long
)

/** 转账 */
@Serializable
data class SparkTransferRequest(
    val to: String,               // 对方指纹
    val amount: Long,             // > 0
    val memo: String = ""
)

/** 运营充值 (admin·mint) */
@Serializable
data class SparkMintRequest(
    val to: String,
    val amount: Long,
    val memo: String = "充值"
)

/** 空请求体 (balance / history) */
@Serializable
data class SparkEmptyRequest(
    val nonce: String = ""
)

/** 统一应答: 成功携带业务字段, 失败携带 error */
@Serializable
data class SparkBalanceResponse(
    val ok: Boolean = false,
    val balance: Long = 0,
    val error: String? = null,
    val message: String? = null
)

@Serializable
data class SparkClaimResponse(
    val ok: Boolean = false,
    val claimed: Boolean = false,   // false = 今日已领 (幂等)
    val amount: Long = 0,
    val balance: Long = 0,
    val date: String = "",
    val error: String? = null,
    val message: String? = null
)

@Serializable
data class SparkSettleResponse(
    val ok: Boolean = false,
    val spent: Long = 0,            // 本次结算扣减量
    val balance: Long = 0,
    val error: String? = null,
    val message: String? = null
)

@Serializable
data class SparkTransferResponse(
    val ok: Boolean = false,
    val balance: Long = 0,
    val error: String? = null,
    val message: String? = null
)

/** 交易明细条目 */
@Serializable
data class SparkTxEntry(
    val type: String,          // OPEN/CLAIM/SPEND/TRANSFER_IN/TRANSFER_OUT/MINT
    val amount: Long,          // 有符号
    val balanceAfter: Long,
    val note: String,
    val ts: Long
)

@Serializable
data class SparkHistoryResponse(
    val ok: Boolean = false,
    val transactions: List<SparkTxEntry> = emptyList(),
    val error: String? = null,
    val message: String? = null
)
