# Engine — 物理隔离安全社交应用 (App A)

基于《物理隔离双端安全社交系统开发文档 V3》实现的聊天端应用。
本仓库仅包含 Engine 自身代码与共享基础库；离线保险箱见 **Vault 仓库** (`com.vault`)。

> v2 安全架构: 身份私钥仅存于 Vault (离线, Keystore 加密)。
> Engine 持有公钥 + 指纹, 一切身份签名 (中继挑战应答 / ECDH 信令) 经 IPC 委托 Vault 完成。

## 项目结构

```
engine/
├── settings.gradle.kts              # 多模块注册
├── build.gradle.kts                 # 根构建配置
├── gradle/libs.versions.toml        # 统一版本目录
│
├── core/                            # 共享基础库层 (与 Vault 仓库保持字节一致)
│   ├── core-protocol/               # WebSocket 协议 (v2: CHALLENGE/HELLO_AUTH 挑战应答)
│   ├── core-crypto/                 # ECDH/ECDSA/AES-GCM (v2: HKDF info 绑定 + AAD)
│   └── core-ipc/                    # URI Scheme IPC 契约 (v2: 签名回调 + 显式包名)
│
├── app-a/                           # Engine: 社交应用 (有 INTERNET, 除标记物外无持久化)
├── relay-server/                    # 无状态 WebSocket 中继 (Ktor + Netty, v2 挑战应答)
└── docs/                            # 部署与测试方案
    ├── DEPLOY_TEST.md               # v3.21: 中继部署拓扑 + 双端 E2E 测试矩阵
    ├── VPS_DEPLOY.md                # v3.21: VPS 从零部署手册 (systemd/Nginx TLS/验收清单)
    └── ECS_DEPLOY.md                # v3.21: 阿里云 ECS 过渡部署 (极早期, 差异章节 + 引用 VPS 手册)
```

## v2 安全模型摘要

| 威胁 | 对策 |
|------|------|
| 恶意 App 抢答/伪造 IPC | 全部 IPC 显式包名投递 + signature 级自定义权限, 入口无 BROWSABLE |
| 登录/绑定回调伪造 | 回调必须携带 ECDSA 签名 (sessionId‖status‖ts), Engine 用绑定公钥验签 + ±120s 时间窗 |
| 中继自报身份/顶号 | 注册须完成挑战-应答: HELLO(指纹+公钥) → CHALLENGE → HELLO_AUTH(签名) |
| ECDH 公钥中间人替换 | SIGNAL 信令携带身份签名, 接收端验签且指纹匹配才采纳 |
| 消息重放/移植 | GCM AAD 绑定 (source, target, seq) + 接收端 seq 单调递增校验 |
| 身份漂移 | 绑定公钥持久化, 二次启动恢复同一身份, 绝不静默生成新身份 |
| 资源滥用 | 单 IP 连接配额 / 20 msg/s 速率限制 / 128KB 载荷上限 (v3.17.1: 支撑 40KB 文本 / 48KB 媒体消息) / 认证 30s 超时 |

## 构建与运行

> 完整部署拓扑 (本机模拟器 / 局域网真机 / VPS 生产) 与双端 E2E 测试矩阵见 **[docs/DEPLOY_TEST.md](docs/DEPLOY_TEST.md)**。
> v3.21 起 debug 包自动放行明文 `ws://` (`src/debug/AndroidManifest.xml`), release 包强制 `wss://`。

### 前置条件
- JDK 17+
- Android SDK (compileSdk 34, minSdk 26)

### 构建命令

```bash
# 构建 App (默认中继地址 ws://10.0.2.2:8080/relay, 适配模拟器本机调试)
./gradlew :app-a:assembleDebug

# 指定生产中继地址 (你的 VPS)
./gradlew :app-a:assembleRelease -PrelayUrl=wss://relay.example.com/relay

# 构建中继服务器 (生成可执行发行包)
./gradlew :relay-server:installDist
```

`RELAY_URL` 经 `BuildConfig` 注入, 见 `app-a/build.gradle.kts`。

### 本地调试中继

```bash
./gradlew :relay-server:installDist
./relay-server/build/install/relay-server/bin/relay-server
# 监听 127.0.0.1:8080/relay (RELAY_HOST/RELAY_PORT 环境变量可覆盖)
```

模拟器内 App 访问宿主机用 `ws://10.0.2.2:8080/relay`。

## 中继服务器 VPS 部署 (生产)

推荐拓扑: **中继仅监听 127.0.0.1, 由 Nginx/Caddy 终结 TLS 后以 wss:// 对外暴露**。

**部署地选择 (v3.17 定案): 推荐 香港 VPS**。

- Engine 定位为安全加密社交应用, 中继是全流量必经点 —— 选址直接决定
  延迟与连通性。香港对中国大陆/东亚用户延迟低 (通常 30-80ms),
  国际带宽充足, 对 wss:// 长连接无干扰。
- 中继零状态零落盘 (仅路由密文), 换机/迁移只需改 DNS —— 选址不构成
  长期锁定风险。
- 注意: 需选择支持无限流/高流量包的套餐, 媒体消息 (贴纸 ≤44KB/条, Base64 后 ~59KB)
  上线后
  流量画像会显著高于纯文本时代。

### 1. 构建并上传

```bash
# 本地构建
./gradlew :relay-server:installDist
tar -czf relay-server.tar.gz -C relay-server/build/install relay-server

# 上传到 VPS
scp relay-server.tar.gz user@your-vps:/opt/
```

### 2. VPS 上解压并配置 JDK

```bash
sudo mkdir -p /opt/relay && sudo tar -xzf /opt/relay-server.tar.gz -C /opt/relay --strip-components=1
sudo apt-get update && sudo apt-get install -y openjdk-17-jre-headless
```

### 3. systemd 服务

```ini
# /etc/systemd/system/relay.service
[Unit]
Description=SecureSocial Relay Server
After=network.target

[Service]
Type=simple
User=relay
Environment=RELAY_HOST=127.0.0.1
Environment=RELAY_PORT=8080
ExecStart=/opt/relay/bin/relay-server
Restart=always
RestartSec=3
# 最小权限加固
NoNewPrivileges=true
ProtectSystem=strict
PrivateTmp=true

[Install]
WantedBy=multi-user.target
```

```bash
sudo useradd -r -s /usr/sbin/nologin relay
sudo chown -R relay:relay /opt/relay
sudo systemctl daemon-reload
sudo systemctl enable --now relay
```

### 4. Nginx TLS 反向代理 (wss://)

```nginx
# /etc/nginx/sites-available/relay
server {
    listen 443 ssl;
    server_name relay.example.com;

    ssl_certificate     /etc/letsencrypt/live/relay.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/relay.example.com/privkey.pem;

    location /relay {
        proxy_pass http://127.0.0.1:8080/relay;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_read_timeout 3600s;   # WebSocket 长连接
        proxy_send_timeout 3600s;
    }
}
```

```bash
sudo ln -s /etc/nginx/sites-available/relay /etc/nginx/sites-enabled/
sudo nginx -t && sudo systemctl reload nginx
sudo apt-get install -y certbot python3-certbot-nginx
sudo certbot --nginx -d relay.example.com
```

### 5. 防火墙

```bash
sudo ufw allow 22/tcp
sudo ufw allow 443/tcp
sudo ufw deny 8080/tcp     # 中继仅经反代暴露, 不直接对外开放
sudo ufw enable
```

### 6. 验证

```bash
# 服务状态
systemctl status relay

# WebSocket 握手 (应返回 101)
curl -i -N -H "Connection: Upgrade" -H "Upgrade: websocket" \
  -H "Sec-WebSocket-Version: 13" -H "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==" \
  https://relay.example.com/relay

# App 侧构建时指向该地址
./gradlew :app-a:assembleRelease -PrelayUrl=wss://relay.example.com/relay
```

## 安全设计验证

- [x] Engine 代码库无 Room/SQLite/DataStore/FileOutputStream (纯内存)
- [x] 私钥绑定成功后立即销毁本地副本 (convertBoundToPublicKeyOnly)
- [x] 中继仅接触密文, 不解析 payload, 不落盘, 无账号体系
- [x] IPC 回调全部验签, 未验签回调不可作为任何安全判定依据
- [x] 全局 FLAG_SECURE 防截屏

## v3.17 变更记录

本批为诊断报告落地修复 + 媒体消息能力升级:

| 项 | 内容 |
|------|------|
| 消息上限 | `SparkEconomy.MAX_MESSAGE_BYTES` 1KB → **40KB** (文本); 新增 `MAX_MEDIA_BYTES` **48KB** (贴纸/表情, 解码后字节) —— 媒体以 Base64 文本装载 (~64KB) 超出文本上限, 故单独计限 |
| 信封上限 | `MessageEnvelope.MAX_PAYLOAD_SIZE` 64KB → **128KB** (覆盖 48KB 媒体 × 加密 + Base64 膨胀 + 信封开销, 最坏 ≈86KB, 余量 ~33%), 中继服务端同源常量自动生效 |
| 本地护栏 | `sendMessageToPeer` / `sendGroupMessage` 发送前按 UTF-8 字节预检, 超限直接判失败, 不再依赖服务端拒绝 |
| 防重放内存 | 三处防重放计数器 (1:1 / 群消息 / 群控制) 由裸 `ConcurrentHashMap` 换为带 2048 条上限的 `SeqGuard`, 修复长期运行内存无限增长 |
| IPC 日志泄露 | sign 请求 payload 与 callback 的 sig/result 移出 URI 查询串, 改走 Intent Extra —— 不再随 ActivityTaskManager 的 START 日志进 logcat (双端同步, 旧 URI 参数保留解析兼容) |
| 构建可复现 | 补齐真 Gradle Wrapper (8.5); 清除误提交的沙箱代理配置 (机器级代理请放 `~/.gradle/gradle.properties`) |
| 版本 | versionName 3.17.0 / versionCode 2 |

贴纸格式约定: 动态贴纸单帧 PNG/WebP 透明底, 512×512, 动态版本
(WebP/Lottie 类 .tgs 思路) 压缩后须 ≤ `MAX_MEDIA_BYTES` (48KB) ——
Spark 官方表情包全量 ≤44KB, 以 "一条消息" 端到端直达, 无需 CDN 引用。

## v3.17.1 变更记录

| 项 | 内容 |
|------|------|
| 消息预算定稿 | 文本上限 60KB → **40KB** (聊天文本远够用, 收紧中继最坏帧体与群扇出流量); 新增媒体预算 `MAX_MEDIA_BYTES` **48KB**, 贴纸 44KB 不变 |
| 群组规模上限 | 新增 `GroupLimits.MAX_MEMBERS` = **200** (100Mbps 中继带宽推导, 见常量注释); 群主侧建群截断 + 满员 JOIN_RESP 拒绝 (新错误码 `GROUP_FULL`) |
| 版本 | versionName 3.17.1 / versionCode 3 |

## v3.21 变更记录 (标记物系统完善 + 部署方案)

| 项 | 内容 |
|------|------|
| 贴纸快照 | `MarkerItem` 增 `stickerId` (默认值前向兼容); 标记贴纸消息不再退化为线格式原文, 列表卡片渲染静态缩略图 + "👋 Spark 表情" 预览; 目录未收录回退原文不丢数据 |
| 标记物管理 | 列表页新增搜索 (内容/对端昵称)、方向分类 (全部/我发出的/收到, FilterChip 计数)、无匹配空态 |
| 导出 | 顶栏 ShareSheet 明文导出 (`exportText()` 人类可读格式, 贴纸渲染为 emoji 预览); 导出为用户主动行为, 与"消息从不落盘"红线不冲突 |
| 图片类支持评估 | 协议暂无图片消息类型; 未来方案已落档 `MarkerStore` 注释: 复制字节至 `marks_assets/` + `assetFile` 字段 + 引用计数清理 (YAGNI 不预置) |
| 部署修复 | `src/debug/AndroidManifest.xml` 为 debug 包放行明文 `ws://` (targetSdk 28+ 默认禁明文, 此前模拟器/局域网拓扑实际连不上); release 强制 `wss://` |
| 部署方案 | 新增 `docs/DEPLOY_TEST.md`: 三种部署拓扑 (模拟器双端/局域网真机/VPS 生产) + 中继容量参数表 + 21 项双端 E2E 测试矩阵 + 排障速查 |
| 版本 | versionName 3.21.0 / versionCode 8 |

## 功能边界 (1.0)

### 已实现
- 端到端加密聊天闭环 (签名信令 + AAD 绑定 + 防重放)
- 密钥绑定流程 (生成 → 二维码/受保护 Extra → Vault 导入 → 签名回调确认)
- Vault 指纹登录 (BiometricPrompt + 签名回调)
- 中继挑战-应答注册 (经 Vault 签名)

### 延后至 2.0+
- mDNS/Wi-Fi Direct/蓝牙 BLE P2P 组网
- QUIC 协议 / 社区中继节点
- 媒体消息/群聊/音视频
