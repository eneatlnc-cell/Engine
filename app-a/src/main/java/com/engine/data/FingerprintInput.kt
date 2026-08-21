package com.engine.data

/**
 * ═══════════════════════════════════════════════════════════════════
 *  v3.23 · 指纹输入解析 —— 添加/搜索联系人共用
 * ═══════════════════════════════════════════════════════════════════
 *
 *  规范依据 (KeyFingerprint.compute): 线上指纹恒为 32 位**小写**十六进制
 *  ("%02x" 编码)。消息引擎按 source 精确匹配联系人
 *  (PersistentContactStore.getContact 的 == 比较), 因此:
 *
 *  · 落盘指纹必须与线上同构 (小写) —— 否则大写条目永远匹配不上,
 *    首条消息到达时被自动再加一条重复联系人 (v3.22 之前 "添加后
 *    不对应/出现两个联系人" 的深层根因之一)
 *  · DID (did:engine:…) 不再作为添加凭据 —— 仅指纹 (用户决策 v3.23)
 *
 *  职责:
 *  · [clean]     输入清洗: 去所有空白 + 统一小写
 *  · [isValid]   校验: 恰好 32 位十六进制
 *  · [isDid]     识别 DID 输入 → 对话框给定向错误提示
 */
object FingerprintInput {

    /** 线上指纹长度 (SHA-256 截取 16 字节的 hex 编码) */
    const val FINGERPRINT_LENGTH = 32

    private val HEX32 = Regex("^[0-9a-f]{32}$")
    private val DID_MARKER = Regex("did:", RegexOption.IGNORE_CASE)

    /**
     * 清洗用户输入: 移除所有空白字符 (含复制带入的换行/空格) + 统一小写。
     * 不剥任何前缀 —— DID 输入按无效处理, 由调用方用 [isDid] 给出定向提示。
     */
    fun clean(raw: String): String = raw.filter { !it.isWhitespace() }.lowercase()

    /** 是否为合法指纹 (清洗后恰好 32 位十六进制) */
    fun isValid(cleaned: String): Boolean = HEX32.matches(cleaned)

    /** 是否疑似 DID 输入 (含 "did:" 片段) */
    fun isDid(raw: String): Boolean = DID_MARKER.containsMatchIn(raw)
}
