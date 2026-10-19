# WheelPlay

**EN** | [CN](README.zh-CN.md)

WheelPlay is an Android-hosted CarPlay server for local networks. It streams your iPhone's CarPlay display to your car's browser and lets you control it through the car's touchscreen.

Install the app on an Android device and open the displayed address in your car's browser. No browser-side client installation or additional server is required.

## Features

- **Browser display and touch control**: CarPlay streaming, touch input, and fullscreen display.
- **Selectable streaming modes**: Choose WebCodecs compressed-stream forwarding (H.264 / HEVC over HTTPS) or JPEG compatibility mode in settings.
- **Optional browser audio**: After pairing, the browser can play CarPlay audio. The browser microphone can provide CarPlay voice input through a trusted HTTPS page; Android handles audio and microphone input by default.
- **Easy pairing**: Scan the QR code or enter a code once. Remembered browsers restore pairing automatically and retain a launch button for selecting options before streaming.
- **iPhone connection management**: Wireless CarPlay connection, connection status, and session management.
- **Local network access**: Automatically detects available network addresses for browser access.
- **Background service**: Background operation and automatic connection.
- **Display and audio settings**: Adjustable display size, resolution, frame rate, and audio buffering.
- **CarPlay preferences**: Follow Android's day/night mode or force day/night; select Android/browser audio output and Android/browser/disabled microphone input. Preview custom 0–40% safe margins on each edge; microphone and safe-area changes reconnect the iPhone. Keep CarPlay when browsers go offline, or disconnect the iPhone after 1/5 minutes; browser reconnection cancels the timer.
- **Diagnostic export**: Save diagnostic reports locally for troubleshooting.

## Getting started

1. Install and launch WheelPlay on the Android server device.
2. Connect the Android device and your car to the same local network, then open the address shown in the app in your car's browser.
3. On first use, scan the QR code with WheelPlay and approve pairing, then select browser options and click “启动显示” (Start display). Alternatively, enter the pairing code and click “配对并启动显示” (Pair and start display). On later visits, pairing restores automatically; select options and click Start display. Select “启动后隐藏顶栏并全屏” to request fullscreen when launching and hide the top bar after connecting. Exiting fullscreen restores the top bar; browsers without fullscreen support only hide the bar.
4. Keep WheelPlay in the foreground on Android. Click “连接 iPhone” (Connect iPhone) on the paired browser’s waiting screen to start the wireless connection, or connect from the app. Complete any device selection or permission prompts on Android and allow CarPlay on the iPhone.
5. Once connected, view and control CarPlay in your car's browser.

Manage remembered browsers under Settings → 浏览器设备 (Browser devices) → 连接过的设备 (Connected devices). View names, pairing times, last connection times and addresses; rename or remove one browser, or remove all. Removal immediately disconnects the device and revokes its existing sessions. Browser disconnection and server restarts retain device memory. Up to 32 browsers can be saved; remove an old entry if the limit is reached.

Device memory is scoped to the current browser and origin (scheme, host and port). Clearing or disabling browser storage, switching HTTP / HTTPS, or changing the address requires fresh pairing. Android persists only hashes of random device secrets; the six-digit code is never stored as a remembered credential, and browser credentials are excluded from app backups.

For 60 fps, select WebCodecs under Settings → Display & Audio, enable HTTPS, and set the negotiated frame rate to 60. WebCodecs sends the compressed H.264 / HEVC stream over the same control WebSocket as JPEG and audio; it requires a secure page and a browser with WebCodecs (Tesla Chrome 113+). Apply the settings and reconnect for the changes to take effect. Actual frame rate depends on the iPhone's output, network conditions, and browser decoding capabilities; static scenes may run at a lower frame rate. Allowing HEVC on the iPhone enables H.265 forwarding to browsers that support HEVC decoding. JPEG mode works without HTTPS and without browser codec support.

Select Audio output and Microphone source under Settings → Display & Audio. Output changes apply immediately; microphone source changes reconnect the iPhone. Disabling microphone input stops capture/uplink and removes input capability advertisements. Current routes are shown; unavailable browsers fall back to Android, while disabled microphone input blocks all fallback capture. Browser microphone input requires a trusted local HTTPS certificate, pairing through HTTPS, and granting microphone permission. See the [browser audio guide](docs/BROWSER_AUDIO.md) (Chinese). If trusted HTTPS or AudioWorklet is unavailable, use the Android microphone.

## Requirements and limitations

Tesla compatibility helps car browsers that block private IP ranges reach the phone's local service through a compatible access address. It also applies to other vehicle brands with the same restriction. Choose **No-root VPN (experimental)** or **Root hotspot routing** under Settings → Tesla compatibility → Connection method. Both are opt-in; switching methods disables the mode until explicitly enabled again, retaining each method's saved address. DuckDNS domain/certificate settings live in the same section and publish that same access address.

- **No-root VPN** requires Android VPN consent, with no Root or Shizuku. Connect the Tesla to this phone's system hotspot and try `http://6.6.6.6:8080`, using the actual Web listener port; no low-port mapping is provided. The shared VPN service retains the IPv4 alias TUN. Long-lived old TUN retention is not guaranteed by Android's API contract and may fail across devices or system updates. “Address configured” confirms the address exists on the phone; hotspot access, Tesla browser behavior, and WebCodecs support require device testing.
- **Root hotspot routing** embeds the [VPN Hotspot](https://github.com/Mygod/VPNHotspot) adaptation and requires `su`, `ip`, and `iptables`. Grant Root manually and enable it; the default entry is `http://100.96.0.1/`. Ports `1–1023` (defaults `80` / `443`) map to the Web listener ports. Startup checks Root only for an enabled Root mode. This method does not request VPN consent.

Both methods clean up when disabled or when the Web service stops. Ordinary LAN Web listeners remain independent; optional HTTPS still requires a trusted certificate. See the [Tesla compatibility guide](docs/TESLA_HTTP.md) (Chinese).

Settings → Web listeners (Web 监听) contains the HTTP port (default `8080`), a self-managed Web hostname shared by HTTP and HTTPS, and an optional HTTPS switch. No Root access is required; listener ports range from `1024–65535`. HTTPS uses the app-managed local certificate; there is no certificate type selector. When DuckDNS is enabled inside Tesla compatibility, the issued Let's Encrypt certificate replaces it automatically. Disabling HTTPS stops TLS while retaining its settings and certificate. See [Web listener and certificate setup](docs/BROWSER_AUDIO.md).

- The server device requires Android 9 or later. The car's browser must support modern web features and touch interaction.
- The server device and car must be able to reach each other over the local network. Wireless connection compatibility depends on the devices' networking capabilities.
- Android handles audio and microphone input by default; browser routing must be enabled manually. Browser microphone input also requires the car's browser to allow installing and trusting a local certificate.
- Only one browser control session is allowed at a time.
- Streaming smoothness and connection stability depend on device performance, network quality, and system background restrictions.
- Use only on trusted local networks. HTTP pairing, WebSocket control, and JPEG transport are unencrypted. Browser microphone input and WebCodecs video use local HTTPS/WSS. Do not bypass certificate warnings or expose the service to the public internet.

## Build

Use Android NDK 28.2.13676358 and CMake 3.22.1. The first build fetches a pinned version of Mbed TLS from GitHub; subsequent builds reuse the local CMake cache. Run:

```sh
./gradlew :mobile:assembleDebug
```

CarPlay authentication resources are built into this deployment. Web and CarPlay read them from private app storage on first launch; there is no runtime import UI. Source-only builds must provide the resources through the build-time asset directory below, otherwise Web and CarPlay remain disabled.

Set `WHEELPLAY_AUTH_ASSETS_DIR` (or `DIPLAY_AUTH_ASSETS_DIR`) to a local asset directory, or use `.local/auth-assets/`, containing `offline-mfi/identity.pk8` and `offline-mfi/certificate.p7b`. These files stay out of Git. Run `./gradlew :mobile:assembleProvisionedDebug` to build and verify both packaged assets against the local input.

## Origins and licensing

This project is based on DiPlay / xcertplay and retains the upstream licenses and third-party notices. See [LICENSE](LICENSE) and [third-party notices](docs/upstream/THIRD_PARTY_NOTICES.md). Third-party assets and components remain subject to their respective licenses.

Native video decryption uses [Mbed TLS 3.6.5](https://github.com/Mbed-TLS/mbedtls/tree/mbedtls-3.6.5), with the version pinned in the CMake configuration. [Native dependency licenses](common/src/main/assets/mbedtls-license.txt) are distributed with the APK.

For streaming diagnostics, inspect `window.wheelplayStats` in the browser console. It includes the server-side source input rate over a 2-second window plus decryption and receive-to-handoff timings; WebCodecs frame counts and the active transport are reported in the status message. Unsupported browser metrics are `null`.
