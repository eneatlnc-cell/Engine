package com.engine.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.engine.data.Contact
import com.engine.data.FingerprintInput
import com.engine.ui.components.BreathingEmptyState
import com.engine.ui.components.GradientAvatar
import com.engine.viewmodel.ContactsViewModel
import kotlinx.coroutines.launch

/**
 * 联系人页面 (Telegram 2026 风格)
 *
 * - 联系人列表: 头像 + 昵称 + 指纹 (等宽字体, 截断省略) + 复制/删除按钮
 * - 点击列表项: 弹出 ModalBottomSheet 展示完整指纹 (可选中/复制)
 * - v3.23: 搜索框移除 (主界面已有搜索, 此处冗余)
 * - v3.23.2: 添加按钮不固定屏幕底部 —— 空态在底部居中 (原位置),
 *   列表态作为列表尾项随列表滚动; 尺寸两态一致
 * - 添加联系人对话框: 仅接受完整指纹 (32 位十六进制),
 *   DID 输入被拒绝 (添加凭据唯一化, v3.23 用户决策)
 */
@Composable
fun ContactsScreen(
    viewModel: ContactsViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val clipboardManager = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var showAddDialog by remember { mutableStateOf(false) }
    var selectedContact by remember { mutableStateOf<Contact?>(null) }

    val contacts = uiState.contacts

    val copyFingerprint: (Contact) -> Unit = { contact ->
        clipboardManager.setText(AnnotatedString(contact.fingerprint))
        scope.launch { snackbarHostState.showSnackbar("指纹已复制") }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (contacts.isEmpty()) {
            // 空态: 呼吸大图 + 底部添加按钮 (保持原位置: 底部居中 48dp)
            Box(modifier = Modifier.fillMaxSize()) {
                BreathingEmptyState(
                    icon = Icons.Filled.Person,
                    title = "暂无联系人",
                    subtitle = "添加对方公钥指纹即可开始安全通讯"
                )
                Button(
                    onClick = { showAddDialog = true },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 48.dp)
                ) {
                    Icon(Icons.Filled.PersonAdd, contentDescription = null)
                    Spacer(modifier = Modifier.size(8.dp))
                    Text("添加联系人")
                }
            }
        } else {
            // 列表态: 添加按钮作为列表尾项 —— 随列表滚动 (用户要求),
            // 不固定在屏幕底部; 尺寸与空态按钮一致
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(
                    items = contacts,
                    key = { it.fingerprint }
                ) { contact ->
                    ContactItem(
                        contact = contact,
                        onClick = { selectedContact = contact },
                        onCopy = { copyFingerprint(contact) },
                        onDelete = { viewModel.removeContact(contact.fingerprint) }
                    )
                }
                item(key = "add_contact_footer") {
                    Button(
                        onClick = { showAddDialog = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 12.dp)
                    ) {
                        Icon(Icons.Filled.PersonAdd, contentDescription = null)
                        Spacer(modifier = Modifier.size(8.dp))
                        Text("添加联系人")
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }

    if (showAddDialog) {
        AddContactDialog(
            onConfirm = { fingerprint, nickname ->
                viewModel.addContact(fingerprint, nickname)
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false }
        )
    }

    selectedContact?.let { contact ->
        ContactDetailBottomSheet(
            contact = contact,
            onCopy = { copyFingerprint(contact) },
            onDelete = { viewModel.removeContact(contact.fingerprint) },
            onDismiss = { selectedContact = null }
        )
    }
}

/**
 * 联系人列表项
 *
 * 点击整行打开指纹详情; 复制/删除按钮各自消费点击事件, 不会触发行点击。
 */
@Composable
private fun ContactItem(
    contact: Contact,
    onClick: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 头像 (v3.7: 指纹渐变 + 超圆, 与聊天列表统一)
        GradientAvatar(
            seed = contact.fingerprint,
            label = contact.nickname.ifBlank { contact.fingerprint },
            size = 48.dp
        )

        Spacer(modifier = Modifier.size(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = contact.nickname.ifBlank { contact.fingerprint.take(8) },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.size(2.dp))
            Text(
                text = contact.fingerprint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        IconButton(onClick = onCopy) {
            Icon(
                imageVector = Icons.Filled.ContentCopy,
                contentDescription = "复制指纹",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = "删除",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 联系人详情底部弹窗: 展示完整指纹 (可选中/复制) + 操作按钮
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContactDetailBottomSheet(
    contact: Contact,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    // 带动画的关闭: 先收起再回调关闭
    val closeWithAnimation: () -> Unit = {
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp)
        ) {
            // 标题: 昵称
            Text(
                text = contact.nickname.ifBlank { contact.fingerprint.take(8) },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.size(12.dp))

            Text(
                text = "指纹",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.size(4.dp))

            // 完整指纹: 等宽 + 大字号 + 可选中复制
            SelectionContainer {
                Text(
                    text = contact.fingerprint,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 18.sp,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.size(24.dp))

            Button(
                onClick = onCopy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.ContentCopy, contentDescription = null)
                Spacer(modifier = Modifier.size(8.dp))
                Text("复制指纹")
            }

            Spacer(modifier = Modifier.size(8.dp))

            OutlinedButton(
                onClick = {
                    onDelete()
                    closeWithAnimation()
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Icon(Icons.Filled.Delete, contentDescription = null)
                Spacer(modifier = Modifier.size(8.dp))
                Text("删除联系人")
            }

            Spacer(modifier = Modifier.size(8.dp))

            TextButton(
                onClick = closeWithAnimation,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("关闭")
            }
        }
    }
}

/**
 * 添加联系人对话框 (v3.23: 仅指纹)
 *
 * 只接受完整指纹 —— 32 位十六进制 (不区分大小写, 自动去空白),
 * 实时校验并给定向错误提示; DID 输入被拒绝 (添加凭据唯一化)。
 * 确认时昵称默认 "用户 + 指纹前 8 位"。
 */
@Composable
private fun AddContactDialog(
    onConfirm: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var fingerprint by remember { mutableStateOf("") }
    val fingerprintFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        fingerprintFocusRequester.requestFocus()
    }

    // v3.23: 输入即校验 —— 清洗 (去空白/小写) 后须恰为 32 位 hex
    val cleaned = FingerprintInput.clean(fingerprint)
    val errorText = when {
        fingerprint.isBlank() -> null
        FingerprintInput.isDid(fingerprint) ->
            "仅支持指纹添加, 请让对方提供完整指纹"
        !FingerprintInput.isValid(cleaned) ->
            "指纹格式不正确: 应为 32 位十六进制字符 (当前 ${cleaned.length} 位)"
        else -> null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加联系人") },
        text = {
            Column {
                OutlinedTextField(
                    value = fingerprint,
                    onValueChange = { fingerprint = it.take(64) },
                    label = { Text("对方指纹") },
                    singleLine = true,
                    isError = errorText != null,
                    supportingText = {
                        Text(
                            text = errorText ?: "32 位十六进制 · 不区分大小写",
                            color = if (errorText != null) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Ascii,
                        imeAction = ImeAction.Done
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(fingerprintFocusRequester)
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = errorText == null,
                onClick = {
                    onConfirm(cleaned, "用户 ${cleaned.take(8)}")
                }
            ) {
                Text("确认")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
