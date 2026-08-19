package com.securesocial.core.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

/**
 * 协议消息序列化工具
 *
 * 提供消息封皮与 JSON 字符串之间的双向转换。
 * 使用宽松的 JSON 解析配置以兼容不同客户端实现。
 */
object ProtocolSerializer {

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    fun encode(envelope: MessageEnvelope): String {
        return json.encodeToString(envelope)
    }

    fun decode(raw: String): MessageEnvelope? {
        return try {
            json.decodeFromString(MessageEnvelope.serializer(), raw)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * HELLO 注册声明 (v2: 必须携带身份公钥)
     */
    fun encodeHello(fingerprint: String, pubkeyBase64: String): String {
        val envelope = MessageEnvelope(
            type = MessageType.HELLO,
            source = fingerprint,
            payload = json.encodeToString(HelloPayload(fingerprint, pubkeyBase64))
        )
        return encode(envelope)
    }

    /**
     * CHALLENGE 注册挑战 (服务器 → 客户端, v2)
     */
    fun encodeChallenge(fingerprint: String, nonceBase64: String): String {
        val envelope = MessageEnvelope(
            type = MessageType.CHALLENGE,
            target = fingerprint,
            payload = json.encodeToString(ChallengePayload(fingerprint, nonceBase64))
        )
        return encode(envelope)
    }

    /**
     * HELLO_AUTH 挑战应答 (客户端 → 服务器, v2)
     */
    fun encodeHelloAuth(fingerprint: String, signatureBase64: String): String {
        val envelope = MessageEnvelope(
            type = MessageType.HELLO_AUTH,
            source = fingerprint,
            payload = json.encodeToString(HelloAuthPayload(fingerprint, signatureBase64))
        )
        return encode(envelope)
    }

    fun encodeMsg(source: String, target: String, payload: String, seq: Long): String {
        return encode(MessageEnvelope(
            type = MessageType.MSG,
            source = source,
            target = target,
            payload = payload,
            seq = seq
        ))
    }

    /**
     * SIGNAL 密钥交换信令 (v2: 携带 ECDH 公钥 + 身份公钥 + 签名)
     */
    fun encodeSignal(source: String, target: String, signal: SignalPayload): String {
        return encode(MessageEnvelope(
            type = MessageType.SIGNAL,
            source = source,
            target = target,
            payload = json.encodeToString(signal)
        ))
    }

    /**
     * 解析 SIGNAL 载荷 (v2)
     */
    fun decodeSignalPayload(payload: String): SignalPayload? {
        return try {
            json.decodeFromString(SignalPayload.serializer(), payload)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 解析 HELLO 载荷 (v2)
     */
    fun decodeHelloPayload(payload: String): HelloPayload? {
        return try {
            json.decodeFromString(HelloPayload.serializer(), payload)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 解析 CHALLENGE 载荷 (v2)
     */
    fun decodeChallengePayload(payload: String): ChallengePayload? {
        return try {
            json.decodeFromString(ChallengePayload.serializer(), payload)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 解析 HELLO_AUTH 载荷 (v2)
     */
    fun decodeHelloAuthPayload(payload: String): HelloAuthPayload? {
        return try {
            json.decodeFromString(HelloAuthPayload.serializer(), payload)
        } catch (e: Exception) {
            null
        }
    }

    fun encodePing(seq: Long): String {
        return encode(MessageEnvelope(
            type = MessageType.PING,
            seq = seq
        ))
    }

    fun encodePong(seq: Long): String {
        return encode(MessageEnvelope(
            type = MessageType.PONG,
            seq = seq
        ))
    }

    fun encodeError(code: String, message: String, target: String? = null): String {
        val envelope = MessageEnvelope(
            type = MessageType.ERROR,
            payload = json.encodeToString(ErrorPayload(code, message, target))
        )
        return encode(envelope)
    }

    // ==================== v3.14: 群组目录服务 ====================

    /**
     * ROOM_REGISTER - 群主登记邀请码 (客户端 → 中继)
     */
    fun encodeRoomRegister(fingerprint: String, code: String): String {
        return encode(MessageEnvelope(
            type = MessageType.ROOM_REGISTER,
            source = fingerprint,
            payload = json.encodeToString(RoomRegisterPayload(code, fingerprint))
        ))
    }

    /**
     * ROOM_LOOKUP - 凭邀请码查询群主指纹 (客户端 → 中继)
     */
    fun encodeRoomLookup(fingerprint: String, code: String): String {
        return encode(MessageEnvelope(
            type = MessageType.ROOM_LOOKUP,
            source = fingerprint,
            payload = json.encodeToString(RoomLookupPayload(code))
        ))
    }

    /**
     * ROOM_INFO - 中继统一应答 (中继 → 客户端)
     */
    fun encodeRoomInfo(target: String, info: RoomInfoPayload): String {
        return encode(MessageEnvelope(
            type = MessageType.ROOM_INFO,
            target = target,
            payload = json.encodeToString(info)
        ))
    }

    fun decodeRoomRegisterPayload(payload: String): RoomRegisterPayload? = try {
        json.decodeFromString(RoomRegisterPayload.serializer(), payload)
    } catch (e: Exception) { null }

    fun decodeRoomLookupPayload(payload: String): RoomLookupPayload? = try {
        json.decodeFromString(RoomLookupPayload.serializer(), payload)
    } catch (e: Exception) { null }

    fun decodeRoomInfoPayload(payload: String): RoomInfoPayload? = try {
        json.decodeFromString(RoomInfoPayload.serializer(), payload)
    } catch (e: Exception) { null }
}
