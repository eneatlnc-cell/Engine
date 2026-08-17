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
├── app-a/                           # Engine: 社交应用 (有 INTERNET, 无持久化)
└── relay-server/                    # 无状态 WebSocket 中继 (Ktor + Netty, v2 挑战应答)
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
| 资源滥用 | 单 IP 连接配额 / 20 msg/s 速率限制 / 64KB 载荷上限 / 认证 30s 超时 |

## 构建与运行

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
