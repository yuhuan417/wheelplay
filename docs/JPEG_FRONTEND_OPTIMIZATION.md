# JPEG 渲染与传输全链路性能优化方案（前端 + 后台）

核对日期：2026-10-16。本文针对低算力车机浏览器（弱单核 CPU、高 DOM 重排开销、GC 停顿敏感、GPU 纹理上传受限）下的 JPEG 视频流**前端渲染 + 手机端后台编码传输**全链路，给出分阶段的问题分析与技术落地方案。

---

## 1. 背景与现状管线

当车机浏览器未启用或无法使用 WebRTC 直通时，WheelPlay 通过手机端 OpenGL 离屏渲染抓取 CarPlay 画面并压缩为 JPEG，经同一条配对 WebSocket（`/stream`）推送至车机浏览器。优化前的端到端管线如下：

```text
[手机端后台]
MediaCodec 解码输出
  │
  └─► SurfaceTexture.onFrameAvailable (wheelplay-video 单线程)
        │
        ├─► updateTexImage() + FramePublishScheduler.request() (固定 66ms 节流 = 15.15 FPS 上限)
        │
        └─► OffscreenVideoOutput.draw() (在 GL 线程串行执行)：
              1. glDrawArrays (全尺寸 Pbuffer，最高 1080p+)
              2. glReadPixels (读取 3.6MB ~ 8.3MB RGBA 像素)
              3. Bitmap.copyPixelsFromBuffer
              4. new ByteArrayOutputStream + Bitmap.compress(JPEG, 75) + toByteArray()
              5. WebSession.publish ──► WebStreamSender (单帧 inFlight 背压，Socket 未开启 TCP_NODELAY)

[车机浏览器前端]
WebSocket Binary (ArrayBuffer)
  │
  ├─► browserAudio.receive(data) ──► DataView 检查音频魔数 (0x31415057)
  │
  └─► new Blob([data], { type: 'image/jpeg' })
        │
        └─► URL.createObjectURL(blob)
              │
              └─► spare.src = nextUrl （spare 处于 [hidden] -> display:none!important）
                    │
                    └─► spare.onload 触发：
                          1. target.hidden = false; screen.hidden = true;
                          2. screen.removeAttribute('src');
                          3. 交换 screen / spare 引用，更新 width / height / live / status
                          4. send({ type: 'ack' }) ──► 服务端收到后才允许发下一帧
                          5. URL.revokeObjectURL(displayedUrl)
```

在车机浏览器与手机后台协同运行时，上述管线存在以下两大类核心瓶颈：
- **第一阶段（车机前端瓶颈）**：DOM 强制重排、网络与解码串行等待、高频 Blob/ObjectURL 分配与 GC 抖动、触控拖拽同步布局抢占、高 DPR 超大分辨率请求。
- **第二阶段（前后端协同与后台管线瓶颈）**：后台硬编码 66ms（15 FPS）抓图上限且与车机 `ack` 脱节、WebSocket 缺少 `TCP_NODELAY` 引起 40ms Nagle 延迟、离屏渲染未限制 JPEG 分辨率（车机软解像素量过大）、GL 线程同步压缩阻塞与每帧重复分配大数组、前端 Canvas 2D 默认插值采样与缺乏 `requestAnimationFrame` 合批上屏。

---

## 2. 第一阶段：车机前端渲染与交互优化（已落地）

### 优化项 1：采用 `createImageBitmap` + `<canvas>` 离屏异步解码渲染（替代双 `<img>` DOM 切换）

#### 问题分析
1. **每帧触发全屏 DOM Layout 重排**：当前在 `onload` 中执行 `target.hidden = false; screen.hidden = true; screen.removeAttribute('src')`。由于 [`style.css`](../common/src/main/assets/web/style.css) 定义了 `[hidden]{display:none!important}`，每帧都在两个全屏元素之间切换 `display: none`，迫使浏览器每帧执行 **Style Recalc → Layout（重排）→ Layer Tree 更新 → Paint**。
2. **隐式延迟解码（Lazy Decode）**：在 Chromium 内核中，`display: none` 的 `<img>` 不在渲染树内。对其赋 `src` 触发 `onload` 时往往仅完成了资源加载与头部解析，真正的 JPEG 像素解码与 GPU 纹理上传可能被推迟到 `hidden = false` 上屏瞬间才在渲染线程同步执行，造成切帧卡顿。
3. **解码缓存反复销毁**：`removeAttribute('src')` 每帧立即销毁旧 `<img>` 的解码缓冲，导致内存频繁分配与释放。

#### 技术方案（方案 A：`createImageBitmap` + `<canvas>` 主路径，保留 `<img>` 降级兜底）
1. **主路径（支持 `createImageBitmap` 的浏览器，Chromium 50+）**：
   - 复用页面已有的 `<canvas id="video-canvas">`（获取 2D 上下文 `canvas.getContext('2d', { alpha: false, desynchronized: true })`，回退 `{ alpha: false }`）。
   - 收到 JPEG `ArrayBuffer` / `Blob` 后，直接调用 `createImageBitmap(blob)`：
     - JPEG 解码在浏览器**后台工作线程池（Worker Threads）**完成，不阻塞 JS 主线程。
     - 解码完成后获得 `ImageBitmap`，调整画布尺寸（仅在 `bitmap.width !== canvas.width || bitmap.height !== canvas.height` 时修改 `canvas.width/height`），执行 `ctx.drawImage(bitmap, 0, 0)`。
     - 绘制后**立即调用 `bitmap.close()`**，确定性地释放底层位图内存与显存，不依赖 GC。
   - 全程 **0 次 DOM 属性/显隐切换、0 次 Layout 重排、0 次 `URL.createObjectURL` 调用**；`{ alpha: false }` 进一步省去车机合成器的 Alpha 混合开销。
2. **兜底路径（不支持 `createImageBitmap` 的旧环境 / 测试桩）**：
   - 若运行时不存在 `createImageBitmap` 或获取 2D Canvas 上下文失败，自动降级到双缓冲 `<img>` 路径，保持完整的向后兼容与单元测试兼容。

---

### 优化项 2：引入单槽位流水线 ACK（1-Frame Pipelining），并行化网络传输与车机解码

#### 问题分析
- 服务端 [`WebStreamSender.kt`](../common/src/main/java/com/shilapi/xcertplay/web/WebStreamSender.kt) 限制最多 1 帧 JPEG 在途（`inFlight = true`，收到 `ack` 后才发送下一帧）。
- 前端原本在**解码并渲染完成后**才发送 `ack`，导致整个周期完全串行：
  `服务端发送 Frame N (网络 RTT) → 车机 CPU 解码 Frame N → 前端发送 ACK → 服务端发送 Frame N+1`。

#### 技术方案
- 将前端 JPEG 接收改造为**“1 个解码中状态（`jpegDecoding`）+ 最多 1 个待解码槽位（`pendingJpeg`）”**的有限流水线：
  1. 在 `createImageBitmap` 异步主路径下，当第 N 帧**开始进入浏览器后台解码线程**且 `pendingJpeg === null` 时，立即发送 `ack` 允许服务端在网络上并行推送第 N+1 帧；若第 N+1 帧到达时第 N 帧仍在解码，则将第 N+1 帧存入 `pendingJpeg`（此时暂不发送 `ack`，防止服务端无限制堆积）。
  2. 一旦第 N 帧解码完成，若 `pendingJpeg` 非空，立即取出 `pendingJpeg` 启动下一轮解码并发送 `ack`。
  3. 维护 `jpegGeneration` 代次计数，在断线或切换 WebRTC 时立即 `bitmap.close()` 并丢弃过期异步帧。

---

### 优化项 3：消除每帧 `Blob URL` 注册/注销开销与 GC 抖动

#### 技术方案
- 在 `createImageBitmap` 主路径下，直接向 `createImageBitmap` 传入 `Blob`，彻底移除 `URL.createObjectURL` 与 `URL.revokeObjectURL` 调用。

---

### 优化项 4：缓存触控容器 `BoundingClientRect`，消除拖拽时的强制同步布局（Layout Thrashing）

#### 技术方案
- 维护缓存变量 `cachedDisplayRect`，仅在 `ResizeObserver`、`resize`、`fullscreenchange` 及显示面板切换时失效重算；`pointerdown` / `pointermove` 直接读取缓存矩形做纯算术坐标归一化，实现 **0 次同步布局查询**。

---

### 优化项 5：CSS 渲染树隔离（CSS Containment）与移除画面元素过渡动画

#### 技术方案
- 在 [`style.css`](../common/src/main/assets/web/style.css) 中为 `#display` 添加 `contain: layout paint size;`，并移除 `#screen, #screen-next, #video` 上的 `transition: opacity .2s ease`。

---

### 优化项 6：热路径（每帧状态更新、DOM 查询与控制消息序列化）减负

#### 技术方案
- 缓存高频 DOM 引用，在 `setStatus` 中对未变化文本快速短路，在 `createControlOutbox` 中对 `ack`（`'{"type":"ack"}'`）和 `ping`（`'{"type":"ping"}'`）使用预序列化常量字符串。

---

### 优化项 7：高分屏车机自适应视口分辨率上限（抑制超大 JPEG 软解开销）

#### 技术方案
- 针对特斯拉 Model Y 等中控屏（全屏 `1920×1080`，但浏览器仅占右侧约 `1150×860` 区域），在 `scheduleViewportReport()` 中对上报视口长边做 **`1280`** 封顶（保持原始宽高比与偶数对齐），避免高 DPR 下请求超出物理窗口尺寸的画幅。

---

## 3. 第二阶段：前后端全链路协同优化（本次实施）

### 优化项 8：解除后台 66ms（15 FPS）硬编码上限，并与车机 `ack` 联动按需抓图

#### 问题分析
1. **硬编码 15 FPS 天花板**：[`FramePublishScheduler.kt`](../shared/src/main/java/com/shilapi/xcertplay/web/FramePublishScheduler.kt) 默认 `intervalMillis: Long = 66`（即最高 **15.15 FPS**），导致即使车机浏览器在第一阶段优化后具备 25~30 FPS 的解码渲染能力，手机后台也无法输出超过 15 FPS 的帧率。
2. **后台定时抓图与车机消费能力脱节**：
   - 当车机端繁忙、上一帧尚在传输或等待 `ack` 且后台已经缓存了一帧尚未发出的最新 JPEG 时，[`OffscreenVideoOutput`](../shared/src/main/java/com/shilapi/xcertplay/web/OffscreenVideoOutput.kt) 仍会周期性执行 `glReadPixels` + `Bitmap.compress(JPEG)`，生成的帧仅覆盖 `WebSession.frame` 而未被发送，白白消耗手机 CPU 与内存带宽。
   - 反之，当车机端刚刚发回 `ack` 准备好接收下一帧时，如果恰好处于 `FramePublishScheduler` 的节流等待窗口内，后台还要空等数十毫秒才触发下一次抓图，平白增加端到端延迟。

#### 技术方案
1. **提升抓图帧率上限**：将 [`FramePublishScheduler`](../shared/src/main/java/com/shilapi/xcertplay/web/FramePublishScheduler.kt) 的默认最小间隔 `intervalMillis` 从 `66ms`（15 FPS）调整为 **`33ms`（约 30 FPS）**。
2. **消费端背压感知与 ACK 即时唤醒**：
   - 在 [`WebStreamSender`](../common/src/main/java/com/shilapi/xcertplay/web/WebStreamSender.kt) / [`LanWebServer`](../common/src/main/java/com/shilapi/xcertplay/web/LanWebServer.kt) / [`WebSession`](../common/src/main/java/com/shilapi/xcertplay/web/WebSession.kt) 中暴露消费就绪状态与 `ack` 回调通知：
     - 当客户端处于 `inFlight == true`（上一帧已发出尚未收到 `ack`）或已有未发送的新帧排队时，`OffscreenVideoOutput` 暂缓执行新的抓图压缩，仅保持 `SurfaceTexture.updateTexImage()` 更新最新纹理并保留 `dirty = true`。
     - 一旦 [`WebStreamSender.acknowledge()`](../common/src/main/java/com/shilapi/xcertplay/web/WebStreamSender.kt) 收到车机 `ack`，立即通知 `OffscreenVideoOutput` 唤醒 `publisher.resume()`，在满足节流间隔时第一时间抓取最新纹理并编码发送。

---

### 优化项 9：WebSocket 与 HTTP 传输层开启 `TCP_NODELAY`，消除 Nagle + Delayed ACK 40ms 卡顿

#### 问题分析
- [`LanWebServer.kt`](../common/src/main/java/com/shilapi/xcertplay/web/LanWebServer.kt) 基于 `NanoWSD` / `NanoHTTPD`，其底层 `ServerSocket.accept()` 产生的客户端 `Socket` 默认未开启 `TCP_NODELAY`（即启用了 Nagle 算法）。
- JPEG 串流是典型的**“服务端发送数十 KB 的 JPEG 分片 ↔ 客户端高频回复数十字节的 `ack` / `touch` / `ping` 小包”**交互模式。Nagle 算法在等待未确认小包合并时，极易与对端 TCP 协议栈的 **Delayed ACK（延迟确认，通常 40ms）** 产生死锁等待，造成每帧传输尾部或控制包出现 **40ms 级别的周期性延迟尖峰**。

#### 技术方案
- 在 [`LanWebServer.kt`](../common/src/main/java/com/shilapi/xcertplay/web/LanWebServer.kt) 中重写 `createClientHandler(finalAccept: Socket, inputStream: InputStream)`，对每个接入的客户端 `Socket` 显式调用 `runCatching { finalAccept.tcpNoDelay = true }`，确保 HTTP 与升级后的 `/stream` WebSocket 连接均立即禁用 Nagle 算法，所有 JPEG 尾包与控制帧即刻发出。

---

### 优化项 10：利用手机 OpenGL 硬件双线性缩放限制 JPEG 编码长边上限（`960`）并优化压缩质量（`65`）

#### 问题分析
1. **特斯拉中控屏右侧浏览器窗口的实际几何特征**：
   - 2022 款特斯拉 Model Y 中控屏物理分辨率为 `1920×1080`，但左侧约 `600~680px` 固定为行车可视化区域，叠加底部 Dock 栏与顶部浏览器地址栏后，浏览器视口实际物理区域仅约 **`1150×840` ~ `1200×880`**（宽高比接近 **4:3**，约 `1.36:1`）。
   - 在接近 4:3 的窗口比例下，若长边设为 `1280`，输出分辨率会达到 `1280×960`（**122.88 万像素**，比标准 16:9 的 `1280×720` 多出 33% 像素，甚至超过窗口本身的物理像素）。
   - 将 JPEG 离屏编码长边设为 **`960`**（`16 × 60`，完美对齐 JPEG 4:2:0 的 `16×16` MCU 宏块）时，4:3 窗口下约为 **`960×704`（67.5 万像素）**，16:9 窗口下为 **`960×540`（51.8 万像素）**，相比 `1280×960` **像素量与车机软解耗时直接下降 45%**，同时在右侧半屏窗口上仍保持良好的图标与导航文字清晰度。
2. **手机端 OpenGL 缩放零额外成本**：[`OffscreenVideoOutput.kt`](../shared/src/main/java/com/shilapi/xcertplay/web/OffscreenVideoOutput.kt) 本身通过 OpenGL ES 着色器（`GL_LINEAR` 双线性过滤）将解码器 `SurfaceTexture` 绘制到离屏 Pbuffer Surface，缩小 Pbuffer 尺寸无需任何 CPU 缩放开销。
3. **压缩质量优化**：JPEG 质量从 `75` 下调为 **`65`**，单帧体积可控制在 **35 KB ~ 55 KB**，进一步降低 Wi-Fi 传输时间与车机端 Huffman 解码耗时。
4. **触控坐标完全兼容**：前端 [`app.js`](../common/src/main/assets/web/app.js) 与后台 [`TouchLease`](../common/src/main/java/com/shilapi/xcertplay/web/TouchLease.kt) 全程使用 `[0.0, 1.0]` 归一化坐标，等比例缩小 JPEG 编码尺寸对触控精度 **0 影响**。

#### 技术方案
1. **JPEG 离屏渲染长边 `960` 封顶**：
   - 在 [`OffscreenVideoOutput`](../shared/src/main/java/com/shilapi/xcertplay/web/OffscreenVideoOutput.kt) 中新增尺寸计算纯函数 `encodeDimensions(sourceWidth: Int, sourceHeight: Int, maxLongEdge: Int = DEFAULT_MAX_LONG_EDGE): Pair<Int, Int>`（其中 `DEFAULT_MAX_LONG_EDGE = 960`）：
     - 当 `maxOf(sourceWidth, sourceHeight) <= maxLongEdge` 时，保持偶数对齐原尺寸；
     - 当长边超过 `960` 时，按 `scale = 960.0 / maxOf(sourceWidth, sourceHeight)` 等比缩放，并对齐到偶数像素（最小为 2）。
   - `SurfaceTexture.setDefaultBufferSize(width, height)` 仍保持原始解码分辨率，而 EGL Pbuffer、`glViewport`、`glReadPixels` 及 `Bitmap` 使用缩放后的 `encodeWidth × encodeHeight`。
2. **调整 JPEG 压缩质量为 `65`**：
   - 将 `frame.compress(Bitmap.CompressFormat.JPEG, DEFAULT_JPEG_QUALITY, bytes)` 默认质量设为 `65`。

---

### 优化项 11：后台 GL 读回与 CPU JPEG 编码异步解耦 + 编码缓冲区复用（零重复大数组分配）

#### 问题分析
- 优化前在 `wheelplay-video` 单一 GL 线程的 `draw()` 中同步串行执行：
  `glDrawArrays` → `glReadPixels` → `frame.copyPixelsFromBuffer` → `new ByteArrayOutputStream` → `frame.compress(JPEG)` → `bytes.toByteArray()`。
- 其中：
  1. `frame.compress(JPEG)` 是纯 CPU 密集型操作，在此期间 GL 线程被完全阻塞，无法及时响应解码器的 `onFrameAvailable` 调用 `updateTexImage()`。
  2. 每帧都 `new ByteArrayOutputStream(...)`，导致每帧在 JVM 堆上重复分配大数组，造成频繁 GC 抖动。

#### 技术方案
1. **复用输出流缓冲区**：在编码器内部复用同一个 `ByteArrayOutputStream` 实例，每帧编码前调用 `reset()` 重置写指针，复用已扩容的内部 `byte[]`，将每帧的大数组分配次数从 2 次降为 1 次（仅最终发布的不可变 `toByteArray()`）。
2. **GL 线程与 JPEG 编码线程双缓冲流水线分离**：
   - `wheelplay-video`（GL 线程）仅负责轻量且必须绑定 EGL 上下文的操作：`updateTexImage()`、`glDrawArrays()`、`glReadPixels()` 以及将像素拷入空闲槽位的 `Bitmap`。
   - 新增专用的单线程编码器 `wheelplay-jpeg`：接收填充好像素的 `Bitmap` 执行 `compress(Bitmap.CompressFormat.JPEG, quality, reusableStream)` 并调用 `publish(jpegBytes)`；编码完成后释放槽位，若期间 GL 线程有新帧待抓取则立即衔接下一帧。

---

### 优化项 12：车机前端 Canvas 禁用插值平滑（`imageSmoothingEnabled = false`）与 `requestAnimationFrame` 合批上屏

#### 问题分析
1. **多余的 2D Canvas 插值开销**：前端 `<canvas id="video-canvas">` 的画布宽高始终与解码出的 `ImageBitmap` 宽高严格 1:1 相等，但 Canvas 2D Context 默认开启 `imageSmoothingEnabled = true`，在部分低端车机浏览器的软件或弱 GPU 光栅化路径下仍会设置双线性采样滤波器。
2. **非对齐 VSync 绘制与过期帧上屏**：`createImageBitmap(blob).then(...)` 在微任务（Microtask）中立即执行 `ctx.drawImage(bitmap, 0, 0)`。若在一个屏幕刷新周期（16.6ms）内连续完成两帧解码，主线程会在同一次 VSync 前执行两次全屏 `drawImage`，第一次绘制完全是浪费。

#### 技术方案
1. **显式关闭平滑插值**：在 `ensureJpegCanvasContext` 初始化上下文以及每次调整 `canvas.width / height` 后（重置 Canvas 宽高会恢复默认 context 状态），显式设置 `jpegContext.imageSmoothingEnabled = false`，强制走最快的 1:1 像素直接拷贝（Blit）路径。
2. **`requestAnimationFrame` 合批上屏（最新帧胜出）**：
   - 当 `createImageBitmap` 解码完成时，立即推进 `pendingJpeg` 的下一帧解码，同时在上屏阶段：
     - 若环境中存在 `window.requestAnimationFrame`，将解码出的 `bitmap` 放入 `stagedBitmap` 槽位（若槽位中已有尚未绘制的旧 `stagedBitmap`，立即对其调用 `.close()` 丢弃），并在 `requestAnimationFrame` 回调中统一执行一次 `drawImage` + `bitmap.close()`。
     - 若环境无 `requestAnimationFrame`，则直接同步绘制并 `close()`。

---

## 4. 全链路优化汇总对比表

| 阶段 | 编号 | 优化维度 | 优化前实现 | 优化后方案 | 核心收益 |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **前端** | **1** | **JPEG 解码与上屏** | 双 `<img>` 每帧切换 `hidden` (`display:none`) | **`createImageBitmap` 后台线程解码 + 单 `<canvas>` (`alpha: false`) 绘制 + `bitmap.close()`** | 消除每帧 Layout 重排与主线程同步解码阻塞，显存即时释放 |
| **前端** | **2** | **帧流控与 ACK 时机** | `onload` 渲染完成后才发 `ack`，网络与解码串行 | **1-Frame 流水线（解码当前帧的同时允许服务端传输下一帧）** | 网络传输耗时与车机解码耗时重叠，吞吐率大幅提升 |
| **前端** | **3** | **二进制对象生命周期** | 每帧 `new Blob` + `URL.createObjectURL` + `revokeObjectURL` | `createImageBitmap` 直接消费 `Blob`，**0 次 ObjectURL 注册/注销** | 消除跨进程 Blob URL 同步开销与周期性 GC 卡顿 |
| **前端** | **4** | **触控坐标映射** | 每次 `pointerdown` / `pointermove` 同步调用 `getBoundingClientRect()` | **缓存容器 `BoundingClientRect`**（仅在尺寸变化时更新） | 消除拖拽地图时的强制同步布局（Layout Thrashing） |
| **前端** | **5** | **CSS 渲染隔离** | `#display` 无隔离，画面元素带 `transition: opacity` | `#display` 增加 `contain: layout paint size`，移除画面过渡 | 限制重绘/重排边界，降低车机合成器负担 |
| **前端** | **6** | **热路径微优化** | 每帧查 DOM、`Date.now()`、重复 `JSON.stringify({type:'ack'})` | 缓存高频 DOM 节点、状态短路、静态控制帧预序列化 | 降低每帧主线程 JS 额外开销 |
| **前端** | **7** | **视口分辨率倍率** | 无上限乘以 `devicePixelRatio` | 保持比例的前提下将上报长边限制在 **`1280`** 以内 | 匹配特斯拉右半屏物理窗口，防止高 DPR 请求超大画幅 |
| **后台+协同** | **8** | **抓图帧率上限与 ACK 联动** | 固定 `66ms`（15 FPS 上限）且与客户端 `ack` 脱节 | **间隔降至 `33ms`（30 FPS 上限）+ 背压暂停无效压缩 + `ack` 即时唤醒抓图** | 突破 15 FPS 锁死，消除最高 50ms 定时器空等与后台无效压缩 |
| **后台** | **9** | **WebSocket 传输延迟** | Socket 默认开启 Nagle 算法（无 `TCP_NODELAY`） | **接入 Socket 强制开启 `tcpNoDelay = true`** | 消除 JPEG 尾包与 `ack`/触控包之间的 **40ms Nagle + Delayed ACK 卡顿** |
| **后台+前端** | **10** | **JPEG 分辨率与质量控制** | 离屏 Pbuffer 保持原尺寸（最高 1080p+）、质量 `75` | **OpenGL 硬件缩放限制 JPEG 长边 `960` + 压缩质量 `65`** | 针对特斯拉右半屏 4:3 窗口削减 **50%~65%** 像素与解码耗时，触控零影响 |
| **后台** | **11** | **编码线程分离与缓冲复用** | GL 线程同步压缩 JPEG + 每帧 `new ByteArrayOutputStream` | **GL 读回与独立 JPEG 编码线程分离 + 复用输出流缓冲区** | 解放 GL 线程，显著减少手机端内存分配与 GC 停顿 |
| **前端** | **12** | **Canvas 1:1 Blit 与 rAF 合批** | 默认开启 `imageSmoothingEnabled` + 微任务立即绘制 | **`imageSmoothingEnabled = false` + `requestAnimationFrame` 最新帧合批上屏** | 走最快像素拷贝路径，杜绝同一刷新周期内重复画图 |

---

## 5. 兼容性与验证计划

1. **WebRTC 与 JPEG 模式切换兼容**：
   - `OffscreenVideoOutput` 的长边 `960` 缩放仅作用于离屏 JPEG 抓图 Pbuffer，不影响 `WebVideoSource` 直通给 WebRTC 的 H.264 码流分辨率，也不影响归一化触控坐标映射。
   - 当 WebRTC 激活时，`source.jpegNeeded == false` 自动停止后台 JPEG 抓图与编码，前端清理未决 `ImageBitmap` 与 `stagedBitmap` 槽位。
2. **自动化测试验证**：
   - 前端：运行 `npm test` 与 `npm run check`，验证 `imageSmoothingEnabled = false`、`requestAnimationFrame` 合批丢弃过期帧、`createImageBitmap` 流水线与 `<img>` 降级路径。
   - 后台：运行 Gradle 单元测试（`FramePublishSchedulerTest`、`WebStreamSenderTest`、`LanWebServerTest` 及新增的 `OffscreenVideoOutputTest`），验证 33ms 节流、ACK 唤醒、`TCP_NODELAY` 及长边 `960` 等比偶数缩放逻辑。

