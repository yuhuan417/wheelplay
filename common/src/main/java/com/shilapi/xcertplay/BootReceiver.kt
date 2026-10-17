package com.shilapi.xcertplay

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/** Starts the CarPlay host after boot or paired iPhone Bluetooth connection when enabled. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                if (!AirPlayPersistence.loadAutoStartOnBoot(context)) return
                val launch = Intent(context, DiPlayActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP,
                    )
                }
                try {
                    context.startActivity(launch)
                } catch (error: RuntimeException) {
                    Log.w(TAG, "Boot auto-start could not launch CarPlayHostActivity", error)
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

    private companion object {
        const val TAG = "xcertplay-boot"
    }
}
