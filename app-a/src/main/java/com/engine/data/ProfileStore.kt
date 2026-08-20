package com.engine.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * ═══════════════════════════════════════════════════════════════════
 *  v3.22 · 本机档案 (DID 页) —— 昵称 + 头像
 * ═══════════════════════════════════════════════════════════════════
 *
 *  定位: DID (did:engine:{指纹}) 本身由密钥指纹派生、不可修改;
 *  本 Store 只管理指纹之外的**展示层**档案 —— 昵称与头像图像。
 *
 *  隐私模型 (与联系人/标记物同族):
 *  - 仅存本机 (filesDir), 不上行中继、不同步对端 —— 昵称/头像
 *    是用户本机的展示偏好; 群聊内成员名仍走协议快照, 不读这里
 *  - 头像为用户主动选择的图像副本 (原 URI 可能失效, 必须复制字节)
 *
 *  存储 (两文件, 均应用私有目录):
 *  - engine_profile.json  昵称 (原子写: .tmp → rename)
 *  - engine_avatar.jpg    头像 JPEG (512px 方形, 原子写)
 *
 *  前向兼容: JSON ignoreUnknownKeys + 字段全默认值,
 *  旧版本 JSON / 降级安装均可无损读取; 头像文件缺失自动回退渐变头像。
 */
@Serializable
data class EngineProfile(
    val nickname: String = "",      // 空串 = 未设置 (UI 回退 "我")
    val hasAvatar: Boolean = false, // engine_avatar.jpg 是否存在
    // 头像版本号: 每次更换/移除自增 —— UI 以此为 remember 键,
    // 修复 "已设头像再换图时 hasAvatar 不变导致界面不刷新"
    val avatarVersion: Int = 0
)

/**
 * 档案仓库 — 文件式持久化 + 内存 StateFlow 响应式视图
 *
 * 线程安全: 写操作 synchronized; 读走无锁 StateFlow。
 * 头像解码不在仓库内做 (避免常驻 Bitmap 内存):
 * UI 以 [EngineProfile.hasAvatar] 为 remember 键调用 [decodeAvatar]。
 */
class ProfileStore(context: Context) {

    companion object {
        private const val TAG = "ProfileStore"
        private const val PROFILE_FILE = "engine_profile.json"
        private const val AVATAR_FILE = "engine_avatar.jpg"
        private const val TMP_SUFFIX = ".tmp"

        /** 头像目标边长: 512px 足够 96dp@xxxhdpi, JPEG 后 <100KB */
        const val AVATAR_TARGET_PX = 512

        /** 昵称长度上限 (UI 同步限制, 防超长溢出) */
        const val NICKNAME_MAX = 24
    }

    private val profileFile = context.filesDir.resolve(PROFILE_FILE)
    private val avatarFile = context.filesDir.resolve(AVATAR_FILE)
    private val json = Json { ignoreUnknownKeys = true }

    private val _profile = MutableStateFlow(EngineProfile())
    val profile: StateFlow<EngineProfile> = _profile.asStateFlow()

    init {
        _profile.value = load()
    }

    // ── 昵称 ────────────────────────────────────────────────

    /**
     * 更新昵称 (trim; 空串 = 清除, 恢复默认展示)
     *
     * @return 是否落盘成功 (失败时内存态不更新, 保数据一致)
     */
    @Synchronized
    fun updateNickname(raw: String): Boolean {
        val nickname = raw.trim().take(NICKNAME_MAX)
        val updated = _profile.value.copy(nickname = nickname)
        return if (persistProfile(updated)) {
            _profile.value = updated
            true
        } else false
    }

    // ── 头像 ────────────────────────────────────────────────

    /**
     * 从相册 URI 设置头像: EXIF 纠向 → 中心方形裁剪 → 降采样 512px → JPEG 落盘
     *
     * 字节复制是硬要求 —— ACTION_GET_CONTENT 返回的 URI 授权随进程消亡,
     * 必须把解码后的图像写进私有目录 (原文件不可依赖)。
     *
     * @return 成功与否 (调用方据此提示; 失败不动旧头像)
     */
    @Synchronized
    fun updateAvatar(context: Context, uri: Uri): Boolean {
        try {
            val resolver = context.contentResolver

            // 1. 探边界 → 采样降载 (大图不整解进内存)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            } ?: return false
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false

            var sample = 1
            while (
                bounds.outWidth / (sample * 2) >= AVATAR_TARGET_PX &&
                bounds.outHeight / (sample * 2) >= AVATAR_TARGET_PX
            ) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            var raw = resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: return false

            // 2. EXIF 旋转纠向 (相机横拍照片元数据带 90°/270°)
            val rotation = resolver.openInputStream(uri)?.use { ins ->
                when (
                    ExifInterface(ins)
                        .getAttributeInt(
                            ExifInterface.TAG_ORIENTATION,
                            ExifInterface.ORIENTATION_NORMAL
                        )
                ) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
            if (rotation != 0f) {
                val matrix = Matrix().apply { postRotate(rotation) }
                val rotated = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
                if (rotated != raw) raw.recycle()
                raw = rotated
            }

            // 3. 中心方形裁剪 → 缩放至目标边长
            val side = minOf(raw.width, raw.height)
            val cropLeft = (raw.width - side) / 2
            val cropTop = (raw.height - side) / 2
            val cropped = Bitmap.createBitmap(raw, cropLeft, cropTop, side, side)
            if (cropped != raw) raw.recycle()
            val final = if (side > AVATAR_TARGET_PX) {
                Bitmap.createScaledBitmap(cropped, AVATAR_TARGET_PX, AVATAR_TARGET_PX, true)
                    .also { if (it != cropped) cropped.recycle() }
            } else cropped

            // 4. JPEG 原子落盘 (头像不透明, JPEG 体积优于 PNG)
            val tmp = avatarFile.resolveSibling(AVATAR_FILE + TMP_SUFFIX)
            tmp.outputStream().use { final.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            if (!tmp.renameTo(avatarFile)) {
                // rename 失败 (被占用等): 退化为直接写
                avatarFile.outputStream().use { final.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            }
            final.recycle()

            val updated = _profile.value.copy(hasAvatar = true, avatarVersion = _profile.value.avatarVersion + 1)
            if (persistProfile(updated)) _profile.value = updated
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update avatar: ${e.message}")
            return false
        }
    }

    /**
     * 移除头像 (回退指纹渐变头像)
     */
    @Synchronized
    fun clearAvatar() {
        val deleted = !avatarFile.exists() || avatarFile.delete()
        if (deleted) {
            val updated = _profile.value.copy(
                hasAvatar = false,
                avatarVersion = _profile.value.avatarVersion + 1
            )
            if (persistProfile(updated)) _profile.value = updated
        }
    }

    /**
     * 解码头像 Bitmap; 未设置/文件缺失返回 null (UI 回退 GradientAvatar)
     *
     * 主线程调用: 512px JPEG 解码为轻量操作 (与贴纸缩略图同策略)。
     */
    fun decodeAvatar(): Bitmap? = try {
        if (avatarFile.exists()) BitmapFactory.decodeFile(avatarFile.absolutePath) else null
    } catch (e: Exception) {
        Log.w(TAG, "Avatar decode failed, fallback: ${e.message}")
        null
    }

    // ── 持久化 ──────────────────────────────────────────────

    /** 冷启动加载; 损坏自愈: JSON 坏 → 空档案, 头像文件在但 JSON 丢失 → 自动发现 */
    private fun load(): EngineProfile {
        val fromJson = try {
            if (profileFile.exists()) {
                json.decodeFromString(EngineProfile.serializer(), profileFile.readText())
            } else EngineProfile()
        } catch (e: Exception) {
            Log.w(TAG, "Profile file corrupted, starting fresh: ${e.message}")
            EngineProfile()
        }
        // JSON 与头像文件可能失步 (外部清理/旧版本), 以文件系统为准
        return fromJson.copy(hasAvatar = avatarFile.exists())
    }

    /** 原子落盘 (写 .tmp → rename; 失败返回 false 且不更新内存态) */
    private fun persistProfile(profile: EngineProfile): Boolean = try {
        val payload = json.encodeToString(EngineProfile.serializer(), profile)
        val tmp = profileFile.resolveSibling(PROFILE_FILE + TMP_SUFFIX)
        tmp.writeText(payload)
        if (tmp.renameTo(profileFile)) {
            true
        } else {
            profileFile.writeText(payload)
            true
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to persist profile: ${e.message}")
        false
    }
}
