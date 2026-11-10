package com.shilapi.xcertplay

import com.shilapi.xcertplay.web.WebSession
import com.shilapi.xcertplay.web.WebListenSettings
import com.shilapi.xcertplay.web.RootAccess
import com.shilapi.xcertplay.media.CarPlayMediaSessionBridge
import com.shilapi.xcertplay.media.MediaCommand
import com.shilapi.xcertplay.media.NowPlayingState
import android.annotation.SuppressLint
import android.os.PowerManager
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.Manifest
import android.app.Notification
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.media.session.MediaSession
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.shilapi.xcertplay.host.R

/** Keeps the connection and its media session alive while no dashboard is visible. */
class DiPlaySessionService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private lateinit var media: CarPlayMediaSessionBridge
    private var sessionSubscription: AutoCloseable? = null
    private val main = Handler(Looper.getMainLooper())
    private var destroyed = false
    private var activationBlocked = false
    private var standby = false
    /** Guards the delayed power-loss standby so a reconnect cancels it. */
    private var powerLossStandbyToken = 0
    /** Guards the delayed power-on so a power dip cannot wake the radios. */
    private var powerWakeToken = 0

    @Volatile private var radioPowerOffVerified: Boolean? = null
    private var receiverRegistered = false
    private var currentState = NowPlayingState()
    private var currentSession: MediaSession? = null
    private var currentArtwork: Bitmap? = null

    /**
     * Runs while the device sits in power-save standby. MIUI (and anything else outside this app)
     * can bring Wi-Fi, the hotspot or Bluetooth back on without a wake request; this notices it,
     * records it and cuts the radios again instead of silently draining the battery.
     */
    private val standbyGuard = object : Runnable {
        override fun run() {
            runStandbyGuardCheck()
            if (!destroyed && standby) main.postDelayed(this, STANDBY_GUARD_INTERVAL_MILLIS)
        }
    }

    private fun runStandbyGuardCheck() {
        if (destroyed || activationBlocked || !standby) return
        val radios = RootRadioPower.radiosBackOn(this)
        if (!radios.any) return
        if (radios.bluetoothOnly) {
            // MIUI restores Bluetooth about 24 s after it is turned off. Bluetooth has an
            // in-process API, so it goes down again immediately instead of waiting for a root
            // round trip - the same rule as Wi-Fi and the hotspot: standby means the radio is off.
            runCatching { RootRadioPower.setBluetoothEnabled(this, false) }
                .onFailure { log("待机守卫：关闭蓝牙失败（${it.message}）") }
            RootRadioPower.bluetoothRebound()
            log("待机守卫：蓝牙又自己打开了，已再次关闭（Wi-Fi 和热点未受影响）")
            return
        }
        log("待机守卫：没有收到唤醒请求，$radios 又自己打开了，重新关闭")
        // Drop the "already off" memo, otherwise the teardown would be skipped as a duplicate.
        RootRadioPower.lastStandbyRadioOff = null
        RootRadioPower.enterStandby(this)
    }

    private fun log(message: String) {
        Log.i(TAG, message)
        DiagnosticLogSink.append(message)
        appendStandbyLog(message)
    }

    /**
     * Standby and wake events happen while the CarPlay host UI is closed, so the in-app report has
     * no writer then. They get their own small file so a later power-loss investigation has the
     * facts even when nothing else was running.
     */
    private fun appendStandbyLog(message: String) {
        runCatching {
            val directory = File(filesDir, "logs").apply { mkdirs() }
            val file = File(directory, "standby.log")
            if (file.length() > STANDBY_LOG_MAX_BYTES) {
                val kept = file.readLines().takeLast(STANDBY_LOG_KEEP_LINES)
                file.writeText(kept.joinToString("\n", postfix = "\n"))
            }
            val line = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date()) + "  " + message
            file.appendText(line + "\n")
        }
    }

    private fun scheduleStandbyGuard() {
        main.removeCallbacks(standbyGuard)
        if (!standby || destroyed) return
        main.postDelayed(standbyGuard, STANDBY_GUARD_INTERVAL_MILLIS)
        // With the screen off and no wake lock the CPU suspends, so a plain handler timer can be
        // deferred for the whole standby. The alarm wakes the CPU for the check (deep doze still
        // rate-limits it, which is the right trade-off once the car has been off for a long time).
        runCatching {
            val alarm = getSystemService(AlarmManager::class.java) ?: return@runCatching
            val intent = Intent(this, DiPlaySessionService::class.java)
                .setAction(ACTION_STANDBY_GUARD)
            val pending = PendingIntent.getService(
                this,
                STANDBY_GUARD_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val at = System.currentTimeMillis() + STANDBY_GUARD_ALARM_MILLIS
            alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        }
    }

    private fun cancelStandbyGuard() {
        main.removeCallbacks(standbyGuard)
        runCatching {
            val alarm = getSystemService(AlarmManager::class.java) ?: return@runCatching
            alarm.cancel(
                PendingIntent.getService(
                    this,
                    STANDBY_GUARD_REQUEST_CODE,
                    Intent(this, DiPlaySessionService::class.java).setAction(ACTION_STANDBY_GUARD),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        }
    }

    private val wakeAndPowerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (destroyed || activationBlocked) return
            when (intent.action) {
                Intent.ACTION_POWER_CONNECTED -> {
                    log("收到通电广播：随车供电已恢复（${batterySummary(context)}）")
                    CarPlayBackgroundSession.powerDisconnected = false
                    // A power dip that comes back before the delay cancels the standby entirely.
                    cancelPendingPowerLossStandby()
                    if (standby || !WebSession.running || !CarPlayBackgroundSession.active) {
                        schedulePowerWake()
                    }
                }
                Intent.ACTION_POWER_DISCONNECTED -> {
                    log("收到断电广播：随车供电已断开（${batterySummary(context)}）")
                    CarPlayBackgroundSession.powerDisconnected = true
                    cancelPendingPowerWake()
                    schedulePowerLossStandby()
                }
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    if (CarPlayBackgroundSession.isUnpluggedOnBattery(context)) return
                    val device = extractBluetoothDevice(intent)
                    if (matchesTargetIphone(device) && !CarPlayBackgroundSession.active) {
                        wakeFromStandby("iPhone 蓝牙已连接")
                    }
                }
                BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED -> {
                    if (CarPlayBackgroundSession.isUnpluggedOnBattery(context)) return
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_CONNECTION_STATE, -1)
                    if (state == BluetoothAdapter.STATE_CONNECTED) {
                        val device = extractBluetoothDevice(intent)
                        if (matchesTargetIphone(device) && !CarPlayBackgroundSession.active) {
                            wakeFromStandby("iPhone 蓝牙已连接")
                        }
                    }
                }
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)
                    log("收到蓝牙状态变化：state=$state（待机=$standby）")
                    if (CarPlayBackgroundSession.isUnpluggedOnBattery(context)) {
                        log("蓝牙事件被忽略：手机在电池供电")
                        return
                    }
                    if (state == BluetoothAdapter.STATE_ON &&
                        (standby || CarPlayBackgroundSession.standby) &&
                        !CarPlayBackgroundSession.active
                    ) {
                        wakeFromStandby("蓝牙已开启")
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Wireless standby cuts the radio with `su`; ask for the grant while the user can see the
        // Magisk prompt instead of failing silently when the car cuts power.
        RootAccess.checkOnStartup()
        com.shilapi.xcertplay.domain.OwnDomainManager.ensureStarted(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "CarPlay 服务", NotificationManager.IMPORTANCE_LOW))
        media = CarPlayMediaSessionBridge(this) { state, session, artwork ->
            currentState = state; currentSession = session; currentArtwork = artwork
            if (!destroyed) updateNotification()
        }
        sessionSubscription = CarPlayBackgroundSession.subscribe { _ -> main.post {
            if (!destroyed) {
                val current = CarPlayBackgroundSession.snapshot()
                media.bind(current?.controller, current?.sink)
            }
        } }
        registerWakeAndPowerReceiver()
    }

    override fun onBind(intent: Intent?): IBinder? = null
    @SuppressLint("WakelockTimeout") // Owned by this foreground service, released in onDestroy.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            standby = false
            powerLossLatch = false
            media.close()
            runWebAction { WebSession.stop() }
            CarPlayBackgroundSession.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_MEDIA && !currentState.available && !CarPlayBackgroundSession.hasSession()) {
            stopSelf()
            return START_NOT_STICKY
        }
        activationBlocked = false
        if (intent?.action == ACTION_STANDBY) {
            standby = true
        } else if (intent?.action != ACTION_MEDIA) {
            standby = false
        }
        updateNotification()
        // Foreground startup must be acknowledged even when an external start is rejected.
        // Never open listeners or acquire a wake lock without a validated identity.
        if (runCatching { DiPlayBootstrap.ensure(this) }.isFailure) {
            activationBlocked = true
            standby = false
            WebSession.stop()
            CarPlayBackgroundSession.stop()
            releaseLocks()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_MEDIA) {
            if (intent.getLongExtra(EXTRA_GENERATION, -1) == media.commandGeneration) {
                intent.getStringExtra(EXTRA_COMMAND)?.let { name ->
                    MediaCommand.entries.firstOrNull { it.name == name }?.let(media::command)
                }
            }
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_STANDBY_GUARD) {
            runStandbyGuardCheck()
            scheduleStandbyGuard()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_STANDBY) {
            log("收到待机请求（幂等，不会当成唤醒）")
            enterStandby()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_WAKE) {
            CarPlayBackgroundSession.powerDisconnected = false
            wakeFromStandby("手动唤醒")
            return START_NOT_STICKY
        }
        if (powerLossLatch || CarPlayBackgroundSession.isUnpluggedOnBattery(this)) {
            log(
                "服务被启动（action=${intent?.action}），仍在电池供电：保持待机" +
                    "（${batterySummary(this)}）",
            )
            CarPlayBackgroundSession.requestStandby("未检测到随车供电，已进入省电待机")
            enterStandby()
            return START_NOT_STICKY
        }
        log("服务被启动（action=${intent?.action}）：${batterySummary(this)}，开始工作")
        activateWebAndLocks()
        return START_NOT_STICKY
    }

    @SuppressLint("WakelockTimeout")
    private fun activateWebAndLocks() {
        standby = false
        radioPowerOffVerified = null
        CarPlayBackgroundSession.standby = false
        RootRadioPower.wakeFromStandby(this) {
            // Wi-Fi is now up (or wake failed); retry DNS/certificate work after the radio.
            com.shilapi.xcertplay.domain.OwnDomainManager.requestCheck(this, force = true)
        }
        runWebAction {
            if (!destroyed) WebSession.start(this)
            updateNotification()
            val acquireWakeLock = {
                if (!destroyed && !standby && WebSession.running) {
                    if (wakeLock == null) {
                        wakeLock = getSystemService(PowerManager::class.java)
                            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WheelPlay:server").apply { acquire() }
                    }
                    if (wifiLock == null) {
                        @Suppress("DEPRECATION")
                        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                        } else {
                            WifiManager.WIFI_MODE_FULL_HIGH_PERF
                        }
                        wifiLock = getSystemService(WifiManager::class.java)
                            ?.createWifiLock(mode, "WheelPlay:lowLatency")
                            ?.apply {
                                setReferenceCounted(false)
                                runCatching { acquire() }
                            }
                    }
                }
            }
            if (Looper.myLooper() == Looper.getMainLooper()) acquireWakeLock() else main.post { acquireWakeLock() }
        }
    }

    /**
     * Car-charger rails dip for a second or two when the ignition is toggled and while USB power
     * is negotiated. Entering standby on the first ACTION_POWER_DISCONNECTED tore down a live
     * CarPlay session, Bluetooth and the hotspot for an 8 s dip, and the (slow) radio power-off
     * then landed after the wake. Wait for the state to persist instead.
     */
    private fun schedulePowerLossStandby() {
        val token = ++powerLossStandbyToken
        main.postDelayed(
            {
                if (destroyed || activationBlocked || token != powerLossStandbyToken) return@postDelayed
                if (!CarPlayBackgroundSession.powerDisconnected) return@postDelayed
                log("随车断电已持续 ${POWER_LOSS_STANDBY_DELAY_MILLIS / 1000} 秒，进入待机省电")
                CarPlayBackgroundSession.requestStandby("随车断电，已进入省电待机")
                enterStandby()
            },
            POWER_LOSS_STANDBY_DELAY_MILLIS,
        )
    }

    private fun cancelPendingPowerLossStandby() {
        powerLossStandbyToken += 1
    }

    /**
     * Car chargers, USB hubs and a half-seated plug all produce "power connected" pulses that last
     * a fraction of a second. Waking on those brought Wi-Fi, the hotspot and Bluetooth back up a
     * few seconds after a power-loss standby, with nobody near the car. Wait for the rail to stay
     * up, then verify it once more before touching the radios.
     */
    /**
     * What the battery service actually reports. A "power connected" broadcast with
     * `plugged=none` means the ROM fired a phantom event; `plugged=usb` means the connector
     * really saw a charger (a loose or dirty port is enough).
     */
    private fun batterySummary(context: Context): String = runCatching {
        val status = context.applicationContext.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
        ) ?: return@runCatching "电池状态未知"
        val plugged = when (status.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)) {
            0 -> "none"
            BatteryManager.BATTERY_PLUGGED_AC -> "ac"
            BatteryManager.BATTERY_PLUGGED_USB -> "usb"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
            else -> "unknown(${status.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)})"
        }
        val level = status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val charging = status.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ==
            BatteryManager.BATTERY_STATUS_CHARGING
        "plugged=$plugged 充电中=$charging 电量=$level%"
    }.getOrDefault("电池状态读取失败")

    private fun schedulePowerWake() {
        val token = ++powerWakeToken
        main.postDelayed(
            {
                if (destroyed || activationBlocked || token != powerWakeToken) return@postDelayed
                val unplugged = runCatching {
                    CarPlayBackgroundSession.isUnpluggedOnBattery(this)
                }.getOrDefault(false)
                if (unplugged) {
                    log("通电只持续了不到 ${POWER_WAKE_DEBOUNCE_MILLIS / 1000} 秒，继续待机省电")
                    return@postDelayed
                }
                wakeFromStandby(TRIGGER_POWER)
            },
            POWER_WAKE_DEBOUNCE_MILLIS,
        )
    }

    private fun cancelPendingPowerWake() {
        powerWakeToken += 1
    }

    private fun enterStandby() {
        if (destroyed || activationBlocked) return
        standby = true
        powerLossLatch = true
        CarPlayBackgroundSession.standby = true
        CarPlayBackgroundSession.active = false
        runWebAction { WebSession.stop() }
        releaseLocks()
        radioPowerOffVerified = null
        RootRadioPower.enterStandby(this) { verified ->
            radioPowerOffVerified = verified
            main.post { if (!destroyed) updateNotification() }
        }
        scheduleStandbyGuard()
        updateNotification()
    }

    internal fun wakeFromStandby(trigger: String) {
        if (destroyed || activationBlocked) return
        val unplugged = runCatching { CarPlayBackgroundSession.isUnpluggedOnBattery(this) }
            .getOrDefault(false)
        // On battery the radios must stay off: a Bluetooth reconnection or a stray service start
        // is not a reason to wake the whole stack. Only car power or an explicit tap may.
        if (unplugged && trigger != TRIGGER_POWER && trigger != TRIGGER_MANUAL) {
            log("唤醒被忽略（$trigger）：手机在电池供电，继续待机省电")
            return
        }
        log("唤醒（$trigger，电池供电=$unplugged）")
        powerLossLatch = false
        cancelStandbyGuard()
        if (runCatching { DiPlayBootstrap.ensure(this) }.isFailure) return
        activateWebAndLocks()
        if (!CarPlayBackgroundSession.wake(trigger) &&
            !CarPlayBackgroundSession.hasSession() &&
            DiPlayPreferences.autoConnect(this) &&
            DiPlayPreferences.phoneAddress(this) != null
        ) {
            runCatching {
                startActivity(
                    Intent(this, CarPlayHostActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
                )
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun extractBluetoothDevice(intent: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

    private fun matchesTargetIphone(device: BluetoothDevice?): Boolean {
        if (device == null) return false
        val targetAddress = DiPlayPreferences.phoneAddress(this)
        if (!targetAddress.isNullOrBlank()) {
            return device.address.equals(targetAddress, ignoreCase = true)
        }
        val name = runCatching { device.name }.getOrNull()
        return name == null || name.contains("iPhone", ignoreCase = true)
    }

    private fun registerWakeAndPowerReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(wakeAndPowerReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(wakeAndPowerReceiver, filter)
            }
            receiverRegistered = true
        }
    }

    private fun unregisterWakeAndPowerReceiver() {
        if (!receiverRegistered) return
        receiverRegistered = false
        runCatching { unregisterReceiver(wakeAndPowerReceiver) }
    }

    private fun runWebAction(action: () -> Unit) {
        // Ordinary Web listeners are unprivileged; Tesla routing has its own worker.
        action()
    }

    private fun mediaAction(command: MediaCommand, icon: Int, title: Int): Notification.Action {
        val intent = Intent(this, DiPlaySessionService::class.java).setAction(ACTION_MEDIA)
            .setData(Uri.parse("wheelplay://media/${media.commandGeneration}/${command.name}"))
            .putExtra(EXTRA_COMMAND, command.name).putExtra(EXTRA_GENERATION, media.commandGeneration)
        val pending = PendingIntent.getService(this, command.ordinal + 10, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Action.Builder(Icon.createWithResource(this, icon), getString(title), pending).build()
    }

    private fun updateNotification() {
        if (destroyed || activationBlocked) return
        val open = PendingIntent.getActivity(this, 0, Intent(this, DiPlayActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_diplay_notification)
            .setContentIntent(open).setOngoing(true)
        val session = currentSession
        if (session != null && currentState.available) {
            builder.setContentTitle(currentState.title?.takeIf { it.isNotBlank() } ?: currentState.appName?.takeIf { it.isNotBlank() } ?: getString(R.string.app_name))
                .setContentText(listOfNotNull(currentState.artist, currentState.album).filter { it.isNotBlank() }.joinToString(" · "))
                .setLargeIcon(currentArtwork).setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(mediaAction(MediaCommand.PREVIOUS, R.drawable.ic_media_skip_previous, R.string.notification_action_previous))
                .addAction(mediaAction(if (currentState.playing) MediaCommand.PAUSE else MediaCommand.PLAY,
                    if (currentState.playing) R.drawable.ic_media_pause else R.drawable.ic_media_play,
                    if (currentState.playing) R.string.notification_action_pause else R.string.notification_action_play))
                .addAction(mediaAction(MediaCommand.NEXT, R.drawable.ic_media_skip_next, R.string.notification_action_next))
                .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
        } else {
            val stop = PendingIntent.getService(this, 1, Intent(this, DiPlaySessionService::class.java)
                .setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val port = if (WebSession.running) WebSession.httpPort else WebListenSettings.httpPort(this)
            val statusText = when {
                WebSession.running -> "局域网串流服务运行中 · $port"
                standby && radioPowerOffVerified == false ->
                    "待机省电中 · 未能关闭 Wi-Fi/热点，请检查 Root 授权"
                standby -> "待机省电中 · 等待 iPhone 蓝牙或随车通电"
                else -> "正在准备串流服务…"
            }
            builder.setContentTitle(getString(R.string.app_name)).setContentText(statusText)
            if (standby) {
                val wake = PendingIntent.getService(this, 2, Intent(this, DiPlaySessionService::class.java)
                    .setAction(ACTION_WAKE), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                builder.addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_media_play),
                    "立即唤醒", wake).build())
            }
            builder.addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_media_stop),
                "停止服务", stop).build())
        }
        val notification = builder.build()
        if (Build.VERSION.SDK_INT >= 29) {
            var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            if (currentState.available && currentState.playing) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            startForeground(NOTIFICATION_ID, notification, types)
        } else startForeground(NOTIFICATION_ID, notification)
    }

    private fun releaseLocks() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        wifiLock?.let { if (it.isHeld) runCatching { it.release() } }
        wifiLock = null
    }

    override fun onDestroy() {
        destroyed = true
        cancelStandbyGuard()
        resetStandbyLatch()
        unregisterWakeAndPowerReceiver()
        sessionSubscription?.close()
        media.close()
        runWebAction { WebSession.stop() }
        releaseLocks()
        CarPlayBackgroundSession.stop()
        super.onDestroy()
    }
    companion object {
        const val ACTION_STOP = "com.shihab.diplay.DISCONNECT"
        const val ACTION_STANDBY = "com.shilapi.xcertplay.STANDBY"
        const val ACTION_WAKE = "com.shilapi.xcertplay.WAKE"
        private const val TAG = "WheelPlayStandby"
        private const val STANDBY_GUARD_INTERVAL_MILLIS = 30_000L
        /**
         * Set once a power-loss standby has run, and shared by every instance: while it is set,
         * nothing but car power coming back or an explicit tap may leave standby. A background
         * service start, a Bluetooth event or a ROM reboot of the radio is not a wake request.
         */
        @Volatile
        private var powerLossLatch = false

        /** The latch must not outlive the service the user stopped. */
        internal fun resetStandbyLatch() {
            powerLossLatch = false
        }
        private const val POWER_WAKE_DEBOUNCE_MILLIS = 5_000L
        internal const val ACTION_STANDBY_GUARD = "com.shilapi.xcertplay.STANDBY_GUARD"
        private const val STANDBY_GUARD_REQUEST_CODE = 3
        private const val STANDBY_GUARD_ALARM_MILLIS = 30_000L
        private const val STANDBY_LOG_MAX_BYTES = 256L * 1024L
        private const val STANDBY_LOG_KEEP_LINES = 1_500
        private const val TRIGGER_POWER = "随车通电"
        private const val TRIGGER_MANUAL = "手动唤醒"
        private const val ACTION_MEDIA = "com.shilapi.xcertplay.MEDIA_COMMAND"
        private const val EXTRA_COMMAND = "command"
        private const val EXTRA_GENERATION = "generation"
        private const val CHANNEL = "diplay_connection"
    private const val POWER_LOSS_STANDBY_DELAY_MILLIS = 20_000L
        internal const val NOTIFICATION_ID = 1
    }
}
