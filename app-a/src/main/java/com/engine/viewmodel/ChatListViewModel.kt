package com.engine.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.engine.EngineApp
import com.engine.data.Contact
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 聊天列表 UI 状态
 */
data class ChatListUiState(
    val chatList: List<Contact> = emptyList(),
    val myFingerprint: String? = null
)

/**
 * 聊天列表 ViewModel
 *
 * 职责:
 * - 启动时生成身份密钥对 (如果不存在)
 * - 暴露联系人列表 + 己方指纹
 */
class ChatListViewModel : ViewModel() {

    private val app = EngineApp.get()
    private val sessionManager = app.sessionManager
    private val contactStore = app.contactStore

    private val _myFingerprint = MutableStateFlow<String?>(null)
    val myFingerprint: StateFlow<String?> = _myFingerprint.asStateFlow()

    val uiState: StateFlow<ChatListUiState> = kotlinx.coroutines.flow.combine(
        contactStore.contacts,
        _myFingerprint,
        app.groupStore.groups
    ) { contacts, fingerprint, groups ->
        ChatListUiState(
            // v3.14: 群会话以伪联系人条目 (fingerprint="grp:<id>") 置顶展示,
            // 点击直接进入群聊天页, 复用既有路由与消息流水线
            chatList = groups.sortedByDescending { it.lastMessageTime ?: it.createdAt }
                .map { g ->
                    Contact(
                        fingerprint = com.engine.data.EngineGroup.conversationKey(g.id),
                        nickname = g.name,
                        lastMessage = g.lastMessage,
                        lastMessageTime = g.lastMessageTime ?: g.createdAt
                    )
                } + contacts,
            myFingerprint = fingerprint
        )
    }.stateIn(
        scope = viewModelScope,
        started = kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000),
        initialValue = ChatListUiState()
    )

    init {
        ensureIdentity()
    }

    /**
     * 确保身份已就绪 (v2, 修复: 身份漂移)
     *
     * 身份的唯一合法来源是 Vault 绑定流程:
     * - 已在内存 (App 启动时恢复 / 刚完成绑定) → 直接使用
     * - 不在内存但有持久化公钥 → 重新装载
     * - 都没有 → 保持 null, UI 引导用户去 "密钥绑定" 页
     * 绝不静默生成新身份 (旧行为会导致节点 ID 漂移、联系人无法识别)。
     */
    fun ensureIdentity() {
        viewModelScope.launch {
            if (sessionManager.identityFingerprint == null) {
                app.boundIdentityStore.getBoundPublicKeyBase64()?.let { pubB64 ->
                    sessionManager.adoptBoundIdentity(pubB64)
                }
            }
            val fp = sessionManager.identityFingerprint
            if (fp != null) {
                app.connectRelay()
            }
            _myFingerprint.value = fp
        }
    }

    /**
     * 获取己方指纹 (短格式, 前 8 位)
     */
    fun getShortFingerprint(): String? {
        return _myFingerprint.value?.take(8)
    }
}
