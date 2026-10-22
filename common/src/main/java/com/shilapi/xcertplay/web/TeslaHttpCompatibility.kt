package com.shilapi.xcertplay.web

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.shilapi.xcertplay.network.TeslaHttpConfig
import com.shilapi.xcertplay.network.TeslaHttpStatus
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Web-service-owned Root hotspot routing. */
internal object TeslaHttpCompatibility {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadScheduledExecutor { work ->
        Thread(work, "tesla-http-routing").apply { isDaemon = true }
    }
    private val routing = RootHttpRouting()
    private val probe = TeslaWifiProbeServer()
    private val revision = AtomicLong()
    @Volatile private var owner: Context? = null
    @Volatile private var tethered = emptySet<String>()
    @Volatile var status = TeslaHttpStatus()
        private set

    // Broadcast action/extra adapted from VPN Hotspot v2.17.1 TetheringManager.kt.
    private const val TETHER_STATE_CHANGED = "android.net.conn.TETHER_STATE_CHANGED"
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != TETHER_STATE_CHANGED) return
            tethered = tetheredInterfaces(intent)
            refresh()
        }
    }

    init { worker.scheduleWithFixedDelay(::updateRouting, 0, 2, TimeUnit.SECONDS) }

    internal fun tetheredInterfaces(intent: Intent): Set<String> =
        (intent.getStringArrayListExtra("tetherArray") ?: intent.getStringArrayExtra("tetherArray")?.toList().orEmpty())
            .filter { Regex("[a-zA-Z0-9_.-]{1,15}").matches(it) && it != "lo" }.toSet()

    private fun preferences(context: Context) = context.getSharedPreferences("tesla_http", Context.MODE_PRIVATE)
    fun config(context: Context): TeslaHttpConfig {
        WebListenSettings.migrate(context)
        val prefs = preferences(context)
        return runCatching {
            // The removed No-root VPN mode must not silently turn into Root routing after upgrade.
            val legacyVpn = prefs.getString("mode", null) == "VPN"
            TeslaHttpConfig(prefs.getBoolean("enabled", false) && !legacyVpn,
                prefs.getString("address", TeslaHttpConfig.DEFAULT_ADDRESS)!!,
                prefs.getInt("http_mapping_port", 80), prefs.getInt("https_mapping_port", 443),
                prefs.getBoolean("probe", false))
        }.getOrDefault(TeslaHttpConfig())
    }

    fun save(context: Context, config: TeslaHttpConfig) {
        val previous = config(context)
        preferences(context).edit().putBoolean("enabled", config.enabled)
            .putString("address", config.address).putInt("http_mapping_port", config.httpMappingPort)
            .putInt("https_mapping_port", config.httpsMappingPort).putBoolean("probe", config.probeResponder)
            .remove("mode").remove("vpn_address").apply()
        refresh(retry = true)
        if (previous.address != config.address)
            WebSession.refreshGeneratedCertificate(context)
    }

    fun start(context: Context) {
        main.post {
            if (owner != null) return@post
            val application = context.applicationContext
            owner = application
            try {
                val filter = IntentFilter(TETHER_STATE_CHANGED)
                val sticky = if (Build.VERSION.SDK_INT >= 33)
                    application.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
                else application.registerReceiver(receiver, filter)
                if (sticky != null) tethered = tetheredInterfaces(sticky)
            } catch (_: Exception) { tethered = emptySet() }
            refresh(retry = true)
        }
    }

    fun stop() {
        main.post {
            owner?.let { runCatching { it.unregisterReceiver(receiver) } }
            owner = null; tethered = emptySet(); status = TeslaHttpStatus()
            refresh()
        }
    }

    fun retry() = refresh(retry = true)

    internal fun plan(config: TeslaHttpConfig, downstreams: List<HttpDownstream>, httpPort: Int?, httpsPort: Int?,
                      authorized: Boolean): RootHttpPlan? =
        if (!authorized || !config.enabled || downstreams.isEmpty() || httpPort == null) null
        else RootHttpPlan(config.address, httpPort, downstreams, httpsPort,
            config.httpMappingPort, config.httpsMappingPort.takeIf { httpsPort != null })

    private fun refresh(retry: Boolean = false) {
        revision.incrementAndGet()
        val config = owner?.let(::config)
        status = TeslaHttpStatus(error = config?.takeIf { it.enabled }?.let { "正在准备 Root 热点路由…" })
        worker.execute { if (retry) routing.retry(); updateRouting() }
    }

    private fun updateRouting() {
        val version = revision.get()
        val context = owner
        val config = context?.let(::config)
        try {
            val downstreams = if (config?.enabled == true && RootAccess.granted) tethered.sorted().mapNotNull { name ->
                val iface = NetworkInterface.getByName(name) ?: return@mapNotNull null
                if (!iface.isUp || iface.isLoopback) return@mapNotNull null
                // Match an actual tethered interface, never Wi-Fi/cellular name heuristics.
                val address = iface.interfaceAddresses.firstOrNull { it.address is Inet4Address && it.networkPrefixLength in 1..30 }
                    ?: return@mapNotNull null
                require(address.address.hostAddress != config.address) { "共享 IP 与热点网关相同，请选择其他地址" }
                HttpDownstream(name, iface.index, "${address.address.hostAddress}/${address.networkPrefixLength}")
            } else emptyList()
            val basePlan = config?.let { plan(it, downstreams, WebSession.httpListenerPort, WebSession.httpsListenerPort, RootAccess.granted) }
            // The probe page is requested on port 80; only mirror the toggle into the root plan then.
            val plan = if (basePlan != null && config?.probeResponder == true && config.httpMappingPort == 80)
                basePlan.copy(probe = true) else basePlan
            val result = routing.reconcile(plan)
            // Root consent can take time. Never publish completion for a stopped/reconfigured owner.
            if (revision.get() == version && owner === context && (context == null || config(context) == config)) {
                val outcome = reconcileProbe(config, plan, result, context)
                status = if (config?.enabled == true && plan == null && result.error == null)
                    outcome.copy(error = if (!RootAccess.granted) "请先请求 Root 权限"
                        else if (WebSession.httpListenerPort == null) "请先启动 Web 监听"
                        else "请先开启系统热点；未检测到可共享的 IPv4 热点接口") else outcome
            } else {
                routing.reconcile(null)
                probe.stop()
            }
        } catch (error: Exception) {
            routing.reconcile(null)
            probe.stop()
            if (revision.get() == version && owner === context) status = TeslaHttpStatus(error = error.message ?: "Root 热点路由配置失败")
        }
    }

    /** Binds the DNS responder only while the Root transaction, the port-80 mapping and the toggle agree. */
    private fun reconcileProbe(config: TeslaHttpConfig?, plan: RootHttpPlan?, result: TeslaHttpStatus,
                               context: Context?): TeslaHttpStatus {
        val wanted = config?.enabled == true && config.probeResponder
        if (!wanted) {
            probe.stop()
            return result
        }
        if (config.httpMappingPort != 80) {
            probe.stop()
            return result.copy(probe = false, probeError = "联网检测应答需要映射 HTTP 端口为 80")
        }
        if (plan == null || result.error != null || context == null || !RootAccess.granted) {
            probe.stop()
            return result.copy(probe = false, probeError = result.error ?: "Root 路由未就绪")
        }
        if (!probe.start("0.0.0.0", plan.address, upstreamProvider(context, plan))) {
            return result.copy(probe = false, probeError = probe.error)
        }
        return result.copy(probe = true, probeRequests = probe.requests, probeError = null)
    }

    /**
     * Prefer the uplink networks' advertised resolvers, then the hotspot gateway. The gateway is
     * last because a tethering resolver may only accept queries that arrive on the hotspot
     * interface. Refreshed lazily, not per packet.
     */
    private fun upstreamProvider(context: Context, plan: RootHttpPlan): () -> List<InetAddress> {
        var refreshedAt = 0L
        var servers: List<InetAddress> = emptyList()
        return {
            val now = SystemClock.elapsedRealtime()
            if (now - refreshedAt > 15_000) {
                refreshedAt = now
                servers = upstreamServers(context, plan)
            }
            servers
        }
    }

    private fun upstreamServers(context: Context, plan: RootHttpPlan): List<InetAddress> {
        val servers = LinkedHashSet<InetAddress>()
        runCatching {
            val manager = context.getSystemService(ConnectivityManager::class.java) ?: return@runCatching
            manager.allNetworks.forEach { network ->
                val capabilities = manager.getNetworkCapabilities(network) ?: return@forEach
                if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return@forEach
                manager.getLinkProperties(network)?.dnsServers?.forEach(servers::add)
            }
        }
        for (downstream in plan.downstreams) {
            runCatching { InetAddress.getByName(downstream.subnet.substringBefore('/')) }.getOrNull()?.let(servers::add)
        }
        return servers.filter { !it.isLoopbackAddress && !it.isAnyLocalAddress && !it.isLinkLocalAddress &&
            it.hostAddress != plan.address }
    }
}
