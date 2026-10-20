package com.shilapi.xcertplay.orchestration

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayContact
import com.shilapi.xcertplay.airplay.AirPlayDeviceInfo
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayListenerIdentity
import com.shilapi.xcertplay.airplay.AirPlayMediaHandler
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.AirPlayTcpAccepted
import com.shilapi.xcertplay.airplay.PairingStore
import com.shilapi.xcertplay.iap2.session.Iap2Session
import com.shilapi.xcertplay.mfi.Iap2MfiAuthenticationClient
import com.shilapi.xcertplay.mfi.LocalMfiAuthenticationClient
import com.shilapi.xcertplay.network.CarPlayBonjour
import com.shilapi.xcertplay.network.diagnosticSummary
import com.shilapi.xcertplay.network.CarPlayVpnService
import com.shilapi.xcertplay.network.HotspotAddressPolicy
import com.shilapi.xcertplay.network.HotspotAddressRecovery
import com.shilapi.xcertplay.network.ManualHotspotManager
import com.shilapi.xcertplay.network.WirelessHotspotInfo
import com.shilapi.xcertplay.network.WirelessHotspotBackend
import com.shilapi.xcertplay.network.WirelessHotspotManager
import com.shilapi.xcertplay.network.WirelessStartupException
import com.shilapi.xcertplay.network.WirelessStartupFailure
import com.shilapi.xcertplay.network.WirelessStartupPolicy
import com.shilapi.xcertplay.network.WifiUplink
import com.shilapi.xcertplay.transport.BlockingDuplexByteStream
import com.shilapi.xcertplay.transport.BluetoothRfcommDuplexStream
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import com.shilapi.xcertplay.transport.Iap2TransportException
import com.shilapi.xcertplay.transport.Iap2WirelessCarPlayEndpoint
import com.shilapi.xcertplay.transport.Iap2WirelessControlClient
import com.shilapi.xcertplay.transport.Iap2WirelessControlTerminal
import com.shilapi.xcertplay.transport.Iap2WirelessIdentification
import com.shilapi.xcertplay.media.CarPlayMediaSource
import com.shilapi.xcertplay.media.CarPlayMediaCoordinator
import com.shilapi.xcertplay.media.NowPlayingState
import com.shilapi.xcertplay.media.MediaCommand
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.iap2.message.Iap2NowPlayingCodec
import java.io.Closeable
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

sealed class CarPlayStatus {
    data object DiscoveringMfi : CarPlayStatus()
    data object MfiReady : CarPlayStatus()
    data object StartingHotspot : CarPlayStatus()
    data class HotspotReady(
        val ssid: String,
        val band: String,
        val channel: Int,
        val bssid: String,
        val address: String,
        val backend: String,
    ) : CarPlayStatus()
    data object WaitingForPairedIphone : CarPlayStatus()
    data object ConnectingBluetooth : CarPlayStatus()
    data object RunningWireless : CarPlayStatus()
    data object WirelessActive : CarPlayStatus()
    data object WirelessActiveFallback : CarPlayStatus()
    data object AttachingNetwork : CarPlayStatus()
    data object ControlEnded : CarPlayStatus()
    data class Failed(
        val message: String,
        val automaticRetry: Boolean = true,
    ) : CarPlayStatus()
}

internal fun isWirelessHandoffInProgress(
    handoffRequested: Boolean,
    tunnelActive: Boolean,
    sessionActive: Boolean,
): Boolean = handoffRequested || tunnelActive || sessionActive

/**
 * Wires the wireless CarPlay path: MFi coprocessor discovery, Bluetooth iAP2 control, the local
 * hotspot, and the AirPlay media/input sessions.
 *
 * All blocking USB/I2C work runs on one worker executor. Status callbacks are delivered on the
 * main thread. This class is the integration seam only and is not evidence of hardware operation.
 */
class CarPlayController(
    context: Context,
    private val config: CarPlayRuntimeConfig,
    private val airPlayConfig: AirPlayConfig,
    private val identity: AirPlayIdentity,
    private val pairings: PairingStore,
    listener: AirPlaySessionListener,
    private val media: AirPlayMediaHandler,
    reportStatus: (CarPlayStatus) -> Unit,
) : Closeable, CarPlayMediaSource {

    private enum class Phase { IDLE, MFI, WIRELESS }

    private val appContext = context.applicationContext
    private val bluetoothAdapter =
        appContext.getSystemService(BluetoothManager::class.java)?.adapter
    private val mediaState = CarPlayMediaCoordinator(
        captureSender = { activeSession?.let { session ->
            { command: MediaCommand -> session.sendMedia(command.hidIndex) }
        } },
        onDiagnostic = ::debugLog,
    )
    override fun mediaSnapshot() = mediaState.snapshot()
    override fun subscribeMediaState(listener: (NowPlayingState) -> Unit) = mediaState.subscribe(listener)
    override fun sendMediaCommand(command: MediaCommand, connectionGeneration: Long?) = mediaState.send(command, connectionGeneration)
    override fun seekTo(positionMs: Long) = mediaState.seekTo(positionMs)
    fun setNightMode(night: Boolean): Boolean = activeSession?.setNightMode(night) ?: false

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val touchExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val touchQueue = TouchDispatchQueue(
        scheduleDrain = touchExecutor::execute,
        deliver = { contacts -> activeSession?.sendTouch(contacts) },
    )
    private val tunnelExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lifecycleLock = Any()
    @Volatile private var uiListener: AirPlaySessionListener? = listener
    @Volatile private var uiStatusReporter: ((CarPlayStatus) -> Unit)? = reportStatus
    private var lastReportedStatus: CarPlayStatus? = null

    @Volatile private var closed = false
    @Volatile private var phase = Phase.IDLE
    @Volatile private var mfiSession: MfiSession? = null
    @Volatile private var csm: Iap2Session? = null
    @Volatile private var activeSession: AirPlaySession? = null
    @Volatile private var hotspot: WirelessHotspotManager? = null
    @Volatile private var bonjour: CarPlayBonjour? = null
    @Volatile private var bluetoothSocket: BluetoothSocket? = null
    @Volatile private var bluetoothStream: BluetoothRfcommDuplexStream? = null
    @Volatile private var wirelessTunnelChannel: Iap2Session? = null
    @Volatile private var wirelessIdentification: Iap2IdentificationConfig? = null
    @Volatile private var wirelessAirPlayEndpoint: Iap2WirelessCarPlayEndpoint? = null
    @Volatile private var vpnService: CarPlayVpnService? = null
    @Volatile private var vpnBound = false
    private val wirelessHandoffRequested = AtomicBoolean(false)
    private val wirelessTunnelReady = AtomicBoolean(false)
    private val wirelessActiveReported = AtomicBoolean(false)
    private val wirelessGeneration = AtomicInteger(0)
    private val wirelessConnectionProof = WirelessConnectionProof<AirPlaySession>()
    private val wirelessHandoffFellBack = AtomicBoolean(false)
    private val wirelessBootstrapAuthenticated = AtomicBoolean(false)
    private val wirelessRecovery = WirelessRecoveryBudget()
    private val wirelessRestartPending = AtomicBoolean(false)
    @Volatile private var firstTcpWatchdog: FirstTcpWatchdog? = null
    @Volatile private var wirelessAddressRecovery: HotspotAddressRecovery? = null
    @Volatile private var wirelessPublishedAddress: InetAddress? = null
    private var preferredWirelessFamily: String? = null

    @Volatile private var wifiLock: WifiManager.WifiLock? = null
    private var vpnLatch = CountDownLatch(1)
    private val teardownComplete = CountDownLatch(1)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            vpnService = (binder as CarPlayVpnService.LocalBinder).service
            vpnLatch.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            vpnService = null
            fail(Iap2TransportException.DeviceUnavailable("CarPlay AirPlay service disconnected"))
        }
    }

    private val sessionListener = object : AirPlaySessionListener {
        override fun onSessionActive(session: AirPlaySession) {
            if (activeSession !== session) {
                touchQueue.reset()
            }
            activeSession = session
            mediaState.resume()
            debugLog(
                "AirPlay session active controller=${session.controllerId ?: "unknown"} " +
                    "peer=${session.host}",
            )
            uiListener?.onSessionActive(session)
        }

        override fun onSessionEnded(session: AirPlaySession) {
            if (activeSession === session) {
                activeSession = null
                touchQueue.reset()
                mediaState.disconnected()
            }
            debugLog("AirPlay session ended peer=${session.host}")
            uiListener?.onSessionEnded(session)
        }

        override fun onTransportError(message: String) {
            debugLog("AirPlay transport error: $message")
            uiListener?.onTransportError(message)
        }

        override fun onDeviceInfo(session: AirPlaySession, info: AirPlayDeviceInfo) {
            debugLog(
                "AirPlay device info name=${info.name} deviceId=${info.deviceId} " +
                    "wifiMac=${info.wifiMac} model=${info.model}",
            )
            uiListener?.onDeviceInfo(session, info)
        }

        // The user tapped the car icon in CarPlay: show the head unit's own menu, like its Home button.
        // The session keeps running in the background, so returning to WheelPlay resumes CarPlay.
        override fun onHostUiRequested(session: AirPlaySession) {
            debugLog("CarPlay requested the car UI")
            val listener = uiListener
            if (listener != null) {
                listener.onHostUiRequested(session)
            } else {
                runCatching {
                    appContext.startActivity(
                        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }.onFailure { debugLog("Car home screen could not open: ${it.javaClass.simpleName}") }
            }
        }

        override fun onCommand(session: AirPlaySession, type: String, params: Map<String, Any?>) {
            debugLog(
                "AirPlay command type=$type params=${params.keys.sorted().joinToString(",")}",
            )
            if (
                !closed &&
                activeSession === session &&
                isBluetoothHandoffCommand(type) &&
                wirelessHandoffRequested.compareAndSet(false, true)
            ) {
                debugLog(
                    "wireless CarPlay Bluetooth handoff requested; " +
                        "waiting for tunnel iAP2 readiness",
                )
                armWirelessHandoffWatchdog(wirelessGeneration.get())
                maybeCompleteWirelessHandoff()
            }
            uiListener?.onCommand(session, type, params)
        }

        override fun onDebugLog(message: String) {
            debugLog(message)
        }
    }

    fun attachUi(
        listener: AirPlaySessionListener,
        reportStatus: (CarPlayStatus) -> Unit,
    ) {
        uiListener = listener
        uiStatusReporter = reportStatus
        mainHandler.post {
            if (uiListener === listener) lastReportedStatus?.let(reportStatus)
        }
    }

    fun isClosed(): Boolean = closed

    fun hasActiveAirPlayAttachment(): Boolean = synchronized(lifecycleLock) {
        !closed && vpnService?.isAttached() == true
    }

    fun start() {
        synchronized(this) {
            if (closed) return
        }
        startMfi()
    }

    private fun acquireWifiLock() {
        if (closed || wifiLock?.isHeld == true) return
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        wifiLock = runCatching {
            appContext.getSystemService(WifiManager::class.java)
                ?.createWifiLock(mode, "WheelPlay:carplayLowLatency")
                ?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
        }.getOrNull()
    }

    private fun releaseWifiLock() {
        wifiLock?.let { lock -> runCatching { if (lock.isHeld) lock.release() } }
        wifiLock = null
    }

    /** Reopens the CH341/MFi path without restarting the app. */
    fun reconnectMfi() = synchronized(lifecycleLock) {
        if (closed) return
        closeMfiSession()
        startMfi()
    }

    /** Re-runs the wireless bring-up using the already-open MFi session. */
    fun reconnectIphone() = synchronized(lifecycleLock) {
        if (closed) return
        if (mfiSession == null) {
            startMfi()
        } else {
            wirelessRecovery.reset()
            preferredWirelessFamily = null
            restartWireless()
        }
    }

    fun sendTouch(contacts: List<AirPlayContact>): Boolean {
        if (closed || activeSession == null) return false
        return touchQueue.enqueue(contacts)
    }

    override fun close() {
        synchronized(this) {
            if (closed) return
            closed = true
        }
        touchQueue.close()
        releaseWifiLock()
        mediaState.close()
        wirelessGeneration.incrementAndGet()
        touchExecutor.shutdownNow()
        tunnelExecutor.shutdownNow()
        val service = vpnService
        unbindVpn()
        Thread(
            {
                try {
                    closeBestEffort("wireless stack") { closeWirelessStack(service) }
                    closeBestEffort("MFi") { mfiSession?.close() }
                    mfiSession = null
                    wirelessIdentification = null
                    wirelessAirPlayEndpoint = null
                } finally {
                    executor.shutdownNow()
                    try {
                        executor.awaitTermination(EXECUTOR_CLOSE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                    teardownComplete.countDown()
                }
            },
            "xcertplay-controller-teardown",
        ).apply {
            isDaemon = true
            start()
        }
    }

    /** Waits for MFi, iAP2 and AirPlay teardown; intended for a non-main lifecycle thread. */
    fun awaitClosed(timeoutMillis: Long): Boolean {
        require(timeoutMillis >= 0) { "timeoutMillis must not be negative" }
        return try {
            teardownComplete.await(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun onControlFrame(source: Iap2Session, frame: Iap2Frame) {
        if (frame.messageId == Iap2NowPlayingCodec.UPDATE) mediaState.receive(source, frame)
    }

    private fun startMfi() {
        phase = Phase.MFI
        onStatus(CarPlayStatus.DiscoveringMfi)
        openLocalMfi(java.io.File(appContext.noBackupFilesDir, LocalMfiAuthenticationClient.DIRECTORY))
    }

    private fun openLocalMfi(directory: java.io.File) {
        debugLog("mfi discovery backend=LocalOffline remoteFallback=disabled")
        executor.execute {
            try {
                val signatures = AtomicInteger(0)
                val client = LocalMfiAuthenticationClient.load(directory) { size ->
                    debugLog("mfi local signature count=${signatures.incrementAndGet()} digestBytes=$size")
                }
                if (closed || phase != Phase.MFI) return@execute
                mfiSession = MfiSession(client, null)
                debugLog("mfi local offline ready protocolMajor=${client.protocolMajor()} certificateBytes=${client.readCertificate().size}")
                onStatus(CarPlayStatus.MfiReady)
                startPhone()
            } catch (error: Throwable) {
                // A broken local identity must fail closed rather than silently use the helper.
                fail(error)
            }
        }
    }

    private fun startPhone() {
        startWireless()
    }

    private fun startWireless() {
        phase = Phase.WIRELESS
        wirelessHandoffRequested.set(false)
        wirelessTunnelReady.set(false)
        wirelessActiveReported.set(false)
        onStatus(CarPlayStatus.StartingHotspot)
        val generation = wirelessGeneration.incrementAndGet()
        executor.execute {
            runWireless(generation)
        }
    }

    private fun restartWireless(terminalError: Throwable? = null) {
        if (!wirelessRestartPending.compareAndSet(false, true)) return
        wirelessGeneration.incrementAndGet()
        firstTcpWatchdog?.terminate()
        Thread(
            {
                closeWirelessStack()
                // Queue cleanup after the old blocking run has unwound, before publishing any new resources.
                if (!closed) executor.execute {
                    closeWirelessStack()
                    wirelessRestartPending.set(false)
                    if (!closed) {
                        if (terminalError == null) startWireless()
                        else fail(terminalError, automaticRetry = false)
                    }
                } else {
                    wirelessRestartPending.set(false)
                }
            },
            "xcertplay-wireless-restart",
        ).apply {
            isDaemon = true
            start()
        }
    }

    private fun runWireless(generation: Int) {
        try {
            debugLog("wireless bring-up generation=$generation starting")
            closeWirelessStack()
            if (
                closed ||
                phase != Phase.WIRELESS ||
                generation != wirelessGeneration.get()
            ) {
                return
            }

            val watchdog = FirstTcpWatchdog(
                listener = AirPlayListenerIdentity(generation),
                schedule = { delay, action ->
                    val task = Runnable { action() }
                    mainHandler.postDelayed(task, delay)
                    val cancel: () -> Unit = { mainHandler.removeCallbacks(task) }
                    cancel
                },
                onTimeout = {
                    recoverWireless(
                        generation,
                        IOException("No AirPlay TCP after CarPlay StartSession"),
                        true,
                    )
                },
                log = { debugLog("wireless startup generation=$generation $it") },
            )
            firstTcpWatchdog = watchdog

            // Give the STA/uplink a chance to associate before the iPhone is told to join the
            // accessory hotspot. A hotspot without internet makes iOS leave it and route media
            // elsewhere, which looks like "connected but no video".
            if (WifiUplink.isWifiEnabled(appContext) && !WifiUplink.isReady(appContext)) {
                debugLog("wireless waiting for the Wi-Fi uplink before Bluetooth bring-up")
                val ready = WifiUplink.await(appContext, WIRELESS_UPLINK_WAIT_MILLIS) {
                    closed || generation != wirelessGeneration.get()
                }
                debugLog("wireless Wi-Fi uplink ready=$ready")
            }

            val mfi = mfiSession?.client
                ?: throw IOException("MFi coprocessor client is unavailable")
            onStatus(CarPlayStatus.WaitingForPairedIphone)

            val adapter = bluetoothAdapter
                ?: throw IOException("Bluetooth adapter is unavailable")
            if (!adapter.isEnabled) {
                // Power-save standby turns Bluetooth off; the radio worker turns it back on
                // asynchronously after external power returns.
                debugLog("wireless waiting for Bluetooth to turn on")
                val deadline = System.nanoTime() + BLUETOOTH_ENABLE_WAIT_NANOS
                while (!adapter.isEnabled && !isStaleWirelessRun(generation) &&
                    System.nanoTime() < deadline
                ) {
                    Thread.sleep(BLUETOOTH_ENABLE_POLL_MILLIS)
                }
            }
            if (!adapter.isEnabled) throw IOException("Bluetooth is not enabled")
            val device = selectWirelessBluetoothDevice(adapter)
            val hostBluetoothMac = accessoryBluetoothMac(adapter)
            debugLog(
                "wireless selected Bluetooth target name=${device.name ?: "unknown"} " +
                    "address=${device.address} localBt=$hostBluetoothMac",
            )

            // Probe and connect Bluetooth RFCOMM first so that when the iPhone is absent,
            // we never power up the Wi-Fi Direct / hotspot radio, mDNS, AirPlay listener, or WifiLock.
            onStatus(CarPlayStatus.ConnectingBluetooth)
            debugLog(
                "wireless RFCOMM connecting address=${device.address} " +
                    "uuid=$IAP2_IPHONE_UUID",
            )
            var socket = device
                .createRfcommSocketToServiceRecord(UUID.fromString(IAP2_IPHONE_UUID))
                .also { bluetoothSocket = it }
            connectBluetoothSocket(socket, device.address)
            debugLog("wireless RFCOMM connected address=${device.address}")
            if (isStaleWirelessRun(generation)) {
                closeWirelessStack()
                return
            }
            var stream = BluetoothRfcommDuplexStream(socket).also { bluetoothStream = it }
            var channel = Iap2Session.openWireless(
                stream,
                traceContext = "wireless-rfcomm",
                onTrace = ::debugLog,
            ).also { csm = it }
            debugLog("wireless iAP2 CSM channel opened over RFCOMM")
            if (isStaleWirelessRun(generation)) {
                closeWirelessStack()
                return
            }

            acquireWifiLock()
            val hotspotInfo = startWirelessHotspot(generation)
            if (isStaleWirelessRun(generation)) {
                closeWirelessStack()
                return
            }
            val startedHotspot = hotspot
            wirelessConnectionProof.begin(generation) {
                if (!isStaleWirelessRun(generation)) startedHotspot?.onCarPlayConfirmed()
            }
            val addressRecovery = HotspotAddressRecovery(
                sample = { hotspotCandidates(hotspotInfo) },
                cancelled = { isStaleWirelessRun(generation) },
                pause = Thread::sleep,
                log = ::debugLog,
            )
            wirelessAddressRecovery = addressRecovery
            val candidates = addressRecovery.awaitCandidates(
                hotspotCandidates(hotspotInfo),
                preferredWirelessFamily,
            )
            val hostAddress = HotspotAddressPolicy.select(candidates, preferredWirelessFamily)
                ?: throw WirelessStartupException(
                    WirelessStartupFailure.HOTSPOT_NOT_READY,
                    "Wireless hotspot did not provide a usable host address",
                )
            if (
                hostAddress is Inet6Address &&
                (!hostAddress.isLinkLocalAddress || hostAddress.scopeId == 0)
            ) {
                throw IOException(
                    "Wireless hotspot link-local IPv6 address is not scoped",
                )
            }
            wirelessPublishedAddress = hostAddress
            val hostAddressText = hostAddressText(hostAddress)
            val deviceIdentifier = hotspotInfo.bssid
                ?.takeUnless { it.equals(ADAPTER_ADDRESS_PLACEHOLDER, ignoreCase = true) }
                ?: airPlayConfig.deviceId
            debugLog(
                "wireless hotspot backend=${hotspotInfo.backend.label} " +
                    "iface=${hotspotInfo.interfaceName ?: "unknown"} " +
                    "family=${if (hostAddress is Inet6Address) "IPv6" else "IPv4"} " +
                    "identitySource=${if (deviceIdentifier == hotspotInfo.bssid) "interface" else "saved"} " +
                    "host=$hostAddressText " +
                    "band=${hotspotInfo.bandLabel} channel=${hotspotInfo.channel} " +
                    "frequency=${hotspotInfo.frequencyMHz?.toString() ?: "unknown"}MHz",
            )
            onStatus(
                CarPlayStatus.HotspotReady(
                    ssid = hotspotInfo.ssid,
                    band = hotspotInfo.bandLabel,
                    channel = hotspotInfo.channel,
                    bssid = deviceIdentifier,
                    address = hostAddressText,
                    backend = hotspotInfo.backend.label,
                ),
            )
            val wirelessAirPlayConfig = airPlayConfig.copy(
                deviceId = deviceIdentifier,
                btMac = hostBluetoothMac,
            )

            onStatus(CarPlayStatus.AttachingNetwork)
            val service = awaitVpnService()
                ?: throw IOException("Could not bind the CarPlay AirPlay service")
            startedHotspot?.validateReady()
            when (
                val result = service.attachWireless(
                    bindAddress = hostAddress,
                    config = wirelessAirPlayConfig,
                    identity = identity,
                    pairings = pairings,
                    mfi = mfi,
                    listener = wirelessSessionListener(generation, watchdog),
                    listenerIdentity = watchdog.listener,
                    media = media,
                )
            ) {
                CarPlayVpnService.AttachResult.Started -> Unit
                CarPlayVpnService.AttachResult.AlreadyStarted ->
                    throw IOException("Wireless AirPlay transport is already attached")
                is CarPlayVpnService.AttachResult.Failed ->
                    throw IOException(result.message)
            }
            debugLog(
                "wireless AirPlay listener attached bind=$hostAddressText " +
                    "port=${airPlayConfig.port}",
            )
            if (isStaleWirelessRun(generation)) {
                closeWirelessStack()
                return
            }

            val bonjourClient = CarPlayBonjour(
                context = appContext,
                config = wirelessAirPlayConfig,
                identity = identity,
                advertisedHost = hostAddress.hostAddress,
                // Bind discovery and its connect probe to the same AP/address family as AirPlay.
                // The car hotspot previously used system NSD, which could resolve another interface
                // or IPv6 while the listener/probe was bound to the AP's IPv4 address.
                useInterfaceMdns = true,
                onEvent = { event -> debugLog("wireless bonjour: ${event.diagnosticSummary()}") },
            )
            bonjour = bonjourClient
            startedHotspot?.validateReady()
            bonjourClient.start()
            debugLog("wireless Bonjour services started mode=interface iface=${hotspotInfo.interfaceName ?: "unknown"}")
            if (isStaleWirelessRun(generation)) {
                closeWirelessStack()
                return
            }

            if (channel.isClosed) {
                debugLog("wireless RFCOMM closed while hotspot was starting; reconnecting RFCOMM")
                closeBluetoothBootstrapTransport()
                socket = device
                    .createRfcommSocketToServiceRecord(UUID.fromString(IAP2_IPHONE_UUID))
                    .also { bluetoothSocket = it }
                connectBluetoothSocket(socket, device.address)
                if (isStaleWirelessRun(generation)) {
                    closeWirelessStack()
                    return
                }
                stream = BluetoothRfcommDuplexStream(socket).also { bluetoothStream = it }
                channel = Iap2Session.openWireless(
                    stream,
                    traceContext = "wireless-rfcomm",
                    onTrace = ::debugLog,
                ).also { csm = it }
            }
            val identification = config.identification.copy(
                wireless = Iap2WirelessIdentification(hostBluetoothMac, hotspotInfo.ssid),
            )
            val endpoint = Iap2WirelessCarPlayEndpoint(
                ssid = hotspotInfo.ssid,
                passphrase = hotspotInfo.passphrase,
                channel = hotspotInfo.channel,
                security = hotspotInfo.security,
                ipAddresses = listOf(hostAddressText),
                airPlayPort = airPlayConfig.port,
                deviceIdentifier = deviceIdentifier,
                publicKey = identity.publicKeyHex,
                sourceVersion = airPlayConfig.sourceVersion,
            )
            wirelessIdentification = identification
            wirelessAirPlayEndpoint = endpoint
            media.setIapTunnelHandler(::startWirelessTunnelControl)

            onStatus(CarPlayStatus.RunningWireless)
            debugLog("wireless Bluetooth iAP2 control starting")
            val activeChannel = channel
            val result = Iap2WirelessControlClient(
                session = activeChannel,
                mfi = Iap2MfiAuthenticationClient(mfi),
            ).run(
                identification = identification,
                endpoint = endpoint,
                timeoutMillis = controlLoopTimeoutMillis(),
                onReady = {
                    if (!isStaleWirelessRun(generation)) {
                        wirelessBootstrapAuthenticated.set(true)
                        if (!wirelessTunnelReady.get()) mediaState.bind(activeChannel)
                    }
                },
                onIncoming = { frame -> onControlFrame(activeChannel, frame) },
                onProgress = ::debugLog,
                beforeStartSession = { startedHotspot?.validateReady() },
                onStartSessionSent = watchdog::startSessionSent,
            )
            if (isStaleWirelessRun(generation)) {
                closeWirelessStack()
                return
            }
            when (result.terminal) {
                Iap2WirelessControlTerminal.CHANNEL_CLOSED -> {
                    wirelessBootstrapAuthenticated.set(false)
                    if (wirelessHandoffFellBack.get()) {
                        throw IOException("Wireless Bluetooth control channel closed during fallback")
                    }
                    debugLog(
                        "wireless RFCOMM EOF: iap2State=${result.stage} " +
                            "wirelessCarPlayAvailable=${result.wirelessCarPlayAvailableSeen} " +
                            "transportIdentifier=${result.transportNotificationSeen} " +
                            "carPlayStartSessions=${result.carPlayStartSessionsSent} " +
                            "postTransportConfigs=${result.postTransportWiFiConfigurationsSent} " +
                            "handoffRequested=${wirelessHandoffRequested.get()} " +
                            "tunnelReady=${wirelessTunnelReady.get()} " +
                            "wirelessActive=${wirelessActiveReported.get()}",
                    )
                    if (!wirelessActiveReported.get()) {
                        val handoffInProgress = isWirelessHandoffInProgress(
                            handoffRequested = wirelessHandoffRequested.get(),
                            tunnelActive = wirelessTunnelChannel != null,
                            sessionActive = activeSession != null,
                        )
                        if (!handoffInProgress) {
                            throw IOException(
                                "Wireless CarPlay control channel closed before tunnel iAP2 ready",
                            )
                        }
                        debugLog(
                            "wireless Bluetooth bootstrap closed during handoff; " +
                                "keeping the Wi-Fi AirPlay tunnel alive",
                        )
                    }
                }
                Iap2WirelessControlTerminal.TIMED_OUT ->
                    if (!wirelessActiveReported.get()) {
                        onStatus(CarPlayStatus.ControlEnded)
                    }
            }
        } catch (error: Throwable) {
            if (closed || generation != wirelessGeneration.get()) {
                return
            }
            if (wirelessActiveReported.get() && !wirelessHandoffFellBack.get() && error !is Error) {
                debugLog("wireless RFCOMM control ended after tunnel handoff: ${error.message}")
            } else {
                debugLog("wireless bring-up failed", error)
                if (error is Error) {
                    closeWirelessStack()
                    throw error
                }
                val startup = generateSequence(error) { it.cause }
                    .filterIsInstance<WirelessStartupException>().firstOrNull()
                if (startup?.reason?.retryable == true) {
                    recoverWireless(generation, error)
                } else {
                    closeWirelessStack()
                    fail(error, automaticRetry = startup?.reason?.retryable != false)
                }
            }
        }
    }

    private fun hotspotCandidates(info: WirelessHotspotInfo): List<InetAddress> = runCatching {
        val iface = info.interfaceName?.let(NetworkInterface::getByName)
        if (iface == null || !iface.isUp) return@runCatching emptyList()
        Collections.list(iface.inetAddresses).mapNotNull { address ->
            when {
                address is Inet6Address && address.isLinkLocalAddress && iface.index > 0 ->
                    Inet6Address.getByAddress(null, address.address, iface.index)
                address is Inet4Address && !address.isLoopbackAddress && !address.isAnyLocalAddress &&
                    !address.isLinkLocalAddress && !address.isMulticastAddress -> address
                else -> null
            }
        }
    }.getOrElse {
        debugLog("wireless address sampling failed: ${it.javaClass.simpleName}")
        emptyList()
    }

    private fun recoverWireless(generation: Int, error: Throwable, refreshFamily: Boolean = false) {
        synchronized(lifecycleLock) {
            if (isStaleWirelessRun(generation) || wirelessRestartPending.get()) return
            if (!wirelessRecovery.retry()) {
                firstTcpWatchdog?.terminate()
                restartWireless(terminalError = error)
                return
            }
            if (refreshFamily) {
                wirelessPublishedAddress?.let { published ->
                    wirelessAddressRecovery?.nextFamily(published)?.let {
                        preferredWirelessFamily = it
                    }
                }
            }
            debugLog(
                "wireless startup recovery: ${error.message}; " +
                    "preferredFamily=$preferredWirelessFamily",
            )
            restartWireless()
        }
    }

    private fun startWirelessTunnelControl(stream: BlockingDuplexByteStream): Boolean {
        if (closed) return false
        val identification = wirelessIdentification ?: return false
        val endpoint = wirelessAirPlayEndpoint ?: return false
        val mfi = mfiSession?.client ?: return false
        debugLog("wireless type-130 tunnel data stream accepted")
        val channel = try {
            Iap2Session.openTunnel(
                stream,
                traceContext = "wireless-tunnel",
                onTrace = ::debugLog,
            )
        } catch (error: Throwable) {
            debugLog("Could not open the tunneled iAP2 link", error)
            return false
        }
        wirelessTunnelChannel = channel
        val generation = wirelessGeneration.get()
        debugLog("wireless iAP2 tunnel control starting")
        return try {
            tunnelExecutor.execute {
                try {
                    val result = Iap2WirelessControlClient(
                        session = channel,
                        mfi = Iap2MfiAuthenticationClient(mfi),
                    ).run(
                        identification = identification,
                        endpoint = endpoint,
                        timeoutMillis = Iap2WirelessControlClient.NO_TIMEOUT_MILLIS,
                        onReady = {
                            if (!closed && generation == wirelessGeneration.get()) mediaState.bind(channel, retainState = true)
                            onWirelessTunnelReady(generation)
                        },
                        onIncoming = { frame -> onControlFrame(channel, frame) },
                        onProgress = { message -> debugLog("iAP tunnel $message") },
                    )
                    if (closed || generation != wirelessGeneration.get()) return@execute
                    when (result.terminal) {
                        Iap2WirelessControlTerminal.TIMED_OUT ->
                            onStatus(CarPlayStatus.ControlEnded)
                        Iap2WirelessControlTerminal.CHANNEL_CLOSED ->
                            if (!keepBluetoothControlAlive(generation)) {
                                onStatus(CarPlayStatus.Failed("Wireless iAP2 tunnel closed"))
                            }
                    }
                } catch (error: Throwable) {
                    if (!closed && generation == wirelessGeneration.get() &&
                        !keepBluetoothControlAlive(generation)
                    ) {
                        debugLog("tunneled iAP2 control failed", error)
                        onStatus(
                            CarPlayStatus.Failed(
                                error.message ?: error.javaClass.simpleName,
                            ),
                        )
                    }
                } finally {
                    mediaState.disconnected(channel)
                    if (wirelessTunnelChannel === channel) wirelessTunnelChannel = null
                }
            }
            true
        } catch (error: Throwable) {
            if (wirelessTunnelChannel === channel) wirelessTunnelChannel = null
            closeBestEffort("tunneled iAP2 link") { channel.close() }
            debugLog("iAP2 tunnel executor rejected the link", error)
            false
        }
    }

    private fun wirelessSessionListener(
        generation: Int,
        watchdog: FirstTcpWatchdog,
    ): AirPlaySessionListener =
        object : AirPlaySessionListener by sessionListener {
            override fun onTcpAccepted(event: AirPlayTcpAccepted) {
                if (!isStaleWirelessRun(generation)) watchdog.accepted(event)
            }

            override fun onTransportError(message: String) {
                if (isStaleWirelessRun(generation)) return
                watchdog.terminate()
                sessionListener.onTransportError(message)
            }

            override fun onSessionActive(session: AirPlaySession) {
                if (isStaleWirelessRun(generation)) return
                if (!watchdog.sessionEstablished()) return
                wirelessConnectionProof.activate(generation, session)
                mainHandler.postDelayed(
                    {
                        if (!isStaleWirelessRun(generation) && activeSession === session) {
                            wirelessRecovery.reset()
                        }
                    },
                    WirelessStartupPolicy.STABLE_SESSION_MILLIS,
                )
                sessionListener.onSessionActive(session)
            }

            override fun onSessionEnded(session: AirPlaySession) {
                if (isStaleWirelessRun(generation)) return
                wirelessConnectionProof.end(generation, session)
                sessionListener.onSessionEnded(session)
            }

            override fun onVideoFrameRendered(session: AirPlaySession) {
                if (isStaleWirelessRun(generation) || activeSession !== session) return
                wirelessConnectionProof.rendered(generation, session)
            }
        }

    private fun onWirelessTunnelReady(generation: Int) {
        synchronized(lifecycleLock) {
            if (
                closed ||
                phase != Phase.WIRELESS ||
                generation != wirelessGeneration.get()
            ) {
                return
            }
            wirelessTunnelReady.set(true)
            wirelessConnectionProof.authenticated(generation)
            debugLog(
                "wireless iAP2 tunnel ready; " +
                    "handoffRequested=${wirelessHandoffRequested.get()}",
            )
        }
        maybeCompleteWirelessHandoff()
    }

    private fun maybeCompleteWirelessHandoff() {
        val generation = synchronized(lifecycleLock) {
            if (closed || !wirelessHandoffRequested.get() || !wirelessTunnelReady.get()) return
            val firstHandoff = wirelessActiveReported.compareAndSet(false, true)
            val promotesFallback = wirelessHandoffFellBack.compareAndSet(true, false)
            if (!firstHandoff && !promotesFallback) return
            wirelessGeneration.get()
        }
        Thread(
            {
                if (
                    closed ||
                    phase != Phase.WIRELESS ||
                    generation != wirelessGeneration.get()
                ) {
                    return@Thread
                }
                debugLog("wireless handoff ready; closing Bluetooth bootstrap transport")
                closeBluetoothBootstrapTransport(generation)
                if (!isStaleWirelessRun(generation)) onStatus(CarPlayStatus.WirelessActive)
            },
            "xcertplay-wireless-handoff",
        ).apply {
            isDaemon = true
            start()
        }
    }

    private fun keepBluetoothControlAlive(generation: Int): Boolean =
        !isStaleWirelessRun(generation) && !wirelessTunnelReady.get() &&
            wirelessBootstrapAuthenticated.get() && csm?.isClosed == false &&
            activeSession != null && wirelessConnectionProof.hasRenderedFrame(generation)

    private fun armWirelessHandoffWatchdog(generation: Int) {
        mainHandler.postDelayed(
            {
                if (
                    closed ||
                    phase != Phase.WIRELESS ||
                    generation != wirelessGeneration.get() ||
                    !wirelessHandoffRequested.get() ||
                    wirelessActiveReported.get()
                ) {
                    return@postDelayed
                }
                val preserved = synchronized(lifecycleLock) {
                    if (!wirelessActiveReported.get() && keepBluetoothControlAlive(generation)) {
                        wirelessHandoffFellBack.set(true)
                        wirelessActiveReported.set(true)
                        debugLog(
                            "wireless handoff fallback: preserving rendered video and " +
                                "authenticated Bluetooth control",
                        )
                        onStatus(CarPlayStatus.WirelessActiveFallback)
                        true
                    } else {
                        false
                    }
                }
                if (preserved) return@postDelayed
                debugLog("wireless handoff timed out waiting for tunnel iAP2 readiness")
                Thread(
                    {
                        if (
                            closed ||
                            phase != Phase.WIRELESS ||
                            generation != wirelessGeneration.get() ||
                            wirelessActiveReported.get()
                        ) {
                            return@Thread
                        }
                        recoverWireless(
                            generation,
                            IOException("Wireless CarPlay handoff timed out waiting for tunnel iAP2"),
                        )
                    },
                    "xcertplay-wireless-handoff-timeout",
                ).apply {
                    isDaemon = true
                    start()
                }
            },
            WIRELESS_HANDOFF_TIMEOUT_MILLIS,
        )
    }

    private fun closeBluetoothBootstrapTransport(generation: Int? = null) {
        val resources = synchronized(lifecycleLock) {
            if (generation != null && isStaleWirelessRun(generation)) return
            Triple(csm, bluetoothStream, bluetoothSocket).also {
                csm = null
                bluetoothStream = null
                bluetoothSocket = null
                wirelessBootstrapAuthenticated.set(false)
            }
        }
        resources.first?.let { channel -> closeBestEffort("wireless CSM") { channel.close() } }
        resources.second?.let { stream -> closeBestEffort("wireless RFCOMM stream") { stream.close() } }
        resources.third?.let { socket -> closeBestEffort("wireless Bluetooth socket") { socket.close() } }
    }

    private fun isBluetoothHandoffCommand(type: String): Boolean =
        type.equals("disableBluetooth", ignoreCase = true) ||
            type.equals("disable-bluetooth", ignoreCase = true)

    private fun startWirelessHotspot(generation: Int): WirelessHotspotInfo {
        if (com.shilapi.xcertplay.network.CarHotspotStatus.isEnabled(appContext) == false) {
            // Informational only: firmware can misreport the AP state (Wi-Fi bridge / repeater modes).
            // The configured credentials are what the iPhone is told, so nothing here blocks.
            debugLog("manual hotspot reported off by the system; sending configured credentials anyway")
        }
        val manager: WirelessHotspotManager = ManualHotspotManager(
            context = appContext,
            ssid = config.manualHotspotSsid
                ?: throw IOException("Manual hotspot SSID is not configured"),
            passphrase = config.manualHotspotPassphrase.orEmpty(),
            band = config.manualHotspotBand,
            channel = config.manualHotspotChannel,
            security = config.manualHotspotSecurity,
            onDiagnostic = ::debugLog,
        )
        hotspot = manager
        return try {
            manager.start(WirelessStartupPolicy.HOTSPOT_READY_MILLIS)
        } catch (failure: Exception) {
            if (hotspot === manager) hotspot = null
            closeBestEffort("manual hotspot") { manager.close() }
            if (isStaleWirelessRun(generation)) throw failure
            if (failure is WirelessStartupException) throw failure
            throw IOException(
                "Could not establish manual hotspot: " +
                    (failure.message ?: failure.javaClass.simpleName),
                failure,
            )
        }
    }

    private fun isStaleWirelessRun(generation: Int): Boolean =
        closed || phase != Phase.WIRELESS || generation != wirelessGeneration.get()

    private fun selectWirelessBluetoothDevice(adapter: BluetoothAdapter): BluetoothDevice {
        val bonded = adapter.bondedDevices.orEmpty()
        config.wirelessBluetoothDeviceAddress?.let { selected ->
            return bonded.firstOrNull { it.address.equals(selected, ignoreCase = true) }
                ?: throw IOException("The selected iPhone is no longer paired. Choose it again in WheelPlay.")
        }
        val iPhones = bonded.filter { device ->
            device.name?.contains("iPhone", ignoreCase = true) == true
        }
        val directlyConnectedIPhones = iPhones.filter(::isBluetoothDeviceConnected)
        Log.i(
            TAG,
            "wireless Bluetooth bondedIPhones=${iPhones.size} " +
                "directlyConnected=${directlyConnectedIPhones.size}",
        )
        val connectedIPhones = if (directlyConnectedIPhones.isNotEmpty()) {
            directlyConnectedIPhones
        } else {
            val connectedAddresses = connectedBluetoothDevices(adapter).mapTo(mutableSetOf()) {
                it.address
            }
            iPhones.filter { it.address in connectedAddresses }
        }
        if (connectedIPhones.size == 1) return connectedIPhones.single()
        if (connectedIPhones.size > 1) {
            throw IOException(
                "Multiple connected iPhones found: " +
                    connectedIPhones.joinToString { "${it.name ?: "iPhone"} (${it.address})" },
            )
        }
        if (iPhones.size == 1) return iPhones.single()
        if (iPhones.size > 1) {
            throw IOException(
                "Multiple bonded iPhones found and none is currently connected; " +
                    "connect one iPhone and retry",
            )
        }
        if (bonded.size == 1) return bonded.single()
        throw IOException(
            "No unambiguous bonded iPhone found; pair one iPhone and retry",
        )
    }

    private fun connectBluetoothSocket(socket: BluetoothSocket, address: String) {
        val result = AtomicReference<Throwable?>()
        val connected = CountDownLatch(1)
        Thread(
            {
                try {
                    socket.connect()
                } catch (error: Throwable) {
                    result.set(error)
                } finally {
                    connected.countDown()
                }
            },
            "wireless-rfcomm-connect",
        ).apply {
            isDaemon = true
            start()
        }
        val completed = try {
            connected.await(RFCOMM_CONNECT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            runCatching { socket.close() }
            throw IOException("Interrupted while connecting RFCOMM to $address", error)
        }
        if (!completed) {
            debugLog(
                "wireless RFCOMM connect timed out after " +
                    "${RFCOMM_CONNECT_TIMEOUT_MILLIS}ms address=$address",
            )
            runCatching { socket.close() }
            throw IOException(
                "Timed out after ${RFCOMM_CONNECT_TIMEOUT_MILLIS}ms connecting RFCOMM to $address",
            )
        }
        when (val failure = result.get()) {
            null -> Unit
            is IOException -> throw failure
            else -> throw IOException("Could not connect RFCOMM to $address", failure)
        }
    }

    private fun closeWirelessStack(service: CarPlayVpnService? = vpnService) {
        firstTcpWatchdog?.terminate()
        firstTcpWatchdog = null
        wirelessAddressRecovery = null
        wirelessPublishedAddress = null
        releaseWifiLock()
        mediaState.disconnected()
        wirelessConnectionProof.clear()
        media.setIapTunnelHandler(null)
        val activeTunnel = wirelessTunnelChannel
        wirelessTunnelChannel = null
        if (activeTunnel != null) closeBestEffort("tunneled iAP2 link") { activeTunnel.close() }

        closeBluetoothBootstrapTransport()

        val activeBonjour = bonjour
        bonjour = null
        if (activeBonjour != null) closeBestEffort("Bonjour") { activeBonjour.close() }

        val activeHotspot = hotspot
        hotspot = null
        if (activeHotspot != null) closeBestEffort("wireless hotspot") { activeHotspot.close() }
        wirelessIdentification = null
        wirelessAirPlayEndpoint = null
        wirelessHandoffRequested.set(false)
        wirelessTunnelReady.set(false)
        wirelessActiveReported.set(false)
        wirelessHandoffFellBack.set(false)
        wirelessBootstrapAuthenticated.set(false)

        if (service != null) closeBestEffort("AirPlay service") { service.detach() }
    }

    private fun isBluetoothDeviceConnected(device: BluetoothDevice): Boolean = try {
        val method = BluetoothDevice::class.java.getMethod("isConnected")
        method.invoke(device) as? Boolean == true
    } catch (error: ReflectiveOperationException) {
        false
    } catch (error: RuntimeException) {
        Log.w(TAG, "Could not read Bluetooth connection state", error)
        false
    }

    private fun connectedBluetoothDevices(adapter: BluetoothAdapter): Set<BluetoothDevice> =
        buildSet {
            addAll(connectedBluetoothDevices(adapter, BluetoothProfile.HEADSET, BluetoothHeadset::class.java))
            addAll(connectedBluetoothDevices(adapter, BluetoothProfile.A2DP, BluetoothA2dp::class.java))
        }

    private fun <T : BluetoothProfile> connectedBluetoothDevices(
        adapter: BluetoothAdapter,
        profile: Int,
        profileClass: Class<T>,
    ): Set<BluetoothDevice> {
        val latch = CountDownLatch(1)
        val devices = java.util.Collections.synchronizedSet(mutableSetOf<BluetoothDevice>())
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profileId: Int, proxy: BluetoothProfile) {
                try {
                    if (profileClass.isInstance(proxy)) {
                        devices.addAll(proxy.connectedDevices.orEmpty())
                    }
                } catch (error: SecurityException) {
                    Log.w(TAG, "Could not read connected Bluetooth devices", error)
                } finally {
                    adapter.closeProfileProxy(profileId, proxy)
                    latch.countDown()
                }
            }

            override fun onServiceDisconnected(profileId: Int) {
                latch.countDown()
            }
        }
        if (!adapter.getProfileProxy(appContext, listener, profile)) return emptySet()
        if (!latch.await(3, TimeUnit.SECONDS)) {
            Log.w(TAG, "Timed out reading Bluetooth profile $profile")
        }
        return synchronized(devices) { devices.toSet() }
    }

    @Suppress("DEPRECATION")
    private fun accessoryBluetoothMac(adapter: BluetoothAdapter): String {
        val address = try {
            adapter.address
        } catch (_: SecurityException) {
            null
        }
        val settingsAddress = try {
            Settings.Secure.getString(appContext.contentResolver, "bluetooth_address")
        } catch (_: SecurityException) {
            null
        }
        return listOfNotNull(address, settingsAddress)
            .firstOrNull {
                BLUETOOTH_ADDRESS.matches(it) &&
                    !it.equals(ADAPTER_ADDRESS_PLACEHOLDER, ignoreCase = true)
            }
            ?: airPlayConfig.btMac
    }

    private fun hostAddressText(address: InetAddress): String {
        val text = address.hostAddress?.substringBefore('%')
        if (text.isNullOrBlank()) {
            throw IOException("Local hotspot host address is unavailable")
        }
        return text
    }

    private fun closeBestEffort(name: String, close: () -> Unit) {
        try {
            close()
        } catch (error: Throwable) {
            debugLog("$name teardown failed", error)
        }
    }

    private fun controlLoopTimeoutMillis(): Long = CONTROL_LOOP_TIMEOUT_MILLIS

    private fun awaitVpnService(): CarPlayVpnService? {
        vpnService?.let { return it }
        bindVpn()
        return try {
            if (vpnLatch.await(VPN_CONNECT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) vpnService else null
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
    }

    private fun bindVpn() {
        if (vpnBound) return
        vpnBound = true
        try {
            val intent = Intent(appContext, CarPlayVpnService::class.java)
            if (!appContext.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)) {
                vpnBound = false
                vpnLatch.countDown()
            }
        } catch (_: Throwable) {
            vpnBound = false
            vpnLatch.countDown()
        }
    }

    private fun unbindVpn() {
        if (!vpnBound) return
        vpnBound = false
        try {
            appContext.unbindService(serviceConnection)
        } catch (_: Exception) {
            // The service may have already been unbound.
        }
        vpnService = null
    }

    private fun closeMfiSession() {
        val session = mfiSession
        mfiSession = null
        if (session != null) {
            executor.execute {
                try {
                    session.close()
                } catch (_: Exception) {
                    // Best effort.
                }
            }
        }
    }

    private fun fail(error: Throwable, automaticRetry: Boolean = true) {
        if (closed) return
        mediaState.disconnected()
        onStatus(
            CarPlayStatus.Failed(
                error.message ?: error.javaClass.simpleName,
                automaticRetry = automaticRetry,
            ),
        )
    }

    private fun debugLog(message: String) {
        Log.i(TAG, message)
        try {
            uiListener?.onDebugLog(message)
        } catch (error: Exception) {
            Log.w(TAG, "debug log callback failed", error)
        }
    }

    private fun debugLog(message: String, error: Throwable) {
        Log.w(TAG, message, error)
        try {
            uiListener?.onDebugLog(
                "$message: ${error.message ?: error.javaClass.simpleName}",
            )
        } catch (callbackError: Exception) {
            Log.w(TAG, "debug log callback failed", callbackError)
        }
    }

    private fun onStatus(status: CarPlayStatus) {
        if (closed) return
        mainHandler.post {
            if (!closed && status != lastReportedStatus) {
                lastReportedStatus = status
                uiListener?.onDebugLog(status.debugLogMessage())
                uiStatusReporter?.invoke(status)
            }
        }
    }

    private fun CarPlayStatus.debugLogMessage(): String = when (this) {
        CarPlayStatus.DiscoveringMfi ->
            "STEP mfi/start: preparing the configured MFi authentication provider"
        CarPlayStatus.MfiReady ->
            "STEP mfi/ready: MFi authentication provider is ready"
        CarPlayStatus.StartingHotspot ->
            "STEP wifi/ap: starting the wireless CarPlay access point"
        is CarPlayStatus.HotspotReady ->
            "STEP wifi/ap-ready: backend=$backend ssid=$ssid band=$band " +
                "channel=$channel bssid=$bssid address=$address"
        CarPlayStatus.WaitingForPairedIphone ->
            "STEP bt/select: waiting for a paired or connected iPhone"
        CarPlayStatus.ConnectingBluetooth ->
            "STEP bt/rfcomm: connecting to the iPhone iAP2 RFCOMM service"
        CarPlayStatus.RunningWireless ->
            "STEP iap2/wireless: Bluetooth control loop running"
        CarPlayStatus.WirelessActive ->
            "STEP handoff/complete: tunnel iAP2 ready; Bluetooth bootstrap released"
        CarPlayStatus.WirelessActiveFallback ->
            "STEP handoff/fallback: rendered video and authenticated Bluetooth control retained"
        CarPlayStatus.AttachingNetwork ->
            "STEP network/attach: attaching the AirPlay network transport"
        CarPlayStatus.ControlEnded ->
            "STEP control/end: the control window ended"
        is CarPlayStatus.Failed ->
            "ERROR $message"
    }

    companion object {
        private const val TAG = "xcertplay-usb"
        private const val IAP2_IPHONE_UUID = "00000000-deca-fade-deca-deafdecacafe"
        private const val WIRELESS_UPLINK_WAIT_MILLIS = 15_000L
        private const val BLUETOOTH_ENABLE_WAIT_NANOS = 10_000_000_000L
        private const val BLUETOOTH_ENABLE_POLL_MILLIS = 250L
        private const val VPN_CONNECT_TIMEOUT_MILLIS = 10_000L
        private const val CONTROL_LOOP_TIMEOUT_MILLIS = 5 * 60_000L
        private const val WIRELESS_HANDOFF_TIMEOUT_MILLIS = 45_000L
        private const val RFCOMM_CONNECT_TIMEOUT_MILLIS = 15_000L
        private const val EXECUTOR_CLOSE_TIMEOUT_MILLIS = 2_000L
        private const val ADAPTER_ADDRESS_PLACEHOLDER = "02:00:00:00:00:00"
        private val BLUETOOTH_ADDRESS = Regex("^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$")
    }
}
