# WheelPlay

**EN** | [CN](README.zh-CN.md)

WheelPlay is an Android-hosted CarPlay server for local networks. It streams your iPhone's CarPlay display to your car's browser and lets you control it through the car's touchscreen.

Install the app on an Android device and open the displayed address in your car's browser. No browser-side client installation or additional server is required.

## Features

- **Browser display and touch control**: CarPlay streaming, touch input, and fullscreen display.
- **WebCodecs video**: The compressed H.264 / HEVC stream is forwarded over the HTTPS WebSocket and decoded by the car's browser.
- **Browser audio and microphone**: CarPlay audio is sent to the connected browser only, and the browser microphone can provide CarPlay voice input through a trusted HTTPS page. The Android device never plays CarPlay audio or captures its own microphone; it only forwards.
- **Open and connect**: Any browser that can open the page can display and control CarPlay; there is no pairing step. Use only trusted local networks.
- **iPhone connection management**: Wireless CarPlay connection, connection status, and session management.
- **Local network access**: Automatically detects available network addresses for browser access.
- **Background service**: Background operation and automatic connection.
- **Display and audio settings**: Adjustable display size, resolution, frame rate, and audio buffering.
- **CarPlay preferences**: Force day or night mode; enable or disable the browser microphone. Preview custom 0–40% safe margins on each edge; microphone and safe-area changes reconnect the iPhone. Keep CarPlay when browsers go offline, or disconnect the iPhone after 1/5 minutes; browser reconnection cancels the timer.
- **Diagnostic export**: Save diagnostic reports locally for troubleshooting.

## Getting started

1. Install and launch WheelPlay on the Android server device.
2. Connect the Android device and your car to the same local network. Enable HTTPS under Settings → Web listeners and trust the app certificate in the car's browser, then open the shown HTTPS address. Video uses WebCodecs, which requires a secure page.
3. Open the shown address in the car's browser, select browser options, and click “启动显示” (Start display). Select “启动后隐藏顶栏并全屏” to request fullscreen when launching and hide the top bar after connecting. Exiting fullscreen restores the top bar; browsers without fullscreen support only hide the bar.
4. Keep WheelPlay in the foreground on Android. Click “连接 iPhone” (Connect iPhone) on the browser’s waiting screen to start the wireless connection, or connect from the app. Complete any device selection or permission prompts on Android and allow CarPlay on the iPhone.
5. Once connected, view and control CarPlay in your car's browser.

Only one browser control session is allowed at a time. The server rejects WebSocket connections whose `Origin` does not match the page, which blocks cross-site attempts from other web pages; any client that can open this page is otherwise trusted.

For 60 fps, enable HTTPS, set the negotiated frame rate to 60, and reopen the HTTPS page. WebCodecs sends the compressed H.264 / HEVC stream over the same control WebSocket as audio; it requires a secure page and a browser with WebCodecs (Tesla Chrome 113+). There is no lower-bandwidth fallback: without HTTPS or WebCodecs the browser shows an error instead of video. Actual frame rate depends on the iPhone's output, network conditions, and browser decoding capabilities; static scenes may run at a lower frame rate. Allowing HEVC on the iPhone enables H.265 forwarding to browsers that support HEVC decoding.

CarPlay audio is always sent to the connected browser; there is no Android speaker fallback. The browser microphone can be enabled or disabled under Settings → Display & Audio, and changes reconnect the iPhone. Disabling microphone input removes the input capability advertisement. Browser microphone input requires a trusted local HTTPS certificate, opening the HTTPS page, and granting microphone permission. See the [browser audio guide](docs/BROWSER_AUDIO.md) (Chinese). If trusted HTTPS or AudioWorklet is unavailable, browser audio and microphone are unavailable.

## Requirements and limitations

Tesla compatibility helps car browsers that block private IP ranges reach the phone's local service through a compatible access address. It also applies to other vehicle brands with the same restriction. Tesla compatibility uses **Root hotspot routing** under Settings → Tesla compatibility and is opt-in. DuckDNS domain/certificate settings live in the same section and publish that same access address.

- **Root hotspot routing** embeds the [VPN Hotspot](https://github.com/Mygod/VPNHotspot) adaptation and requires `su`, `ip`, and `iptables`. Grant Root manually and enable it; the default video entry is `https://100.96.0.1/` (the plain `http://100.96.0.1/` entry only pairs and shows settings). Ports `1–1023` (defaults `80` / `443`) map to the Web listener ports. Startup checks Root only when this mode is enabled. This method does not request VPN consent and does not occupy the VPN slot. It can also answer the car's ConnMan Wi-Fi internet check (`connman.vn.cloud.tesla.cn`) locally, so a hotspot without an uplink is still reported as online while the car reaches WheelPlay.

The mode cleans up when disabled or when the Web service stops. Ordinary LAN Web listeners remain independent; optional HTTPS still requires a trusted certificate. See the [Tesla compatibility guide](docs/TESLA_HTTP.md) (Chinese).

Settings → Web listeners (Web 监听) contains the HTTP port (default `8080`), a self-managed Web hostname shared by HTTP and HTTPS, and an optional HTTPS switch. No Root access is required; listener ports range from `1024–65535`. HTTPS uses the app-managed local certificate; there is no certificate type selector. When DuckDNS is enabled inside Tesla compatibility, the issued Let's Encrypt certificate replaces it automatically. Disabling HTTPS stops TLS while retaining its settings and certificate. See [Web listener and certificate setup](docs/BROWSER_AUDIO.md).

- The server device requires Android 9 or later. The car's browser must support modern web features and touch interaction.
- Video requires a trusted HTTPS page and a browser with WebCodecs (Tesla Chrome 113+); there is no JPEG fallback. Without both, the browser shows an error and no video.
- The server device and car must be able to reach each other over the local network. Wireless connection compatibility depends on the devices' networking capabilities.
- CarPlay audio and microphone input are browser-only. The Android device does not capture audio; browser microphone input requires the car's browser to trust the local certificate and grant microphone permission.
- Only one browser control session is allowed at a time.
- Streaming smoothness and connection stability depend on device performance, network quality, and system background restrictions.
- Use only on trusted local networks. HTTP pages and WebSocket control are unencrypted; video and browser audio require local HTTPS/WSS. Do not bypass certificate warnings or expose the service to the public internet.

## Build

Use Android NDK 28.2.13676358 and CMake 3.22.1. The current build targets **arm64-v8a only** (Xiaomi 8 / Snapdragon 845); 32-bit and emulator ABIs are not packaged. The first build fetches a pinned version of Mbed TLS from GitHub; subsequent builds reuse the local CMake cache. Run:

```sh
./gradlew :mobile:assembleDebug
```

CarPlay authentication resources are built into this deployment. Web and CarPlay read them from private app storage on first launch; there is no runtime import UI. Source-only builds must provide the resources through the build-time asset directory below, otherwise Web and CarPlay remain disabled.

Set `WHEELPLAY_AUTH_ASSETS_DIR` (or `DIPLAY_AUTH_ASSETS_DIR`) to a local asset directory, or use `.local/auth-assets/`, containing `offline-mfi/identity.pk8` and `offline-mfi/certificate.p7b`. These files stay out of Git. Run `./gradlew :mobile:assembleProvisionedDebug` to build and verify both packaged assets against the local input.

## Origins and licensing

This project is based on DiPlay / xcertplay and retains the upstream licenses and third-party notices. See [LICENSE](LICENSE) and [third-party notices](docs/upstream/THIRD_PARTY_NOTICES.md). Third-party assets and components remain subject to their respective licenses.

Native video decryption uses [Mbed TLS 3.6.5](https://github.com/Mbed-TLS/mbedtls/tree/mbedtls-3.6.5), with the version pinned in the CMake configuration. [Native dependency licenses](common/src/main/assets/mbedtls-license.txt) are distributed with the APK.

For streaming diagnostics, inspect `window.wheelplayStats` in the browser console. It includes the server-side source input rate over a 2-second window plus decryption and receive-to-handoff timings; WebCodecs frame counts and the active transport are reported in the status message. Unsupported browser metrics are `null`.
