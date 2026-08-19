package com.engine.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import javax.crypto.SecretKey

/**
 * 群成员 (v3.14)
 */
data class GroupMember(
    val fp: String,
    val nickname: String,
    val role: String                  // GroupRoles: OWNER / ADMIN / MEMBER
)

/**
 * 群组 (v3.14) — 纯内存, 退出即焚
 *
 * 隐私红线延续: 群数据 (花名册/群名/群密钥) 严格内存态, 不落盘。
 * - 群主重建群: 重新生成邀请码与群密钥, 成员重新凭码加入
 * - 成员端: 群主在线分发 KEY 后即拥有会话能力; 重启后需重新入群
 */
data class EngineGroup(
    val id: String,                   // 群 ID (UUID)
    val name: String,
    val ownerFp: String,
    val myRole: String,               // 群主视角 OWNER, 成员视角 MEMBER
    val members: List<GroupMember>,
    val groupKey: SecretKey?,         // AES-256 群密钥 (null = 尚未收到分发)
    val keyVersion: Int,              // 群主每次成员减少时 +1 轮换
    val inviteCode: String?,          // 群主侧持有 (成员侧为 null)
    val inviteConfirmed: Boolean,     // 群主侧: 中继登记已确认
    val createdAt: Long,
    val lastMessage: String? = null,  // 聊天列表预览
    val lastMessageTime: Long? = null
) {
    val isOwner: Boolean get() = myRole == com.securesocial.core.protocol.GroupRoles.OWNER

    companion object {
        /** 会话键约定: 聊天流水线直接以 "grp:<id>" 作为会话标识复用 */
        const val KEY_PREFIX = "grp:"

        fun conversationKey(groupId: String) = KEY_PREFIX + groupId

        fun isGroupConversation(peerKey: String) = peerKey.startsWith(KEY_PREFIX)

        fun groupIdOf(peerKey: String) = peerKey.removePrefix(KEY_PREFIX)
    }
}

/**
 * 协议成员条目 → 应用侧群成员 (昵称空缺时回退指纹短码)
 */
fun com.securesocial.core.protocol.GroupMemberData.toMember(): GroupMember =
    GroupMember(
        fp = fp,
        nickname = nickname.ifBlank { "用户 ${fp.take(8)}" },
        role = role
    )

/**
 * 纯内存群组仓库 (v3.14)
 *
 * 与联系人/消息同为内存态; StateFlow 驱动 UI。
 */
class GroupStore {

    private val _groups = MutableStateFlow<List<EngineGroup>>(emptyList())
    val groups: StateFlow<List<EngineGroup>> = _groups.asStateFlow()

    fun getGroup(id: String): EngineGroup? =
        _groups.value.find { it.id == id }

    /** 群主按邀请码定位 (JOIN_REQ 不携带 groupId) */
    fun findByInviteCode(code: String): EngineGroup? =
        _groups.value.find { it.inviteCode == code }

    fun newGroupId(): String = UUID.randomUUID().toString().replace("-", "").take(16)

    /** 新建/整体替换 (建群 / KEY 分发落地) */
    @Synchronized
    fun upsert(group: EngineGroup) {
        val current = _groups.value.toMutableList()
        val idx = current.indexOfFirst { it.id == group.id }
        if (idx >= 0) current[idx] = group else current.add(group)
        _groups.value = current
    }

    /** 更新花名册与群名 (ROSTER / KEY) */
    @Synchronized
    fun updateRoster(groupId: String, name: String, members: List<GroupMember>, keyVersion: Int? = null, key: SecretKey? = null) {
        mutate(groupId) {
            copy(
                name = name,
                members = members,
                keyVersion = keyVersion ?: this.keyVersion,
                groupKey = key ?: this.groupKey
            )
        }
    }

    /** 群主: 轮换密钥 */
    @Synchronized
    fun rotateKey(groupId: String, newKey: SecretKey) {
        mutate(groupId) {
            copy(groupKey = newKey, keyVersion = keyVersion + 1)
        }
    }

    /** 群主: 更换邀请码 (冲突/重试) */
    @Synchronized
    fun updateInviteCode(groupId: String, code: String, confirmed: Boolean = false) {
        mutate(groupId) { copy(inviteCode = code, inviteConfirmed = confirmed) }
    }

    @Synchronized
    fun setInviteConfirmed(groupId: String, confirmed: Boolean) {
        mutate(groupId) { copy(inviteConfirmed = confirmed) }
    }

    /** 成员变更 (入群/退群) 后同步成员表 */
    @Synchronized
    fun setMembers(groupId: String, members: List<GroupMember>) {
        mutate(groupId) { copy(members = members) }
    }

    @Synchronized
    fun updateLastMessage(groupId: String, text: String, time: Long) {
        mutate(groupId) { copy(lastMessage = text, lastMessageTime = time) }
    }

    /** 退群/解散: 移除群 */
    @Synchronized
    fun remove(groupId: String) {
        _groups.value = _groups.value.filterNot { it.id == groupId }
    }

    @Synchronized
    fun clearAll() {
        _groups.value = emptyList()
    }

    private fun mutate(groupId: String, block: EngineGroup.() -> EngineGroup) {
        val current = _groups.value.toMutableList()
        val idx = current.indexOfFirst { it.id == groupId }
        if (idx >= 0) {
            current[idx] = current[idx].block()
            _groups.value = current
        }
    }
}
