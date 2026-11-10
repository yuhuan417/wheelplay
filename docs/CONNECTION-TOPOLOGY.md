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

## 第 3 步（iPhone 加入 M）为什么会静默失败

第 1、2 步成功不代表第 3 步会发生：iAP2 只把凭据**交给** iOS，是否加入、加入失败都没有回执。
判断方法很直接——手动加入 M 后 CarPlay 立刻出画面，说明 `0x5703`/`0x4301` 已被接受（AirPlay 端口、
公钥、IP 都正确），失败的只有 iOS 的自动加入。已知会拦住的条件：

| 条件 | 代码里的处理 |
|---|---|
| 下发的 SSID ≠ 热点实际广播的名字 | 用扫描结果里 **AP 接口 BSSID 对得上的那条**取广播名下发（MIUI 桥接会加 `_5G` 后缀），不一致时记日志并在热点状态里提示 |
| 下发口令 ≠ 热点真实口令 | 优先读 `SoftApConfiguration.getPassphrase()`（旧路径 `WifiConfiguration.preSharedKey`）并下发实际口令；读不到才用设置值，并在状态/日志里标出 `passphraseSource` |
| 热点隐藏 SSID | 读 `isHiddenSsid`，在热点状态里提示取消隐藏；iOS 不会自动加入隐藏网络 |
| `0x4300` 的 `wirelessAvailable = 0` | 解析该字段并告警；表示 iOS 没有为这个配件允许无线 CarPlay，此时它不会为无线会话加入热点 |
| 下发的 `channel` 已过期 | 按 扫描到的 AP BSSID 频点 → STA 连接频点 → 配置频点 → 配置信道 取值，未知才发 0（auto） |
| iPhone 该网络的「自动加入」被关过 | App 侧无法改变；手动加入一次后 iOS 会重新记住这个网络 |

注意：**iPhone 开着个人热点不影响它加入系统热点**（2026-11-03 复测，见下节）；加不进去的是 Wi-Fi Direct。
这几条与凭据无关时 App 也无法自动修复，只能把原因说清楚（看门狗超时现在报 `the iPhone did not join '<ssid>'`）。

`0x5703`/`0x4301` 的进度日志带 `ssid / passphraseLength / channel / security / ip`（不含口令），
启动时另有 `wireless handoff hints ... passphraseSource=... hiddenSsid=...`，
控制通道结束时的 `wireless RFCOMM EOF` 带 `wirelessOfferedByPhone`。

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

小米 8 这类单射频设备上 STA 与 AP 必须同信道（MIUI「WLAN 信号桥」就是单信道桥接），
AOSP/高通在热点信道为 AUTO 时也会优先选择与 STA 相同的信道。若热点先起来、STA 之后才
关联到另一个信道，框架会把 SoftAP 重调到 STA 信道：已连的 Tesla/iPhone 会掉线，已经通过
iAP2 `0x5703` / `0x4301` 下发给 iPhone 的 `channel` 也会过期。

因此恢复供电时：

1. 一次 Root 事务先同时启用 Wi-Fi 和蓝牙，热点不启动；
2. 轮询 `WifiUplink.isAssociated()`，等 STA 关联、信道确定；最多等 10 秒，等不到也继续；
3. 再启动系统热点。

`WifiUplink` 只判断关联，不要求 `NET_CAPABILITY_INTERNET`：车机的 ConnMan 联网检测
（`connman.vn.cloud.tesla.cn`）已由本地应答，热点没有外网仍然可用，而信道只取决于关联。
`CarPlayController` 在建立蓝牙/iAP2 前的等待同样改为按关联判断（最多 15 秒），避免 iPhone
被要求加入一个即将换信道或没有外网的热点。

断电待机时仍会同时关闭 Wi-Fi、热点和蓝牙。

待机期间还有一个 30 秒一轮的守卫（`DiPlaySessionService.standbyGuard`）：如果 Wi-Fi、热点或蓝牙
在没有唤醒请求的情况下又自己打开（MIUI 的省电策略、系统的状态恢复、残留的广播都可能），守卫会把
它们重新关掉，并写一行日志说明当时是哪几路射频亮着：

```
待机守卫：没有收到唤醒请求，wifi=true hotspot=true bluetooth=true 又自己打开了，重新关闭
```

手机在电池供电时，只有「随车通电」和「立即唤醒」能真的离开待机；`iPhone 蓝牙已连接` 之类的
蓝牙事件会被忽略并记录（`唤醒被忽略（…）：手机在电池供电，继续待机省电`）。

## iPhone 个人热点当上行时无法同时用于 CarPlay（2026-11-04 真机确认）

adb + root 直读设备状态：

```
wlan1  type AP   ssid metaq   inet 192.168.43.1/24          ← 给 iPhone 连的热点
wlan0  managed   ssid huanhua   freq 5745  inet 172.20.10.4/28  ← 上行 = iPhone 个人热点
/data/vendor/wifi/hostapd/hostapd.conf: hw_mode=a, channel=0, chanlist=149 153 157 161, ieee80211w=0
iw dev wlan1 station dump: 没有 iPhone 客户端
```

`172.20.10.0/28` 是 iOS 个人热点的固定网段。把日志里的关联与加入结果对照：

| 时刻 | 安卓 STA | 结果 |
|---|---|---|
| 10:03:43 | 未关联 | 加入成功（first TCP） |
| 10:04:35 | `associated=false` | 加入成功（0.17 s） |
| 09:47–09:54 | 已关联 huanhua | 连续 9 次 `did not join` |

结论：**iPhone 正在给车机供热点时不加入 `metaq`；只是"热点开关开着但没有客户端"时可以**（这解释了之前的两种互相矛盾的实测）。单靠这一条就足以让 CarPlay 永远等不到 TCP。

处理：

- 要无线 CarPlay：关掉 iPhone 个人热点，或给车机换一个上行（车机热点/蜂窝/另一台手机）。
- 代码现在用 `WifiUplink.isIphonePersonalHotspot()`（匹配 172.20.10.1–14）识别，写入日志、诊断报告 `uplink=iphone-hotspot` 与热点状态提示，`next=` 直接给出上面这条建议，不再静默重试。
- `channel=0` 不是原因：10:03:43 与 10:04:35 两次成功用的就是 `channel=0`。

## iPhone 个人热点与 Wi-Fi Direct（结论已修正）

**旧结论（作废）**：曾根据一次实测记录写下「iPhone 不能同时当热点 AP 和 M 的客户端」，
并把「控制通道通了但没视频」归因于此。

**2026-11-03 复测推翻**：iPhone 先开个人热点、再加入 CarPlay 热点是可以的；加不进去的是
**Wi-Fi Direct（P2P）**，不是系统热点。所以「个人热点占用射频」不能作为蓝牙引导失败的默认解释，
那次的「connected 但无视频」另有原因（最可能是下发给 iOS 的 SSID/口令与真实广播不一致，
或信道提示过期）。

因此当前结论：

- 系统热点（AP 模式）+ 个人热点并存，不构成 iOS 侧阻塞；
- Wi-Fi Direct 组 iOS 加不进去，这也是只保留「系统热点」后端的原因；
- 蓝牙引导失败时按下面那张表排查，不要先归咎于个人热点。

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

## 先压住上行、CarPlay 起来后再连 iPhone 热点（2026-11-04 真机验证）

iOS 的顺序要求：**iPhone 已经在给本机供热点时不加入 metaq；CarPlay 先起来后，再打开热点则可以并存**（两条链路同时在线，实测 5 GHz 同信道也成立）。

`CarPlayController.holdIphoneHotspotUplinkDuringBringUp()`：

1. bring-up 时若 `WifiUplink.isIphonePersonalHotspot()` 为真且当前没有会话，用 root 执行 `ip link set <wlan0> down`，只动 STA，不动 AP/蓝牙；
2. 让 iPhone 可以加入 metaq；
3. `onSessionActive`（CarPlay 会话建立）→ `ip link set <wlan0> up`，框架自动连回 iPhone 热点，双链路成立；
4. 180 秒兜底释放（`UPLINK_HOLD_MAX_MILLIS`；真机实测 iOS 用了约 123 秒才离开热点加入 metaq），`close()` 也会释放，避免任何路径把设备留在离线状态；
5. 每次 bring-up 只压一次（`shouldHoldIphoneHotspotUplink`），重试不会来回抖。

真机时序（11:30）：

```
11:30:03  wlan0=UP   sta=huanhua                     ← 热点开着，本机连着它
11:30:07  wlan0=DOWN sta=NONE   无线上行已压住，等 CarPlay
11:30:10  wlan0=DOWN            CarPlay session established → 立即释放
11:30:14  wlan0=UP   sta=huanhua  result=joined  uplink-held=yes
11:30:14→11:31:44 双链路稳定 90 秒
```

冷启动实测（11:40:02 压住 → 11:42:0x 才建立会话，约 2 分钟）：iPhone 当时开着个人热点、还连着另一个 Wi-Fi，
本机什么都没操作，最终 `result=joined uplink-held=yes next=nothing to check`。这说明**压住上行足够久，iOS 会自己放弃热点并加入 metaq**，
但耗时在分钟级；加入后 iPhone 的热点通常已关闭，需要外网时要再手动打开（打开后即可双链路并存）。

## 真机闭环：BSSID + 上游次序 + Apple vendor IE（2026-11-04 19:41）

三个改动一起上，在"iPhone 正把热点服务给车机"这个原本 100% 失败的场景里一次通过：

1. `0x5703` 参数 0 带 AP BSSID（`Iap2WirelessCarPlayEndpoint.accessPointBssid`，对齐上游；无效/全零/广播 MAC 丢弃）；
2. 启动次序改回上游：热点 → AirPlay 监听 → Bonjour 广播 → **最后**连 RFCOMM；
3. hostapd 广播 Apple vendor IE `dd0800a0400000020021`（**App 自动注入**，见下节）。

真机时序：

```
19:41:13  安卓已连 huanhua（iPhone 热点）+ metaq AP 在广播   ← 旧版本此状态永不成功
19:41:16  压住上行（wlan0 DOWN）
19:41:19  first TCP（3 秒）
19:41:22  result=joined uplink=iphone-hotspot
19:41:25  wlan0 UP，自动连回 huanhua
19:42:56  稳定：STA=huanhua/5745 + ARP 192.168.43.188(iPhone)，0 次 session ended
```

对比同场景旧版本：30 秒超时、9 次 `did not join`、或等待约 2 分钟。

**尚未做 A/B**：这三项里哪几项是必要的不清楚。要最小化改动，应逐项回退复测（去掉 IE → 去掉 BSSID → 恢复旧次序）。

### vendor IE 的自动注入（App 内，已真机验证）

`HotspotVendorElement.ensure()` 在每次 bring-up 最开始执行（root，worker 线程）：

1. `grep` 检查 `/data/vendor/wifi/hostapd/hostapd.conf` 是否已有
   `vendor_elements=dd0800a0400000020021`；有则直接返回 `present`，不做任何重启；
2. 没有则：`chattr -i` → 追加该行 → `chmod 444` → `chattr +i`（MIUI 的该文件带 immutable 属性）；
3. **`svc wifi disable` + `svc wifi enable`** 让 hostapd 重建接口（`vendor_elements` 只在建接口时生效；
   实测 HUP 重载和 `hostapd_cli set` 都不会进入 beacon）；
4. 然后在已经"带着 IE"的 AP 上继续走 bring-up。

真机日志（配置里本来没有 IE）：

```
19:51:58  conf_ie=0 ap=1        ← 起点：热点在跑，但没有 IE
19:52:04  conf_ie=1             ← 已写入
19:52:11  wireless vendor element restarted
19:52:30  session established
19:53:23  ARP 192.168.43.188 (iPhone) on wlan1，session ended = 0
```

代价：只有"缺 IE"的那一次 bring-up 会多花约 10 秒重启 Wi-Fi；之后每次都是 `present`，直接跳过。

### iOS 硬约束：iPhone 供热点时不会加入我们的 AP（2026-11-04 定案）

连续对照实验（同一份代码、同样的凭据/BSSID/IE）：

| iPhone 热点 | STA 状态 | hostapd 收到的关联帧 | 结果 |
|---|---|---|---|
| 关 | 任意 | 有 | 20~30 秒内加入（场景 1，稳定复现） |
| 开 | 已压住 | **0 条** | 永不加入（20:12、20:42、20:50） |
| 开 | 从未连上（预压制） | **0 条** | 永不加入（20:50，证伪"连上了就太晚"） |
| 开 → 用户手动关闭 | 已压住 | 有 | **12 秒内加入**（20:52，无需重启会话） |
| CarPlay 已建立后再开热点 | — | — | 正常共存，双链路稳定 |

蓝牙侧一切正常：`iap2-wireless-available=true`、`iap2-config-sent=2`、`iap2-start-sessions=1`，
iOS 明确表示"无线 CarPlay 可用"，但它**一个 802.11 关联帧都不会发**。

因此：

- **能做的**：热点的上行存在时压住 STA（避免它一直提供服务）、并在 UI 上**立即**提示
  "请关闭 iPhone 个人热点，CarPlay 连上后可以再打开"；用户关掉后 App 的自动重试会接上。
- **做不到的**：让 iOS 在供热点期间加入我们的 AP。这与频率/信道/BSSID/vendor IE/启动次序都无关。

补充观察：STA 不在时 ACS 会把 AP 选到 153（`freq=5765`），STA 在 149 时会钉在 149；每次重建可能换信道，但对 iOS 的决策没有影响。

### 最终形态（2026-11-04 21:10 真机验证，全自动）

iPhone 供热点时无法被劝服，因此不再尝试任何"技术手段"，只做提示：

- **不重建 AP**：`HotspotVendorElement.ensure()` 只在配置缺 IE 时写入，从不重启 Wi-Fi；
- **不压上行**：STA 全程保持连接（`UplinkHold` 已删除）；
- **粘性提示**：检测到上行是 iPhone 热点 → 主界面显示
  "iPhone 正在共享个人热点，不会加入车内热点。请先关闭 iPhone 个人热点；CarPlay 连上后可以再打开热点上网。"
  该提示通过 `iphoneHotspotBlocked` 粘住，重连/控制通道就绪都不会覆盖它，只有**真正出画面**（`WebSession.videoActive`）才清除；
- **文案诚实**：`WirelessActive` 只表示 iAP2 控制通道就绪，显示为
  "蓝牙控制通道已就绪，等待 iPhone 加入热点"，不再冒充"CarPlay session started"。

真机时序：

```
21:10:11  上电：STA=huanhua（iPhone 热点）
21:10:13  vendor element present（无重启、无压制）
          → 屏幕显示"请关闭 iPhone 个人热点…"
21:10:27  用户关热点 → STA 掉线
21:10:29  first TCP / session established（约 2 秒）
21:10:41  用户重新开热点 → STA 自动连回 huanhua
21:12:12  双链路：STA=huanhua 172.20.10.4 + iPhone 192.168.43.188
          ping 223.5.5.5 32ms，session ended = 0
```
