package com.shilapi.xcertplay

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Starts the CarPlay host after boot or paired iPhone Bluetooth connection when enabled. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                if (!AirPlayPersistence.loadAutoStartOnBoot(context)) {
                    log(context, "开机自启：设置未开启，忽略")
                    return
                }
                // Launching an activity from a boot receiver is a background activity start, which
                // Android 10 blocks unless the app is exempt - the UI simply never appeared. The
                // foreground service is allowed and is what actually matters: it brings up the web
                // server and the power-loss standby logic without needing the screen.
                try {
                    context.startForegroundService(
                        Intent(context, DiPlaySessionService::class.java),
                    )
                    log(context, "开机自启：已启动 DiPlaySessionService")
                } catch (error: RuntimeException) {
                    log(context, "开机自启：service 启动失败（${error.javaClass.simpleName}: ${error.message}）")
                }
            }
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                if (!DiPlayPreferences.autoConnect(context)) return
                if (CarPlayBackgroundSession.isUnpluggedOnBattery(context)) return
                if (CarPlayBackgroundSession.active) return
                val targetAddress = DiPlayPreferences.phoneAddress(context)
                if (targetAddress.isNullOrBlank()) return
                @Suppress("DEPRECATION")
                val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                } ?: return
                if (!device.address.equals(targetAddress, ignoreCase = true)) return
                try {
                    context.startForegroundService(
                        Intent(context, DiPlaySessionService::class.java)
                            .setAction(DiPlaySessionService.ACTION_WAKE),
                    )
                } catch (error: RuntimeException) {
                    Log.w(TAG, "Bluetooth ACL auto-wake could not start DiPlaySessionService", error)
                }
            }
        }
    }

    /** logcat plus the standby log, which is readable after a boot even when the UI never opened. */
    private fun log(context: Context, message: String) {
        Log.i(TAG, message)
        runCatching {
            val directory = File(context.filesDir, "logs").apply { mkdirs() }
            val line = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date()) + "  " + message
            File(directory, "standby.log").appendText(line + "\n")
        }
    }

    private companion object {
        const val TAG = "xcertplay-boot"
    }
}
