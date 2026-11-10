package com.shilapi.xcertplay

import android.bluetooth.BluetoothManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ResultReceiver
import android.os.SystemClock
import android.util.Log
import androidx.annotation.Keep
import com.shilapi.xcertplay.network.CarHotspotStatus
import com.shilapi.xcertplay.network.WifiUplink
import com.shilapi.xcertplay.web.RootAccess
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Uses Root (`su`) during power-save standby to completely power down the system Wi-Fi radio,
 * Wi-Fi hotspot (SoftAP / Tethering / MIUI WLAN bridge) and Bluetooth, and powers them back on
 * when external power returns.
 *
 * Bluetooth and Wi-Fi come up immediately. The hotspot is created only after the Wi-Fi STA has
 * associated (or the bounded wait expires), because a single-radio device has to run the AP on the
 * STA channel: creating it earlier makes the framework retune the AP when the STA connects.
 */
internal object RootRadioPower {
    private const val TAG = "xcertplay-usb"
    private const val TETHERING_WIFI = 0
    private const val STANDBY_DEBOUNCE_MILLIS = 3_000L
    private const val WIFI_ASSOCIATION_POLL_MILLIS = 250L
    private const val WIFI_ASSOCIATION_LOG_INTERVAL_MILLIS = 2_000L
    private const val RADIO_OFF_CHECKS = 3
    private const val RADIO_OFF_RECHECK_MILLIS = 500L
    private const val SHELL_PATH =
        "export PATH=/system/bin:/system/xbin:/vendor/bin:/odm/bin:\$PATH"

    private val WIFI_INTERFACES_DOWN = """
        for path in /sys/class/net/*; do
          iface=${'$'}{path##*/}
          case "${'$'}iface" in
            wlan*|ap*|softap*|p2p*)
              ip link set "${'$'}iface" down >/dev/null 2>&1 || true
              ;;
          esac
        done
    """.trimIndent()

    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "wheelplay-root-radio").apply { isDaemon = true }
    }

    @Volatile
    internal var suspendedByStandby = false

    /** Last verified outcome of the standby teardown; null until a standby request completes. */
    @Volatile
    internal var lastStandbyRadioOff: Boolean? = null

    /** Suppresses the duplicate request produced by the service receiver and the standby intent. */
    @Volatile
    private var lastStandbyRequestAt = 0L

    /** Aborts the association wait (and the deferred hotspot start) when power is cut again. */
    private val wakeGeneration = AtomicInteger(0)

    /**
     * Overridable in unit tests so Robolectric tests never spawn real `su` processes.
     * On real Android devices (`Build.FINGERPRINT != "robolectric"`), always attempt `su -c`
     * because Magisk/KernelSU hides `/sbin/su` from unprivileged `File.exists()` stat checks.
     */
    @Volatile
    internal var rootAvailable: () -> Boolean = {
        val isRobolectric = Build.FINGERPRINT == null ||
            Build.FINGERPRINT.equals("robolectric", ignoreCase = true)
        !isRobolectric || RootAccess.granted
    }

    @Volatile
    internal var executeRootScript: (String) -> Boolean = ::defaultExecuteRootScript

    /** Test seam for the post-script check that both the Wi-Fi radio and the SoftAP are gone. */
    @Volatile
    internal var verifyRadioOff: (Context) -> Boolean = ::defaultVerifyRadioOff

    /** Test seams so unit tests do not touch the real Bluetooth stack. */
    @Volatile
    internal var bluetoothEnabled: (Context) -> Boolean = ::defaultBluetoothEnabled

    @Volatile
    internal var setBluetoothEnabled: (Context, Boolean) -> Unit = ::defaultSetBluetoothEnabled

    /** Test seam for the pre-hotspot check that the Wi-Fi STA is associated (channel fixed). */
    @Volatile
    internal var wifiAssociated: (Context) -> Boolean = WifiUplink::isAssociated

    /** Maximum time the hotspot start waits for the STA association; overridable in tests. */
    @Volatile
    internal var associationTimeoutMillis = 10_000L

    fun enterStandby(context: Context, onComplete: ((Boolean) -> Unit)? = null) {
        // A wake may arrive while the (slow) root power-off script is still running; the captured
        // generation lets the worker notice and step aside instead of tearing down the radios of a
        // device that has already woken up.
        val generation = wakeGeneration.incrementAndGet()
        val appContext = context.applicationContext
        val bluetoothWasEnabled = runCatching { bluetoothEnabled(appContext) }.getOrDefault(false)
        val duplicate = synchronized(this) {
            val now = SystemClock.elapsedRealtime()
            val duplicate = suspendedByStandby && now - lastStandbyRequestAt < STANDBY_DEBOUNCE_MILLIS
            lastStandbyRequestAt = now
            suspendedByStandby = true
            duplicate
        }
        if (duplicate) {
            onComplete?.invoke(lastStandbyRadioOff == true)
            return
        }

        worker.execute {
            var radioOff = false
            try {
                if (wakeGeneration.get() != generation) {
                    log("Standby aborted: the device woke before the radio power-off started")
                    return@execute
                }
                if (bluetoothWasEnabled) {
                    runCatching { setBluetoothEnabled(appContext, false) }
                        .onFailure { log("Standby Bluetooth disable failed: ${it.message}") }
                }
                if (!rootAvailable()) {
                    log("Standby radio power-off skipped: root is not available")
                    return@execute
                }
                val apkPath = appContext.applicationInfo?.sourceDir.orEmpty()
                val packageName = appContext.packageName
                // Grant WRITE_SETTINGS first so in-process ConnectivityManager/IWifiManager calls succeed
                executeRootScript(
                    "appops set $packageName WRITE_SETTINGS allow >/dev/null 2>&1; " +
                        "cmd appops set $packageName WRITE_SETTINGS allow >/dev/null 2>&1; " +
                        "exit 0",
                )
                runCatching { BinderRadioControl.stopHotspotAndWifi(appContext, packageName) }
                if (wakeGeneration.get() != generation) {
                    log("Standby aborted after the framework calls: the device woke")
                    return@execute
                }
                val script = buildStandbyScript(apkPath, packageName)
                val ok = executeRootScript(script)
                runCatching { BinderRadioControl.stopHotspotAndWifi(appContext, packageName) }
                radioOff = awaitRadioOff(appContext)
                if (!radioOff) {
                    // Some MIUI builds keep SoftAP alive after the framework calls and even after
                    // `svc wifi disable`; drop every Wi-Fi interface at the kernel level and retry.
                    log("SoftAP still visible after first pass; forcing Wi-Fi interfaces down")
                    executeRootScript(buildForceStandbyScript())
                    radioOff = awaitRadioOff(appContext)
                }
                log(
                    "Standby radio power-off executed (script=$ok, radioOff=$radioOff)",
                )
                if (wakeGeneration.get() != generation) {
                    // The power-off landed after the wake; put the radios back.
                    log("Standby raced with a wake; restoring radios")
                    wakeFromStandby(appContext)
                }
            } catch (error: Throwable) {
                log("Standby radio power-off failed: ${error.javaClass.simpleName}: ${error.message}")
            } finally {
                lastStandbyRadioOff = radioOff
                onComplete?.invoke(radioOff)
            }
        }
    }

    private fun awaitRadioOff(context: Context): Boolean {
        repeat(RADIO_OFF_CHECKS) { attempt ->
            if (attempt > 0) Thread.sleep(RADIO_OFF_RECHECK_MILLIS)
            if (runCatching { verifyRadioOff(context) }.getOrDefault(false)) return true
        }
        return false
    }

    /**
     * Names the radios that are on although standby turned them off, or null when everything
     * stayed off. Used by the standby guard to detect a wake nobody asked for.
     */
    internal fun radiosBackOn(context: Context): String? {
        val app = context.applicationContext
        val wifi = runCatching {
            app.getSystemService(WifiManager::class.java)?.isWifiEnabled == true
        }.getOrDefault(false)
        val hotspot = runCatching { CarHotspotStatus.isEnabled(app) }.getOrNull() == true
        val bluetooth = runCatching { bluetoothEnabled(app) }.getOrDefault(false)
        if (!wifi && !hotspot && !bluetooth) return null
        return "wifi=$wifi hotspot=$hotspot bluetooth=$bluetooth"
    }

    private fun defaultVerifyRadioOff(context: Context): Boolean {
        val wifiOff = runCatching {
            context.getSystemService(WifiManager::class.java)?.isWifiEnabled == false
        }.getOrDefault(false)
        // A hidden firmware may report null; treat that as "not known to be on".
        val hotspotOn = runCatching { CarHotspotStatus.isEnabled(context) }.getOrNull()
        return wifiOff && hotspotOn != true
    }

    private fun log(message: String) {
        Log.i(TAG, message)
        DiagnosticLogSink.append(message)
    }

    fun wakeFromStandby(context: Context, onComplete: (() -> Unit)? = null) {
        val generation = wakeGeneration.incrementAndGet()
        val appContext = context.applicationContext
        val wasSuspended = suspendedByStandby
        suspendedByStandby = false

        worker.execute {
            try {
                val wifiOff = runCatching {
                    appContext.getSystemService(WifiManager::class.java)?.isWifiEnabled == false
                }.getOrDefault(false)
                val hotspotOff = runCatching { CarHotspotStatus.isEnabled(appContext) == false }.getOrDefault(true)
                val bluetoothOff = runCatching { !bluetoothEnabled(appContext) }.getOrDefault(true)
                if (!wasSuspended && !wifiOff && !hotspotOff && !bluetoothOff) {
                    log("Wake radio power-on skipped: Wi-Fi, hotspot and Bluetooth are already on")
                    return@execute
                }

                // Bluetooth has an in-process API, so it does not depend on root.
                runCatching { setBluetoothEnabled(appContext, true) }
                    .onFailure { log("Wake Bluetooth enable failed: ${it.message}") }
                if (!rootAvailable()) {
                    log("Wake Wi-Fi/hotspot power-on skipped: root is not available")
                    return@execute
                }

                val apkPath = appContext.applicationInfo?.sourceDir.orEmpty()
                val packageName = appContext.packageName

                // Phase 1: Wi-Fi and Bluetooth immediately. The hotspot waits for the STA
                // association because the AP has to share the STA channel on this radio.
                val wifiScriptOk = executeRootScript(
                    buildWakeScript(
                        apkPath = apkPath,
                        packageName = packageName,
                        enableWifi = true,
                        enableHotspot = false,
                        enableBluetooth = true,
                    ),
                ).also {
                    runCatching {
                        BinderRadioControl.startWifiAndHotspot(
                            context = appContext,
                            callerPkg = packageName,
                            enableWifi = true,
                            enableHotspot = false,
                        )
                    }
                }
                val associated = awaitWifiAssociation(appContext, generation)
                log("Wake Wi-Fi association ready=$associated (wifiScript=$wifiScriptOk)")
                if (generation != wakeGeneration.get()) {
                    log("Wake aborted before starting the hotspot")
                    return@execute
                }

                // Phase 2: the STA channel is settled (or the bounded wait expired), so the AP can
                // be created on it without a later channel switch.
                val hotspotScriptOk = executeRootScript(
                    buildWakeScript(
                        apkPath = apkPath,
                        packageName = packageName,
                        enableWifi = false,
                        enableHotspot = true,
                    ),
                ).also {
                    runCatching {
                        BinderRadioControl.startWifiAndHotspot(
                            context = appContext,
                            callerPkg = packageName,
                            enableWifi = false,
                            enableHotspot = true,
                        )
                    }
                }
                log(
                    "Wake radio power-on executed (wifiScript=$wifiScriptOk " +
                        "hotspotScript=$hotspotScriptOk associationReady=$associated)",
                )
            } finally {
                onComplete?.invoke()
            }
        }
    }

    /**
     * Waits for the STA association, which fixes the channel the AP must use. Returns false when
     * the wait expired, was interrupted or was cancelled by a new standby request; the caller
     * starts the hotspot anyway, because a hotspot without an uplink is still usable for the car.
     */
    private fun awaitWifiAssociation(context: Context, generation: Int): Boolean {
        if (runCatching { wifiAssociated(context) }.getOrDefault(false)) return true
        // System.nanoTime() is monotonic on the device and keeps advancing under Robolectric,
        // where the simulated SystemClock would otherwise leave this loop without a deadline.
        val startNanos = System.nanoTime()
        val timeoutMillis = associationTimeoutMillis.coerceAtLeast(0)
        var lastLog = 0L
        while (true) {
            if (generation != wakeGeneration.get()) return false
            if (runCatching { wifiAssociated(context) }.getOrDefault(false)) return true
            val elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000
            if (elapsedMillis >= timeoutMillis) return false
            if (elapsedMillis - lastLog >= WIFI_ASSOCIATION_LOG_INTERVAL_MILLIS) {
                lastLog = elapsedMillis
                log("Waiting for the Wi-Fi STA association before starting the hotspot")
            }
            try {
                Thread.sleep(minOf(WIFI_ASSOCIATION_POLL_MILLIS, (timeoutMillis - elapsedMillis).coerceAtLeast(1)))
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
    }

    internal fun buildStandbyScript(apkPath: String, packageName: String): String = buildString {
        appendLine(SHELL_PATH)
        appendLine("appops set $packageName WRITE_SETTINGS allow >/dev/null 2>&1 || true")
        appendLine("cmd appops set $packageName WRITE_SETTINGS allow >/dev/null 2>&1 || true")
        if (apkPath.isNotBlank()) {
            val helperRoot = "CLASSPATH='$apkPath' /system/bin/app_process /system/bin com.shilapi.xcertplay.RootHotspotMain stop $packageName"
            val helperSys = "CLASSPATH='$apkPath' /system/bin/app_process /system/bin com.shilapi.xcertplay.RootHotspotMain stop android"
            // Keep the helper output: when Binder calls are denied this is the only diagnostic.
            appendLine("$helperRoot || true")
            appendLine("su 1000 -c \"$helperSys\" || true")
        }
        appendLine("cmd connectivity tether stop wifi >/dev/null 2>&1 || true")
        appendLine("cmd tethering stop wifi >/dev/null 2>&1 || true")
        appendLine("cmd wifi stop-softap >/dev/null 2>&1 || true")
        appendLine("svc wifi disable >/dev/null 2>&1 || true")
        appendLine("cmd wifi set-wifi-enabled disabled >/dev/null 2>&1 || true")
        appendLine("svc bluetooth disable >/dev/null 2>&1 || true")
        appendLine("cmd bluetooth_manager disable >/dev/null 2>&1 || true")
        appendLine("setprop ctl.stop hostapd >/dev/null 2>&1 || true")
        // Several ROMs (MIUI included) keep the SoftAP beaconing after `svc wifi disable`.
        // Dropping every Wi-Fi/AP interface guarantees the SSID disappears.
        appendLine("killall -9 hostapd >/dev/null 2>&1 || true")
        appendLine(WIFI_INTERFACES_DOWN)
        appendLine("exit 0")
    }

    /**
     * Second pass for ROMs where `svc wifi disable` leaves the SoftAP (and its SSID) alive.
     * Bringing every Wi-Fi interface down stops beaconing even when the framework still believes
     * tethering is active.
     */
    internal fun buildForceStandbyScript(): String = buildString {
        appendLine(SHELL_PATH)
        appendLine("killall -9 hostapd >/dev/null 2>&1 || true")
        appendLine(WIFI_INTERFACES_DOWN)
        appendLine("sleep 1")
        appendLine("killall -9 hostapd >/dev/null 2>&1 || true")
        appendLine(WIFI_INTERFACES_DOWN)
        appendLine("exit 0")
    }

    internal fun buildWakeScript(
        apkPath: String,
        packageName: String,
        enableWifi: Boolean,
        enableHotspot: Boolean,
        enableBluetooth: Boolean = false,
    ): String = buildString {
        appendLine("appops set $packageName WRITE_SETTINGS allow >/dev/null 2>&1 || true")
        appendLine("cmd appops set $packageName WRITE_SETTINGS allow >/dev/null 2>&1 || true")
        if (enableBluetooth) {
            appendLine("svc bluetooth enable >/dev/null 2>&1 || true")
            appendLine("cmd bluetooth_manager enable >/dev/null 2>&1 || true")
        }
        if (enableWifi) {
            appendLine("ip link set wlan0 up >/dev/null 2>&1 || true")
            appendLine("svc wifi enable >/dev/null 2>&1 || true")
            appendLine("cmd wifi set-wifi-enabled enabled >/dev/null 2>&1 || true")
        }
        if (enableHotspot) {
            if (!enableWifi) {
                // Standby brought the whole radio down; tethering needs the interface back up.
                appendLine("ip link set wlan0 up >/dev/null 2>&1 || true")
            }
            if (apkPath.isNotBlank()) {
                val helperSys = "CLASSPATH='$apkPath' /system/bin/app_process /system/bin com.shilapi.xcertplay.RootHotspotMain start android"
                val helperRoot = "CLASSPATH='$apkPath' /system/bin/app_process /system/bin com.shilapi.xcertplay.RootHotspotMain start $packageName"
                appendLine("su 1000 -c \"$helperSys\" || true")
                appendLine("$helperRoot || true")
            }
            appendLine("cmd connectivity tether start wifi >/dev/null 2>&1 || true")
            appendLine("cmd tethering start wifi >/dev/null 2>&1 || true")
        }
        appendLine("exit 0")
    }

    private fun defaultBluetoothEnabled(context: Context): Boolean = runCatching {
        context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
    }.getOrDefault(false)

    @Suppress("DEPRECATION")
    private fun defaultSetBluetoothEnabled(context: Context, enabled: Boolean) {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return
        runCatching { if (enabled) adapter.enable() else adapter.disable() }
            .onFailure { log("Bluetooth adapter ${if (enabled) "enable" else "disable"} failed: ${it.message}") }
    }

    private fun defaultExecuteRootScript(script: String): Boolean = try {
        val process = ProcessBuilder("su", "-c", script)
            .redirectErrorStream(true)
            .start()
        val output = StringBuilder()
        val readerThread = Thread({
            runCatching {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        if (output.length < 1000) output.append(line).append('\n')
                    }
                }
            }
        }, "root-radio-stdout").apply { isDaemon = true; start() }
        val finished = process.waitFor(20, TimeUnit.SECONDS)
        readerThread.join(500)
        if (!finished) {
            process.destroyForcibly()
            log("Root radio script timed out")
            false
        } else {
            val code = process.exitValue()
            if (output.isNotBlank()) {
                log("Root radio script output (exit=$code): ${output.toString().trim()}")
            }
            code == 0
        }
    } catch (error: Exception) {
        log("Root radio command failed: ${error.javaClass.simpleName}: ${error.message}")
        false
    }

    internal object BinderRadioControl {
        private fun getServiceBinder(name: String): IBinder? = runCatching {
            val sm = Class.forName("android.os.ServiceManager")
            sm.getMethod("getService", String::class.java).invoke(null, name) as? IBinder
        }.getOrNull()

        private fun getIWifiManager(): Any? = runCatching {
            val binder = getServiceBinder("wifi") ?: return@runCatching null
            Class.forName("android.net.wifi.IWifiManager\$Stub")
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, binder)
        }.getOrNull()

        private fun getIConnectivityManager(): Any? = runCatching {
            val binder = getServiceBinder("connectivity") ?: return@runCatching null
            Class.forName("android.net.IConnectivityManager\$Stub")
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, binder)
        }.getOrNull()

        fun stopHotspotAndWifi(context: Context?, callerPkg: String) {
            val candidatePackages = listOf(callerPkg, "android", "com.android.shell", "root").distinct()

            // 1. IConnectivityManager: untether all active tethered interfaces + stopTethering(0)
            getIConnectivityManager()?.let { cm ->
                val cmClass = cm.javaClass
                runCatching {
                    val tethered = cmClass.getMethod("getTetheredIfaces").invoke(cm) as? Array<*>
                    val untether = cmClass.getMethod("untether", String::class.java)
                    tethered?.filterIsInstance<String>()?.forEach { iface ->
                        runCatching { untether.invoke(cm, iface) }
                    }
                    for (iface in listOf("wlan1", "softap0", "ap0", "wlan0")) {
                        runCatching { untether.invoke(cm, iface) }
                    }
                }
                for (method in cmClass.methods.filter { it.name == "stopTethering" }) {
                    for (pkg in candidatePackages) {
                        runCatching {
                            when (method.parameterTypes.size) {
                                1 -> method.invoke(cm, TETHERING_WIFI)
                                2 -> method.invoke(cm, TETHERING_WIFI, pkg)
                                3 -> method.invoke(cm, TETHERING_WIFI, pkg, null)
                            }
                        }
                    }
                }
            }

            // 2. Context ConnectivityManager / TetheringManager stopTethering(0)
            if (context != null) {
                runCatching {
                    val conn = context.getSystemService(ConnectivityManager::class.java)
                    conn?.javaClass?.getMethod("stopTethering", Int::class.javaPrimitiveType)
                        ?.invoke(conn, TETHERING_WIFI)
                }
                runCatching {
                    val tm = context.getSystemService("tethering")
                    tm?.javaClass?.getMethod("stopTethering", Int::class.javaPrimitiveType)
                        ?.invoke(tm, TETHERING_WIFI)
                }
            }

            // 3. IWifiManager: stopSoftAp() + setWifiApEnabled(null, false) + setWifiEnabled(pkg, false)
            getIWifiManager()?.let { wm ->
                val wmClass = wm.javaClass
                runCatching { wmClass.getMethod("stopSoftAp").invoke(wm) }
                runCatching {
                    wmClass.methods.firstOrNull { it.name == "setWifiApEnabled" && it.parameterTypes.size == 2 }
                        ?.invoke(wm, null, false)
                }
                for (pkg in candidatePackages) {
                    runCatching {
                        wmClass.getMethod("setWifiEnabled", String::class.java, Boolean::class.javaPrimitiveType)
                            .invoke(wm, pkg, false)
                    }
                }
            }

            // 4. Context WifiManager fallback
            if (context != null) {
                runCatching {
                    val wifi = context.getSystemService(WifiManager::class.java)
                    wifi?.javaClass?.methods?.firstOrNull { it.name == "stopSoftAp" && it.parameterTypes.isEmpty() }
                        ?.invoke(wifi)
                    @Suppress("DEPRECATION")
                    wifi?.isWifiEnabled = false
                }
            }
        }

        fun startWifiAndHotspot(
            context: Context?,
            callerPkg: String,
            enableWifi: Boolean,
            enableHotspot: Boolean,
        ) {
            val candidatePackages = listOf(callerPkg, "android", "com.android.shell", "root").distinct()

            // 1. Enable Wi-Fi
            if (enableWifi) {
                getIWifiManager()?.let { wm ->
                    val wmClass = wm.javaClass
                    for (pkg in candidatePackages) {
                        runCatching {
                            wmClass.getMethod("setWifiEnabled", String::class.java, Boolean::class.javaPrimitiveType)
                                .invoke(wm, pkg, true)
                        }
                    }
                }
                if (context != null) {
                    runCatching {
                        @Suppress("DEPRECATION")
                        context.getSystemService(WifiManager::class.java)?.isWifiEnabled = true
                    }
                }
            }

            if (!enableHotspot) return

            // 2. Start Wi-Fi Tethering via IConnectivityManager (Android 8/9/10/11+)
            val receiver = ResultReceiver(Handler(Looper.getMainLooper()))
            getIConnectivityManager()?.let { cm ->
                val cmClass = cm.javaClass
                for (method in cmClass.methods.filter { it.name == "startTethering" }) {
                    for (pkg in candidatePackages) {
                        runCatching {
                            when (method.parameterTypes.size) {
                                3 -> method.invoke(cm, TETHERING_WIFI, receiver, false)
                                4 -> method.invoke(cm, TETHERING_WIFI, receiver, false, pkg)
                                5 -> method.invoke(cm, TETHERING_WIFI, receiver, false, pkg, null)
                            }
                        }
                    }
                }
            }

            // 3. Start Wi-Fi Tethering via TetheringManager (Android 11+)
            if (context != null) {
                runCatching {
                    val tm = context.getSystemService("tethering") ?: return@runCatching
                    val callbackClass = Class.forName("android.net.TetheringManager\$StartTetheringCallback")
                    val latch = CountDownLatch(1)
                    val callback = Proxy.newProxyInstance(
                        callbackClass.classLoader,
                        arrayOf(callbackClass),
                    ) { _, method, _ ->
                        if (method.name == "onTetheringStarted" || method.name == "onTetheringFailed") {
                            latch.countDown()
                        }
                        null
                    }
                    val executor = java.util.concurrent.Executor { it.run() }
                    tm.javaClass.methods.firstOrNull {
                        it.name == "startTethering" &&
                            it.parameterTypes.size == 3 &&
                            it.parameterTypes[0] == Int::class.javaPrimitiveType
                    }?.invoke(tm, TETHERING_WIFI, executor, callback)
                    latch.await(2, TimeUnit.SECONDS)
                }
            }

            // 4. Start SoftAP via IWifiManager if not yet enabled
            getIWifiManager()?.let { wm ->
                val wmClass = wm.javaClass
                val alreadyEnabled = runCatching {
                    (wmClass.getMethod("getWifiApEnabledState").invoke(wm) as? Int) == 13
                }.getOrDefault(false)
                if (!alreadyEnabled) {
                    runCatching {
                        wmClass.methods.firstOrNull { it.name == "startSoftAp" && it.parameterTypes.size == 1 }
                            ?.invoke(wm, null)
                    }
                    runCatching {
                        wmClass.methods.firstOrNull { it.name == "startTetheredHotspot" && it.parameterTypes.size == 1 }
                            ?.invoke(wm, null)
                    }
                }
            }
        }
    }
}

/**
 * Entry point invoked via `app_process` under root/system UID so Binder calls to `IWifiManager`
 * and `IConnectivityManager` execute with system/root privileges across Android 9–15.
 */
@Keep
object RootHotspotMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val action = args.firstOrNull() ?: return
        val callerPkg = args.getOrNull(1) ?: "android"
        try {
            @Suppress("DEPRECATION")
            if (Looper.getMainLooper() == null) {
                Looper.prepareMainLooper()
            }
            val context = runCatching {
                val activityThreadClass = Class.forName("android.app.ActivityThread")
                val activityThread = activityThreadClass.getMethod("systemMain").invoke(null)
                activityThreadClass.getMethod("getSystemContext").invoke(activityThread) as? Context
            }.getOrNull()

            if (action.equals("stop", ignoreCase = true)) {
                RootRadioPower.BinderRadioControl.stopHotspotAndWifi(context, callerPkg)
            } else if (action.equals("start", ignoreCase = true)) {
                RootRadioPower.BinderRadioControl.startWifiAndHotspot(
                    context = context,
                    callerPkg = callerPkg,
                    enableWifi = true,
                    enableHotspot = true,
                )
            }
        } catch (error: Throwable) {
            System.err.println("RootHotspotMain($action) failed: ${error.message}")
        }
    }
}
