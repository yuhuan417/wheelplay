# 连接拓扑（测试基线）

本文档固定 WheelPlay 的测试拓扑。**后续所有讨论、改动、验证都以此拓扑为基准**；
不在此拓扑内的场景（iPhone 反过来当站点等）只作对照，不当作需求。

记录日期：2026-10-14　代码基线：`5c5b080`

## 拓扑

```
        iPhone
       （个人热点 A，172.x）
            │  AP
            │
            ▼  STA（用于上网）
    ┌───────────────┐
    │   安卓设备     │  ← 跑 WheelPlay
    │  （WLAN 信号桥）│
    └───────────────┘
            │  AP
            │
            ▼  STA
        车机（当前用电脑模拟，192.x）
```

| 链路 | 谁当 AP | 网段 | 作用 |
|---|---|---|---|
| **A** | iPhone | 172.x | 安卓侧上网 |
| **M** | 安卓（WLAN 信号桥） | 192.x | 车机接入 + **CarPlay 承载网** |

三台设备经 A→M 桥接后均可上网（该性质在 CarPlay 建立后可能改变，见「未决问题」）。

## 目标行为

点「连接 iPhone」后应发生：

1. 蓝牙 RFCOMM 连上 iPhone（要求已在**系统蓝牙**里配对过）
2. iAP2 下发 **M 的凭据**，而不是任何系统探测结果：
   - `0x5703 accessory-wifi-configuration` → SSID / passphrase / channel / security
   - `0x4301 carplay-start-session` → SSID / passphrase / **安卓在 M 上的 IP** / port / public key
3. iPhone 加入 M
4. AirPlay 打到安卓在 M 上的 192 地址

## App 侧配置要求

- 连接网络固定为 **「设备热点」**；Wi-Fi Direct / LocalOnlyHotspot 后端已删除
- 名称 / 密码填 **M 的**，必须与系统里那个信号桥一致
- iPhone 必须先在系统蓝牙里配对好（App 不扫描、不发起配对）

### 为什么固定使用系统热点

Tesla 兼容模式的 Root 路由只认**系统热点**——它监听 `TETHER_STATE_CHANGED` 的
`tetherArray`，且明确「不根据 wlan 名称猜测热点」。要让 CarPlay 与车机浏览器共存，
两者必须共用同一个系统热点，因此代码只保留手动热点后端。

## 承载地址是怎么选出来的

`ManualHotspotManager.findLocalHotspotInterface()`：

- `isUsableInterface` **排除当前主用接口**——连 A 的 wlan0（172.x）会被直接排除
- 在剩下的接口里按名打分：`ap*` / `*softap*` 100 分，`p2p*` 80 分，`wlan*` 70 分；192.x 地址再 +30
- 因此选中 M 的 AP 接口，拿到的就是发给 iPhone 的那个 192 地址

## 电源恢复顺序

断电待机后恢复供电时，必须先让安卓的 STA 连上 Wi-Fi 上行（A），再启动 M 的 SoftAP。
若先开热点，Tesla 和 iPhone 会在连接 M 后发现没有外网而认为热点不可用。
`RootRadioPower.wakeFromStandby()` 现在分两阶段执行：

1. 启用 Wi-Fi 和蓝牙，并轮询 `WifiUplink.isReady()`（Wi-Fi 传输 + INTERNET 能力，或已关联 SSID）；
2. 上行就绪后才启动系统热点；蓝牙最多等待 10 秒恢复。

断电待机时会同时关闭蓝牙以省电，并记录断电前蓝牙是否开启；恢复供电只恢复原先开启的蓝牙。

`CarPlayController` 在建立蓝牙/iAP2 前也会做一次同样的上行等待，避免手动连接时
iPhone 被过早要求加入没有外网的热点。

## iPhone 热点 A 的冲突（已确认）

实测：iPhone 保持个人热点 A 时，CarPlay 控制通道可能已建立（页面显示 connected），
但视频流不会走 M；在 iPhone 上关闭 Wi-Fi、关闭个人热点并手动加入 M 后，视频立即出现，
且不需要在 App 里重新点击连接。

这说明 iOS 不能在作为热点 AP 的同时稳定地作为 M 的客户端，属于 iOS 侧限制。
应用目前会在 AirPlay 会话建立后 10 秒仍无视频时请求一次关键帧，并在页面上提示
关闭 iPhone 个人热点、手动加入 M。若要同时保留外网和自动 CarPlay，需为安卓提供
不依赖 iPhone 个人热点的上行网络（例如车机热点、另一台手机或蜂窝网络）。

2. **改后仍可能拦住流程的两道闸**（报错原文，便于对照）：
   - `Timed out after 60000ms waiting for the manual hotspot: local hotspot interface was not found`
     —— 接口发现失败，等满 60 秒（`HOTSPOT_START_TIMEOUT_MILLIS = 60_000`）
   - `Manual hotspot SSID does not match the active local AP configuration: 'X'`
     —— ROM 报出的 SoftAP 配置与所填不一致

3. **设置里「Debug logs / Show on-screen debug logs」开关是失效的。**
   `updateDebugOverlays()` 无条件把 `statusScrollView` 设为 `GONE`，且 `statusView` 字段从未被赋值。
   打开它不会在屏幕上看到日志。

## 取日志

**屏幕上看不到原始错误**：`friendlyStage()` 会把含 `"Failed"` 的消息替换成
`"Connection interrupted. Retrying…"`，并且失败后立刻自动重连，看起来像「没报错」。

| 途径 | 方法 |
|---|---|
| logcat | `adb logcat -s xcertplay-usb`（连接 / iAP2 / 无线 bring-up 共用此 tag，失败带堆栈） |
| 日志文件 | `/data/data/<包名>/files/logs/diplay.log`（有轮转归档） |
| 诊断报告 | 设置 → 「关于与诊断」→「保存诊断报告」→ `Downloads/WheelPlay/WheelPlay-<时间戳>.txt` |

诊断报告内嵌全部日志，并附设备信息、当前模式、Tesla 兼容状态；内容经 `DiagnosticRedactor` 脱敏。
