# Engine VPS 部署手册 (v3.21)

> 从零到双端可测的完整操作手册。所有命令均已实际验证。
> 测试矩阵与三种拓扑对比见 [DEPLOY_TEST.md](DEPLOY_TEST.md); 本文是方案 C 的落地细节。

## 部署总览

```
                        Internet
                           │
                    ┌──────▼──────┐
                    │  Nginx :443 │  TLS 终结 (Let's Encrypt)
                    │   wss://    │  反向代理 → 127.0.0.1:8080
                    └──────┬──────┘
                    ┌──────▼──────┐
                    │  中继 :8080 │  仅监听 127.0.0.1, 不对外
                    │  (systemd)  │  无状态, 零落盘, 只见密文
                    └─────────────┘
```

**部署架构三原则**:
1. 中继只监听 `127.0.0.1`,由 Nginx 终结 TLS 后以 `wss://` 对外 —— 明文流量永不离开本机
2. 中继零状态零落盘 —— 换机/迁移只需改 DNS 指向, 无数据迁移负担
3. 全部密文经 ECDH+AES-GCM 端到端加密, 中继被攻破也不泄露消息内容

**部署目标**: 两台手机 (走 4G/5G, 非同一 Wi-Fi) 通过 `wss://relay.example.com/relay` 互通。

---

## 0. 前置准备

| 项 | 要求 | 说明 |
|---|---|---|
| VPS | 1C/1G 起, 推荐香港 | 中继是全流量必经点, 香港对大陆延迟 30-80ms; 带宽选不限流套餐 (媒体消息流量显著高于纯文本) |
| 系统 | Ubuntu 22.04 / Debian 12 | 以下命令基于 apt |
| 域名 | 任意一个子域 | 如 `relay.example.com`, 提前做 A 记录指向 VPS IP |
| 本地构建机 | JDK 17 + 项目代码 | 构建中继发行包; 若同时构建 App 还需 Android SDK 34 |
| 测试手机 ×2 | 各装 Vault + Engine | 双端 E2E 测试用 |

DNS 检查 (解析生效再继续):

```bash
dig +short relay.example.com   # 应返回 VPS 公网 IP
```

---

## 1. 本地构建中继发行包

```bash
# 项目根目录
./gradlew :relay-server:installDist
```

产物: `relay-server/build/install/relay-server/` (~16MB, 含启动脚本 + 46 个依赖 jar)

打包上传:

```bash
tar -czf relay-server.tar.gz -C relay-server/build/install relay-server
scp relay-server.tar.gz root@your-vps-ip:/opt/
```

> 验证过的冒烟测试 (可选, 本地先确认包能跑):
> ```bash
> ./relay-server/build/install/relay-server/bin/relay-server
> # 日志出现 "Responding at http://127.0.0.1:8080" 即正常
> # curl WS 握手后 30 秒被断开 (认证超时) 是预期防线行为
> ```

---

## 2. VPS 环境: JDK 17 + 专用用户

```bash
# JDK 17 (中继仅需 JRE, headless 版最省)
apt-get update && apt-get install -y openjdk-17-jre-headless
java -version   # 确认 17.x

# 专用运行用户 (无登录 shell, 最小权限)
useradd -r -s /usr/sbin/nologin relay

# 解压发行包
mkdir -p /opt/relay
tar -xzf /opt/relay-server.tar.gz -C /opt/relay --strip-components=1
chown -R relay:relay /opt/relay
chmod +x /opt/relay/bin/relay-server
```

---

## 3. systemd 服务

创建 `/etc/systemd/system/relay.service`:

```ini
[Unit]
Description=Engine Relay Server (stateless WebSocket relay)
After=network.target

[Service]
Type=simple
User=relay
Environment=RELAY_HOST=127.0.0.1
Environment=RELAY_PORT=8080
ExecStart=/opt/relay/bin/relay-server
Restart=always
RestartSec=3
# JVM 内存画像: 每连接 ~50KB + 订阅表, 1000 并发约 100MB 堆
Environment=JAVA_OPTS=-Xms128m -Xmx512m
# 每连接一个 fd, WebSocket 长连接场景必须抬高
LimitNOFILE=65535
# 最小权限加固
NoNewPrivileges=true
ProtectSystem=strict
ReadWritePaths=/opt/relay
PrivateTmp=true
ProtectHome=true

[Install]
WantedBy=multi-user.target
```

启用并启动:

```bash
systemctl daemon-reload
systemctl enable --now relay
systemctl status relay   # Active: active (running) 即成功
```

本机自检:

```bash
# 进程监听确认 (应只见 127.0.0.1:8080, 无 0.0.0.0)
ss -tlnp | grep 8080

# 日志确认 Ktor 启动行
journalctl -u relay -n 20 --no-pager | grep -E "Responding|started"
# 预期: "Responding at http://127.0.0.1:8080"
```

---

## 4. Nginx TLS 反向代理 (wss://)

```bash
apt-get install -y nginx certbot python3-certbot-nginx
```

创建 `/etc/nginx/sites-available/relay`:

```nginx
server {
    listen 80;
    server_name relay.example.com;
    # certbot 会自动补 443/SSL 段, 先占位 80 用于签发
    location /relay {
        proxy_pass http://127.0.0.1:8080/relay;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        # 真实客户端 IP (中继仅信任环回对端的 XFF, 防伪造)
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        # WebSocket 长连接: 默认 60s 超时会踢掉静默连接
        proxy_read_timeout 3600s;
        proxy_send_timeout 3600s;
    }
}
```

启用 + 签发证书:

```bash
ln -s /etc/nginx/sites-available/relay /etc/nginx/sites-enabled/
nginx -t && systemctl reload nginx
certbot --nginx -d relay.example.com        # 自动改写为 443+SSL 并配续期
systemctl status certbot.timer              # 确认自动续期定时器 active
```

> certbot 改写后的配置会自动包含 `listen 443 ssl` 与证书路径; `location /relay` 段保持不动即可。

---

## 5. 防火墙

```bash
apt-get install -y ufw
ufw allow 22/tcp     # SSH (建议再配 fail2ban)
ufw allow 80/tcp     # Let's Encrypt 续期 HTTP-01 验证
ufw allow 443/tcp    # wss:// 入口
ufw deny 8080/tcp    # 中继仅经反代暴露, 不直接对外
ufw enable
ufw status verbose
```

---

## 6. 部署验证清单 (逐项过)

```bash
# ① 服务存活
systemctl is-active relay            # active

# ② 监听面收敛 (只有 127.0.0.1:8080, 无 0.0.0.0)
ss -tlnp | grep -E '8080|443'

# ③ WS 握手 (经公网 TLS 路径; 返回头含 HTTP/1.1 101 即升级成功,
#    curl 挂住 30 秒后被断开 = 认证超时防线正常)
curl -i -N --max-time 35 \
  -H "Connection: Upgrade" -H "Upgrade: websocket" \
  -H "Sec-WebSocket-Version: 13" \
  -H "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==" \
  https://relay.example.com/relay

# ④ 中继日志看到连接进出
journalctl -u relay -f
# 预期两行:
#   New WebSocket connection from <你的IP> (direct=127.0.0.1)
#   Connection closed from <你的IP> (0 online)
# 注意 direct=127.0.0.1 说明走了反代, 客户端 IP 来自 XFF —— 正确形态

# ⑤ 明文端口不可直达 (应超时/拒绝)
curl -m 5 http://your-vps-ip:8080/relay || echo "OK: 8080 不对外"

# ⑥ 证书有效期
echo | openssl s_client -connect relay.example.com:443 2>/dev/null | \
  openssl x509 -noout -dates
```

六项全过, 中继侧部署完成。

---

## 7. App 客户端构建

**测试期可用 debug 包走 wss** (debug overlay 只放行了明文 ws, 不影响 wss):

```bash
./gradlew :app-a:assembleDebug -PrelayUrl=wss://relay.example.com/relay
adb install app-a/build/outputs/apk/debug/app-a-debug.apk
```

**发布形态必须 release 包** (强制 TLS, 无明文权限):

```bash
./gradlew :app-a:assembleRelease -PrelayUrl=wss://relay.example.com/relay
adb install app-a/build/outputs/apk/release/app-a-release-unsigned.apk
```

> 两台手机各自完成: 装 Vault → Engine 内密钥绑定 → Vault 导入 → 指纹登录。
> Vault 与 Engine 必须同签名 (debug 共用 keystore) 才能通过 signature 级 IPC 校验。

---

## 8. 双端验收 (按 DEPLOY_TEST.md 矩阵执行)

核心路径 (完整 21 项矩阵见 [DEPLOY_TEST.md](DEPLOY_TEST.md)):

| 优先级 | 验证点 | 通过标准 |
|---|---|---|
| P0 | 双端注册上线 | 中继日志两条 `Node authenticated & registered` |
| P0 | 1:1 消息互通 | 双向秒达, 状态到"已送达" |
| P0 | 群聊扇出 | 单帧上行, 中继一次 fanout 全员收到 |
| P1 | 心跳挂机 ≥10 分钟 | 无断连, 中继无 `Rate limit exceeded` |
| P1 | 断网重连 | 飞行模式 30s 恢复后自动重连+重订阅 |
| P1 | 标记物 (v3.21) | 标记/搜索/分类/导出/重启持久化全过 |

---

## 9. 日常运维

### 更新中继版本

```bash
# 本地: 构建新包上传
./gradlew :relay-server:installDist
tar -czf relay-server.tar.gz -C relay-server/build/install relay-server
scp relay-server.tar.gz root@your-vps-ip:/opt/

# VPS: 原子替换 (旧目录留作回滚)
systemctl stop relay
mv /opt/relay /opt/relay.bak.$(date +%s)
mkdir -p /opt/relay && tar -xzf /opt/relay-server.tar.gz -C /opt/relay --strip-components=1
chown -R relay:relay /opt/relay && systemctl start relay
systemctl is-active relay   # active 后观察日志 1 分钟无异常再删 .bak
```

### 日志与监控

```bash
journalctl -u relay -f                                    # 实时跟踪
journalctl -u relay --since "1 hour ago" | grep -c WARN   # 异常计数
journalctl -u relay | grep "online$" | tail -1            # 最近在线峰值
```

关键日志行语义:

| 日志行 | 含义 | 处置 |
|---|---|---|
| `Node authenticated & registered: <fp8>... (N online)` | 正常注册 | N 为当前在线数 |
| `Rate limit exceeded for <fp8>...` | 触发 20 msg/s 限流 | 单条正常; 频繁出现查客户端是否异常重发 |
| `SOURCE_MISMATCH: conn=... claims=...` | 身份冒用尝试 | 关注来源 IP, 必要时 ufw 封禁 |
| `Connection quota exceeded for <ip>` | 单 IP 超 20 连接 | 正常多设备; 若来自陌生 IP 属滥用 |
| `FINGERPRINT_MISMATCH in HELLO` | 指纹与公钥不匹配 | 恶意探测, 可忽略 |

### 备份

中继零落盘, **无需备份任何数据**。仅需保存: VPS 登录凭据 + 域名 DNS 控制权。

### 回滚

```bash
systemctl stop relay
rm -rf /opt/relay && mv /opt/relay.bak.<timestamp> /opt/relay
systemctl start relay
```

---

## 10. 排障速查

| 症状 | 根因与处理 |
|---|---|
| App 永远「连接中」 | ① `dig` 域名解析是否生效 ② 证书是否过期 (`openssl s_client`) ③ 手机时间是否准确 (TLS 校验依赖) ④ release 包连 `ws://` 被系统拒 (必须 wss) |
| curl 握手 400 | Nginx 缺 `proxy_set_header Upgrade/Connection` 三行, WS 升级头没透传 |
| curl 握手 404 | 路径必须 `/relay`; Nginx `location` 与 `proxy_pass` 都要带 `/relay` 后缀 |
| 反代后全员 IP 限流 | `X-Forwarded-For` 未配置时所有连接 IP 均为 127.0.0.1, 撞单 IP 20 连接配额 → 检查第 4 步 Nginx 配置 |
| 中继起不来 | `journalctl -u relay -e` 看首条错误; 常见: JDK 版本 < 17 (`java -version`), 端口被占 (`ss -tlnp`) |
| certbot 签发失败 | 80 端口未放行 (HTTP-01 验证需要), 或 DNS 未生效 |
| 重启后服务没起来 | `systemctl is-enabled relay` 应为 enabled; 否则 `systemctl enable relay` |
| 群消息丢成员 | 掉线成员重连后需自动重订阅; 中继日志查 `Subscribed` 行是否存在 |

---

## 附: 协议限制参考 (部署容量规划)

| 参数 | 值 |
|---|---|
| 载荷上限 | 128 KB/帧 |
| 单连接业务帧 | 20 msg/s |
| 单连接控制帧 | 128 msg/s (GROUP_SUBSCRIBE/FANOUT) |
| 单 IP 并发连接 | 20 |
| 单连接群订阅 | 64 群 |
| 群级扇出令牌桶 | 10 msg/s · 16MB 突发 · 2MB/s |
| 全局扇出令牌桶 | 32MB 突发 · 8MB/s |
| 群成员上限 | 200 (客户端侧) |
| 认证超时 | 30s |

1C/1G VPS 实测画像: ~1000 并发连接 + 数百活跃群, JVM 堆 512m 内。
