# APK 服务端架构

核对日期：2026-09-30。本文描述 WheelPlay 当前实现，非原版车机本地显示模式。

```text
iPhone ─ 无线 CarPlay ─ CarPlayController / CarPlayMediaEngine
                                      │ H.264 / HEVC
                               AndroidMediaSink
                                      │ 压缩帧（WebVideoSource 解密、保留 avcC/hvcC）
                                WebSession 当前视频源
                                      │
车机浏览器 ← HTTP/HTTPS 静态资源及 WebSocket ← LanWebServer :8080 / :8443（视频仅 HTTPS）
    │ normalized contacts                         │
    └──────────────────────────────────── TouchLease
                                                  │
                                      CarPlayController.sendTouch

iPhone 音频 → AndroidMediaSink 解码 PCM → BrowserAudioBridge → 浏览器 Web Audio
浏览器麦克风 → AudioWorklet PCM → BrowserAudioBridge → MicrophoneUplink → iPhone
```

Web 服务由 DiPlaySessionService 创建/销毁；视频源生命周期属于媒体 sink。返回首页或切换应用不会解绑视频源。原版 CarPlay 控制器仍由后台会话快照持有；原版 Activity 负责权限和重连配置。服务使用非粘性前台生命周期，不宣称能在系统强杀后恢复。显示基准固定为 1280×720，避免服务端屏幕旋转触发 CarPlay 重协商；原版显示缩放设置仍可能改变实际协商分辨率，网页使用收到的图像尺寸映射触摸坐标。

## 协议 v1

1. `GET /`、`GET /app.js`、`GET /style.css` 提供 APK 内置资产。
2. `GET /stream` 升级为 WebSocket，要求 `Origin` 与 `Host` 同源；不配对，任何能打开页面的浏览器都可连接。跨源返回 403。
3. 视频只有 WebCodecs 一种传输：压缩帧经同一 WebSocket 以 `WVC1` 二进制帧发送，配置帧先于其描述的帧到达。客户端不回复视频 ACK。
4. 客户端每 400 ms 发送 `{"type":"ping"}`；服务端回复 `{"type":"status","width":1280,"height":720,"streaming":true,"stage":"..."}`。
5. 触摸消息 `{"type":"touch","contacts":[{"id":0,"x":0.5,"y":0.5,"down":true}]}`。坐标归一化到实际图像区域，排除 letterbox；最多两个固定槽位，缺失槽位为抬起。长按期间随心跳重发，超过 1500 ms 未收到触摸则释放。
6. 已被占用时新连接收到 `{"type":"busy","message":"..."}` 后关闭。断线释放所有触点。页面失焦、隐藏或退出也主动释放。
7. 服务端向当前网页发送 `audio-route`，网页只用 `audio-ready` 回报浏览器音频与麦克风是否就绪。解码 PCM 打包为 `WPA1` 二进制帧（流号、采样率、声道、序号、PCM16LE）；浏览器通过 `audio-ack` 确认，最多四帧在途，过期帧丢弃。麦克风先等待 `mic-config`（采样率、声道、输入 ID），随后发送 `WPM1` PCM 帧；仅当前浏览器且 ID/序号匹配时接收。Android 不播放音频、不采集麦克风；断开或浏览器未就绪时没有本地回退。
8. WebCodecs 只在 HTTPS 页面提供：`webcodecs-config` 携带 avcC/hvcC 记录，`webcodecs-frame` 携带 PTS 与 Annex B 数据；浏览器通过 `webcodecs-ready`、`webcodecs-keyframe`、`webcodecs-fallback` 回报状态。没有备用视频通道：HTTPS、WebCodecs 或解码失败时保留错误并停止画面，由页面显示原因。

## 生命周期与资源约束

- 每个媒体 sink 有独立的视频源和代次编号；旧会话结束不会覆盖/清空新会话的视频源。
- 解密与参数集解析在接收线程完成；网络发送在独立工作线程，不阻塞解码回调。
- WebCodecs 帧、音频与控制回复共用单一写线程。控制回复优先，音频队列最多 16 帧并在 100 ms 后过期；音频和视频各自背压，接收线程不等待网络写入。
- 浏览器拥塞时保留未提交的触摸边界事件，合并连续移动与心跳，每 10 ms 重试。待发送触摸最多 64 条，1500 ms 无发送进展或队列溢出时关闭连接；断线清空待发送状态。
- 客户端首次连接超时为 8 秒；服务端在超过 6 秒未收到客户端消息时关闭连接并释放控制权，客户端也检查 6 秒状态回复超时。服务端看门狗每 500 ms 检查一次。
- 静止画面不会仅因缺少新帧被判为断线；状态依据屏幕流是否激活。新浏览器加入时请求关键帧，从下一个关键帧开始解码。
- 前台服务持有 PARTIAL_WAKE_LOCK，并在销毁时释放。移除界面任务不会主动停止服务；系统强杀和厂商省电策略仍需真机验证。
- 断电待机时同时关闭 Wi-Fi、热点和蓝牙；恢复供电时先启用 Wi-Fi 并等待 STA 上行真正连接，再启动系统热点，并重新启用断电前处于开启状态的蓝牙。否则 Tesla/iPhone 会因热点没有外网而拒绝或离开该连接，CarPlay 也会因蓝牙未恢复而无法连接。
- 停止服务释放监听端口、客户端、定时器和输入状态。
- 视频、浏览器播放音频与浏览器麦克风都要求安全上下文：服务额外开放 HTTPS 8443，证书由本机私有存储中保留的安装专属 CA 签发；根证书下载与 SHA-256 指纹供用户在车机浏览器核对并配置。HTTP 只提供设置和状态页面，不发送视频。HTTPS 与 HTTP 共用单浏览器控制权，来源按协议分别校验。部分车机浏览器不允许安装本地 CA，此时无法使用浏览器视频/麦克风。

浏览器音频使用 PCM/Web Audio，与视频传输独立。Android 不播放 CarPlay 音频，也不采集麦克风；浏览器不能获得麦克风权限、关闭麦克风或连接断开时没有本地回退。CarPlay 设备端的实际音频路由与延迟仍需真机验证。


## APK 界面层

`DiPlayActivity` 是唯一的主界面与启动入口，承载启动（Launch）、iPhone、设置三个同级页面。`ServerUi.Shell` 持有三页内容、底部标准 BottomNavigationView 和状态栏遮罩，切换仅改变页面可见性，保留页面控件及滚动位置；重复点击当前 Tab 不重建视图，不启动 Activity，不重置滚动。

使用 Material Components 1.13.0 与 `Theme.Material3.Dark.NoActionBar`。按钮、开关、文本、分隔线、输入框、弹窗与连接进度指示器分别使用对应的 Material 组件；Tab 指示器采用组件默认动效，页面本身没有整屏平移。主题集中在 `themes.xml`。

`CarPlayHostActivity` 保留协议控制器与权限申请的所有权，仅作为显式连接流程使用，不再承载 Tab。会话启动后返回主界面，连接仍在后台运行。通知也直接打开主界面。打开主界面启动 Web 服务；自动连接继续受用户设置控制。断开 iPhone 后恢复独立 Web 服务，让服务面板继续可用。

控制器、认证、媒体管线和触摸协议未改动。连接方式与设置仍调用原有持久化和重连流程；启动连接时的权限由系统界面处理。主界面旋转时重建布局，但不重建 CarPlay 会话。

`ServerWindow` 统一要求主界面及连接流程显示系统状态栏和导航栏，内容区避开系统栏和屏幕开孔。服务端不再读取旧全屏偏好或提供本机全屏开关，浏览器全屏控制不受影响。

选择型偏好使用 `ServerUi.PreferenceRow`：左侧标题，右侧当前值及方向提示，无按钮边框，整行带主题水波纹并支持键盘焦点和读屏。选择确认后更新值文本，原有 Material 单选弹窗与保存/重连回调保留。

`LanAddresses` 结合 Android ConnectivityManager 的网络类型/当前网络及 NetworkInterface 的 IPv4 地址，保留接口来源；无法取得系统元数据时按接口名降级识别。稳定排序优先普通 Wi-Fi / 以太网，其次热点、未知网络、VPN、移动网络，同级优先当前网络。仅显示启用的非回环接口上的私有 IPv4 地址。主界面只展示首选项，其余默认折叠；每秒刷新地址变化时保留展开状态，避免反复重建地址列表。排序不代表对车机可达性的实际探测。
