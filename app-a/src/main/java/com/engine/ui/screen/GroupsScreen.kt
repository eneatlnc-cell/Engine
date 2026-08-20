package com.engine.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.HowToReg
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.engine.EngineApp
import com.engine.data.EngineGroup
import com.engine.ui.components.BreathingEmptyState
import com.engine.ui.components.GradientAvatar
import com.securesocial.core.protocol.GroupRoles

/**
 * ═══════════════════════════════════════════════════════════════════
 *  v3.14 · 群组管理页 —— 设置抽屉 "群组" 行的落地页
 * ═══════════════════════════════════════════════════════════════════
 *
 *  · 我的群组列表: 群名 / 成员数 / 我的角色; 点击进入群聊
 *  · 群主行: 邀请码 (点击复制)
 *  · 操作: 顶栏 新建 / 加入; 行内 退群 (成员) / 解散 (群主)
 *  · 群数据纯内存: 退出应用后群需重建或凭码重入
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupsScreen(
    navController: NavController
) {
    val app = EngineApp.get()
    val groups by app.groupStore.groups.collectAsState()
    val clipboard = LocalClipboardManager.current

    var showCreate by remember { mutableStateOf(false) }
    var showJoin by remember { mutableStateOf(false) }
    var confirmAction by remember { mutableStateOf<EngineGroup?>(null) }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("群组", fontWeight = FontWeight.SemiBold)
                        Text(
                            "${groups.size} 个群 · 仅存内存",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { showJoin = true }) {
                        Icon(Icons.Default.HowToReg, contentDescription = "加入群组")
                    }
                    IconButton(onClick = { showCreate = true }) {
                        Icon(Icons.Default.GroupAdd, contentDescription = "新建群组")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent
                )
            )
        }
    ) { innerPadding ->
        if (groups.isEmpty()) {
            // v3.22: 呼吸动画空态大图 (与聊天/联系人/标记物四页统一);
            // 旧静态小图 + alpha 0.5/0.6 文字夜间对比度不足, 一并修复
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                BreathingEmptyState(
                    icon = Icons.Filled.Groups,
                    title = "还没有群组",
                    subtitle = "右上角加入或新建 · 聊天页 + 也可建群"
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(groups, key = { it.id }) { group ->
                    GroupCard(
                        group = group,
                        onOpen = {
                            navController.navigate("chat/${EngineGroup.conversationKey(group.id)}")
                        },
                        onDetail = {
                            navController.navigate("groupDetail/${group.id}")
                        },
                        onCopyCode = { code -> clipboard.setText(AnnotatedString(code)) },
                        onDanger = { confirmAction = group }
                    )
                }
            }
        }
    }

    if (showCreate) {
        NewGroupDialog(onDismiss = { showCreate = false })
    }
    if (showJoin) {
        JoinGroupDialog(onDismiss = { showJoin = false })
    }

    // 退群 / 解散二次确认
    confirmAction?.let { group ->
        AlertDialog(
            onDismissRequest = { confirmAction = null },
            title = { Text(if (group.isOwner) "解散群组?" else "退出群组?") },
            text = {
                Text(
                    if (group.isOwner)
                        "将解散 \"${group.name}\" 并通知所有成员, 此操作不可恢复。"
                    else
                        "将退出 \"${group.name}\"; 群主会轮换密钥, 你将无法再收到该群消息。"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        app.leaveGroup(group.id)
                        confirmAction = null
                    }
                ) {
                    Text(
                        if (group.isOwner) "解散" else "退出",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmAction = null }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun GroupCard(
    group: EngineGroup,
    onOpen: () -> Unit,
    onDetail: () -> Unit,
    onCopyCode: (String) -> Unit,
    onDanger: () -> Unit
) {
    val app = EngineApp.get()
    val pendingRequests by app.pendingJoinRequests.collectAsState()
    val myPending = pendingRequests.count { it.groupId == group.id }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .clickable(onClick = onOpen)
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GradientAvatar(seed = group.id, label = group.name, size = 40.dp)
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        group.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    // v3.19: 待审角标 (审批门禁下有新申请)
                    if (group.isOwner && myPending > 0) {
                        Spacer(Modifier.size(6.dp))
                        Text(
                            "$myPending 待审",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onError,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.error)
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                Text(
                    text = buildString {
                        append("${group.members.size} 名成员 · ")
                        append(
                            when (group.myRole) {
                                GroupRoles.OWNER -> "我是群主"
                                GroupRoles.ADMIN -> "管理员"
                                else -> "成员"
                            }
                        )
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // v3.19: 群详情 (成员/门禁/审批/邀请码)
            IconButton(onClick = onDetail) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = "群详情",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onDanger) {
                Icon(
                    imageVector = Icons.Default.Logout,
                    contentDescription = if (group.isOwner) "解散" else "退出",
                    // v3.22: 去 alpha 叠加, 夜间全对比度 (同页 314 行修复)
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }

        // 群主: 邀请码 (点击复制)
        if (group.isOwner && group.inviteCode != null) {
            Spacer(Modifier.height(10.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
                    .clickable { onCopyCode(group.inviteCode!!) }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(
                    "邀请码",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.size(12.dp))
                Text(
                    group.inviteCode!!,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    if (group.inviteConfirmed) "已登记 · 点击复制" else "登记中...",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // 成员昵称预览 (一行)
        Spacer(Modifier.height(8.dp))
        Text(
            text = group.members.joinToString(" / ") { it.nickname },
            style = MaterialTheme.typography.labelSmall,
            // v3.22: 去 alpha 0.7 叠加 —— 夜间 onSurfaceVariant×0.7
            // 对比度不足, 用户实测 "看不清"
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
