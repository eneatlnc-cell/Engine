# Engine 部署与双端测试方案 (v3.21)

> 目标: 一份文档跑通 **中继部署 → 双端安装 → E2E 验收** 全流程。
> 方案 C 的逐步落地命令 (systemd/Nginx TLS/防火墙/验收清单/运维回滚) 见 **[VPS_DEPLOY.md](VPS_DEPLOY.md)**; 本文侧重拓扑选择与测试矩阵。

## 0. 拓扑与前提

```
┌─────────┐   wss://   ┌──────────┐   wss://   ┌─────────┐
│ Engine A │◄──────────►│  中继 VPS │◄──────────►│ Engine B │
└────┬────┘            └──────────┘            └────┬────┘
     │ IPC (同签名)                               │ IPC
┌────▼────┐                                   ┌────▼────┐
│ Vault A │  身份私钥离线保管                    │ Vault B │
└─────────┘                                   └─────────┘
```

- **Engine** (`com.engine`): 聊天客户端, 每设备一个身份
- **Vault** (`com.vault`, 独立仓库): 身份私钥保险箱, 一切签名经 IPC 委托
- **中继**: 无状态 WebSocket 路由, 只见密文, 零落盘

**前提 (每台测试设备)**:
1. 安装 Vault APK 与 Engine APK —— **必须同签名** (开发期共用 debug keystore, 否则 signature 级 IPC 权限不授予)
2. 在 Engine 完成密钥绑定 → Vault 导入 → 登录 (LoginScreen 流程), 获得**稳定指纹**
3. 中继地址在构建时注入 (`-PrelayUrl=...`); debug 包默认 `ws://10.0.2.2:8080/relay`

**明文流量说明 (v3.21)**: targetSdk 28+ 默认禁止明文 HTTP/WS。v3.21 起
`app-a/src/debug/AndroidManifest.xml` 为 **debug 包**放行 `usesCleartextTraffic`
(方案 A/B 的 `ws://` 拓扑可用); **release 包不放行**, 强制 `wss://`。

---

## 方案 A: 本机模拟器双端 (5 分钟, 功能回归首选)

```bash
# 1. 启动本机中继 (默认监听 127.0.0.1:8080)
./gradlew :relay-server:installDist
./relay-server/build/install/relay-server/bin/relay-server

# 2. 构建双端 APK (默认 relayUrl 已指向模拟器宿主机)
./gradlew :app-a:assembleDebug
adb -s emulator-5554 install app-a/build/outputs/apk/debug/app-a-debug.apk
adb -s emulator-5556 install app-a/build/outputs/apk/debug/app-a-debug.apk
# (Vault APK 同样各装一份)

# 3. 双模拟器各完成: 绑定 → 登录 → 复制指纹互加联系人
```

模拟器访问宿主机统一用 `10.0.2.2`, 两个模拟器连同一中继即可互通。

**适用**: 贴纸 / 标记物 / 小群扇出 / 断网重连等功能回归 (下文矩阵 #3-#11)。

## 方案 B: 局域网真机双端 (真实射频 + Wi-Fi 环境)

```bash
# 1. 中继改为监听全网卡 (手机需能访问宿主机 IP)
RELAY_HOST=0.0.0.0 ./relay-server/build/install/relay-server/bin/relay-server

# 2. 以宿主机局域网 IP 构建 (查 IP: ip addr / ifconfig)
./gradlew :app-a:assembleDebug -PrelayUrl=ws://192.168.1.100:8080/relay

# 3. USB 安装到两台手机
adb install app-a/build/outputs/apk/debug/app-a-debug.apk
```

**适用**: 真机性能 / 长连接心跳 / Wi-Fi 切换断连恢复。注意中继直连暴露
`8080` 明文端口, 仅限内网测试, 测完关闭。

## 方案 C: VPS 跨网络生产形态 (发布前必跑)

按 **[VPS_DEPLOY.md](VPS_DEPLOY.md)** 完成 systemd + Nginx TLS + 防火墙, 然后:

```bash
# release 包指向正式域名 (强制 TLS)
./gradlew :app-a:assembleRelease -PrelayUrl=wss://relay.example.com/relay
```

两台手机走 4G/5G 蜂窝 (非同一 Wi-Fi), 验证跨运营商 NAT 长连接稳定性。

### v3.18+ 中继容量参数 (群扇出)

单机 1C/1G VPS 可支撑 ~1000 并发连接 + 数百活跃群。systemd 单元建议补充:

```ini
# /etc/systemd/system/relay.service 追加
Environment=JAVA_OPTS=-Xms128m -Xmx512m
LimitNOFILE=65535
```

中继侧协议限制 (无需配置, 部署时知悉即可):

| 参数 | 值 | 说明 |
|---|---|---|
| 载荷上限 | 128 KB/帧 | v3.17 起支持媒体消息 |
| 单连接业务帧 | 20 msg/s | 滑动窗口, 超限断连 |
| 单连接控制帧 | 128 msg/s | GROUP_SUBSCRIBE/GROUP_FANOUT 专用预算 |
| 单 IP 并发连接 | 20 | 反代部署下以 X-Forwarded-For 为准 |
| 单连接群订阅 | 64 群 | 超限回 GROUP_SUBSCRIBE_LIMIT |
| 群级扇出 | 10 msg/s · 16MB 突发 · 2MB/s | 双层令牌桶 (群级) |
| 全局扇出 | 32MB 突发 · 8MB/s | 双层令牌桶 (全中继) |
| 群成员上限 | 200 | 客户端侧限制 |

内存画像: 每连接 ~50KB (Netty 会话+缓冲) + 每订阅条目 ~200B;
1000 连接 × 10 群订阅 ≈ 100MB 堆内, `-Xmx512m` 留足扇出突发余量。

---

## 双端 E2E 测试矩阵

按序执行, 每项标注验证的功能版本。

### 基础链路

| # | 场景 | 步骤 | 预期 |
|---|---|---|---|
| 1 | 身份建立 | 每设备: 绑定密钥 → Vault 导入 → 登录 | 指纹生成且**重启后不变** (身份漂移防护) |
| 2 | 互加联系人 | A 复制指纹 → B 粘贴添加 (反向同理) | 双方联系人列表出现对方; 昵称默认指纹前 8 位 |
| 3 | 1:1 文本 | 双向收发若干条 | 双向秒达; 己方状态 → 已送达; 中继日志 `MSG routed` |

### 群聊扇出 (v3.18)

| # | 场景 | 步骤 | 预期 |
|---|---|---|---|
| 4 | 建群 | 群主: 新建群 → 复制邀请码; 成员: 凭码加入 | 三端群列表同步; 中继日志 `Subscribed ...` (GROUP_SUBSCRIBE 生效) |
| 5 | 群消息单帧扇出 | 任一成员发一条群消息 | **上行仅 1 帧** (中继日志一次 fanout), 其余两端各收 1 份; 对端可见发送者昵称 |
| 6 | 群心跳挂机 | 三端静置 ≥10 分钟 | 无一端断连 (v3.18 前大群心跳会打满限流); 中继无 `Rate limit exceeded` |
| 7 | 成员离线感知 | 一端杀进程 | 其余两端下次交互获知其离线; 重启后自动重连+重订阅 |
| 8 | 断网重连 | 飞行模式 30s → 恢复 | 自动重连, 恢复期消息按发送失败/重试语义处理, 重连后群订阅自动重建 |

### 贴纸 (v3.20)

| # | 场景 | 步骤 | 预期 |
|---|---|---|---|
| 9 | 1:1 贴纸 | 表情面板选贴纸发送 | 对端渲染 140dp 动态大表情 (无气泡); 点击全屏预览 |
| 10 | 群贴纸 | 群内发贴纸 | 单帧上行扇出到全部成员, 各端动态渲染 |
| 11 | 未知引用回退 | (可选) 手工构造 `[spark:st999_none]` 文本发出 | 按普通文本渲染, 不崩不丢 (兼容语义) |

### 标记物 (v3.21 本次重点)

| # | 场景 | 步骤 | 预期 |
|---|---|---|---|
| 12 | 标记文本 | 长按文本消息 → 标记 | 气泡出现书签角标; 标记物列表新增卡片 (含原文快照) |
| 13 | 标记贴纸 | 长按贴纸消息 → 标记 | 列表卡片显示**贴纸缩略图 + "👋 Spark 表情"**, 非线格式原文 |
| 14 | 取消标记 | 再次长按 → 取消标记 | 书签角标消失, 列表条目移除 |
| 15 | 搜索 | 列表页输入关键词 | 按内容/对端昵称实时过滤; 无匹配显示专用空态 |
| 16 | 分类 | 切换「全部 / 我发出的 / 收到」 | 过滤正确, chip 显示各类计数 |
| 17 | 单条删除 | 卡片右上 ✕ | 即时移除并落盘 |
| 18 | 全部清空 | 顶栏 DeleteSweep | 二次确认对话框 → 确认后清空; **聊天消息不受影响** |
| 19 | 导出 | 顶栏 IosShare | ShareSheet 弹出; 分享文本含全部快照 (贴纸渲染为 emoji 预览) |
| 20 | 持久化 | 杀进程 → 重启 App | 标记物完整保留; **聊天消息清空** (内存态隐私红线) |
| 21 | 旧数据兼容 | (升级测试) v3.20 标记物文件 → 装 v3.21 | 旧 JSON 无 stickerId 字段, 正常加载不丢 |

### 大群压测 (可选, v3.18 核心问题复现)

>21 人群是 v3.18 修复对象, 双手机难以构造, 建议多开模拟器 (≥22 个身份)
或编写中继直连压测脚本灌 GROUP_SUBSCRIBE + GROUP_MSG。验收口径:
单连接上行恒为 1 帧/消息, 中继群级令牌桶在 10 msg/s 内全部放行,
无 `Rate limit exceeded` 断连风暴。

---

## 观测与排障

```bash
# 中继日志跟踪
journalctl -u relay -f          # VPS
# 或本机直接看 stdout

# 关键日志行速查
# "Node authenticated & registered: <fp8>..."   认证+注册成功
# "Subscribed <fp8>... -> group <gid8>..."       群订阅生效 (debug 级)
# "Rate limit exceeded for <fp8>..."             触发限流 (需关注)
# "SOURCE_MISMATCH: ..."                         身份冒用尝试
# "Connection quota exceeded for <ip>"           单 IP 连接配额打满
```

| 症状 | 排查顺序 |
|---|---|
| App 永远「连接中」 | ① relayUrl 是否指向可达地址 ② debug 包才有 ws:// 权限 (v3.21 前 debug 包需手动加 usesCleartextTraffic) ③ 模拟器须用 `10.0.2.2` 非 `localhost` ④ 防火墙放行 |
| 反代后全部 IP 限流 | Nginx 需 `proxy_set_header X-Forwarded-For`, 且中继仅信任环回对端的 XFF (代码内置) |
| 互相收不到消息 | 双方指纹是否已互加为联系人; 对端是否离线 (离线即丢弃+发送方标失败) |
| 群消息只到部分人 | 中继日志查 `Subscribed`; 掉线成员需重连后重订阅 |
| ws 握手 400/404 | 路径须为 `/relay`; Nginx location 与 proxy_pass 均带 `/relay` |

## 构建速查

```bash
./gradlew :app-a:assembleDebug                                   # 模拟器默认地址
./gradlew :app-a:assembleDebug  -PrelayUrl=ws://192.168.1.100:8080/relay   # 局域网
./gradlew :app-a:assembleRelease -PrelayUrl=wss://relay.example.com/relay  # 生产
./gradlew :relay-server:installDist                              # 中继发行包
```
