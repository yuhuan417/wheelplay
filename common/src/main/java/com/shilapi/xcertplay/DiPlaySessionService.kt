package com.shilapi.xcertplay

import com.shilapi.xcertplay.web.WebSession
import com.shilapi.xcertplay.web.WebListenSettings
import com.shilapi.xcertplay.web.RootAccess
import com.shilapi.xcertplay.media.CarPlayMediaSessionBridge
import com.shilapi.xcertplay.media.MediaCommand
import com.shilapi.xcertplay.media.NowPlayingState
import android.annotation.SuppressLint
import android.os.PowerManager
import android.Manifest
import android.app.Notification
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
    private var receiverRegistered = false
    private var currentState = NowPlayingState()
    private var currentSession: MediaSession? = null
    private var currentArtwork: Bitmap? = null

    private val wakeAndPowerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (destroyed || activationBlocked) return
            when (intent.action) {
                Intent.ACTION_POWER_CONNECTED -> {
                    if (standby || !WebSession.running || !CarPlayBackgroundSession.active) {
                        wakeFromStandby("随车通电")
                    }
                }
                Intent.ACTION_POWER_DISCONNECTED -> {
                    if (!CarPlayBackgroundSession.active) {
                        if (!CarPlayBackgroundSession.requestStandby("随车断电，已进入省电待机")) {
                            enterStandby()
                        }
                    }
                }
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    val device = extractBluetoothDevice(intent)
                    if (matchesTargetIphone(device) && !CarPlayBackgroundSession.active) {
                        wakeFromStandby("iPhone 蓝牙已连接")
                    }
                }
                BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED -> {
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
        if (com.shilapi.xcertplay.web.TeslaHttpCompatibility.config(this).let {
            it.enabled && it.mode == com.shilapi.xcertplay.network.TeslaHttpMode.ROOT
        }) RootAccess.checkOnStartup()
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
        if (!(DiPlayBootstrap.importing && WebSession.running) &&
            runCatching { DiPlayBootstrap.ensure(this) }.isFailure) {
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
        if (intent?.action == ACTION_STANDBY) {
            enterStandby()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_WAKE) {
            wakeFromStandby("手动唤醒")
            return START_NOT_STICKY
        }
        activateWebAndLocks()
        return START_NOT_STICKY
    }

    @SuppressLint("WakelockTimeout")
    private fun activateWebAndLocks() {
        standby = false
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

    private fun enterStandby() {
        if (destroyed || activationBlocked) return
        standby = true
        runWebAction { WebSession.stop() }
        releaseLocks()
        updateNotification()
    }

    internal fun wakeFromStandby(trigger: String) {
        if (destroyed || activationBlocked) return
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
            if (Build.VERSION.SDK_INT >= 30 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
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
        private const val ACTION_MEDIA = "com.shilapi.xcertplay.MEDIA_COMMAND"
        private const val EXTRA_COMMAND = "command"
        private const val EXTRA_GENERATION = "generation"
        private const val CHANNEL = "diplay_connection"
        internal const val NOTIFICATION_ID = 1
    }
}
