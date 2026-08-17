package com.engine.data

import com.securesocial.core.crypto.EcdhKeyAgreement
import com.securesocial.core.crypto.EcdsaOperations
import com.securesocial.core.crypto.KeyFingerprint
import com.securesocial.core.crypto.SignalAuth
import com.securesocial.core.protocol.SignalPayload
import java.security.KeyPair
import java.security.PublicKey
import javax.crypto.SecretKey

/**
 * 应用级会话管理器 (v2)
 *
 * 信任模型 (与架构对齐: Vault = 本地私钥保管 + 签名服务, Engine = 应用):
 * - 身份 ECDSA 私钥仅存在于 Vault (TEE 加密); Engine 只持有身份公钥 + 指纹
 * - 需要身份签名的操作 (中继注册挑战应答 / ECDH 信令签名) 全部经 IPC 委托 Vault 完成
 * - ECDH 临时密钥对仍由 Engine 本地生成 (仅内存), 每次会话更换
 *
 * v2 变更 (修复: 身份漂移):
 * - 移除 ensureIdentity() 的 "未绑定即静默生成新身份" 行为
 * - 新增 adoptBoundIdentity(): 从 BoundIdentityStore 恢复绑定身份 (公钥)
 * - 绑定完成后调用 convertBoundToPublicKeyOnly() 销毁内存中的身份私钥
 *
 * 安全约束:
 * - 严禁持久化任何私钥或会话密钥
 * - destroyTransientKeys() 清除 ECDH 私钥与会话密钥; 绑定公钥 (非秘密) 可保留
 */
class SessionManager {

    private val ecdsa = EcdsaOperations()
    private val ecdh = EcdhKeyAgreement()

    // ---- 身份 (v2: 公钥为主, 私钥仅在首次绑定流程中短暂持有) ----

    /** 绑定的身份公钥 (X.509); 非秘密, 用于验签与 HELLO 声明 */
    @Volatile
    var identityPublicKey: PublicKey? = null
        private set

    /** 首次绑定流程中短暂持有的身份密钥对 (生成 QR 用); 导入 Vault 成功后必须清除 */
    @Volatile
    var identityKeyPair: KeyPair? = null
        private set

    /** 身份公钥指纹 (32 字符十六进制) */
    @Volatile
    var identityFingerprint: String? = null
        private set

    // ---- ECDH 临时密钥 ----

    @Volatile
    var ecdhKeyPair: KeyPair? = null
        private set

    // 会话密钥缓存: peerFingerprint -> AES-256 SecretKey
    private val sessionKeys = mutableMapOf<String, SecretKey>()

    // 对方 ECDH 公钥缓存: peerFingerprint -> 对方 ECDH 公钥
    val peerPublicKeys = mutableMapOf<String, PublicKey>()

    /**
     * 首次绑定流程: 采纳 TemporaryKeyGenerator 生成的密钥对 (私钥仅存内存, 供二维码展示)
     *
     * 私钥经 QR / 受保护 Intent Extra 导入 Vault 后, Engine 必须调用
     * convertBoundToPublicKeyOnly() 销毁本地私钥。
     */
    @Synchronized
    fun beginBindingIdentity(keyPair: KeyPair) {
        destroyTransientKeys()
        identityKeyPair = keyPair
        identityPublicKey = keyPair.public
        identityFingerprint = KeyFingerprint.compute(keyPair.public)
        ecdhKeyPair = ecdh.generateKeyPair()
    }

    /**
     * 恢复已绑定身份 (二次启动): 仅加载公钥与指纹, 不涉及任何私钥
     *
     * @return 指纹; 未绑定时返回 null
     */
    @Synchronized
    fun adoptBoundIdentity(publicKeyBase64: String): String? {
        return try {
            val pub = ecdsa.decodePublicKey(
                java.util.Base64.getDecoder().decode(publicKeyBase64)
            )
            identityKeyPair = null
            identityPublicKey = pub
            val fp = KeyFingerprint.compute(pub)
            identityFingerprint = fp
            ecdhKeyPair = ecdh.generateKeyPair()
            fp
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 绑定完成: 销毁内存中的身份私钥, 只保留公钥
     *
     * 此后 Engine 对身份私钥零持有, 一切签名经 Vault。
     */
    @Synchronized
    fun convertBoundToPublicKeyOnly() {
        identityKeyPair = null
        System.runFinalization()
        System.gc()
    }

    /** 身份公钥 X.509 编码 (用于中继 HELLO 与 SIGNAL idpub 字段) */
    fun getIdentityPublicKeyEncoded(): ByteArray? {
        return identityKeyPair?.public?.encoded ?: identityPublicKey?.encoded
    }

    /**
     * 生成新的 ECDH 临时密钥对 (每次发起密钥交换前调用)
     */
    @Synchronized
    fun rotateEcdhKeyPair() {
        ecdhKeyPair = ecdh.generateKeyPair()
    }

    /**
     * ECDH 密钥协商: 己方 ECDH 私钥 + 对方公钥 → AES-256 会话密钥
     *
     * v2: HKDF info 绑定双方身份指纹 (EcdhKeyAgreement.sessionKeyInfo),
     * 消除 unknown key-share —— 会话密钥只属于这一对身份。
     */
    @Synchronized
    fun ensureSessionKey(peerPublicKey: PublicKey, peerFingerprint: String): SecretKey {
        peerPublicKeys[peerFingerprint] = peerPublicKey
        sessionKeys[peerFingerprint]?.let { return it }

        val myEcdh = ecdhKeyPair
            ?: throw IllegalStateException("ECDH key pair not generated")
        val myFp = identityFingerprint
            ?: throw IllegalStateException("Identity not loaded")

        val sessionKey = ecdh.agreeAndDerive(
            privateKey = myEcdh.private,
            publicKey = peerPublicKey,
            info = EcdhKeyAgreement.sessionKeyInfo(myFp, peerFingerprint)
        )
        sessionKeys[peerFingerprint] = sessionKey
        return sessionKey
    }

    /**
     * 验证对方发来的 SIGNAL 信令 (v2, 修复: ECDH 公钥无认证)
     *
     * 验证条件 (全部通过才采纳):
     * 1. idpub 的指纹 == 信封声称的 source
     * 2. ECDSA.verify(idpub, sig, "ENGINE-SIGNAL-V1" ‖ ecdh ‖ senderFp ‖ receiverFp)
     *
     * @return 对方 ECDH 公钥; 验证失败返回 null
     */
    fun verifyIncomingSignal(signal: SignalPayload, senderFp: String, receiverFp: String): PublicKey? {
        return try {
            val idPubBytes = java.util.Base64.getDecoder().decode(signal.idpub)
            val idPub = ecdsa.decodePublicKey(idPubBytes)

            // 条件 1: 身份公钥指纹必须与信封 source 一致
            if (!KeyFingerprint.matches(idPub, senderFp)) return null

            // 条件 2: 签名验证 (绑定 ECDH 公钥 + 双方身份)
            val ecdhBytes = java.util.Base64.getDecoder().decode(signal.ecdh)
            val sigBytes = java.util.Base64.getDecoder().decode(signal.sig)
            val content = SignalAuth.signingContent(ecdhBytes, senderFp, receiverFp)
            if (!ecdsa.verify(idPub, content, sigBytes)) return null

            // 采纳: 返回 ECDH 公钥
            ecdsa.decodePublicKey(ecdhBytes)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 构建 SIGNAL 待签内容 (发起方经 Vault 签名)
     */
    fun buildOutgoingSignalSigningContent(ecdhPubBytes: ByteArray, peerFp: String): ByteArray? {
        val myFp = identityFingerprint ?: return null
        return SignalAuth.signingContent(ecdhPubBytes, myFp, peerFp)
    }

    @Synchronized
    fun getSessionKey(peerFingerprint: String): SecretKey? {
        return sessionKeys[peerFingerprint]
    }

    /** 己方 ECDH 公钥编码 */
    fun getMyEcdhPublicKeyEncoded(): ByteArray? {
        return ecdhKeyPair?.public?.encoded
    }

    /**
     * 销毁全部临时密钥材料: ECDH 私钥 / 会话密钥 / 绑定流程中的身份私钥
     *
     * 绑定身份公钥 (非秘密) 保留, 供下次登录直接使用。
     */
    @Synchronized
    fun destroyTransientKeys() {
        identityKeyPair = null
        ecdhKeyPair = null
        sessionKeys.clear()
        peerPublicKeys.clear()
        System.runFinalization()
        System.gc()
    }

    /** 兼容旧名称 */
    @Deprecated("Use destroyTransientKeys", ReplaceWith("destroyTransientKeys()"))
    fun destroyPrivateKey() = destroyTransientKeys()

    /**
     * 清除指定对端的会话密钥
     */
    @Synchronized
    fun clearSessionKey(peerFingerprint: String) {
        sessionKeys.remove(peerFingerprint)
        peerPublicKeys.remove(peerFingerprint)
    }
}
