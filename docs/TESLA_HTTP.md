# Tesla 兼容模式（实验）

此模式主要用于帮助**屏蔽私有网段访问的车机浏览器**绕过地址限制，通过兼容访问地址连接手机上的本地服务。功能名称沿用「Tesla 兼容」，也适用于有同类限制的其他品牌车机；具体可用性仍需在目标车机上验证。

只提供 **Root 热点路由** 方案，默认关闭。需要已 Root 的设备以及可用的 `su`、`ip`、`iptables`；不需要 Android VPN 授权，也不占用 VPN 槽位。系统热点的 IP 和 DHCP 网段保持不变。

## Root 热点路由

此方案通过内置的 [VPN Hotspot](https://github.com/Mygod/VPNHotspot) Root 路由适配，让车机从 Android 热点访问 WheelPlay 的本地共享 IPv4 地址。无需另外安装 VPN Hotspot。

## Root 热点路由使用

1. 在运行 WheelPlay 的 Android 设备上开启系统热点，让车机连接这个热点。
2. 在「设置 → Tesla 兼容（实验）」点击「请求 Root 权限」，在 Magisk 等 Root 管理器中授予 **WheelPlay** 权限。已启用此模式时，App 启动会在后台检查一次 Root；关闭时不会请求 Root。检查成功后才显示其余设置，再打开「启用 Tesla 兼容」。拒绝授权后不会自动重复请求，可再次点击「请求 Root 权限」重试。按钮可独立于热点和 Web 服务使用；此模式不申请 Android VPN 授权。
3. 默认共享 IP 是 `100.96.0.1`，可改为一般 IPv4 单播地址，包括私有或公网地址；回环、多播、链路本地及未指定地址不可用。避免与移动网络、其他 VPN 或局域网冲突。
4. 映射 HTTP 端口默认 `80`，映射 HTTPS 端口默认 `443`，有效范围为 `1–1023`，两者不能相同。它们通过 Root 映射到「Web 监听」中的实际端口（默认 `8080` / `8443`），只作用于配置的访问 IP。HTTPS 映射仅在 Web HTTPS 开启且监听成功时安装。
5. 等待「Root 热点路由已启用」，先用 `http://100.96.0.1/` 打开页面；**视频必须使用 HTTPS 入口 `https://100.96.0.1/`（HTTPS 映射默认 443）并让车机信任证书，WebCodecs 不会在 HTTP 页面出画面。** HTTP 入口仅用于设置与状态。拒绝 Root、缺少命令、端口占用或规则安装失败时，App 不展示已就绪的共享入口。修复后点击「重新启动 Root 路由」；不会持续重复申请 Root。
6. 普通 HTTP 端口、Web 域名及可选 HTTPS 在「设置 → Web 监听」中配置，无需 Root。自备域名需配置 DNS A 记录，解析到实际访问 IP；填写域名不自动配置 DNS。映射端口不是 `80` 时使用 `http://你的域名:映射端口/`。地址、域名或端口变化后，浏览器需要重新打开页面。

模式默认关闭，配置会保存。Root 检查结果仅在本次进程中有效，已启用时重新启动进程会自动重新检查；检查过程不阻塞普通 Web 监听。旧版本的端口与地址偏好保留；Web 域名和高位端口迁移到 Web 监听，旧低位端口迁移为映射端口，实际监听改用高位端口。Web 域名同时用于 HTTP 与 HTTPS；HTTPS 默认使用本地证书，启用 DuckDNS 后会安装 Let's Encrypt 证书。无 Root 时仍可使用普通局域网 HTTP/HTTPS 入口。

## Wi-Fi 联网检测应答（实验）

车机加入 Wi-Fi 后，ConnMan 会请求 `http://connman.vn.cloud.tesla.cn/online/status.html` 做联网检测，只有得到 HTTP 200 才认为该 Wi-Fi 在线；失败时可能提示检测失败并离开热点。该端点是**明文 HTTP**，不涉及证书校验，因此可以在已 Root 的手机上本地应答。

在「设置 → Tesla 兼容（实验）→ 应答 Wi-Fi 联网检测」开启后（要求已启用 Tesla 兼容、映射 HTTP 端口为 `80`）：

- Root 事务在热点接口把**所有** UDP 53 查询 DNAT 到热点网关地址的 `15353` 端口（App 以普通权限监听 `0.0.0.0:15353`，不需要 root 绑定低位端口）。命中 `connman.vn.cloud.tesla.cn`、`connman.vn.cloud.tesla.com`、`connman.vn.tesla.services` 时返回访问 IP 的 A 记录；`AAAA` 返回无答案（NOERROR），让车机回退到 A。其余查询转发给热点网关和当前上行网络通告的 DNS。回程由 conntrack 还原成车机原本查询的 DNS 地址与 53 端口，车机侧看到的是正常 DNS 应答。
- 车机随后请求 `http://访问IP/online/status.html`，经现有 `80 → Web 监听端口` 的 DNAT 进入 App；App 返回与生产端点一致的 46 字节页面和 `X-ConnMan-Status: online`。

热点没有上行网络时，车机因此仍会认为 Wi-Fi 在线。关闭开关、停止 Web 服务、Root 路由失败或 App 退出时，DNS 重定向规则随现有 Root 事务按逆序撤销，本地 DNS 套接字随进程关闭；不需要修改系统 DNS、DHCP 或持久设置。

限制与风险：

- 只覆盖明文 HTTP 的检测路径。若目标车机版本改用 HTTPS 或校验响应内容，此功能不会生效，也不能用本地证书伪造。
- 开启期间热点客户端的**全部** UDP DNS 查询都经 App 转发；上行 DNS 不可用时只能解析上述 probe 域名。功能默认关闭，关闭后立即恢复系统 DNS 路径。
- 仅处理 UDP 53，不处理 TCP 53 或 DNS-over-HTTPS/TLS；DNAT 与 conntrack 回程改写依赖内核 IPv4 NAT。
- 需要真实热点与 Tesla 实车验证：确认车机不再报告联网检测失败，并在诊断日志或 `adb logcat -s WheelPlayTesla` 中看到 `Wi-Fi 联网检测：...` 的 DNS 与 HTTP 记录。该日志会随诊断报告导出，但不改变现有脱敏规则。

## Root 路由和生命周期

- 适配 VPN Hotspot 的下游策略路由、成对规则和逆序回滚机制，源码版本及修改见 [UPSTREAM.md](UPSTREAM.md)。共享范围是 **WheelPlay 的本地 HTTP/HTTPS 服务**，不提供 Internet 转发。
- 用 Root 将共享地址作为 `/32` 添加到 loopback，通过 `lookup local` 投递；按系统实际报告的热点接口安装 HTTP 和 HTTPS 端口的 INPUT/OUTPUT 放行及源地址限定的回程路由。回程使用 Android 的 `local_network`（97）路由表，不依赖 Internet 默认路由。只展示完整安装成功的入口。
- 只识别系统 `TETHER_STATE_CHANGED` 的 `tetherArray`，定期检查接口及 IPv4 地址；不根据 `wlan` 名称猜测热点。热点关闭时撤销规则，重开或接口/地址变化时重新创建。系统未提供热点状态时显示未检测到接口。
- 关闭模式、停止 Web 服务、端口或地址变更时撤销旧规则。Root shell 持有事务，App 退出导致 stdin 关闭时执行清理；不清空系统/其他应用的规则，不修改持久系统设置。清理失败会撤下入口并提示检查或重启设备。
- HTTP/HTTPS 的低位端口转发与共享 IPv4 地址、热点投递和回程规则在同一 Root 事务中管理。仅对发往此 `/32` 地址的 TCP 连接做端口 DNAT，保留目标 IP；普通局域网地址和本机预览继续使用 Web 监听端口。安装前检查现有监听器，已占用的低位端口会拒绝转发。任一步骤失败都会回滚；清理未确认时阻止继续安装新规则。启动回滚未确认时，本次进程内的手动重试、配置变化及 Web 服务停止/重启均不会解除保护，需检查残留规则或重启设备。Root 路由失败不影响普通 Web 监听。
- 精确的访问 IP 匹配不依赖 `addrtype` 内核模块，兼容缺少该模块的 ROM。规则安装失败时提示具体命令及原始错误；仍需内核支持 IPv4 NAT/DNAT，以及规则使用的 TCP、comment、conntrack 匹配模块。
- Tesla 模式与 CarPlay 会话独立；连接/断开 iPhone 不重建 HTTP 地址。Root 路由不占用 VPN 槽位，也不申请 VPN 授权。
- 复用现有 HTTP/WebSocket、画面、触控和浏览器音频。Android 不播放音频也不采集麦克风；浏览器麦克风仍要求可信 HTTPS。

## Root 验证

自动测试覆盖配置持久化、热点广播解析、命令输入校验、Root 拒绝后不自动重复申请、热点/端口变化和停止清理。真实 shell 测试以记录命令的替身代替内核操作，验证成功步骤逆序撤销、部分安装失败回滚及 stdin 关闭清理。无线 AirPlay 与 Root 路由的独立性、HTTP 端口绑定失败回退和 WebSocket 仍由回归测试覆盖。

这些测试不执行真实 Android Root 路由，尚需在 Root Android 热点及 Tesla 实机上验证：

1. 先让另一台电脑/手机连接热点，访问完整的 HTTP IP 地址并打开页面，检查双向连接与服务端日志。
2. 再用 Tesla 测试 HTTPS 入口及可选域名，记录证书信任情况和 WebCodecs 支持。路由就绪不保证所有 Tesla 版本允许本地地址或 HTTPS。
3. 验证 HTTPS 页面下的 WebCodecs 连续画面、触控、音频；同时确认 HTTP 页面会明确提示需要 HTTPS。
4. 测试热点关闭/重开、端口与 IP 变化、前后台切换、无线 iPhone 连接及断开、关闭模式、停止服务和强制停止 App；核对 `/32` 地址及规则清理。
5. 拒绝/撤销 Root，确认失败时不展示共享地址、不反复弹授权；恢复权限后手动重试。
6. 打开「应答 Wi-Fi 联网检测」，断开热点上行网络后让车机重新加入：确认不再出现联网检测失败，车机可继续访问 WheelPlay；诊断日志应出现 probe 域名的 DNS 查询和 `/online/status.html` 请求。再关闭开关、停止 Web 服务、强杀 App，核对 DNS DNAT 规则与 15353 监听均已消失，车机 DNS 恢复正常。

### DuckDNS 自动证书的触发时机

- App / 前台服务启动时立即检查一次；
- 恢复供电时，等 `RootRadioPower` 完成 Wi-Fi 上行与热点恢复后再检查，避免网络未就绪就发起 ACME；
- 检查前最多等待 60 秒让可用互联网网络出现；
- 失败后按 2 / 4 / 8 / 16 / 30 分钟退避重试，并在省电待机期间跳过；
- 证书剩余有效期 ≤ 30 天时续签；正常运行时每 6 小时检查一次，因此实际续签最多延迟约 6 小时；
- A 记录随检查刷新；地址未变时最多每 6 小时刷新一次，地址变化会立即触发。

在「设置 → Web 监听」打开「启用 HTTPS」后，可修改 Web HTTPS 端口；关闭后停止 HTTPS 监听并撤下 HTTPS 映射，HTTP 继续可用。默认本地证书包含配置的共享 IP 与可选域名，修改地址后会刷新。DuckDNS 自动证书使用同一访问 IP，并在签发后替换 HTTPS 证书。详见 [BROWSER_AUDIO.md](BROWSER_AUDIO.md)。
