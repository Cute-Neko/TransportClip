# TransportClip · 公式快传

把 Android 平板上的手写公式或截图发到 Windows 剪贴板。适合在 Samsung Notes 写完公式后，直接粘贴到电脑上的文档或聊天窗口。

- 轻点悬浮球截屏，拖动选区后手动发送。
- 长按悬浮球打开临时手写板。支持触控笔笔压、笔身按键整笔擦除，以及 App 内的笔宽、笔色和橡皮擦范围预设。
- 点「完成」立即返回原界面，图片在后台发送。失败的图片可在 App 内重试。
- Windows 托盘收到图片后自动写入剪贴板，可切换文件模式和图片模式，也可用 **Ctrl+Alt+V** 重新复制最近一张。

## 组成

| 模块 | 目录 | 技术 |
|---|---|---|
| Android 客户端 | `android/` | Kotlin、Jetpack Ink，Android 11 及以上 |
| Windows 托盘客户端 | `windows/windows-src/` | Go、WebSocket、Windows 剪贴板 |
| 自建中继 | `server/server-src/` | Go、HTTP、WebSocket |

## 构建

- Android 需要 JDK 17、Android SDK 36、Gradle 8.14.2。在 `android/` 运行 `gradle assembleDebug`，APK 生成在 `app/build/outputs/apk/debug/`。发布版可用自己的签名，通过本地 `android/keystore.properties` 配置 `storeFile`、`storePassword`、`keyAlias` 和 `keyPassword`。
- Windows 需要 Go 1.23 或更新版本。在 `windows/windows-src/` 运行 `go build -trimpath -ldflags "-s -w -H windowsgui" -o TransportClip.exe .`。
- 中继需要 Go 1.23 或更新版本。在 `server/server-src/` 运行 `go build -o transport-relay .`。

## 自建与连接

1. 生成并保存一份至少 32 字符的随机设备令牌，例如 `openssl rand -hex 32`。用 `./transport-relay -addr 127.0.0.1:8787 -token-file /path/to/token.txt` 启动中继。仓库提供 [systemd 单元示例](server/deploy/transport-relay.service)。使用 HTTPS 反向代理，将 `/tr-relay/` 连同路径前缀转发到中继，确保 `/tr-relay/ws` 支持 WebSocket Upgrade。
2. 运行 Windows 客户端，在托盘选择「编辑配置」，将 `server_url` 改为 `wss://relay.example.com/tr-relay/ws`（替换为自己的域名），将 `token` 填为同一令牌。重启客户端后，明文令牌会转为 DPAPI 加密存储。
3. 在 Android App 的「服务器设置」填入 `https://relay.example.com/tr-relay/upload` 和同一令牌。打开系统无障碍设置，启用「公式快传」。若侧载后系统显示「由受限设置控制」，先点该条目，再到应用信息通过系统提供的入口授权。
4. 点「发送测试图片」，然后在 Windows 按 **Ctrl+V** 验证链路。

中继只转发 PNG 且不将其保存到磁盘。Android 和 Windows 端会在本地保留图片历史。悬浮球截图在用户点击时触发，框选内容在用户确认发送后上传到配置的中继。Android 端的无障碍服务用于提供悬浮球和触发系统截图。

## 协议

Android 使用 `POST /tr-relay/upload` 上传 PNG（最多 16 MiB），以 `Authorization: Bearer <token>` 鉴权。Windows 从 `/tr-relay/ws` 收取图片元数据和二进制 PNG，并在写入剪贴板后返回 ACK。中继收到 ACK 后才向上传端确认送达。

## 许可

项目采用 [GNU GPL v3 或更新版本](LICENSE)。Android 的悬浮球和截屏方式参考了 Woshicado 的 [Circle to Share](https://github.com/woshicado/circle-to-share)。手写板使用 [Jetpack Ink](https://developer.android.com/develop/ui/views/touch-and-input/stylus-input/about-ink-api)。
