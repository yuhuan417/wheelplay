// SPDX-License-Identifier: AGPL-3.0-only
// UI copy and visual language adapted from DiAuto. See docs/THIRD_PARTY_NOTICES.md.
package com.shilapi.xcertplay

import android.Manifest
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textfield.TextInputEditText
import com.shilapi.xcertplay.web.WebSession
import com.shilapi.xcertplay.web.RootAccess
import com.shilapi.xcertplay.web.LanAddresses
import com.shilapi.xcertplay.web.TeslaHttpCompatibility
import com.shilapi.xcertplay.web.WebListenSettings
import com.shilapi.xcertplay.web.BrowserExperienceLink
import com.shilapi.xcertplay.network.CarPlayVpnService
import com.shilapi.xcertplay.network.TeslaHttpMode
import io.github.g00fy2.quickie.QRResult
import io.github.g00fy2.quickie.ScanQRCode
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.widget.doAfterTextChanged
import com.shilapi.xcertplay.host.R
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Connection and preference pages in the shared server navigation. */
class DiPlayActivity : AppCompatActivity() {
    private val ui by lazy { ServerUi(this) }
    private val handler = Handler(Looper.getMainLooper())
    private var page = "service"
    private lateinit var shell: ServerUi.Shell
    private var webAddress: TextView? = null
    private var webInterface: TextView? = null
    private var teslaStatus: TextView? = null
    private var renderedRootState: Triple<Boolean, Boolean, String>? = null
    private var webBusy = false
    private var awaitingTeslaVpnConsent = false
    private val teslaVpnConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val requested = awaitingTeslaVpnConsent
        awaitingTeslaVpnConsent = false
        if (requested && TeslaHttpCompatibility.config(this).mode == TeslaHttpMode.VPN) {
            applyTeslaHttpEnabled(result.resultCode == RESULT_OK)
            if (result.resultCode != RESULT_OK) toast("VPN 授权已取消，免 Root 模式保持关闭")
        }
    }
    private var otherAddresses: LinearLayout? = null
    private var otherAddressesToggle: ServerUi.DisclosureRow? = null
    private var addressesExpanded = false
    private var displayedAddresses: List<LanAddresses.Entry>? = null
    private var webCode: TextView? = null
    private var webPairingControls: LinearLayout? = null
    private var quickBrowserButton: Button? = null
    private var webViewer: TextView? = null
    private var webStage: TextView? = null
    private var sessionConnectButton: Button? = null
    private var setupError: String? = null
    private var status: TextView? = null
    private var connectButton: com.google.android.material.button.MaterialButton? = null
    private var disconnectButton: Button? = null
    private var lastRunning: Boolean? = null
    private var pendingConnect = false
    private var initialLaunch = true
    private var exportInProgress = false
    private var exportButton: Button? = null
    private var audioOutputStatus: TextView? = null
    private var microphoneSourceStatus: TextView? = null
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        connect()
    }
    private val tick = object : Runnable {
        override fun run() { refreshStatus(); refreshService(); refreshRootSettings(); refreshAudioRoutes(); handler.postDelayed(this, 1000) }
    }
    private val bluetoothPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) choosePhone() else permissionHelp("附近设备", "请允许访问附近设备，以连接已配对的 iPhone。")
    }
    private val export = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) exportDiagnostics(uri)
    }

    private val scan = registerForActivityResult(ScanQRCode()) { result ->
        val payload = when (result) {
            is QRResult.QRSuccess -> result.content.rawValue?.take(257)
            QRResult.QRUserCanceled -> null
            QRResult.QRMissingPermission -> {
                permissionHelp("相机", "扫码配对需要相机权限，也可以在车机网页手动输入配对码。")
                null
            }
            is QRResult.QRError -> {
                toast("扫码失败，请检查相机权限或关闭正在使用相机的其他应用。")
                null
            }
        } ?: return@registerForActivityResult
        val request = WebSession.inspectPairing(payload)
        if (request == null) {
            toast("二维码无效或已过期，请刷新此服务端的车机网页后重试。")
        } else {
            MaterialAlertDialogBuilder(this).setTitle("允许这台车机连接？")
                .setMessage("浏览器地址：${request.peer}\n授权后，该浏览器可以显示 CarPlay 并发送触控操作。")
                .setNegativeButton("取消", null)
                .setPositiveButton("允许连接") { _, _ ->
                    toast(if (WebSession.approvePairing(payload)) "已授权，车机网页将自动连接" else "请求已过期，请刷新网页重新扫码")
                }.show()
        }
    }
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scanPairing() else permissionHelp("相机", "扫码配对需要相机权限，也可以在车机网页手动输入配对码。")
    }
    private fun scanPairing() {
        if (!WebSession.running) { toast("请等待 Web 服务启动后再扫码"); return }
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            toast("此设备没有相机，请在车机网页输入配对码"); return
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraPermission.launch(Manifest.permission.CAMERA); return
        }
        scan.launch(null)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (TeslaHttpCompatibility.config(this).let { it.enabled && it.mode == TeslaHttpMode.ROOT }) RootAccess.checkOnStartup()
        ServerWindow.showSystemBars(window)
        refreshAuthentication()
        awaitingTeslaVpnConsent = savedInstanceState?.getBoolean("awaitingTeslaVpnConsent") ?: false
        page = savedInstanceState?.getString("page") ?: intent.getStringExtra("page")
            ?: if (setupError == null) "service" else "settings"
        createShell()
        startWebServiceIfReady()
        com.shilapi.xcertplay.domain.OwnDomainManager.ensureStarted(this)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (page != "service") navigate("service") else moveTaskToBack(true)
            }
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        navigate(intent.getStringExtra("page") ?: "service")
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("page", page)
        outState.putBoolean("awaitingTeslaVpnConsent", awaitingTeslaVpnConsent)
        super.onSaveInstanceState(outState)
    }
    override fun onConfigurationChanged(newConfig: Configuration) { super.onConfigurationChanged(newConfig); createShell() }
    override fun onResume() {
        super.onResume(); refreshAuthentication(); render(); ServerWindow.showSystemBars(window); handler.removeCallbacks(tick); handler.post(tick)
        WebSession.setPhoneConnectHandler {
            if (setupError != null) setupError!!
            else {
                connect()
                "已请求连接 iPhone；如有提示，请在 Android 上完成设备选择或授权，并在 iPhone 上允许 CarPlay"
            }
        }
        // Back from the car settings: refresh the car hotspot reminder on the home page.
        if (!initialLaunch) { refreshPhone(); refreshService() }
        if (initialLaunch) {
            initialLaunch = false
            if (setupError == null &&
                !CarPlayBackgroundSession.hasSession() &&
                !CarPlayBackgroundSession.isUnpluggedOnBattery(this) &&
                DiPlayPreferences.autoConnect(this) && intent.getStringExtra("page") == null) {
                handler.post { connect() }
            }
        }
    }
    override fun onPause() { WebSession.setPhoneConnectHandler(null); handler.removeCallbacks(tick); super.onPause() }
    override fun onDestroy() { super.onDestroy() }

    private fun normalizedPage(value: String) = when (value) {
        "home" -> "phone"
        "about" -> "settings"
        "phone", "settings" -> value
        else -> "service"
    }

    private fun navigate(destination: String) {
        val target = normalizedPage(destination)
        shell.select(target)
        page = target
    }

    private fun createShell() {
        val initial = normalizedPage(page)
        shell = ui.Shell(initial) { page = it }
        page = initial
        shell.put("service", ui.content(::service))
        refreshPhone()
        shell.put("settings", ui.content(::settings))
        setContentView(shell.root)
        refreshService()
    }

    private fun refreshPhone() {
        status = null; connectButton = null; disconnectButton = null; lastRunning = null
        shell.put("phone", ui.content(::home))
        refreshStatus()
    }

    private fun render() {
        refreshPhone()
        exportButton = null
        teslaStatus = null
        shell.put("settings", ui.content(::settings))
    }

    private fun service(content: LinearLayout) {
        content.addView(label(getString(R.string.app_name), 21, TEXT, true).apply { setPadding(0, 0, 0, dp(24)) })
        content.addView(label("在车机浏览器中使用 CarPlay", 28, TEXT, true))
        content.addView(space(24))
        ui.card(content, "车机访问", R.drawable.ic_server_server) { body ->
            body.addView(label("局域网地址", 14, MUTED))
            webAddress = label("正在启动服务…", 21, ACCENT, true).apply {
                setTextIsSelectable(true); setPadding(0, dp(8), 0, dp(8))
            }
            body.addView(webAddress)
            webInterface = label("", 14, MUTED)
            body.addView(webInterface)
            quickBrowserButton = button("在本机浏览器中体验", false, R.drawable.ic_server_launch) {
                openLocalBrowserExperience()
            }.apply { isEnabled = false }
            body.addView(quickBrowserButton, ui.secondaryButtonLayout(12))
            otherAddressesToggle = ui.DisclosureRow("其他地址") {
                addressesExpanded = !addressesExpanded
                updateAddressExpansion(animate = true)
            }.apply { visibility = View.GONE }
            body.addView(otherAddressesToggle, ui.secondaryButtonLayout(4))
            otherAddresses = column().apply { visibility = View.GONE }
            body.addView(otherAddresses)
            displayedAddresses = null
        }
        ui.card(content, "浏览器配对", R.drawable.ic_server_qr) { body ->
            webPairingControls = column().apply {
                addView(label("扫描车机网页上的二维码，即可授权显示与触控。", 16, MUTED))
                addView(button("扫描二维码配对", true, R.drawable.ic_server_scan) { scanPairing() }, matchButton(12))
                addView(label("或在网页输入配对码", 14, MUTED).apply { setPadding(0, dp(16), 0, 0) })
                webCode = label("— — — — — —", 32, TEXT, true).apply {
                    letterSpacing = .12f; setTextIsSelectable(true); setPadding(0, dp(8), 0, dp(16))
                }
                addView(webCode)
            }
            body.addView(webPairingControls)
            webViewer = label("等待车机浏览器连接", 16, MUTED)
            body.addView(webViewer)
        }
        ui.card(content, "iPhone 会话", R.drawable.ic_server_phone) { body ->
            webStage = label("等待连接 iPhone", 16, TEXT)
            body.addView(webStage)
            sessionConnectButton = button("连接", false, R.drawable.ic_server_phone) {
                if (!CarPlayBackgroundSession.hasSession() || CarPlayBackgroundSession.standby) connect()
            }.apply { visibility = View.GONE }
            body.addView(sessionConnectButton, ui.secondaryButtonLayout(12))
            body.addView(button("管理 iPhone 连接", false) { navigate("phone") }, ui.secondaryButtonLayout(16))
        }
        content.addView(label("音频与麦克风默认使用此设备，可在 App 设置中分别启用浏览器转发。", 14, MUTED))
        content.addView(button("停止服务", false) {
            startService(Intent(this, DiPlaySessionService::class.java).setAction(DiPlaySessionService.ACTION_STOP))
            finishAndRemoveTask()
        }, ui.secondaryButtonLayout(24))
    }

    private fun refreshService() {
        val addresses = if (WebSession.running && WebSession.error == null) WebSession.addresses(this) else emptyList()
        val primary = addresses.firstOrNull()
        val addressText = if (!WebSession.running && setupError != null) "内置认证资源不可用，请重新安装应用"
            else if (!WebSession.running && CarPlayBackgroundSession.standby) "待机省电中（等待 iPhone 蓝牙或随车通电唤醒）"
            else WebSession.error ?: if (WebSession.running) primary?.url
                ?: "未找到局域网地址，请连接 Wi-Fi" else "正在启动服务…"
        if (webAddress?.text?.toString() != addressText) webAddress?.text = addressText
        webInterface?.text = primary?.let { it.description + if (it.hostname.isNotEmpty()) "\n直接 IP：${it.ipUrl}" else "" }.orEmpty()
        val compatibility = TeslaHttpCompatibility.status
        val teslaConfig = TeslaHttpCompatibility.config(this)
        teslaStatus?.text = if (!teslaConfig.enabled) "已关闭"
            else if (!WebSession.running && setupError != null) "认证资源不可用"
            else compatibility.error ?: compatibility.address?.let {
                if (compatibility.mode == TeslaHttpMode.VPN) "免 Root 地址已配置：$it；热点与车机访问需实测"
                else "Root 热点路由已启用：$it（${compatibility.downstreams.joinToString()}）；请用车机测试访问"
            } ?: "正在准备${teslaConfig.mode.label}…"
        webInterface?.visibility = if (primary == null) View.GONE else View.VISIBLE
        if (displayedAddresses != addresses) {
            displayedAddresses = addresses
            otherAddresses?.removeAllViews()
            addresses.drop(1).forEachIndexed { index, entry ->
                otherAddresses?.addView(label(entry.description, 14, MUTED))
                otherAddresses?.addView(label(entry.url, 16, TEXT).apply {
                    setTextIsSelectable(true); setPadding(0, dp(4), 0, if (index < addresses.size - 2) dp(16) else 0)
                })
            }
            updateAddressExpansion()
        }
        webCode?.text = if (WebSession.running) WebSession.code else "— — — — — —"
        quickBrowserButton?.isEnabled = WebSession.running && BrowserExperienceLink.local(WebSession.code, WebSession.httpPort) != null
        val viewerConnected = WebSession.hasViewer
        webPairingControls?.visibility = if (viewerConnected) View.GONE else View.VISIBLE
        webViewer?.text = if (!WebSession.running && setupError != null) "认证资源不可用"
            else if (viewerConnected) "车机浏览器已连接" else "等待车机浏览器连接"
        webStage?.text = setupError ?: if (WebSession.videoActive) "CarPlay 画面正在串流" else WebSession.stage
    }

    private fun openLocalBrowserExperience() {
        val url = BrowserExperienceLink.local(WebSession.code, WebSession.httpPort)
        if (!WebSession.running || url == null) {
            toast("请等待 Web 服务启动后再打开")
            return
        }
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { toast("未找到可打开网页的浏览器") }
    }

    private fun updateAddressExpansion(animate: Boolean = false) {
        val count = ((displayedAddresses?.size ?: 0) - 1).coerceAtLeast(0)
        otherAddressesToggle?.visibility = if (count > 0) View.VISIBLE else View.GONE
        otherAddressesToggle?.setExpanded(addressesExpanded, animate)
        otherAddresses?.visibility = if (count > 0 && addressesExpanded) View.VISIBLE else View.GONE
    }

    private fun home(content: LinearLayout) {
        content.addView(label("连接 iPhone", 30, TEXT, true))
        content.addView(label("此设备接收 CarPlay，车机浏览器负责显示与触控。", 16, MUTED).apply { setPadding(0, dp(8), 0, dp(24)) })
        status = label("等待连接", 20, ACCENT, true)
        ui.card(content, "连接状态", R.drawable.ic_server_phone) { it.addView(status) }
        ui.card(content, "无线连接", R.drawable.ic_server_wifi) { body ->
            body.addView(label("先将 iPhone 与这台 Android 设备完成蓝牙配对，并保持双方蓝牙和 Wi-Fi 开启。", 15, MUTED))
            body.addView(ui.preference("iPhone", selectedPhoneName()) { choosePhone() }, matchButton())
            connectButton = button("连接 iPhone", true, R.drawable.ic_server_phone) { connect() }
            body.addView(connectButton, matchButton(12))
            disconnectButton = button("断开 iPhone", false) {
                disconnectButton?.isEnabled = false
                CarPlayBackgroundSession.stop { runOnUiThread {
                    startWebServiceIfReady()
                    WebSession.stage = "iPhone 已断开"
                    refreshStatus(); refreshService()
                } }
            }.apply { visibility = View.GONE }
            body.addView(disconnectButton, ui.secondaryButtonLayout(12))
        }
        setupError?.let { content.addView(label(it, 16, WARNING)) }
    }

    private fun settings(content: LinearLayout) {
        content.addView(label("设置", 30, TEXT, true))
        content.addView(label("昼夜模式、音频输出和浏览器离线策略立即生效。修改画面、麦克风来源或串流参数会重新连接；其他设置在下次连接生效。", 17, MUTED).apply { setPadding(0, dp(8), 0, dp(24)) })
        section(content, "自动连接") { card ->
            toggle(card, "打开应用时自动连接", "使用上次的连接方式和已选择的 iPhone。", DiPlayPreferences.autoConnect(this)) { DiPlayPreferences.saveAutoConnect(this, it) }
            toggle(card, "设备启动后打开", "需要系统允许应用自启动。", AirPlayPersistence.loadAutoStartOnBoot(this)) { AirPlayPersistence.saveAutoStartOnBoot(this, it) }
            card.addView(ui.preference("iPhone", selectedPhoneName()) { choosePhone() }, matchButton())
        }
        section(content, "无线连接") { card -> wirelessLinkControls(card) }
        section(content, "浏览器设备") { card ->
            card.addView(label("配对后的浏览器会被记住，下次打开时自动恢复配对；选择启动选项后，点击网页上的「启动显示」。", 16, MUTED))
            card.addView(ui.preference("连接过的设备", "查看、重命名或移除浏览器") { showBrowserDevices() })
            val policies = BrowserOfflinePolicy.entries
            experienceChoice(card, "浏览器离线后的处理", policies.map { it.label },
                policies.indexOf(AirPlayPersistence.loadBrowserOfflinePolicy(this))) {
                WebSession.setBrowserOfflinePolicy(this, policies[it])
            }
            card.addView(label("计时从 CarPlay 会话启动且无浏览器连接时开始；浏览器重连会取消计时。超时只断开 iPhone，Web 服务保持运行。", 14, MUTED))
        }
        section(content, "Web 监听") { card ->
            card.addView(ui.preference("HTTP 端口", WebListenSettings.httpPort(this).toString()) {
                textInput("HTTP 监听端口（1024–65535）", WebListenSettings.httpPort(this).toString(), false) { value ->
                    val port = value.toIntOrNull()
                    if (port == null || port !in 1024..65535) toast("HTTP 监听端口必须为 1024–65535")
                    else configureWeb("HTTP 端口已保存，浏览器请使用新地址重新连接") { WebSession.setHttpPort(this, port) }
                }
            }.apply { isEnabled = !webBusy })
            card.addView(ui.preference("Web 域名（可选）", WebListenSettings.hostname(this).ifEmpty { "使用 IP 地址" }) {
                textInput("Web 域名（可选）", WebListenSettings.hostname(this), false) { value ->
                    configureWeb("Web 域名已保存") { WebSession.setHostname(this, value.lowercase(Locale.ROOT)) }
                }
            }.apply { isEnabled = !webBusy })
            card.addView(label("监听设置无需 Root。Web 域名同时用于 HTTP 和 HTTPS，需解析到实际访问的 IP；填写不会自动配置 DNS。HTTPS 默认使用本地证书；启用 Tesla 兼容中的 DuckDNS 后会自动安装 Let's Encrypt 证书。", 14, MUTED))
            toggle(card, "启用 HTTPS", "为浏览器提供 HTTPS 访问；浏览器麦克风需要可信证书。", WebListenSettings.httpsEnabled(this), enabled = !webBusy) {
                configureWeb(if (it) "HTTPS 已开启" else "HTTPS 已关闭") { WebSession.setHttpsEnabled(this, it) }
            }
            if (WebListenSettings.httpsEnabled(this)) {
                card.addView(ui.preference("Web HTTPS 端口", WebListenSettings.httpsPort(this).toString()) {
                    textInput("HTTPS 监听端口（1024–65535）", WebListenSettings.httpsPort(this).toString(), false) { value ->
                        val port = value.toIntOrNull()
                        if (port == null || port !in 1024..65535) toast("HTTPS 监听端口必须为 1024–65535")
                        else configureWeb("HTTPS 端口已保存，浏览器请使用新端口") { WebSession.setHttpsPort(this, port) }
                    }
                }.apply { isEnabled = !webBusy })
            }
        }
        section(content, "Tesla 兼容（实验）") { card ->
            renderedRootState = rootState()
            val config = TeslaHttpCompatibility.config(this)
            val compatibilityDescription = label("主要用于帮助屏蔽私有网段访问的车机绕过地址限制，访问手机上的本地服务。除 Tesla 外，有同类限制的其他品牌车机也可尝试。", 14, MUTED)
            card.addView(ui.preference("连接方案", config.mode.label) {
                MaterialAlertDialogBuilder(this).setTitle("Tesla 连接方案")
                    .setSingleChoiceItems(TeslaHttpMode.entries.map { it.label }.toTypedArray(), config.mode.ordinal) { dialog, index ->
                        val selected = TeslaHttpMode.entries[index]
                        if (selected != config.mode) {
                            TeslaHttpCompatibility.save(this, TeslaHttpCompatibility.config(this).copy(mode = selected, enabled = false))
                            com.shilapi.xcertplay.domain.OwnDomainManager.requestCheck(this, force = true)
                            render(); refreshService()
                        }
                        dialog.dismiss()
                    }.setNegativeButton("取消", null).show()
            }.apply { isEnabled = !webBusy && !awaitingTeslaVpnConsent })
            if (config.mode == TeslaHttpMode.VPN) {
                card.addView(compatibilityDescription)
                toggle(card, "启用免 Root Tesla 兼容", "需要 Android VPN 授权，无需 Root 或 Shizuku；可能与其他 VPN 冲突。", config.enabled,
                    enabled = !webBusy && !awaitingTeslaVpnConsent) { setTeslaHttpEnabled(it) }
                teslaStatus = label("", 14, MUTED)
                card.addView(teslaStatus)
                card.addView(ui.preference("访问 IP 地址", config.vpnAddress) {
                    textInput("免 Root 访问 IP 地址", config.vpnAddress, false) { value ->
                        runCatching { TeslaHttpCompatibility.save(this, TeslaHttpCompatibility.config(this).copy(vpnAddress = value)) }
                            .onSuccess {
                                com.shilapi.xcertplay.domain.OwnDomainManager.requestCheck(this, force = true)
                                render(); refreshService()
                            }.onFailure { toast(it.message ?: "访问 IP 无效") }
                    }
                }.apply { isEnabled = !webBusy && !awaitingTeslaVpnConsent })
                card.addView(label("请让车机连接此手机的系统热点，使用 http://${config.vpnAddress}:${WebListenSettings.httpPort(this)}。此方案可能不兼容部分手机或车机，系统更新后可能失效。", 14, MUTED))
                card.addView(label("直接使用 Web 监听端口，无低位端口映射。可选 HTTPS 仍需车机信任证书。", 14, MUTED))
                card.addView(button("授权或重试 VPN", false) { setTeslaHttpEnabled(true) }
                    .apply { isEnabled = !webBusy && !awaitingTeslaVpnConsent }, ui.secondaryButtonLayout(12))
                card.addView(button("打开热点设置", false) { openCarWifiSettings() }, ui.secondaryButtonLayout(12))
            } else {
                val rootRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                rootRow.addView(button("请求 Root 权限", false) {
                    configureWeb("Root 已授权") { RootAccess.request().also { TeslaHttpCompatibility.retry() } }
                }.apply { isEnabled = !webBusy && !RootAccess.checking })
                rootRow.addView(label(RootAccess.status, 14, MUTED).apply { maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END },
                    LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
                card.addView(rootRow)
                card.addView(compatibilityDescription)
                if (RootAccess.granted) {
                    toggle(card, "启用 Tesla 兼容", "将 HTTP / HTTPS 低位端口及自定义 IP 共享给热点设备。", config.enabled, enabled = !webBusy) {
                        setTeslaHttpEnabled(it)
                    }
                    teslaStatus = label("", 14, MUTED)
                    card.addView(teslaStatus)
                    card.addView(ui.preference("映射 HTTP 端口", "${config.httpMappingPort} → ${WebListenSettings.httpPort(this)}") {
                        textInput("映射 HTTP 端口（1–1023）", config.httpMappingPort.toString(), false) { value ->
                            saveTeslaMapping(value, https = false)
                        }
                    }.apply { isEnabled = !webBusy })
                    card.addView(ui.preference("映射 HTTPS 端口", "${config.httpsMappingPort} → " +
                        if (WebListenSettings.httpsEnabled(this)) WebListenSettings.httpsPort(this).toString() else "HTTPS 已关闭") {
                        textInput("映射 HTTPS 端口（1–1023）", config.httpsMappingPort.toString(), false) { value ->
                            saveTeslaMapping(value, https = true)
                        }
                    }.apply { isEnabled = !webBusy })
                    card.addView(ui.preference("访问 IP 地址", config.address) {
                        textInput("访问 IP 地址", config.address, false) { value ->
                            runCatching { TeslaHttpCompatibility.save(this, TeslaHttpCompatibility.config(this).copy(address = value)) }
                                .onSuccess { render(); refreshService() }.onFailure { toast(it.message ?: "访问 IP 无效") }
                        }
                    }.apply { isEnabled = !webBusy })
                    card.addView(label("映射只作用于此访问 IP，不改变 Web 监听端口。HTTPS 映射仅在 HTTPS 开启时生效；请开启系统热点，并让 HTTPS 证书覆盖访问 IP 或域名。", 14, MUTED))
                    card.addView(button("重新启动 Root 路由", false) { TeslaHttpCompatibility.retry() }, ui.secondaryButtonLayout(12))
                    card.addView(button("打开热点设置", false) { openCarWifiSettings() }, ui.secondaryButtonLayout(12))
                }
                }
            card.addView(label("DuckDNS 域名与自动证书", 16, TEXT, true).apply {
                setPadding(0, dp(24), 0, dp(4))
            })
            ownDomainControls(card)
        }
        section(content, "画面与音频") { card ->
            val appearances = CarPlayAppearance.entries
            experienceChoice(card, "CarPlay 昼夜模式", appearances.map { it.label },
                appearances.indexOf(AirPlayPersistence.loadAppearance(this))) {
                WebSession.setAppearance(this, appearances[it])
            }
            card.addView(label("即时发送给 iPhone；跟随 Android 时使用此服务设备的系统昼夜模式。", 14, MUTED))
            carPlaySizeControl(card)
            safeAreaControl(card)
            toggle(card, "自适应浏览器尺寸", "按车机浏览器的实际画面比例协商 CarPlay 分辨率；浏览器尺寸变化时会重新连接。", AirPlayPersistence.loadAdaptiveBrowserSize(this)) {
                AirPlayPersistence.saveAdaptiveBrowserSize(this, it)
                WebSession.setAdaptiveBrowserSize(it)
            }
            // 0.3x is CarPlayDisplayScale.MIN_TENTHS, so the list reaches the supported floor.
            val resolutionScales = listOf(10, 8, 6, 3)
            choice(card, "分辨率", listOf("原始分辨率", "80% · 降低负载", "60% · 更低负载", "30% · 最低负载"),
                resolutionScales.indexOf(AirPlayPersistence.loadDisplayScaleTenths(this)).coerceAtLeast(0)) {
                AirPlayPersistence.saveDisplayScaleTenths(this, resolutionScales[it])
            }
            val bufferPresets = com.shilapi.xcertplay.media.MediaAudioBuffer.presets
            choice(card, "音频缓冲", listOf("300 ms · 默认", "500 ms", "1000 ms · 更稳定"),
                bufferPresets.indexOf(AirPlayPersistence.loadMediaBufferMillis(this)).coerceAtLeast(0)) {
                WebSession.setMediaBufferMillis(this, bufferPresets[it])
            }
            val outputs = CarPlayAudioOutput.entries
            experienceChoice(card, "音频输出", outputs.map { it.label }, if (AirPlayPersistence.loadBrowserAudioPlayback(this)) 1 else 0) {
                WebSession.setBrowserAudioPlayback(this, outputs[it] == CarPlayAudioOutput.BROWSER)
                refreshAudioRoutes()
            }
            toggle(card, "CarPlay 仅视频（音频由 iPhone 输出）",
                "不再向 iPhone 声明 CarPlay 音频输出能力，音频由 iPhone 自身路由（例如已配对的蓝牙车机）；修改后重新连接生效。",
                AirPlayPersistence.loadDisableAudioOutput(this)) { enabled ->
                AirPlayPersistence.saveDisableAudioOutput(this, enabled)
                reconnectForPreference()
                refreshAudioRoutes()
            }
            audioOutputStatus = label("", 14, MUTED).also(card::addView)
            card.addView(label("即时切换。浏览器未就绪或离线时回退 Android；浏览器可能需要点击页面才能播放声音。开启“仅视频”后此选项不再生效。", 14, MUTED))
            val microphones = CarPlayMicrophoneSource.entries
            experienceChoice(card, "麦克风来源", microphones.map { it.label },
                microphones.indexOf(AirPlayPersistence.loadMicrophoneSource(this)), reconnect = true) {
                WebSession.setMicrophoneSource(this, microphones[it])
                refreshAudioRoutes()
            }
            microphoneSourceStatus = label("", 14, MUTED).also(card::addView)
            card.addView(label("修改后重新连接。浏览器麦克风需要可信 HTTPS 和授权，未就绪时回退 Android；禁用会停止上传并取消麦克风能力声明。", 14, MUTED))
            refreshAudioRoutes()
            choice(card, "协商帧率", listOf("30 fps · 较低负载", "60 fps · 更流畅"), if (AirPlayPersistence.loadFps(this) == 60) 1 else 0) { AirPlayPersistence.saveFps(this, if (it == 1) 60 else 30) }
            val technologies = com.shilapi.xcertplay.web.StreamTechnology.entries
            choice(card, "串流技术", technologies.map { it.label },
                technologies.indexOf(AirPlayPersistence.loadStreamTechnology(this))) {
                AirPlayPersistence.saveStreamTechnology(this, technologies[it])
            }
            card.addView(label("JPEG 兼容性最好。WebCodecs 通过 HTTPS 页面直传 H.264 / HEVC 压缩流（Tesla Chrome 113+），追求 60 fps 请将协商帧率设为 60；实际帧率以网页显示为准。", 14, MUTED))
            card.addView(label("短暂断流时保留最后一帧并等待恢复，持续异常超过所选时间才回退 JPEG。不增加视频缓冲；不支持的编码或彻底失败的连接仍立即回退。下次浏览器连接生效。", 14, MUTED))
            toggle(card, "允许 iPhone 使用 HEVC", "下次连接生效。JPEG 模式由 Android 解码；WebCodecs 模式需要浏览器支持 HEVC 解码。关闭时使用 H.264。", AirPlayPersistence.loadHevcEnabled(this)) { AirPlayPersistence.saveHevcEnabled(this, it) }
            toggle(card, "右舵布局", "将 CarPlay 控件移到右侧。", AirPlayPersistence.loadRightHandDrive(this)) { AirPlayPersistence.saveRightHandDrive(this, it) }

        }
        section(content, "权限与连接帮助") { card ->
            card.addView(label("附近设备权限用于连接 iPhone，麦克风用于 Siri 和通话。旧版 Android 的无线连接还需要定位权限。", 16, MUTED))
            card.addView(ui.preference("浏览器 HTTPS 证书", "查看本设备证书指纹") {
                val fingerprint = WebSession.tls?.fingerprint
                MaterialAlertDialogBuilder(this)
                    .setTitle("浏览器 HTTPS 证书指纹")
                    .setMessage(if (!WebListenSettings.httpsEnabled(this)) "HTTPS 已关闭，请在 Web 监听中开启。"
                        else if (fingerprint == null) "HTTPS 服务正在准备或启动失败，请稍后重试。"
                        else "请在车机安装证书前，与浏览器页面显示的 SHA-256 指纹逐字核对：\n\n$fingerprint")
                    .setPositiveButton("关闭", null)
                    .show()
            })
            card.addView(button("应用权限", false) { openSystem(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }, ui.secondaryButtonLayout(16))
            card.addView(button("系统蓝牙设置", false) { openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }, ui.secondaryButtonLayout(12))
            card.addView(button("无线连接帮助", false) { wirelessHelp() }, ui.secondaryButtonLayout(12))
        }
        section(content, "关于与诊断") { card ->
            card.addView(label("${getString(R.string.app_name)} · ${version()}", 17, TEXT, true))
            exportButton = button(if (exportInProgress) "正在保存报告…" else "保存诊断报告", false) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) exportDiagnostics()
                else chooseReportDestination()
            }.apply { isEnabled = !exportInProgress }
            card.addView(exportButton, ui.secondaryButtonLayout(12))
            val destination = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) "报告保存到 Downloads/WheelPlay。" else "请选择报告保存位置。"
            card.addView(label(destination + "不会自动上传，报告排除协议载荷和凭据。", 14, MUTED).apply { setPadding(0, dp(12), 0, 0) })
            card.addView(label("基于 xcertplay / DiPlay，保留 GPL-3.0 与 AGPL-3.0 许可声明。CarPlay 与 NIO 图标属于各自权利人。", 13, MUTED).apply { setPadding(0, dp(20), 0, 0) })
        }
        section(content, "开源") { card ->
            card.addView(ui.preference("源码地址", "github.com/fython/wheelplay") {
                runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/fython/wheelplay")))
                }.onFailure { toast("未找到可打开网页的浏览器") }
            })
        }
    }

    private fun experienceChoice(card: LinearLayout, title: String, options: List<String>, initial: Int,
        reconnect: Boolean = false, save: (Int) -> Unit) {
        var selection = initial
        val control = ui.preference(title, options[selection]) {}
        control.setOnClickListener {
            var pending = selection
            MaterialAlertDialogBuilder(this).setTitle(title)
                .setSingleChoiceItems(options.toTypedArray(), selection) { _, index -> pending = index }
                .setPositiveButton("保存") { _, _ ->
                    if (pending != selection) {
                        save(pending)
                        selection = pending
                        control.setValue(options[selection])
                        if (reconnect) reconnectForPreference()
                    }
                }.setNegativeButton("取消", null).show()
        }
        card.addView(control, matchButton())
    }

    private fun reconnectForPreference() {
        if (CarPlayBackgroundSession.hasSession()) connect()
        else toast("已保存，下次连接 iPhone 生效")
    }

    private fun refreshAudioRoutes() {
        val session = CarPlayBackgroundSession.snapshot()
        audioOutputStatus?.text = when {
            AirPlayPersistence.loadDisableAudioOutput(this) -> "当前输出：iPhone 自身（CarPlay 仅视频）"
            session == null -> "当前：未连接 iPhone"
            AirPlayPersistence.loadBrowserAudioPlayback(this) && WebSession.audio.playbackReady && session.sink.isRemoteMediaAvailable() -> "当前输出：车机浏览器"
            AirPlayPersistence.loadBrowserAudioPlayback(this) -> "当前输出：Android（浏览器尚未就绪或已离线）"
            else -> "当前输出：Android"
        }
        val source = AirPlayPersistence.loadMicrophoneSource(this)
        microphoneSourceStatus?.text = when {
            source == CarPlayMicrophoneSource.DISABLED -> "当前：麦克风已禁用"
            session == null -> "当前：未连接 iPhone"
            source == CarPlayMicrophoneSource.BROWSER && WebSession.audio.microphoneReady -> "输入来源：浏览器（按需采集）"
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED -> "Android 麦克风未授权；浏览器未就绪时无法输入语音"
            source == CarPlayMicrophoneSource.BROWSER -> "输入来源：Android（浏览器麦克风尚未就绪）"
            else -> "输入来源：Android（按需采集）"
        }
    }

    private fun safeAreaControl(card: LinearLayout) {
        val margins = AirPlayPersistence.loadSafeMargins(this)
        val summary = when {
            margins == null -> {
                val size = WebSession.currentBrowserViewport ?: (1280 to 720)
                if (AirPlayPersistence.loadSafeAreaRect(this, size.first, size.second) != null) "保留已有安全区域" else "全屏"
            }
            margins.isFullScreen -> "全屏"
            else -> "上 ${margins.top}% · 下 ${margins.bottom}% · 左 ${margins.left}% · 右 ${margins.right}%"
        }
        card.addView(ui.preference("画面安全区域", summary) { editSafeArea() })
        card.addView(label("为被车机控件遮挡的边缘设置留白，按画面比例适配分辨率。保存后重新连接，让 iPhone 在安全区域内布局；触控坐标仍覆盖完整画面。", 14, MUTED))
    }

    private fun editSafeArea() {
        val current = AirPlayPersistence.loadSafeMargins(this) ?: CarPlaySafeMargins()
        val values = listOf(current.top, current.bottom, current.left, current.right)
        val names = listOf("上边留白", "下边留白", "左边留白", "右边留白")
        val inputs = mutableListOf<TextInputEditText>()
        val content = column().apply { setPadding(dp(24), dp(8), dp(24), 0) }
        val validation = label("每边 0–40%，0 表示不留白。", 14, MUTED)
        var previewMargins = current
        val preview = object : View(this) {
            override fun onDraw(canvas: android.graphics.Canvas) {
                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                val border = dp(8).toFloat()
                val w = width - 2 * border; val h = height - 2 * border
                paint.color = SURFACE
                canvas.drawRect(border, border, border + w, border + h, paint)
                paint.color = ACCENT
                paint.alpha = 100
                canvas.drawRect(border + w * previewMargins.left / 100, border + h * previewMargins.top / 100,
                    border + w * (100 - previewMargins.right) / 100, border + h * (100 - previewMargins.bottom) / 100, paint)
            }
        }.apply { contentDescription = "画面安全区域预览" }
        content.addView(preview, LinearLayout.LayoutParams(-1, dp(112)))
        content.addView(label("高亮区域为 CarPlay 布局区域", 14, MUTED))
        for (index in names.indices) {
            val field = TextInputLayout(this).apply {
                hint = names[index]; suffixText = "%"; boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            }
            val input = TextInputEditText(field.context).apply {
                setSingleLine(); inputType = android.text.InputType.TYPE_CLASS_NUMBER; setText(values[index].toString())
            }
            inputs.add(input); field.addView(input, LinearLayout.LayoutParams(-1, -2))
            input.doAfterTextChanged {
                runCatching {
                    val parts = inputs.map { it.text.toString().toInt() }
                    if (parts.size == 4) CarPlaySafeMargins(parts[0], parts[1], parts[2], parts[3]) else null
                }.getOrNull()?.let { previewMargins = it; preview.invalidate() }
            }
            content.addView(field, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
        content.addView(validation)
        val dialog = MaterialAlertDialogBuilder(this).setTitle("画面安全区域").setView(ScrollView(this).apply { addView(content) })
            .setNegativeButton("取消", null).setNeutralButton("恢复全屏") { _, _ ->
                AirPlayPersistence.saveSafeMargins(this, CarPlaySafeMargins())
                render(); reconnectForPreference()
            }.setPositiveButton("保存", null).create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val result = runCatching {
                    val parts = inputs.map { it.text.toString().toIntOrNull() ?: error("请输入 0–40 的整数") }
                    CarPlaySafeMargins(parts[0], parts[1], parts[2], parts[3])
                }
                result.onSuccess {
                    AirPlayPersistence.saveSafeMargins(this, it)
                    dialog.dismiss(); render(); reconnectForPreference()
                }.onFailure { validation.text = it.message ?: "请输入有效的留白比例"; validation.setTextColor(WARNING) }
            }
        }
        dialog.show()
    }

    private fun refreshAuthentication() {
        setupError = runCatching { DiPlayBootstrap.ensure(this) }.exceptionOrNull()?.let {
            "内置认证资源不可用，请重新安装应用。"
        }
    }

    private fun startWebServiceIfReady() {
        if (setupError == null) {
            startForegroundService(Intent(this, DiPlaySessionService::class.java))
        }
    }

    private fun rootState() = Triple(RootAccess.granted, RootAccess.checking, RootAccess.status)

    private fun refreshRootSettings() {
        if (!webBusy && renderedRootState != rootState()) {
            teslaStatus = null
            shell.put("settings", ui.content(::settings))
        }
    }

    private fun ownDomainControls(card: LinearLayout) {
        val config = com.shilapi.xcertplay.domain.OwnDomainSettings.load(this)
        if (!TeslaHttpCompatibility.config(this).enabled) {
            card.addView(label("Tesla 兼容当前未启用；DuckDNS 仍会按上方访问 IP 发布解析地址。", 14, MUTED))
        }
        toggle(card, "启用 DuckDNS 域名与自动证书",
            "Tesla 兼容的域名部分：通过 DuckDNS 的 DNS-01 验证向 Let's Encrypt 申请正式证书，到期前 30 天自动续签；解析地址与上方 Tesla 访问 IP 共用。",
            config.enabled) { enabled ->
            val next = config.copy(enabled = enabled)
            val problem = next.validate()
            if (problem != null) { toast(problem); render(); return@toggle }
            com.shilapi.xcertplay.domain.OwnDomainSettings.save(this, next)
            if (enabled) {
                com.shilapi.xcertplay.domain.OwnDomainManager.ensureStarted(this)
                com.shilapi.xcertplay.domain.OwnDomainManager.requestCheck(this, force = true)
            }
            render()
        }
        card.addView(ui.preference("DuckDNS 子域名", config.subdomain.ifBlank { "未设置" }) {
            textInput("DuckDNS 子域名", config.subdomain, false) { value ->
                val next = config.copy(subdomain = value.trim().lowercase(Locale.ROOT)
                    .removeSuffix(".duckdns.org").trim('.'))
                val problem = next.validate()
                if (problem != null) toast(problem) else {
                    com.shilapi.xcertplay.domain.OwnDomainSettings.save(this, next); render()
                }
            }
        })
        card.addView(ui.preference("DuckDNS Token", if (config.token.isBlank()) "未设置" else "已设置") {
            textInput("DuckDNS Token", config.token, true) { value ->
                val next = config.copy(token = value.trim())
                val problem = next.validate()
                if (problem != null) toast(problem) else {
                    com.shilapi.xcertplay.domain.OwnDomainSettings.save(this, next); render()
                }
            }
        })
        card.addView(ui.preference("证书通知邮箱（可选）", config.email.ifBlank { "未设置" }) {
            textInput("证书通知邮箱（可选）", config.email, false) { value ->
                val next = config.copy(email = value.trim())
                val problem = next.validate()
                if (problem != null) toast(problem) else {
                    com.shilapi.xcertplay.domain.OwnDomainSettings.save(this, next); render()
                }
            }
        })
        val accessAddress = TeslaHttpCompatibility.config(this).accessAddress
        card.addView(label("解析地址与 Tesla 兼容共用：$accessAddress", 14, MUTED))
        val status = com.shilapi.xcertplay.domain.OwnDomainManager.status
        card.addView(label(buildString {
            append(status.phase)
            if (status.domain.isNotBlank()) append(" · ").append(status.domain)
            if (status.address.isNotBlank()) append("\n解析地址：").append(status.address)
            status.certificateUntilMillis?.let {
                append("\n证书有效期至：").append(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(it)))
            }
            if (status.message.isNotBlank()) append("\n").append(status.message)
            status.lastError?.let { append("\n错误：").append(it) }
        }, 14, if (status.lastError == null) MUTED else WARNING))
        card.addView(button("立即检查 / 申请证书", false) {
            com.shilapi.xcertplay.domain.OwnDomainManager.requestCheck(this, force = true)
            toast("已开始检查；完成后重新进入设置查看状态")
        }, ui.secondaryButtonLayout(12))
        card.addView(button("刷新状态", false) { render() }, ui.secondaryButtonLayout(8))
        card.addView(label(buildString {
            append("证书安装后会替换当前 HTTPS 证书并重启 HTTPS 监听。")
            if (!WebListenSettings.httpsEnabled(this@DiPlayActivity)) append("当前 HTTPS 未启用，申请到的证书会先保存，启用 HTTPS 后生效。")
            append("车机请打开 https://")
            append(config.domain.ifBlank { "<子域名>.duckdns.org" })
            append(':').append(WebListenSettings.httpsPort(this@DiPlayActivity)).append("/。")
            append("Token 仅保存在应用私有存储，不会写入诊断日志。")
            append("停用后不会自动恢复默认证书，可在上方“证书类型”中恢复。")
        }, 14, MUTED))
    }

    private fun saveTeslaMapping(value: String, https: Boolean) {
        val port = value.toIntOrNull()
        if (port == null || port !in 1..1023) { toast("映射端口必须为 1–1023"); return }
        runCatching {
            val config = TeslaHttpCompatibility.config(this)
            TeslaHttpCompatibility.save(this, if (https) config.copy(httpsMappingPort = port) else config.copy(httpMappingPort = port))
        }.onSuccess { render(); refreshService() }.onFailure { toast(it.message ?: "端口映射保存失败") }
    }

    private fun setTeslaHttpEnabled(enabled: Boolean) {
        if (enabled && TeslaHttpCompatibility.config(this).mode == TeslaHttpMode.VPN) {
            if (awaitingTeslaVpnConsent) return
            try {
                val consent = CarPlayVpnService.prepare(this)
                if (consent != null) {
                    awaitingTeslaVpnConsent = true
                    teslaVpnConsent.launch(consent)
                    render()
                    return
                }
            } catch (error: Exception) {
                awaitingTeslaVpnConsent = false
                toast("无法请求 VPN 授权：${error.message ?: error.javaClass.simpleName}")
                render()
                return
            }
        }
        applyTeslaHttpEnabled(enabled)
    }

    private fun applyTeslaHttpEnabled(enabled: Boolean) {
        TeslaHttpCompatibility.save(this, TeslaHttpCompatibility.config(this).copy(enabled = enabled))
        if (!WebSession.running) startWebServiceIfReady()
        handler.post { render(); refreshService() }
    }

    // BYD maps the AOSP tether action to its own hotspot screen; other firmware falls back to Wi-Fi settings.
    // BYD shows that screen as a dialog and closes it unless its own settings or the car home screen is on top,
    // so the home screen goes first.
    private fun openCarWifiSettings() {
        val hotspot = Intent("com.android.settings.WIFI_TETHER_SETTINGS")
        val target = packageManager.resolveActivity(hotspot, 0)?.activityInfo?.packageName
        if (target == null) {
            openSystem(Intent(Settings.ACTION_WIRELESS_SETTINGS))
            return
        }
        if (target == "com.byd.carsettings") {
            runCatching { startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)) }
        }
        if (runCatching { startActivity(hotspot) }.isSuccess) return
        openSystem(Intent(Settings.ACTION_WIRELESS_SETTINGS))
    }

    // Wi-Fi Direct is the default link. The car's own hotspot is an alternative when Wi-Fi Direct is unstable.
    // The runtime config rejects manual mode without valid credentials, so it is only saved together with them.
    private fun wirelessLinkControls(parent: LinearLayout) {
        val ssid = storedSsid()
        val password = storedPassword()
        parent.addView(ui.preference("热点名称", ssid) {
            textInput("热点名称", ssid, secret = false, preserveWhitespace = true) { value ->
                hotspotError(value, password)?.let { toast(it); return@textInput }
                saveHotspotCredentials(value, password)
                render()
            }
        }, matchButton(0))
        parent.addView(ui.preference("热点密码", if (password.isEmpty()) "未设置" else "•".repeat(8)) {
            textInput("热点密码", password, secret = true) { value ->
                hotspotError(ssid, value)?.let { toast(it); return@textInput }
                saveHotspotCredentials(ssid, value)
                render()
            }
        }, matchButton(0))
        parent.addView(label("保持系统热点开启，并在此填写相同的名称与密码。iPhone 会连接该网络，修改在下次连接生效。", 14, MUTED).apply {
            setPadding(0, dp(8), 0, 0)
        })
    }

    private fun selectedPhoneName() = if (DiPlayPreferences.phoneAddress(this) == null) "未选择" else DiPlayPreferences.phoneName(this)

    private fun storedSsid() = AirPlayPersistence.loadManualHotspotSsid(this)
    private fun storedPassword() = AirPlayPersistence.loadManualHotspotPassphrase(this)
    private fun hotspotError(ssid: String, password: String) =
        com.shilapi.xcertplay.orchestration.ManualHotspotValidation.validate(ssid, password)

    private fun saveHotspotCredentials(ssid: String, password: String) {
        AirPlayPersistence.saveManualHotspotSsid(this, ssid)
        AirPlayPersistence.saveManualHotspotPassphrase(this, password)
        AirPlayPersistence.saveManualHotspotSecurity(this,
            com.shilapi.xcertplay.orchestration.ManualHotspotValidation.securityFor(password))
        AirPlayPersistence.saveManualHotspotBand(this, com.shilapi.xcertplay.orchestration.ManualHotspotBand.AUTO)
        AirPlayPersistence.saveManualHotspotChannel(this, 0)
    }

    private fun showBrowserDevices() {
        val devices = WebSession.rememberedBrowsers(this).list()
        if (devices.isEmpty()) {
            MaterialAlertDialogBuilder(this).setTitle("连接过的浏览器")
                .setMessage("还没有记住的浏览器。首次在网页扫码或输入配对码后会自动保存。")
                .setPositiveButton("关闭", null).show()
            return
        }
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        val items = devices.map { "${it.name}\n最近连接：${date.format(Date(it.lastUsedAt))} · ${it.peer}" } + "移除全部设备"
        MaterialAlertDialogBuilder(this).setTitle("连接过的浏览器（${devices.size}）")
            .setItems(items.toTypedArray()) { _, index ->
                if (index == devices.size) {
                    MaterialAlertDialogBuilder(this).setTitle("移除全部浏览器？")
                        .setMessage("当前浏览器连接会断开，所有浏览器下次使用时需要重新扫码或输入配对码。")
                        .setNegativeButton("取消", null)
                        .setPositiveButton("移除全部") { _, _ -> WebSession.forgetAllBrowsers(this); showBrowserDevices() }.show()
                } else {
                    val device = devices[index]
                    MaterialAlertDialogBuilder(this).setTitle(device.name)
                        .setMessage("首次配对：${date.format(Date(device.createdAt))}\n最近连接：${date.format(Date(device.lastUsedAt))}\n最近地址：${device.peer}")
                        .setNeutralButton("重命名") { _, _ ->
                            textInput("浏览器名称", device.name, secret = false) {
                                WebSession.rememberedBrowsers(this).rename(device.id, it)
                                showBrowserDevices()
                            }
                        }
                        .setNegativeButton("取消", null)
                        .setPositiveButton("移除设备") { _, _ ->
                            WebSession.forgetBrowser(this, device.id); showBrowserDevices()
                        }.show()
                }
            }.setNegativeButton("关闭", null).show()
    }

    private fun configureWeb(success: String, action: () -> String?) {
        if (webBusy) { toast("正在更新设置，请稍候"); return }
        webBusy = true
        render()
        Thread({
            val failure = runCatching(action).getOrElse { "Web 设置更新失败：${it.message ?: it.javaClass.simpleName}" }
            runOnUiThread {
                webBusy = false
                if (!isFinishing && !isDestroyed) { toast(failure ?: success); render(); refreshService() }
            }
        }, "web-configure").start()
    }

    private fun textInput(title: String, current: String, secret: Boolean, preserveWhitespace: Boolean = false,
        onCancel: () -> Unit = {}, save: (String) -> Unit) {
        val field = TextInputLayout(this).apply {
            hint = title
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            if (secret) endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
        }
        val input = TextInputEditText(field.context).apply {
            setText(current)
            setSingleLine()
            isSaveEnabled = !secret
            inputType = if (secret) {
                android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else {
                android.text.InputType.TYPE_CLASS_TEXT
            }
        }
        field.addView(input, LinearLayout.LayoutParams(-1, -2))
        val container = column().apply { setPadding(dp(24), dp(8), dp(24), 0); addView(field) }
        MaterialAlertDialogBuilder(this).setTitle(title).setView(container)
            .setPositiveButton("保存") { _, _ ->
                save(input.text.toString().let { if (secret || preserveWhitespace) it else it.trim() })
            }
            .setNegativeButton("取消") { _, _ -> onCancel() }.setOnCancelListener { onCancel() }.show()
    }

    private fun carPlaySizeControl(parent: LinearLayout) {
        val sizes = com.shilapi.xcertplay.airplay.CarPlaySize.entries
        val current = com.shilapi.xcertplay.airplay.CarPlaySize.fromWidthMillimeters(AirPlayPersistence.loadWidthPhysicalMm(this))
        choice(parent, "CarPlay 显示尺寸", sizes.map { it.label }, sizes.indexOf(current)) {
            AirPlayPersistence.saveWidthPhysicalMm(this, sizes[it].widthMillimeters)
        }
        parent.addView(label("调整 CarPlay 图标和文字大小，应用后会重新连接。", 14, MUTED).apply {
            setPadding(0, 0, 0, dp(16))
        })
    }

    private fun connect() {
        refreshAuthentication()
        if (setupError != null) { toast(setupError!!); return }
        if (DiPlayPreferences.phoneAddress(this) == null) {
            pendingConnect = true; choosePhone(); return
        }
        val preferences = getSharedPreferences("diplay", MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED && !preferences.getBoolean("notification_asked", false)) {
            preferences.edit().putBoolean("notification_asked", true).apply()
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        val open = {
            startWebServiceIfReady()
            openProjection()
        }
        if (CarPlayBackgroundSession.standby) {
            startWebServiceIfReady()
            if (CarPlayBackgroundSession.wake("手动连接")) {
                navigate("service")
                refreshStatus(); refreshService()
                return
            }
        }
        if (CarPlayBackgroundSession.hasSession()) CarPlayBackgroundSession.stop { runOnUiThread { open() } }
        else open()
    }
    private fun openProjection() {
        navigate("service")
        startActivity(Intent(this, CarPlayHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NO_ANIMATION))
        overridePendingTransition(0, 0)
    }
    private fun choosePhone() {
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT); return
        }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            MaterialAlertDialogBuilder(this).setTitle("请开启蓝牙")
                .setMessage("请开启这台 Android 设备的蓝牙，并先与 iPhone 配对。")
                .setPositiveButton("打开蓝牙设置") { _, _ -> openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                .setNegativeButton("稍后", null).show(); return
        }
        val devices = runCatching { adapter.bondedDevices.sortedBy { it.name ?: "" } }.getOrDefault(emptyList())
        if (devices.isEmpty()) {
            MaterialAlertDialogBuilder(this).setTitle("请先配对 iPhone")
                .setMessage("在 iPhone 的“设置 → 蓝牙”中与这台 Android 设备配对，然后返回选择 iPhone。")
                .setPositiveButton("打开蓝牙设置") { _, _ -> openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                .setNegativeButton("知道了", null).show(); return
        }
        MaterialAlertDialogBuilder(this).setTitle("选择 iPhone")
            .setItems(devices.map { device ->
                val name = device.name ?: "已配对设备"
                if (devices.count { it.name == device.name } > 1) "$name · ${device.address.takeLast(5)}" else name
            }.toTypedArray()) { _, index ->
                val device = devices[index]
                DiPlayPreferences.savePhone(this, device.address, device.name ?: "iPhone")
                val start = pendingConnect; pendingConnect = false
                render()
                if (start) connect()
            }.setNeutralButton("配对其他设备") { _, _ -> openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
            .setNegativeButton("取消") { _, _ -> pendingConnect = false }.show()
    }

    private fun wirelessHelp() {
        MaterialAlertDialogBuilder(this).setTitle("无线连接帮助")
            .setMessage("将 iPhone 与此设备完成蓝牙配对，保持 Wi-Fi 与系统热点开启，并在 iPhone 上允许 CarPlay。请关闭其他投屏应用。")
            .setPositiveButton("知道了", null).show()
    }

    private fun refreshStatus() {
        val running = CarPlayBackgroundSession.hasSession() && !CarPlayBackgroundSession.standby
        status?.text = when {
            setupError != null -> "认证配置需要处理"
            CarPlayBackgroundSession.active -> "CarPlay 已连接"
            CarPlayBackgroundSession.standby -> "蓝牙待机省电中（等待 iPhone 靠近或随车通电）"
            running -> "正在连接 iPhone…"
            DiPlayPreferences.phoneAddress(this) != null -> "已选择 ${DiPlayPreferences.phoneName(this)}"
            else -> "尚未选择 iPhone"
        }
        if (lastRunning != running) {
            connectButton?.visibility = if (running) View.GONE else View.VISIBLE
            disconnectButton?.visibility = if (running) View.VISIBLE else View.GONE
            disconnectButton?.isEnabled = true
            lastRunning = running
        }
        connectButton?.isEnabled = setupError == null
        sessionConnectButton?.visibility = if (!running && !CarPlayBackgroundSession.active &&
            DiPlayPreferences.phoneAddress(this) != null) View.VISIBLE else View.GONE
        sessionConnectButton?.isEnabled = setupError == null
    }
    private fun reportFileName() = "WheelPlay-${SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())}.txt"

    private fun chooseReportDestination() {
        // Some head units omit or disable DocumentsUI. Launch itself can throw, before
        // the result callback and the background writer's exception handler ever run.
        runCatching { export.launch(reportFileName()) }.onFailure {
            toast(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                "无法打开保存位置，请重试保存到下载目录。"
                else "此设备没有可用的文件选择器。")
        }
    }

    private fun exportDiagnostics(uri: Uri? = null) {
        if (exportInProgress) return
        exportInProgress = true
        exportButton?.apply { isEnabled = false; text = "正在保存报告…" }
        val appContext = applicationContext
        val fileName = reportFileName()
        Thread({
            val result = runCatching {
                val report = buildString {
                    appendLine("WheelPlay ${version()} · private beta diagnostic report")
                    appendLine("Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
                    appendLine("Head unit: ${Build.MANUFACTURER} ${Build.MODEL}")
                    appendLine("Connection: wireless CarPlay")
                    appendLine("Authentication: local experimental beta identity; no remote fallback")
                    appendLine("Saved video preference (may differ from active session): ${if (AirPlayPersistence.loadHevcEnabled(appContext)) "HEVC" else "H.264"}; ${AirPlayPersistence.loadFps(appContext)} fps")
                    appendLine("CarPlay size: ${com.shilapi.xcertplay.airplay.CarPlaySize.fromWidthMillimeters(AirPlayPersistence.loadWidthPhysicalMm(appContext)).label}")
                    appendLine("Saved resolution preference (may differ from active session): ${AirPlayPersistence.loadDisplayScaleTenths(appContext) * 10}%")
                    appendLine("Tesla compatibility: enabled=${TeslaHttpCompatibility.config(appContext).enabled}; mode=${TeslaHttpCompatibility.config(appContext).mode}; addressConfigured=${TeslaHttpCompatibility.status.address != null}; downstreams=${TeslaHttpCompatibility.status.downstreams.joinToString()}")
                    TeslaHttpCompatibility.status.error?.let { appendLine("Tesla compatibility status: $it") }
                    val ownDomain = com.shilapi.xcertplay.domain.OwnDomainManager.status
                    appendLine("Own domain: phase=${ownDomain.phase}; domain=${ownDomain.domain}; address=${ownDomain.address}; certificateUntil=${ownDomain.certificateUntilMillis}; error=${ownDomain.lastError}")
                    appendLine("Session: ${if (CarPlayBackgroundSession.active) "active" else if (CarPlayBackgroundSession.hasSession()) "connecting" else "stopped"}")
                    appendLine("Head-unit board: ${Build.BOARD}; hardware: ${Build.HARDWARE}; build: ${Build.DISPLAY}")
                    appendLine()
                    appendLine("--- Last display negotiation (timestamps distinguish it from current settings) ---")
                    appendLine(DisplayDiagnosticSnapshot.report(appContext))
                    appendLine()
                    for (name in SessionLogFile.REPORT_NAMES) {
                        val file = File(appContext.filesDir, "logs/$name")
                        if (file.isFile) {
                            appendLine("--- $name ---")
                            file.useLines { lines -> lines.forEach { line -> DiagnosticRedactor.redact(line)?.let { appendLine(it) } } }
                        }
                    }
                }
                if (uri != null) DiagnosticExportStore.write(appContext.contentResolver, uri, report)
                else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    DiagnosticExportStore.saveToDownloads(appContext.contentResolver, fileName, report)
                } else error("A save location is required")
            }
            runOnUiThread {
                exportInProgress = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                exportButton?.apply { isEnabled = true; text = "保存诊断报告" }
                if (result.isSuccess) {
                    MaterialAlertDialogBuilder(this).setTitle("诊断报告已保存")
                        .setMessage(if (uri == null) "Downloads/WheelPlay/$fileName" else "报告已保存到所选位置。")
                        .setPositiveButton("完成", null).show()
                } else {
                    MaterialAlertDialogBuilder(this).setTitle("无法保存报告")
                        .setMessage("请检查存储空间，或选择其他保存位置。")
                        .setPositiveButton("选择位置") { _, _ -> chooseReportDestination() }
                        .setNegativeButton("关闭", null).show()
                }
            }
        }, "diplay-export").start()
    }
    private fun permissionHelp(title: String, body: String) {
        MaterialAlertDialogBuilder(this).setTitle(title).setMessage(body).setPositiveButton("应用设置") { _, _ ->
            openSystem(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }.setNegativeButton("稍后", null).show()
    }
    private fun openSystem(intent: Intent) { runCatching { startActivity(intent) }.onFailure { toast("请从此设备的系统设置中打开该选项。") } }
    private fun toast(message: String) { com.google.android.material.snackbar.Snackbar.make(shell.root, message, com.google.android.material.snackbar.Snackbar.LENGTH_LONG).show() }
    private fun version() = packageManager.getPackageInfo(packageName, 0).versionName ?: "0.1.0-beta.1"
    private fun section(parent: LinearLayout, title: String, build: (LinearLayout) -> Unit) =
        ui.section(parent, title, content = build)
    private fun toggle(parent: LinearLayout, title: String, description: String, value: Boolean, enabled: Boolean = true, save: (Boolean) -> Unit) {
        val line = row().apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(12), 0, dp(12)) }
        val text = column(); text.addView(label(title, 18, TEXT, true)); text.addView(label(description, 14, MUTED).apply { setPadding(0, dp(4), 0, 0) })
        line.addView(text, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(16) })
        line.addView(MaterialSwitch(this).apply { contentDescription = title; isChecked = value; isEnabled = enabled; minHeight = dp(48); setOnCheckedChangeListener { _, checked -> save(checked) } })
        parent.addView(line)
    }
    private fun choice(parent: LinearLayout, title: String, options: List<String>, current: Int, save: (Int) -> Unit) {
        var selection = current
        val control = ui.preference(title, options[selection].substringBefore(" · ")) {}
        control.setOnClickListener {
            var pendingSelection = selection
            MaterialAlertDialogBuilder(this).setTitle(title)
                .setSingleChoiceItems(options.toTypedArray(), selection) { _, index -> pendingSelection = index }
                .setPositiveButton(if (CarPlayBackgroundSession.hasSession()) "应用并重新连接" else "保存") { _, _ ->
                    if (pendingSelection != selection) {
                        selection = pendingSelection
                        save(selection)
                        control.setValue(options[selection].substringBefore(" · "))
                        if (CarPlayBackgroundSession.hasSession()) {
                            connect()
                        }
                    }
                }.setNegativeButton("取消", null).show()
        }
        parent.addView(control, matchButton(0))
    }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(-1, -2) }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = LinearLayout.LayoutParams(-1, -2) }
    private fun label(value: String, size: Int, color: Int, bold: Boolean = false) = ui.text(value, size, color, bold)
    private fun button(title: String, primary: Boolean, @androidx.annotation.DrawableRes iconRes: Int = 0, click: () -> Unit) = ui.button(title, primary, iconRes, click)
    private fun matchButton(top: Int = 0) = LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }
    private fun space(height: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(height)) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    companion object {
        private val BG = ServerUi.BG
        private val SURFACE = ServerUi.SURFACE
        private val BORDER = ServerUi.BORDER
        private val ACCENT = ServerUi.ACCENT
        private val TEXT = ServerUi.TEXT
        private val MUTED = ServerUi.MUTED
        private val WARNING = ServerUi.WARNING
    }
}
