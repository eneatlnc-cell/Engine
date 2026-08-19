package com.engine.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.engine.EngineApp
import com.engine.ui.components.GradientAvatar
import com.securesocial.core.protocol.GroupErrorCodes

/**
 * ═══════════════════════════════════════════════════════════════════
 *  v3.14 · 新建群组对话框 (群主侧)
 * ═══════════════════════════════════════════════════════════════════
 *
 *  流程: 群名 + 从联系人多选初始成员 → 建群
 *  → 群密钥(v1)生成并分发, 邀请码经中继登记 (回执异步)
 *  → 展示邀请码 (可复制), 供其他人口播/截图入群
 */
@Composable
fun NewGroupDialog(
    onDismiss: () -> Unit
) {
    val app = EngineApp.get()
    val contacts by app.contactStore.contacts.collectAsState()
    val clipboard = LocalClipboardManager.current

    var name by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var createdGid by remember { mutableStateOf<String?>(null) }

    val groups by app.groupStore.groups.collectAsState()
    val created = createdGid?.let { gid -> groups.find { it.id == gid } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (created == null) "新建群组" else "群组已创建") },
        text = {
            if (created == null) {
                Column {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { if (it.length <= 20) name = it },
                        label = { Text("群名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "选择成员 (${selected.size})",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    if (contacts.isEmpty()) {
                        Text(
                            "暂无联系人, 可先创建只含自己的群组",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(220.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            items(contacts, key = { it.fingerprint }) { contact ->
                                val checked = contact.fingerprint in selected
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable {
                                            selected = if (checked)
                                                selected - contact.fingerprint
                                            else selected + contact.fingerprint
                                        }
                                        .padding(horizontal = 4.dp, vertical = 6.dp)
                                ) {
                                    GradientAvatar(
                                        seed = contact.fingerprint,
                                        label = contact.nickname,
                                        size = 32.dp
                                    )
                                    Spacer(Modifier.size(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            contact.nickname,
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            "@${contact.fingerprint.take(8)}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Icon(
                                        imageVector = if (checked) Icons.Default.CheckCircle
                                        else Icons.Default.RadioButtonUnchecked,
                                        contentDescription = null,
                                        tint = if (checked) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        created.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "邀请码 (口播给要邀请的人)",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = created.inviteCode ?: "生成中...",
                            fontSize = 32.sp,
                            letterSpacing = 3.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.size(8.dp))
                        IconButton(onClick = {
                            created.inviteCode?.let {
                                clipboard.setText(AnnotatedString(it))
                            }
                        }) {
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = "复制邀请码",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (created.inviteConfirmed) "已在中继登记 · 24 小时有效"
                        else "正在中继登记...",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "新成员: 设置 → 群组 → 加入群组, 输入邀请码即可。\n群主需在线, 申请将自动通过。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            if (created == null) {
                TextButton(
                    onClick = {
                        createdGid = app.createGroup(name, selected.toList())
                    },
                    enabled = name.isNotBlank()
                ) { Text("创建") }
            } else {
                TextButton(onClick = onDismiss) { Text("完成") }
            }
        },
        dismissButton = {
            if (created == null) {
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    )
}

/**
 * ═══════════════════════════════════════════════════════════════════
 *  v3.14 · 加入群组对话框 (成员侧)
 * ═══════════════════════════════════════════════════════════════════
 *
 *  流程: 输入 8 位邀请码 → 中继目录查询群主指纹 → JOIN_REQ
 *  → 群主在线自动批准, KEY 分发到达即入群成功 (群列表出现该群)。
 */
@Composable
fun JoinGroupDialog(
    onDismiss: () -> Unit
) {
    val app = EngineApp.get()
    val lookupResult by app.roomLookupResult.collectAsState()
    val myFp = app.sessionManager.identityFingerprint

    var code by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf(false) }

    // 查询回执到达后自动关闭 (入群申请已发出 / 错误已提示)
    LaunchedEffect(lookupResult) {
        if (submitted && lookupResult != null && lookupResult?.ok == true) {
            kotlinx.coroutines.delay(1600)
            onDismiss()
        }
    }

    val normalized = code.trim().uppercase()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("加入群组") },
        text = {
            Column {
                OutlinedTextField(
                    value = code,
                    onValueChange = {
                        code = it.uppercase().filter { ch -> ch.isLetterOrDigit() }.take(8)
                    },
                    label = { Text("邀请码 (8 位)") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))

                when {
                    !submitted -> Text(
                        "向群主在线时申请, 自动通过。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    lookupResult == null -> Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.size(8.dp))
                        Text("查询中...", style = MaterialTheme.typography.bodySmall)
                    }
                    lookupResult?.ok == true -> {
                        if (lookupResult?.ownerFingerprint == myFp) {
                            Text(
                                "这是你自己发布的邀请码",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        } else {
                            Text(
                                "申请已发送, 群主批准后群聊将出现在列表中",
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                    else -> Text(
                        text = when (lookupResult?.error) {
                            GroupErrorCodes.NOT_FOUND -> "邀请码不存在或已过期"
                            GroupErrorCodes.RATE_LIMITED -> "查询太频繁, 请一分钟后再试"
                            GroupErrorCodes.INVALID_CODE -> "邀请码格式不正确"
                            else -> "暂无法入群, 请确认已连接中继"
                        },
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    submitted = true
                    app.joinByInviteCode(normalized)
                },
                enabled = normalized.length == 8 && !submitted
            ) { Text("申请入群") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}
