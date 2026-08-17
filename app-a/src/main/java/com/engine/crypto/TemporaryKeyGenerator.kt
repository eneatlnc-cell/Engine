package com.engine.crypto

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.securesocial.core.crypto.EcdsaOperations
import com.securesocial.core.crypto.KeyFingerprint
import com.securesocial.core.crypto.KeyPayloadSerializer
import java.security.KeyPair

/**
 * 密钥对生成结果
 *
 * @param keyPair   ECDSA P-256 密钥对 (仅存内存)
 * @param fingerprint 公钥指纹 (32 字符十六进制)
 * @param qrPayload  二维码 JSON 载荷 (含公钥+私钥, 仅通过光学通道传递)
 */
data class KeyGenerationResult(
    val keyPair: KeyPair,
    val fingerprint: String,
    val qrPayload: String
)

/**
 * 临时密钥对生成器 + 二维码渲染
 *
 * 职责:
 * - generate(): 生成 ECDSA P-256 密钥对 → 计算指纹 → 序列化为 JSON 载荷
 * - renderQrCode(): 使用 ZXing 将 JSON 载荷渲染为二维码 Bitmap
 *
 * 安全约束:
 * - 生成的私钥仅存内存, 通过二维码光学通道传递给 Vault
 * - 二维码展示页面必须设置 FLAG_SECURE 防截屏
 */
class TemporaryKeyGenerator {

    private val ecdsa = EcdsaOperations()

    /**
     * 生成 ECDSA P-256 密钥对 + 计算指纹 + 序列化为 JSON 载荷
     *
     * @return KeyGenerationResult 包含密钥对、指纹、二维码载荷
     */
    fun generate(): KeyGenerationResult {
        // 生成 ECDSA P-256 密钥对
        val keyPair = ecdsa.generateKeyPair()

        // 计算公钥指纹
        val fingerprint = KeyFingerprint.compute(keyPair.public)

        // 序列化为二维码 JSON 载荷 (含公钥+私钥)
        val qrPayload = KeyPayloadSerializer.serialize(
            publicKey = ecdsa.encodePublicKey(keyPair.public),
            privateKey = ecdsa.encodePrivateKey(keyPair.private)
        )

        return KeyGenerationResult(
            keyPair = keyPair,
            fingerprint = fingerprint,
            qrPayload = qrPayload
        )
    }

    /**
     * 使用 ZXing 将载荷字符串渲染为二维码 Bitmap
     *
     * @param payload 二维码内容 (JSON 字符串)
     * @param size   二维码边长 (像素)
     * @return 黑白二维码 Bitmap
     */
    fun renderQrCode(payload: String, size: Int): Bitmap {
        val hints = mapOf(
            EncodeHintType.MARGIN to 1,
            EncodeHintType.CHARACTER_SET to "UTF-8"
        )

        val bitMatrix = MultiFormatWriter()
            .encode(payload, BarcodeFormat.QR_CODE, size, size, hints)

        val width = bitMatrix.width
        val height = bitMatrix.height
        val pixels = IntArray(width * height)

        for (y in 0 until height) {
            val offset = y * width
            for (x in 0 until width) {
                pixels[offset + x] = if (bitMatrix.get(x, y)) {
                    Color.BLACK
                } else {
                    Color.WHITE
                }
            }
        }

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }
}
