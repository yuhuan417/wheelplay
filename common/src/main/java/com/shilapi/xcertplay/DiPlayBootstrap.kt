package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import java.security.MessageDigest

/** Installs the bundled authentication resources into private storage on first use. */
internal object DiPlayBootstrap {
    @Synchronized fun ensure(context: Context) {
        val store = MfiAssetStore(context.noBackupFilesDir)
        if (!store.exists()) {
            store.installFiles(
                { context.assets.open("offline-mfi/identity.pk8") },
                { context.assets.open("offline-mfi/certificate.p7b") },
            )
        }
        store.load()
        AirPlayPersistence.saveDebugLogsEnabled(context, false)
    }

    fun deviceId(identity: AirPlayIdentity): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(identity.publicKey).take(6).toByteArray()
        bytes[0] = ((bytes[0].toInt() and 0xfc) or 0x02).toByte()
        return bytes.joinToString(":") { "%02X".format(it.toInt() and 0xff) }
    }
}

internal object DiPlayPreferences {
    private fun prefs(context: Context) = context.getSharedPreferences("diplay", Context.MODE_PRIVATE)
    fun phoneAddress(context: Context): String? = prefs(context).getString("phone_address", null)
    fun phoneName(context: Context): String = prefs(context).getString("phone_name", null) ?: "Your iPhone"
    fun savePhone(context: Context, address: String, name: String) {
        prefs(context).edit().putString("phone_address", address).putString("phone_name", name).apply()
    }
    fun autoConnect(context: Context) = prefs(context).getBoolean("auto_connect", false)
    fun saveAutoConnect(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("auto_connect", value).apply()
    }
}
