package com.shilapi.xcertplay.domain

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.AtomicFile
import android.util.Log
import com.shilapi.xcertplay.DiagnosticLogSink
import com.shilapi.xcertplay.web.LanTls
import com.shilapi.xcertplay.web.WebListenSettings
import com.shilapi.xcertplay.web.WebSession
import java.io.File
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps a DuckDNS A record pointed at this device and issues/renews a Let's Encrypt certificate
 * with an ACME DNS-01 challenge. The certificate is installed as the HTTPS listener's identity;
 * the existing certificate stays in place until a new one has been validated.
 */
internal object OwnDomainManager {
    data class Status(
        val phase: String = "未启用",
        val message: String = "",
        val domain: String = "",
        val address: String = "",
        val certificateUntilMillis: Long? = null,
        val lastError: String? = null,
    )

    @Volatile var status = Status()
        private set

    private const val TAG = "wheelplay-domain"
    private const val RENEW_BEFORE_MILLIS = 30L * 24 * 60 * 60 * 1000
    private const val CHECK_INTERVAL_MILLIS = 6L * 60 * 60 * 1000
    private const val RETRY_INTERVAL_MILLIS = 60L * 60 * 1000
    private const val RECORD_REFRESH_MILLIS = 6L * 60 * 60 * 1000
    private const val DEBOUNCE_MILLIS = 30_000L
    private const val INTERNET_WAIT_MILLIS = 20_000L

    private val scheduler = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "wheelplay-own-domain").apply { isDaemon = true }
    }
    private val busy = AtomicBoolean(false)
    @Volatile private var loopStarted = false
    @Volatile private var appContext: Context? = null
    @Volatile private var nextCheckAt = 0L
    @Volatile private var lastRunAt = 0L

    fun ensureStarted(context: Context) {
        val app = context.applicationContext
        appContext = app
        if (!OwnDomainSettings.load(app).enabled) {
            status = Status()
            return
        }
        startLoop()
        requestCheck(app)
    }

    fun requestCheck(context: Context, force: Boolean = false) {
        val app = context.applicationContext
        appContext = app
        if (!OwnDomainSettings.load(app).enabled) {
            status = Status()
            return
        }
        startLoop()
        if (force) nextCheckAt = 0
        scheduler.execute { runOnce(app, force) }
    }

    private fun startLoop() {
        if (loopStarted) return
        loopStarted = true
        scheduler.scheduleWithFixedDelay({
            val context = appContext ?: return@scheduleWithFixedDelay
            if (!OwnDomainSettings.load(context).enabled) return@scheduleWithFixedDelay
            if (System.currentTimeMillis() < nextCheckAt) return@scheduleWithFixedDelay
            runOnce(context, force = false)
        }, 0, 10, TimeUnit.MINUTES)
    }

    private fun runOnce(context: Context, force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastRunAt < DEBOUNCE_MILLIS) return
        if (!busy.compareAndSet(false, true)) return
        lastRunAt = now
        var config = OwnDomainSettings.load(context)
        try {
            if (!config.enabled) {
                status = Status()
                return
            }
            config.validate()?.let { throw OwnDomainException(it) }
            val address = config.addressOverride.ifBlank { localHotspotAddress() }
                ?: throw OwnDomainException("找不到安卓热点/信号桥的 IPv4 地址，请在“解析地址”里手动填写")
            status = Status("检查中", "正在检查 DuckDNS 解析与证书", config.domain, address)
            val networks = awaitInternetNetworks(context)
            if (networks.isEmpty()) {
                throw OwnDomainException(
                    "没有可用的互联网网络；请先连接 Wi-Fi 或移动数据后再更新 DuckDNS",
                )
            }
            var selected: InternetNetwork? = null
            var duckFailure: Throwable? = null
            for (network in networks) {
                try {
                    updateRecord(context, config, address) { url ->
                        network.network.openConnection(URL(url)) as HttpURLConnection
                    }
                    selected = network
                    break
                } catch (error: Throwable) {
                    duckFailure = error
                    log(
                        "DuckDNS update failed via iface=${network.interfaceName}: " +
                            (error.message ?: error.javaClass.simpleName).take(240),
                    )
                }
            }
            val network = selected
                ?: throw (duckFailure ?: OwnDomainException("DuckDNS 解析更新失败"))
            log(
                "using network iface=${network.interfaceName} " +
                    "validated=${network.validated}",
            )
            val openConnection: (String) -> HttpURLConnection = { url ->
                network.network.openConnection(URL(url)) as HttpURLConnection
            }

            val stored = readStored(context)
            val valid = stored != null && stored.cert.notAfter.time - now > RENEW_BEFORE_MILLIS &&
                matchesDomain(stored.cert, config.domain)
            if (valid) {
                val installed = LanTls.customCertificate(context)
                if (installed == null || !installed.encoded.contentEquals(stored.cert.encoded)) {
                    install(context, stored)
                }
                status = ready(config, address, stored.cert, "")
                nextCheckAt = System.currentTimeMillis() + CHECK_INTERVAL_MILLIS
                log("certificate valid until ${date(stored.cert.notAfter)}; next check in 6h")
                return
            }

            status = Status("申请中", "正在通过 DuckDNS 申请 Let's Encrypt 证书", config.domain, address)
            log("issuing certificate for ${config.domain} at $address")
            val issued = AcmeDns01Client(log = ::log, openConnection = openConnection).issue(
                domain = config.domain,
                accountKey = loadOrCreateAccountKey(context),
                email = config.email,
                setTxt = { value ->
                    val result = DuckDnsApi.update(DuckDnsApi.txtUrl(config.subdomain, config.token, value))
                    log("DuckDNS TXT update: ${result.response.lineSequence().firstOrNull().orEmpty()}")
                    result.ok
                },
                clearTxt = {
                    val result = DuckDnsApi.update(DuckDnsApi.clearTxtUrl(config.subdomain, config.token))
                    log("DuckDNS TXT clear: ${result.response.lineSequence().firstOrNull().orEmpty()}")
                },
            )
            val next = Stored(issued.chainPem, issued.keyPem, parseCertificate(issued.chainPem)
                ?: throw OwnDomainException("签发的证书无法解析"))
            writeStored(context, next)
            install(context, next)
            status = ready(config, address, next.cert, "")
            nextCheckAt = System.currentTimeMillis() + CHECK_INTERVAL_MILLIS
            log("certificate issued; valid until ${date(next.cert.notAfter)}")
        } catch (error: Throwable) {
            val message = buildString {
                append(error.message ?: error.javaClass.simpleName)
                if (error is AcmeDns01Client.AcmeException) {
                    error.problem?.takeIf { it.isNotBlank() }?.let {
                        if (!contains(it)) append(": ").append(it)
                    }
                }
            }.take(500)
            log("own domain failed: $message")
            status = Status(
                phase = "失败",
                message = "证书或解析失败；现有证书保持不变",
                domain = config.domain,
                address = status.address,
                certificateUntilMillis = status.certificateUntilMillis,
                lastError = message,
            )
            nextCheckAt = System.currentTimeMillis() + RETRY_INTERVAL_MILLIS
        } finally {
            busy.set(false)
        }
    }

    private fun ready(config: OwnDomainConfig, address: String, cert: X509Certificate, note: String): Status {
        val https = WebListenSettings.httpsEnabled(appContext ?: return Status())
        return Status(
            phase = if (https) "已启用" else "证书已就绪",
            message = buildString {
                append(if (https) "HTTPS 正在使用该证书" else "HTTPS 未启用，请在 Web 监听中打开")
                if (note.isNotBlank()) append("；").append(note)
            },
            domain = config.domain,
            address = address,
            certificateUntilMillis = cert.notAfter.time,
        )
    }

    private fun updateRecord(
        context: Context,
        config: OwnDomainConfig,
        address: String,
        openConnection: (String) -> HttpURLConnection,
    ) {
        val now = System.currentTimeMillis()
        val last = OwnDomainSettings.lastRecordAddress(context)
        if (last == address && now - OwnDomainSettings.lastRecordAt(context) < RECORD_REFRESH_MILLIS) return
        val result = try {
            DuckDnsApi.update(DuckDnsApi.addressUrl(config.subdomain, config.token, address), openConnection)
        } catch (error: Throwable) {
            throw OwnDomainException(
                "无法通过 HTTPS 连接 DuckDNS（${error.message ?: error.javaClass.simpleName}）",
            )
        }
        if (!result.ok) throw OwnDomainException("DuckDNS 更新解析失败：${result.response.take(160)}")
        OwnDomainSettings.saveRecord(context, address)
        log("A record ${config.domain} -> $address")
    }

    private data class InternetNetwork(
        val network: Network,
        val interfaceName: String,
        val validated: Boolean,
    )

    /** Bind DuckDNS/ACME traffic to a network that actually has internet access. */
    private fun awaitInternetNetworks(context: Context): List<InternetNetwork> {
        val deadline = System.currentTimeMillis() + INTERNET_WAIT_MILLIS
        var lastLog = 0L
        while (true) {
            internetNetworks(context).takeIf { it.isNotEmpty() }?.let { return it }
            if (System.currentTimeMillis() >= deadline) return emptyList()
            val now = System.currentTimeMillis()
            if (now - lastLog >= 5_000L) {
                lastLog = now
                log("waiting for an internet-capable network")
            }
            try {
                Thread.sleep(250L)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                return emptyList()
            }
        }
    }

    /**
     * Returns internet-capable, non-VPN networks ordered from best to worst. The process default
     * may be the local AP, the Tesla alias, or a stale VPN during bring-up; binding each request
     * avoids sending DuckDNS/ACME traffic out of an interface without upstream connectivity.
     */
    private fun internetNetworks(context: Context): List<InternetNetwork> {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        return connectivity.allNetworks.mapNotNull { network ->
            val capabilities = connectivity.getNetworkCapabilities(network) ?: return@mapNotNull null
            if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                return@mapNotNull null
            }
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            ) {
                return@mapNotNull null
            }
            val validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            val score = (if (validated) 4 else 0) +
                (if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) 2 else 0) +
                (if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) 1 else 0)
            InternetNetwork(
                network = network,
                interfaceName = connectivity.getLinkProperties(network)?.interfaceName ?: "unknown",
                validated = validated,
            ) to score
        }.sortedByDescending { it.second }.map { it.first }
    }

    private fun install(context: Context, stored: Stored) {
        val prepared = LanTls.preparePem(stored.chain, stored.key)
        prepared.use {
            val error = WebSession.installCertificate(context, it)
            if (error != null) throw OwnDomainException(error)
        }
        log("certificate installed into the HTTPS listener")
    }

    private fun localHotspotAddress(): String? {
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull() ?: return null
        var best: Pair<Int, String>? = null
        for (networkInterface in Collections.list(interfaces)) {
            val name = networkInterface.name
            if (!runCatching { networkInterface.isUp && !networkInterface.isLoopback }.getOrDefault(false)) continue
            if (EXCLUDED.any { name.startsWith(it) }) continue
            for (address in Collections.list(networkInterface.inetAddresses)) {
                if (address !is Inet4Address) continue
                val bytes = address.address
                // Site-local addresses plus 100.64.0.0/10, which Tesla-focused setups use as a LAN.
                val carrierGrade = bytes[0] == 100.toByte() && (bytes[1].toInt() and 0xC0) == 64
                if (!address.isSiteLocalAddress && !carrierGrade) continue
                var score = when {
                    name.startsWith("ap") || name.contains("softap", ignoreCase = true) -> 100
                    name.startsWith("p2p") -> 80
                    name.startsWith("wlan") -> 70
                    else -> 10
                }
                score += if (bytes[0] == 192.toByte() && bytes[1] == 168.toByte()) 30 else 20
                val host = address.hostAddress ?: continue
                if (best == null || score > best.first) best = score to host
            }
        }
        return best?.second
    }

    private data class Stored(val chain: ByteArray, val key: ByteArray, val cert: X509Certificate)

    private fun directory(context: Context) = File(context.noBackupFilesDir, "own-domain")

    private fun readStored(context: Context): Stored? {
        val dir = directory(context)
        val chain = readFile(File(dir, "chain.pem")) ?: return null
        val key = readFile(File(dir, "key.pem")) ?: return null
        val cert = parseCertificate(chain) ?: return null
        return Stored(chain, key, cert)
    }

    private fun writeStored(context: Context, stored: Stored) {
        val dir = directory(context)
        dir.mkdirs()
        writeFile(File(dir, "chain.pem"), stored.chain)
        writeFile(File(dir, "key.pem"), stored.key)
    }

    private fun parseCertificate(pem: ByteArray): X509Certificate? = runCatching {
        CertificateFactory.getInstance("X.509").generateCertificate(pem.inputStream()) as X509Certificate
    }.getOrNull()

    private fun matchesDomain(cert: X509Certificate, domain: String): Boolean = runCatching {
        cert.subjectAlternativeNames?.any { entry ->
            entry.size >= 2 && entry[0] == 2 && entry[1].toString().equals(domain, ignoreCase = true)
        } == true
    }.getOrDefault(false)

    private fun loadOrCreateAccountKey(context: Context): KeyPair {
        val dir = directory(context)
        dir.mkdirs()
        val privateFile = File(dir, "account-key.der")
        val publicFile = File(dir, "account-pub.der")
        val privateBytes = readFile(privateFile)
        val publicBytes = readFile(publicFile)
        if (privateBytes != null && publicBytes != null) {
            val restored = runCatching {
                val factory = KeyFactory.getInstance("EC")
                KeyPair(
                    factory.generatePublic(X509EncodedKeySpec(publicBytes)),
                    factory.generatePrivate(PKCS8EncodedKeySpec(privateBytes)),
                )
            }.getOrNull()
            if (restored != null) return restored
        }
        val generated = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
        }.generateKeyPair()
        writeFile(privateFile, generated.private.encoded)
        writeFile(publicFile, generated.public.encoded)
        return generated
    }

    private fun readFile(file: File): ByteArray? = runCatching {
        if (file.isFile && file.length() in 1..(64 * 1024)) file.readBytes() else null
    }.getOrNull()

    private fun writeFile(file: File, bytes: ByteArray) {
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            output.write(bytes)
            atomic.finishWrite(output)
        } catch (error: Throwable) {
            atomic.failWrite(output)
            throw error
        }
        file.setReadable(false, false)
        file.setReadable(true, true)
        file.setWritable(false, false)
        file.setWritable(true, true)
    }

    private fun date(date: Date): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(date)

    private fun log(message: String) {
        Log.i(TAG, message)
        DiagnosticLogSink.append("Own domain: $message")
    }

    private class OwnDomainException(message: String) : Exception(message)

    private val EXCLUDED = listOf("rmnet", "ccmni", "pdp", "dummy", "tun", "ppp", "lo")
}
