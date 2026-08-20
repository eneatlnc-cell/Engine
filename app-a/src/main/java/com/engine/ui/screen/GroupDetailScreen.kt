package com.engine.ui.screen

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.HowToReg
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import com.engine.data.GroupGateMode
import com.engine.data.JoinRequest
import com.engine.ui.components.GradientAvatar
import com.securesocial.core.protocol.GroupLimits
import com.securesocial.core.protocol.GroupRoles

/**
 * ═══════════════════════════════════════════════════════════════════
 *  v3.19 · 群详情页 (P1 群聊 UI + P2 门禁)
 * ═══════════════════════════════════════════════════════════════════
 *
 *  入口: 群聊页顶栏标题 / 群组管理页群卡片设置键
 *
 *  全员可见:
 *  · 群名片 (名称/成员数/上限/角色)
 *  · 成员列表: 在场圆点 (心跳窗口判定) + 角色徽章
 *
 *  群主专属:
 *  · 门禁切换: 自动通过 / 群主审批 (JOIN_REQ → 待审队列)
 *  · 待审申请列表: 逐条 同意 / 拒绝
 *  · 邀请码: 展示 / 复制 / 刷新 (旧码 24h 过期自然失效)
 *
 *  危险区: 退群 (成员) / 解散 (群主)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupDetailScreen(
    navController: NavController,
    groupId: String
) {
    val app = EngineApp.get()
    val groups by app.groupStore.groups.collectAsState()
    val pendingRequests by app.pendingJoinRequests.collectAsState()
    val clipboard = LocalClipboardManager.current

    val group = groups.find { it.id == groupId }
    var confirmLeave by remember { mutableStateOf(false) }

    // 群已消散 (解散/退群/被移除后落回): 自动返回
    if (group == null) {
        androidx.compose.runtime.LaunchedEffect(Unit) {
            navController.popBackStack()
        }
        return
    }

    // 在场状态刷新拍 (15s): 成员圆点跟随心跳窗口变化
    var presenceTick by remember { mutableStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(15_000)
            presenceTick++
        }
    }

    val myRequests = pendingRequests.filter { it.groupId == groupId }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("群详情", fontWeight = FontWeight.SemiBold)
                        Text(
                            group.name,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ---- 群名片 ----
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                        .padding(16.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        GradientAvatar(seed = group.id, label = group.name, size = 52.dp)
                        Spacer(Modifier.size(14.dp))
                        Column {
                            Text(
                                group.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "${group.members.size}/${GroupLimits.MAX_MEMBERS} 名成员 · " +
                                    "密钥 v${group.keyVersion}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "群数据仅存内存 · 最后一人离线群即消散",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
            }

            // ---- 群主: 门禁 + 待审申请 ----
            if (group.isOwner) {
                item { GateCard(group) }

                if (myRequests.isNotEmpty()) {
                    item {
                        Text(
                            "待审申请 (${myRequests.size})",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    items(myRequests, key = { "${it.groupId}:${it.fp}" }) { req ->
                        JoinRequestCard(
                            request = req,
                            onApprove = { app.approveJoinRequest(groupId, req.fp) },
                            onReject = { app.rejectJoinRequest(groupId, req.fp) }
                        )
                    }
                }

                item {
                    InviteCodeCard(
                        group = group,
                        onCopy = { clipboard.setText(AnnotatedString(it)) },
                        onRefresh = { app.refreshInviteCode(groupId) }
                    )
                }
            }

            // ---- 成员列表 ----
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "成员 (${group.members.size})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "圆点 = 在线",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
            }
            items(group.members, key = { it.fp }) { member ->
                MemberRow(
                    presenceTick = presenceTick,
                    groupId = group.id,
                    fp = member.fp,
                    nickname = member.nickname,
                    role = member.role
                )
            }

            // ---- 危险区 ----
            item {
                TextButton(
                    onClick = { confirmLeave = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        Icons.Default.Logout,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.size(6.dp))
                    Text(
                        if (group.isOwner) "解散群组" else "退出群组",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
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
                        app.leaveGroup(groupId)
                        confirmLeave = false
                        navController.popBackStack()
                    }
                ) {
                    Text(
                        if (group.isOwner) "解散" else "退出",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmLeave = false }) { Text("取消") }
            }
        )
    }
}

/**
 * 门禁卡 (群主): 自动通过 / 群主审批 切换
 */
@Composable
private fun GateCard(group: EngineGroup) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.HowToReg,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.size(8.dp))
            Text(
                "入群门禁",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            if (group.gateMode == GroupGateMode.APPROVAL)
                "申请将进入待审队列, 由你逐条同意或拒绝 (群主离线期间申请排队等待)"
            else
                "凭邀请码申请, 群主在线即自动通过",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = group.gateMode == GroupGateMode.AUTO,
                onClick = { EngineApp.get().setGroupGate(group.id, GroupGateMode.AUTO) },
                label = { Text("自动通过") }
            )
            FilterChip(
                selected = group.gateMode == GroupGateMode.APPROVAL,
                onClick = { EngineApp.get().setGroupGate(group.id, GroupGateMode.APPROVAL) },
                label = { Text("群主审批") }
            )
        }
    }
}

/**
 * 待审申请卡: 申请者 + 申请时间 + 同意/拒绝
 */
@Composable
private fun JoinRequestCard(
    request: JoinRequest,
    onApprove: () -> Unit,
    onReject: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        GradientAvatar(seed = request.fp, label = request.nickname, size = 36.dp)
        Spacer(Modifier.size(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                request.nickname,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "@${request.fp.take(8)} · 申请入群",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onApprove) {
            Icon(
                Icons.Default.Check,
                contentDescription = "同意",
                tint = MaterialTheme.colorScheme.primary
            )
        }
        IconButton(onClick = onReject) {
            Icon(
                Icons.Default.Close,
                contentDescription = "拒绝",
                tint = MaterialTheme.colorScheme.error
            )
        }
    }
}

/**
 * 邀请码卡 (群主): 大号码 + 复制 + 刷新 + 登记状态
 */
@Composable
private fun InviteCodeCard(
    group: EngineGroup,
    onCopy: (String) -> Unit,
    onRefresh: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "邀请码",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.weight(1f))
            Text(
                if (group.inviteConfirmed) "已在中继登记 · 24 小时有效"
                else "正在中继登记...",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = group.inviteCode ?: "生成中...",
                fontSize = 28.sp,
                letterSpacing = 3.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { group.inviteCode?.let(onCopy) }) {
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = "复制邀请码",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onRefresh) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = "更换邀请码",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "刷新后旧码立即作废 (自然过期 24h), 需将新码告知被邀请人",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
    }
}

/**
 * 成员行: 在场圆点 + 头像 + 昵称 + 角色徽章
 *
 * 在场状态以 [presenceTick] 为键重算: 页面级 15s 刷新拍驱动,
 * 不为每个成员行起独立轮询协程 (200 人群也只有一个定时器)。
 */
@Composable
private fun MemberRow(
    presenceTick: Int,
    groupId: String,
    fp: String,
    nickname: String,
    role: String
) {
    val app = EngineApp.get()
    val online = remember(presenceTick, groupId, fp) {
        app.isMemberOnline(groupId, fp)
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Box {
            GradientAvatar(seed = fp, label = nickname, size = 38.dp)
            // 在场圆点: 心跳窗口内收到 PRESENCE = 在线
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .align(Alignment.BottomEnd)
                    .clip(CircleShape)
                    .background(
                        if (online) Color(0xFF4CAF50)
                        else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                    )
            )
        }
        Spacer(Modifier.size(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                nickname,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "@${fp.take(8)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        // 角色徽章
        if (role == GroupRoles.OWNER) {
            RoleBadge("群主", MaterialTheme.colorScheme.primary)
        } else if (role == GroupRoles.ADMIN) {
            RoleBadge("管理", MaterialTheme.colorScheme.tertiary)
        }
        if (online) {
            Spacer(Modifier.width(4.dp))
            Text(
                "在线",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF4CAF50)
            )
        }
    }
}

@Composable
private fun RoleBadge(label: String, color: Color) {
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    )
}
