# 文档索引

核对日期：2026-09-29。

本目录描述 **WheelPlay 当前分支**：

- [使用与构建说明](../README.md)：安装、配对、认证资产和构建方式。
- [架构与协议](ARCHITECTURE.md)：离屏解码、Web 服务、触摸及生命周期。
- [WebRTC 稳定性与卡顿恢复](WEBRTC_RECOVERY.md)：回退原因、可配置容忍时间及真机排查方法。
- [JPEG 渲染与传输全链路性能优化方案](JPEG_FRONTEND_OPTIMIZATION.md)：针对低算力车机浏览器的前端异步位图解码、流水线 ACK 与手机后台 30 FPS 按需抓图、OpenGL 960 硬件缩放、`TCP_NODELAY` 及异步编码方案。
- [认证资源导入](AUTHENTICATION_IMPORT.md)：手动选择资源、从用户下载的 DiPlay APK 提取及本地存储。
- [Tesla HTTP 兼容模式](TESLA_HTTP.md)：无证书虚拟地址入口、可选域名和实机验证限制。
- [验证记录](VALIDATION.md)：已通过的检查、APK 校验值和未完成的真机验证。
- [组件间距检查](SPACING_REVIEW.md)：本轮发现、尺寸约定、修正和验证范围。
- [上游来源](UPSTREAM.md)：原版提交及本分支改动范围。
- [NanoHTTPD 许可](NANOHTTPD-LICENSE.txt)。

`upstream/` 保留上游文档及许可证，属于原版参考资料，其中安装位置、构建版本及 HUD 流程可能不适用于 Web 分支。本分支用法以根目录 README 和本目录为准。
