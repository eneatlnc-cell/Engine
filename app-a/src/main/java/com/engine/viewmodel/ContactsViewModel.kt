package com.engine.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.engine.EngineApp
import com.engine.data.Contact
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * 联系人页面 UI 状态
 */
data class ContactsUiState(
    val contacts: List<Contact> = emptyList()
)

/**
 * 联系人 ViewModel
 *
 * 职责:
 * - 暴露联系人列表
 * - 添加联系人
 */
class ContactsViewModel : ViewModel() {

    private val app = EngineApp.get()

    val uiState: StateFlow<ContactsUiState> = app.contactStore.contacts
        .map { ContactsUiState(it) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = ContactsUiState()
        )

    /**
     * 添加联系人
     */
    fun addContact(fingerprint: String, nickname: String) {
        app.contactStore.addContact(fingerprint, nickname)
    }

    /**
     * 移除联系人
     */
    fun removeContact(fingerprint: String) {
        app.contactStore.removeContact(fingerprint)
    }
}
