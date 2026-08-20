package com.engine

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.engine.data.BoundIdentityStore
import com.engine.ui.components.EngineBackground
import com.engine.ui.screen.ChatListScreen
import com.engine.ui.screen.ChatScreen
import com.engine.ui.screen.DidScreen
import com.engine.ui.screen.GroupDetailScreen
import com.engine.ui.screen.GroupsScreen
import com.engine.ui.screen.LoginScreen
import com.engine.ui.screen.MarksScreen
import com.engine.ui.theme.EngineTheme
import com.engine.viewmodel.LoginUiState
import com.engine.viewmodel.LoginViewModel

/**
 * v3.19: 入群结论全局对话框 (满员 / 群主拒绝)
 *
 * JOIN_RESP 拒绝在申请发出数秒后到达, 入群对话框早已关闭 ——
 * 经 EngineApp.joinFeedback 全局呈现, 任意页面均可见。
 */
@Composable
private fun JoinFeedbackDialog(app: EngineApp) {
    val feedback by app.joinFeedback.collectAsState()
    feedback?.let { fb ->
        AlertDialog(
            onDismissRequest = { app.joinFeedback.value = null },
            icon = {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { Text("无法入群") },
            text = { Text(fb.message) },
            confirmButton = {
                TextButton(onClick = { app.joinFeedback.value = null }) {
                    Text("知道了")
                }
            }
        )
    }
}

/**
 * 主 Activity
 *
 * 职责:
 * - Navigation Compose 导航 (聊天列表 ↔ 聊天页面)
 * - 登录门控: 已绑定指纹且未通过 Vault 验证时, 全屏展示 LoginScreen
 * - 处理 Intent (myvault://callback) → 传递给等待中的 ViewModel
 *
 * v3: 登录态为进程级 —— 切换应用 (退后台/回前台) 不再重新验证,
 * 仅进程结束后再次启动时需重新通过 Vault 指纹验证。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 全局防截屏: 所有页面 (聊天/登录/联系人/密钥) 均禁止截屏与录屏
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        // 处理启动 Intent (myvault://callback)
        handleCallbackIntent(intent)

        val app = application as EngineApp

        setContent {
            EngineTheme(darkTheme = app.isDarkTheme) {
                // v3.8: 统一渐变背景 —— 端点取自插值后的 scheme,
                // 与所有组件同源同钟, 状态栏同款顶端色 (见 ThemeSync.kt)
                EngineBackground {
                    LoginGate {
                        val navController = rememberNavController()

                        NavHost(
                            navController = navController,
                            startDestination = "chatList"
                        ) {
                            // 聊天列表主屏 (含底部导航: 聊天/联系人/密钥绑定)
                            composable("chatList") {
                                ChatListScreen(
                                    navController = navController
                                )
                            }

                            // 聊天详情页
                            composable(
                                route = "chat/{peerFingerprint}",
                                arguments = listOf(
                                    navArgument("peerFingerprint") {
                                        type = NavType.StringType
                                    }
                                )
                            ) { backStackEntry ->
                                val peerFingerprint =
                                    backStackEntry.arguments?.getString("peerFingerprint") ?: ""

                                ChatScreen(
                                    navController = navController,
                                    peerFingerprint = peerFingerprint
                                )
                            }

                            // v3.22: DID 身份页 (设置抽屉 → DID 身份; 头像/昵称管理)
                            composable("did") {
                                DidScreen(navController = navController)
                            }

                            // v3.16: 标记物列表 (设置抽屉 → 标记物)
                            composable("marks") {
                                MarksScreen(navController = navController)
                            }

                            // v3.14: 群组管理 (设置抽屉 → 群组)
                            composable("groups") {
                                GroupsScreen(navController = navController)
                            }

                            // v3.19: 群详情 (群聊顶栏 / 群组管理卡片 → 成员列表/门禁/审批/邀请码)
                            composable(
                                route = "groupDetail/{groupId}",
                                arguments = listOf(
                                    navArgument("groupId") {
                                        type = NavType.StringType
                                    }
                                )
                            ) { backStackEntry ->
                                val groupId =
                                    backStackEntry.arguments?.getString("groupId") ?: ""

                                GroupDetailScreen(
                                    navController = navController,
                                    groupId = groupId
                                )
                            }
                        }

                        // v3.19: 入群结论全局对话框 (拒绝反馈异步到达,
                        // 此时入群对话框多已关闭, 需全局呈现)
                        JoinFeedbackDialog(app)
                    }
                }
            }
        }
    }

    /**
     * 登录门控:
     * - 已绑定身份 && 未登录 → LoginScreen (Vault 指纹验证)
     * - 否则 → 主内容
     *
     * 绑定指纹在每次 ON_RESUME 时刷新, 保证 "重新绑定" 后状态即时生效。
     */
    @Composable
    private fun LoginGate(content: @Composable () -> Unit) {
        val app = application as EngineApp
        // 直接读取 (mutableStateOf 属性在组合内读取即具响应性, 无需 by 委托)
        val isLoggedIn = app.isLoggedIn
        val lifecycleOwner = LocalLifecycleOwner.current

        var boundFingerprint by remember {
            mutableStateOf(BoundIdentityStore(applicationContext).getBoundFingerprint())
        }
        var showRebindConfirm by remember { mutableStateOf(false) }

        // 每次回到前台时重新读取绑定状态
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    boundFingerprint = BoundIdentityStore(applicationContext).getBoundFingerprint()
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

        if (boundFingerprint != null && !isLoggedIn) {
            val loginViewModel: LoginViewModel = viewModel()
            val loginState by loginViewModel.uiState.collectAsState()

            LoginScreen(
                boundFingerprint = boundFingerprint ?: "",
                onVerify = { loginViewModel.verify() },
                onRebind = { showRebindConfirm = true },
                isLoading = loginState is LoginUiState.Loading,
                errorMessage = (loginState as? LoginUiState.Error)?.message
            )

            // 重新绑定为危险操作: 必须二次确认
            if (showRebindConfirm) {
                AlertDialog(
                    onDismissRequest = { showRebindConfirm = false },
                    icon = {
                        Icon(
                            imageVector = Icons.Filled.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                    },
                    title = { Text("重新绑定密钥?") },
                    text = {
                        Text(
                            "当前身份将永久失效, 所有联系人将无法再识别本设备。\n\n" +
                                "确认后将进入密钥绑定流程: 生成新密钥对并导入 Vault, " +
                                "Vault 中的旧密钥会被新密钥替换。"
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                showRebindConfirm = false
                                BoundIdentityStore(applicationContext).clearBoundFingerprint()
                                app.pendingRebindToKeys = true
                                boundFingerprint = null
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
        } else {
            content()
        }
    }

    /**
     * 处理 onNewIntent (App 已在后台时收到 myvault://callback)
     */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleCallbackIntent(intent)
    }

    /**
     * 解析 myvault://callback Intent 并投递给 EngineApp
     */
    private fun handleCallbackIntent(intent: Intent?) {
        if (intent == null) return
        val app = application as EngineApp
        val callback = app.ipcClient.handleCallbackIntent(intent)
        if (callback != null) {
            app.deliverCallback(callback)
        }
        // 清除 Intent 数据, 避免重复处理
        intent.data = null
    }
}
