package com.engine.crypto

import com.securesocial.core.crypto.AesGcmCipher
import java.util.Base64
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * 群组加密器 (v3.14)
 *
 * 群消息加解密路径:
 * - 群主生成 AES-256 群密钥, 经 1:1 ECDH 会话密钥加密分发给每位成员
 * - 群消息: 群密钥 + AES-GCM 加密一次, 按成员扇出同一份密文
 * - AAD = "GROUP-MSG-V1" ‖ groupId ‖ senderFp ‖ seq
 *   (与接收者无关 → 扇出免重复加密; 绑定发送者与序号 → 防移植/防重放)
 *
 * 密钥轮换: 成员减少时群主换新钥 (keyVersion+1) 并对余员重发 KEY;
 * 离线成员上线后解密失败 → KEY_REQ 补发。
 */
class GroupCryptoManager {

    companion object {
        const val AAD_DOMAIN = "GROUP-MSG-V1"
    }

    private val aes = AesGcmCipher()

    /** 生成 AES-256 群密钥 */
    fun generateGroupKey(): SecretKey {
        val gen = KeyGenerator.getInstance("AES")
        gen.init(256)
        return gen.generateKey()
    }

    /** 群密钥 → Base64 (KEY 分发载荷) */
    fun keyToBase64(key: SecretKey): String =
        Base64.getEncoder().encodeToString(key.encoded)

    /** Base64 → 群密钥 */
    fun base64ToKey(b64: String): SecretKey? = try {
        val raw = Base64.getDecoder().decode(b64)
        if (raw.size != 32) null else SecretKeySpec(raw, "AES")
    } catch (e: Exception) {
        null
    }

    /** 群消息 AAD: 与接收者无关, 扇出共用一份密文 */
    fun buildGroupAad(groupId: String, senderFp: String, seq: Long): ByteArray {
        return (AAD_DOMAIN + groupId + senderFp + seq).toByteArray(Charsets.UTF_8)
    }

    fun encrypt(plaintext: String, key: SecretKey, aad: ByteArray): String =
        aes.encryptString(plaintext, key, aad)

    fun decrypt(ciphertext: String, key: SecretKey, aad: ByteArray): String =
        aes.decryptString(ciphertext, key, aad)
}
