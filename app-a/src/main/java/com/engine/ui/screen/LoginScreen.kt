package com.engine.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * 身份验证登录页面
 *
 * 二次启动 (已绑定、未登录) 时全屏展示:
 * - 已绑定的公钥指纹 (截断显示, 等宽字体)
 * - "通过 Vault 指纹验证" 按钮 → IPC 唤起 Vault 系统指纹弹窗
 * - 加载中状态 (CircularProgressIndicator)
 * - 错误信息提示
 * - "重新绑定密钥" 文本按钮 (危险操作入口)
 *
 * 设计说明: 不再要求手输 6 位动态码 —— 原流程中 Engine 必须唤起 Vault
 * 才能完成比对, 数字码本身不提供任何 Engine 侧可验证的密码学保证,
 * 反而增加转抄成本并引入被暴力猜测的攻击面。改为直接以 Vault 侧
 * 生物识别作为持有证明, 步骤从 5 步降至 2 步。
 *
 * @param boundFingerprint 已绑定的公钥指纹 (32 字符十六进制)
 * @param onVerify 触发 Vault 指纹验证
 * @param onRebind 重新绑定回调, 清除身份后进入密钥绑定流程
 * @param isLoading 是否正在等待 Vault 回调
 * @param errorMessage 验证错误信息 (非空时展示)
 */
@Composable
fun LoginScreen(
    boundFingerprint: String,
    onVerify: () -> Unit,
    onRebind: () -> Unit,
    isLoading: Boolean = false,
    errorMessage: String? = null
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 6.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 图标
                Icon(
                    imageVector = Icons.Filled.Fingerprint,
                    contentDescription = null,
                    modifier = Modifier.size(56.dp),
                    tint = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(4.dp))

                // 标题
                Text(
                    text = "身份验证",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                // 副标题
                Text(
                    text = "点击下方按钮, 在 Vault 中完成指纹验证",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(12.dp))

                // 已绑定指纹 (截断显示, 等宽字体)
                val truncatedFingerprint = if (boundFingerprint.length > 12) {
                    "${boundFingerprint.take(12)}…"
                } else {
                    boundFingerprint
                }
                Text(
                    text = "已绑定指纹: $truncatedFingerprint",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(16.dp))

                // 验证按钮
                Button(
                    onClick = { onVerify() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoading
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                        Text("等待 Vault 指纹验证…")
                    } else {
                        Text("通过 Vault 指纹验证", fontWeight = FontWeight.SemiBold)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 重新绑定按钮 (危险操作: 会使旧身份永久失效)
                TextButton(
                    onClick = onRebind,
                    enabled = !isLoading
                ) {
                    Text(
                        "重新绑定密钥",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // 错误信息
                if (errorMessage != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}
