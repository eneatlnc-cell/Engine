package com.engine.crypto

import com.engine.data.SessionManager
import com.securesocial.core.crypto.AesGcmCipher
import com.securesocial.core.crypto.EcdhKeyAgreement
import com.securesocial.core.crypto.EcdsaOperations
import javax.crypto.SecretKey
import java.security.PublicKey

/**
 * 端到端加密管理器
 *
 * 依赖: EcdhKeyAgreement, AesGcmCipher, EcdsaOperations, SessionManager
 *
 * 加密流程:
 * 1. 从 SessionManager 获取/协商 AES-256 会话密钥 (ECDH + HKDF)
 * 2. 使用 AES-256-GCM 加密明文 → Base64 密文
 *
 * 解密流程:
 * 1. 从 SessionManager 获取会话密钥
 * 2. 使用 AES-256-GCM 解密 Base64 密文 → 明文
 *
 * 会话密钥缓存于 SessionManager, 避免重复协商。
 */
class E2ECryptoManager(
    private val sessionManager: SessionManager
) {

    private val aesGcm = AesGcmCipher()
    private val ecdhKeyAgreement = EcdhKeyAgreement()
    private val ecdsaOperations = EcdsaOperations()

    /**
     * ECDH 密钥协商: 使用己方 ECDH 私钥 + 对方公钥派生会话密钥
     *
     * 协商结果缓存于 SessionManager。
     *
     * @param peerPublicKey 对方 ECDH 公钥
     * @param peerFingerprint 对方公钥指纹
     */
    fun ensureSessionKey(peerPublicKey: PublicKey, peerFingerprint: String) {
        sessionManager.ensureSessionKey(peerPublicKey, peerFingerprint)
    }

    /**
     * 注册对方公钥 (用于后续 ECDH 协商)
     */
    fun registerPeerPublicKey(peerPublicKeyEncoded: ByteArray, peerFingerprint: String) {
        // ECDH 公钥与 ECDSA 公钥同为 EC P-256, 使用 EcdsaOperations 解码
        val publicKey = ecdsaOperations.decodePublicKey(peerPublicKeyEncoded)
        sessionManager.peerPublicKeys[peerFingerprint] = publicKey
    }

    /**
     * 加密消息 (v2: AAD 绑定消息上下文)
     *
     * @param plaintext       明文
     * @param peerFingerprint 对方公钥指纹
     * @param aad             附加认证数据 (= AesGcmCipher.buildMessageAad(source, target, seq)),
     *                        绑定 "发送方 ‖ 接收方 ‖ 序列号", 密文移植到其他上下文即认证失败
     * @return Base64 编码的密文 (iv.ciphertext.authTag 格式)
     * @throws IllegalStateException 若无会话密钥且无法协商 (对方公钥缺失)
     */
    fun encryptMessage(plaintext: String, peerFingerprint: String, aad: ByteArray): String {
        val key = getOrDeriveSessionKey(peerFingerprint)
        return aesGcm.encryptString(plaintext, key, aad)
    }

    /**
     * 解密消息 (v2: AAD 校验消息上下文)
     *
     * @param ciphertext      Base64 密文
     * @param peerFingerprint 对方公钥指纹
     * @param aad             附加认证数据, 必须与加密时一致, 否则 AEADBadTagException
     * @return 明文字符串
     */
    fun decryptMessage(ciphertext: String, peerFingerprint: String, aad: ByteArray): String {
        val key = getOrDeriveSessionKey(peerFingerprint)
        return aesGcm.decryptString(ciphertext, key, aad)
    }

    /**
     * 获取或协商会话密钥
     */
    private fun getOrDeriveSessionKey(peerFingerprint: String): SecretKey {
        // 先查缓存
        sessionManager.getSessionKey(peerFingerprint)?.let { return it }

        // 无缓存则尝试 ECDH 协商
        val peerPublicKey = sessionManager.peerPublicKeys[peerFingerprint]
            ?: throw IllegalStateException(
                "No session key and no peer public key for fingerprint: $peerFingerprint"
            )

        return sessionManager.ensureSessionKey(peerPublicKey, peerFingerprint)
    }

    /**
     * 检查是否已有会话密钥
     */
    fun hasSessionKey(peerFingerprint: String): Boolean {
        return sessionManager.getSessionKey(peerFingerprint) != null
    }
}
