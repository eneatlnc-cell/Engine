package com.engine.ui.screen

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.engine.viewmodel.KeyBindingUiState
import com.engine.viewmodel.KeyBindingViewModel

/**
 * 密钥绑定页面
 *
 * - 未绑定: "生成密钥对" 按钮进入二维码流程
 * - 已绑定: 展示当前绑定指纹状态卡, 重新绑定需二次确认 (危险操作)
 * - FLAG_SECURE 已由 MainActivity 全局设置, 此处不再单独管理
 *   (原先 onDispose 会 clearFlags, 导致离开本页后全局防截屏失效)
 */
@Composable
fun KeyBindingScreen(
    onSuccess: () -> Unit,
    viewModel: KeyBindingViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var showRebindConfirm by remember { mutableStateOf(false) }

    // 每次进入该页时刷新绑定状态
    LaunchedEffect(Unit) {
        viewModel.refreshBoundState()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            when (val state = uiState) {
                is KeyBindingUiState.Idle -> {
                    IdleContent(
                        onGenerate = { viewModel.generateKeyPair() },
                        // v3.13: 数据清除/换机后的第一推荐 —— 从 Vault 恢复同一 DID
                        onRestore = { viewModel.restoreFromVault() }
                    )
                }

                is KeyBindingUiState.Bound -> {
                    BoundContent(
                        fingerprint = state.fingerprint,
                        onRebind = { showRebindConfirm = true }
                    )
                }

                is KeyBindingUiState.Generating -> {
                    ProgressContent(text = "正在生成密钥对…")
                }

                is KeyBindingUiState.ShowingQR -> {
                    ShowingQRContent(
                        bitmap = state.bitmap,
                        fingerprint = state.fingerprint,
                        onLaunchAppB = { viewModel.launchAppB() }
                    )
                }

                is KeyBindingUiState.WaitingCallback -> {
                    ProgressContent(text = "等待 Vault 回调中…")
                }

                is KeyBindingUiState.Restoring -> {
                    ProgressContent(text = "正在从 Vault 恢复身份…")
                }

                is KeyBindingUiState.Success -> {
                    SuccessContent(
                        onSuccess = onSuccess
                    )
                }

                is KeyBindingUiState.Error -> {
                    ErrorContent(
                        message = state.message,
                        onRetry = { viewModel.generateKeyPair() }
                    )
                }
            }
        }
    }

    // 重新绑定二次确认: 防止误触导致旧身份永久失效
    if (showRebindConfirm) {
        AlertDialog(
            onDismissRequest = { showRebindConfirm = false },
            title = { Text("重新绑定密钥?") },
            text = {
                Text(
                    "重新绑定将生成全新密钥对, Vault 中的旧私钥会被覆盖, " +
                        "旧身份将永久失效, 所有联系人将无法再识别当前设备。"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRebindConfirm = false
                        viewModel.rebind()
                    }
                ) {
                    Text("确认重新绑定", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRebindConfirm = false }) {
                    Text("取消")
                }
            }
        )
    }
}

/**
 * 空闲状态: 生成按钮 + v3.13 恢复入口
 *
 * 恢复入口排序在生成之前: 清除数据/换机场景下, 用户的第一诉求是
 * "找回原来的身份" (DID 不变, 联系人不动), 而不是创建新身份。
 * Vault 无绑定时回 NO_BINDING, UI 引导回落到生成新密钥对。
 */
@Composable
private fun IdleContent(
    onGenerate: () -> Unit,
    onRestore: () -> Unit
) {
    Text(
        text = "密钥绑定",
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onBackground
    )
    Spacer(modifier = Modifier.size(16.dp))
    Text(
        text = "曾绑定过 Vault? 可直接恢复原身份 (DID 不变)\n或生成新密钥对开始全新身份",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
    )
    Spacer(modifier = Modifier.size(32.dp))
    Button(
        onClick = onRestore,
        contentPadding = PaddingValues(horizontal = 32.dp, vertical = 14.dp)
    ) {
        Text("从 Vault 恢复身份", fontWeight = FontWeight.SemiBold)
    }
    Spacer(modifier = Modifier.size(12.dp))
    OutlinedButton(
        onClick = onGenerate,
        contentPadding = PaddingValues(horizontal = 32.dp, vertical = 14.dp)
    ) {
        Text("生成新密钥对", fontWeight = FontWeight.SemiBold)
    }
}

/**
 * 已绑定状态: 展示当前绑定指纹, 重新绑定降级为需二次确认的危险操作
 */
@Composable
private fun BoundContent(
    fingerprint: String,
    onRebind: () -> Unit
) {
    Icon(
        imageVector = Icons.Filled.Key,
        contentDescription = null,
        modifier = Modifier.size(56.dp),
        tint = MaterialTheme.colorScheme.primary
    )
    Spacer(modifier = Modifier.size(16.dp))
    Text(
        text = "密钥已绑定",
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onBackground
    )
    Spacer(modifier = Modifier.size(24.dp))

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "当前绑定身份指纹",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.size(8.dp))
            Text(
                text = fingerprint,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.size(8.dp))
            Text(
                text = "私钥安全保存在 Vault 中 (TEE 加密)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    Spacer(modifier = Modifier.size(24.dp))

    Text(
        text = "如需更换身份可重新绑定, 该操作会使旧身份永久失效",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
    )
    Spacer(modifier = Modifier.size(12.dp))

    OutlinedButton(
        onClick = onRebind,
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.error
        ),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 10.dp)
    ) {
        Icon(
            imageVector = Icons.Filled.Warning,
            contentDescription = null,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.size(8.dp))
        Text("重新绑定密钥 (危险操作)")
    }
}

/**
 * 展示二维码状态
 */
@Composable
private fun ShowingQRContent(
    bitmap: android.graphics.Bitmap,
    fingerprint: String,
    onLaunchAppB: () -> Unit
) {
    Text(
        text = "密钥二维码",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onBackground
    )
    Spacer(modifier = Modifier.size(8.dp))
    Text(
        text = "指纹: ${fingerprint.take(16)}…",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(modifier = Modifier.size(20.dp))

    // 二维码 (带 M3 primary 描边)
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = "密钥二维码",
        modifier = Modifier
            .size(260.dp)
            .border(4.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
            .padding(8.dp)
    )

    Spacer(modifier = Modifier.size(20.dp))

    Text(
        text = "点击下方按钮, Vault 将自动识别并导入密钥",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
    )

    Spacer(modifier = Modifier.size(24.dp))

    // 操作按钮
    Button(
        onClick = onLaunchAppB,
        contentPadding = PaddingValues(horizontal = 32.dp, vertical = 14.dp)
    ) {
        Text("一键召唤 Vault", fontWeight = FontWeight.SemiBold)
    }
}

/**
 * 进度状态 (生成中 / 等待回调)
 */
@Composable
private fun ProgressContent(text: String) {
    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
    Spacer(modifier = Modifier.size(16.dp))
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
    )
}

/**
 * 成功状态
 */
@Composable
private fun SuccessContent(onSuccess: () -> Unit) {
    Icon(
        imageVector = Icons.Filled.CheckCircle,
        contentDescription = null,
        modifier = Modifier.size(64.dp),
        tint = MaterialTheme.colorScheme.primary
    )
    Spacer(modifier = Modifier.size(16.dp))
    Text(
        text = "密钥绑定成功",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onBackground
    )
    Spacer(modifier = Modifier.size(8.dp))
    Text(
        text = "已建立安全通信身份",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(modifier = Modifier.size(24.dp))
    Button(
        onClick = onSuccess,
        contentPadding = PaddingValues(horizontal = 32.dp, vertical = 14.dp)
    ) {
        Text("完成", fontWeight = FontWeight.SemiBold)
    }
}

/**
 * 错误状态
 */
@Composable
private fun ErrorContent(message: String, onRetry: () -> Unit) {
    Icon(
        imageVector = Icons.Filled.Error,
        contentDescription = null,
        modifier = Modifier.size(64.dp),
        tint = MaterialTheme.colorScheme.error
    )
    Spacer(modifier = Modifier.size(16.dp))
    Text(
        text = "密钥绑定失败",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onBackground
    )
    Spacer(modifier = Modifier.size(8.dp))
    Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
    )
    Spacer(modifier = Modifier.size(24.dp))
    Button(
        onClick = onRetry,
        contentPadding = PaddingValues(horizontal = 32.dp, vertical = 14.dp)
    ) {
        Text("重试", fontWeight = FontWeight.SemiBold)
    }
}
