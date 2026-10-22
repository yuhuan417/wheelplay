# WheelPlay

[EN](README.md) | **CN**

WheelPlay 是一款 Android 局域网 CarPlay 服务端应用，可将 iPhone 的 CarPlay 画面传输到车机浏览器，并支持通过车机触屏操作。

应用安装在 Android 设备上，车机通过浏览器访问即可使用，无需安装客户端或部署额外服务器。

## 功能

- **浏览器显示与触控**：支持 CarPlay 画面串流、触摸操作和全屏显示。
- **WebCodecs 视频**：通过 HTTPS WebSocket 转发 H.264 / HEVC 压缩流，由车机浏览器解码。
- **浏览器音频与麦克风**：CarPlay 音频只发送到当前浏览器；浏览器麦克风可通过可信 HTTPS 页面接入 CarPlay 语音输入。Android 不播放 CarPlay 音频，也不采集自己的麦克风，只做转发。
- **打开即用**：只要能打开网页即可显示和操作 CarPlay，没有配对步骤；请只在可信局域网使用。
- **iPhone 连接管理**：支持无线 CarPlay 连接，查看连接状态并管理会话。
- **局域网访问**：自动识别可用网络地址，便于车机访问。
- **后台服务**：支持后台运行及自动连接。
- **显示与音频设置**：可调整画面尺寸、分辨率、帧率和音频缓冲。
- **CarPlay 使用偏好**：可固定始终日间／始终夜间，以及启用或禁用浏览器麦克风。自定义四边 0–40% 安全区域，并预览布局；修改麦克风或安全区域会重新连接。浏览器离线策略可选保持连接或等待 1／5 分钟后断开 iPhone，重连浏览器会取消计时。
- **诊断导出**：支持将诊断报告保存到本地，便于排查问题。

## 使用方式

1. 在 Android 服务设备上安装并启动 WheelPlay。
2. 将服务设备与车机连接到同一局域网；在「设置 → Web 监听」开启 HTTPS，并在车机浏览器信任本机证书后打开显示的 HTTPS 地址。视频使用 WebCodecs，需要安全页面。
3. 在车机浏览器中打开应用显示的地址，选择网页选项并点击「启动显示」。勾选「启动后隐藏顶栏并全屏」可在启动时进入全屏、连接后隐藏顶栏；退出全屏会恢复顶栏，不支持全屏的浏览器仅隐藏顶栏。
4. 保持 Android 上的 WheelPlay 在前台，在浏览器等待画面页点击「连接 iPhone」，即可发起无线连接；也可直接在应用中连接。按提示在 Android 上完成设备选择或授权，并在 iPhone 上允许使用 CarPlay。
5. 连接完成后，在车机浏览器中查看和操作 CarPlay。

同一时间只允许一个浏览器控制会话。服务端会拒绝 `Origin` 与页面不一致的 WebSocket 连接，阻止其他网页跨站发起连接；除此之外，能打开本页的客户端都视为可信。

追求 60 fps 时，启用 HTTPS、将协商帧率设为 60，然后重新打开 HTTPS 页面。WebCodecs 通过与音频相同的控制 WebSocket 发送 H.264 / HEVC 压缩流，需要安全页面和支持 WebCodecs 的浏览器（Tesla Chrome 113+）。没有低带宽回退：缺少 HTTPS 或 WebCodecs 时页面会显示错误而不出画面。实际帧率取决于 iPhone 输出、网络和浏览器解码能力，静态画面可能降低帧率。允许 iPhone 使用 HEVC 后，可向支持 HEVC 解码的浏览器发送 H.265。

CarPlay 音频始终只发送到当前车机浏览器，没有 Android 扬声器回退。可在 App「设置 → 画面与音频」启用或禁用浏览器麦克风，修改后重新连接 iPhone；禁用会取消麦克风输入能力声明。浏览器麦克风需要先配置该设备的本地 HTTPS 证书，再从 HTTPS 入口打开页面并授权；步骤见 [浏览器音频说明](docs/BROWSER_AUDIO.md)。

## 使用要求与限制

- 服务设备需运行 Android 9 或更高版本；车机浏览器需支持现代网页与触摸交互功能。
- 视频需要可信 HTTPS 页面和支持 WebCodecs 的浏览器（Tesla Chrome 113+），没有 JPEG 回退；缺少任一条件时页面会提示错误且没有画面。
- 服务设备与车机必须能够通过局域网互相访问。无线连接兼容性取决于设备的网络能力。
- 音频与麦克风只走车机浏览器，Android 不播放也不采集。浏览器麦克风还要求车机浏览器允许安装并信任本地证书。
- 同一时间仅允许一个浏览器控制会话。
- 画面流畅度与连接稳定性受设备性能、网络质量及系统后台管理影响。
- 仅适用于可信局域网；HTTP 页面与 WebSocket 控制未加密，视频和浏览器音频需要本地 HTTPS/WSS。不应通过证书警告强行进入页面；请勿将服务暴露到公网。

## Web 监听配置

「设置 → Web 监听」集中配置 HTTP 端口（默认 `8080`）、HTTP / HTTPS 共用的自备 Web 域名和可选 HTTPS，无需 Root。实际监听端口范围为 `1024–65535`；打开 HTTPS 后显示 Web HTTPS 端口（默认 `8443`）。HTTPS 使用 App 管理的本地证书，不再提供证书类型选择；启用 Tesla 兼容中的 DuckDNS 后，签发的 Let's Encrypt 证书会自动替换它。关闭 HTTPS 仅停止 TLS 监听，保留配置与证书。详见 [Web 监听与证书说明](docs/BROWSER_AUDIO.md)。

## Tesla 兼容模式（实验）

此模式主要帮助屏蔽私有网段访问的车机浏览器，通过兼容访问地址绕过地址限制，访问手机上的本地服务。功能名称沿用「Tesla 兼容」，也适用于有同类限制的其他品牌车机。

Tesla 兼容只使用 **Root 热点路由**，默认关闭，需手动授权 Root 后启用。DuckDNS 域名与自动证书也在此卡片中配置，并共用同一个访问地址。

- **Root 热点路由**：内置适配 [VPN Hotspot](https://github.com/Mygod/VPNHotspot)，需要 `su`、`ip`、`iptables`。手动请求 Root 后启用，默认视频入口 `https://100.96.0.1/`（普通 `http://100.96.0.1/` 仅用于设置）；映射端口范围 `1–1023`，默认 `80` / `443`，转发到 Web 的高位监听端口。仅已启用时会在启动时后台检查 Root；此方案不申请 VPN 授权，也不占用 VPN 槽位。还可开启「应答 Wi-Fi 联网检测」：由手机本地回答车机对 `connman.vn.cloud.tesla.cn` 的明文 HTTP 检测，热点没有上行网络时车机仍会认为 Wi-Fi 在线。

关闭模式或停止 Web 服务时会清理资源；普通局域网 Web 监听可独立使用。可选 HTTPS 仍要求可信证书。详见 [使用与验证说明](docs/TESLA_HTTP.md)。

## 构建

使用 Android NDK 28.2.13676358 和 CMake 3.22.1。当前构建只打包 **arm64-v8a**（小米 8 / 骁龙 845），不包含 32 位和模拟器 ABI。首次构建会从 GitHub 获取固定版本的 Mbed TLS；后续复用本地 CMake 缓存。运行 `./gradlew :mobile:assembleDebug`。

本部署已内置 CarPlay 认证资源，首次启动时复制到应用私有目录；设置页不再提供导入入口。源码构建必须通过下方构建期资产目录提供资源，否则 Web 与 CarPlay 保持禁用。

通过 `WHEELPLAY_AUTH_ASSETS_DIR`（兼容 `DIPLAY_AUTH_ASSETS_DIR`）指定本地资产目录，或放置在 `.local/auth-assets/`；目录下必须有 `offline-mfi/identity.pk8` 和 `offline-mfi/certificate.p7b`。这些文件不纳入 Git。配置后运行 `./gradlew :mobile:assembleProvisionedDebug`，构建并校验 APK 内的两项资产与本地配置一致。

## 来源与许可

本项目基于 DiPlay / xcertplay 开发，保留上游许可证与第三方声明。详情参见 [LICENSE](LICENSE) 和 [第三方声明](docs/upstream/THIRD_PARTY_NOTICES.md)。第三方素材与组件遵循各自许可证。

原生视频解密使用 [Mbed TLS 3.6.5](https://github.com/Mbed-TLS/mbedtls/tree/mbedtls-3.6.5)，版本固定在 CMake 配置中；[原生依赖许可](common/src/main/assets/mbedtls-license.txt)随 APK 分发。

串流调试时，可在浏览器控制台读取 `window.wheelplayStats`：包含服务端源输入速率（2 秒窗口）以及解密、接收至交接耗时；WebCodecs 帧数与当前传输方式由状态消息提供。未支持的浏览器指标为 `null`。
