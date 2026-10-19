package com.shilapi.xcertplay.domain

import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.Extensions
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Provider
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * Minimal ACME v2 client for a single DNS-01 authorization, using an EC P-256 account key and
 * ES256 JWS. Android has no acme.sh/openssl CLI, so the protocol is implemented here; the caller
 * supplies the DNS TXT record operations (DuckDNS).
 */
internal class AcmeDns01Client(
    private val directoryUrl: String = PRODUCTION_DIRECTORY,
    private val log: (String) -> Unit = {},
    private val sleep: (Long) -> Unit = Thread::sleep,
    private val dnsWaitMillis: Long = DEFAULT_DNS_WAIT_MILLIS,
    private val pollIntervalMillis: Long = DEFAULT_POLL_INTERVAL_MILLIS,
    private val pollTimeoutMillis: Long = DEFAULT_POLL_TIMEOUT_MILLIS,
    private val openConnection: (String) -> HttpURLConnection = {
        URL(it).openConnection() as HttpURLConnection
    },
) {
    data class Issued(val chainPem: ByteArray, val keyPem: ByteArray)

    class AcmeException(message: String, val problem: String? = null) : Exception(message)

    private val provider: Provider = BouncyCastleProvider()
    private lateinit var directory: JSONObject
    private lateinit var keyPair: KeyPair
    private lateinit var jwk: JSONObject
    private lateinit var thumbprint: String
    private var nonce: String? = null
    private var accountUrl: String? = null

    /**
     * Issues one certificate for [domain]. [setTxt] publishes the ACME TXT value, [clearTxt]
     * removes it best-effort. The returned chain is the PEM chain from the CA; the key is the new
     * PKCS#8 EC private key generated for this certificate.
     */
    fun issue(
        domain: String,
        accountKey: KeyPair,
        email: String,
        setTxt: (String) -> Boolean,
        clearTxt: () -> Unit,
    ): Issued {
        require(domain.isNotBlank()) { "domain must not be blank" }
        require(accountKey.private.algorithm.equals("EC", ignoreCase = true)) { "ACME account key must be EC" }
        this.keyPair = accountKey
        this.jwk = publicJwk(accountKey.public as ECPublicKey)
        this.thumbprint = thumbprint(this.jwk)
        this.nonce = null
        this.accountUrl = null

        log("ACME directory $directoryUrl")
        directory = JSONObject(get(directoryUrl).body.toString(StandardCharsets.UTF_8))
        ensureNonce()
        registerAccount(email.trim())

        val orderResponse = postJws(
            directory.getString("newOrder"),
            JSONObject().put("identifiers", org.json.JSONArray().put(
                JSONObject().put("type", "dns").put("value", domain),
            )).toString(),
            useJwk = false,
        )
        val orderUrl = orderResponse.location ?: throw AcmeException("ACME newOrder returned no order URL")
        val order = JSONObject(orderResponse.body.toString(StandardCharsets.UTF_8))
        val authzUrls = order.getJSONArray("authorizations")
        val finalizeUrl = order.getString("finalize")
        log("ACME order created for $domain")

        val authzUrl = authzUrls.getString(0)
        val challenge = selectDnsChallenge(authzUrl)
        val keyAuthorization = "${challenge.token}.$thumbprint"
        val txtValue = b64(sha256(keyAuthorization.toByteArray(StandardCharsets.US_ASCII)))
        try {
            if (!setTxt(txtValue)) throw AcmeException("DuckDNS rejected the ACME TXT record")
            log("ACME TXT record published; waiting ${dnsWaitMillis / 1000}s for DuckDNS propagation")
            sleep(dnsWaitMillis)
            postJws(challenge.url, "{}", useJwk = false)
            pollAuthorization(authzUrl)
            val certificateUrl = finalize(orderUrl, finalizeUrl, domain)
            val chain = postJwsAsGet(certificateUrl).body
            if (!chain.toString(StandardCharsets.US_ASCII).contains("BEGIN CERTIFICATE")) {
                throw AcmeException("ACME certificate response was not PEM")
            }
            log("ACME certificate issued for $domain")
            return Issued(chainPem = chain, keyPem = privateKeyPem())
        } finally {
            runCatching { clearTxt() }
        }
    }

    private fun registerAccount(email: String) {
        val newAccountUrl = directory.getString("newAccount")
        val payload = JSONObject().put("termsOfServiceAgreed", true)
        if (email.isNotBlank()) {
            payload.put("contact", org.json.JSONArray().put("mailto:$email"))
        }
        val response = try {
            postJws(newAccountUrl, payload.toString(), useJwk = true)
        } catch (error: AcmeException) {
            // A syntactically valid but undeliverable address is rejected as invalidContact.
            // The address is optional, so retry once without it instead of blocking issuance.
            val rejectedContact = error.problem?.contains("invalidContact", ignoreCase = true) == true ||
                error.problem?.contains("invalidEmail", ignoreCase = true) == true
            if (!rejectedContact || email.isBlank()) throw error
            log("ACME rejected the contact email; retrying without a contact")
            postJws(
                newAccountUrl,
                JSONObject().put("termsOfServiceAgreed", true).toString(),
                useJwk = true,
            )
        }
        accountUrl = response.location ?: throw AcmeException("ACME newAccount returned no account URL")
        log("ACME account ready")
    }

    private data class Challenge(val token: String, val url: String)

    private fun selectDnsChallenge(authzUrl: String): Challenge {
        val authz = JSONObject(postJwsAsGet(authzUrl).body.toString(StandardCharsets.UTF_8))
        if (authz.optString("status") == "valid") {
            throw AcmeException("ACME authorization is already valid but no certificate was finalized")
        }
        val challenges = authz.getJSONArray("challenges")
        for (index in 0 until challenges.length()) {
            val challenge = challenges.getJSONObject(index)
            if (challenge.optString("type") == "dns-01") {
                return Challenge(challenge.getString("token"), challenge.getString("url"))
            }
        }
        throw AcmeException("ACME authorization offered no dns-01 challenge")
    }

    private fun pollAuthorization(authzUrl: String) {
        val deadline = System.currentTimeMillis() + pollTimeoutMillis
        while (true) {
            val authz = JSONObject(postJwsAsGet(authzUrl).body.toString(StandardCharsets.UTF_8))
            when (authz.optString("status")) {
                "valid" -> { log("ACME authorization valid"); return }
                "invalid", "revoked", "deactivated", "expired" -> {
                    throw AcmeException("ACME authorization ${authz.optString("status")}",
                        challengeError(authz))
                }
            }
            if (System.currentTimeMillis() > deadline) throw AcmeException("ACME authorization timed out")
            sleep(pollIntervalMillis)
        }
    }

    private fun challengeError(authz: JSONObject): String? {
        val challenges = authz.optJSONArray("challenges") ?: return null
        for (index in 0 until challenges.length()) {
            val challenge = challenges.getJSONObject(index)
            val error = challenge.optJSONObject("error") ?: continue
            return error.optString("detail").ifBlank { error.optString("type") }
        }
        return null
    }

    private fun finalize(orderUrl: String, finalizeUrl: String, domain: String): String {
        val certKey = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
        }.generateKeyPair()
        certificateKey = certKey
        val csr = csr(domain, certKey)
        postJws(finalizeUrl, JSONObject().put("csr", b64(csr)).toString(), useJwk = false)
        val deadline = System.currentTimeMillis() + pollTimeoutMillis
        while (true) {
            val order = JSONObject(postJwsAsGet(orderUrl).body.toString(StandardCharsets.UTF_8))
            when (order.optString("status")) {
                "valid" -> return order.optString("certificate").takeIf { it.isNotBlank() }
                    ?: throw AcmeException("ACME order has no certificate URL")
                "invalid" -> throw AcmeException("ACME order invalid", order.optJSONObject("error")?.optString("detail"))
            }
            if (System.currentTimeMillis() > deadline) throw AcmeException("ACME finalize timed out")
            sleep(pollIntervalMillis)
        }
    }

    private var certificateKey: KeyPair? = null

    private fun csr(domain: String, key: KeyPair): ByteArray {
        val builder = JcaPKCS10CertificationRequestBuilder(X500Name("CN=$domain"), key.public)
        val san = GeneralNames(arrayOf(GeneralName(GeneralName.dNSName, domain)))
        val extensions = Extensions(arrayOf(Extension(Extension.subjectAlternativeName, false, san.encoded)))
        builder.addAttribute(PKCSObjectIdentifiers.pkcs_9_at_extensionRequest, extensions)
        val signer = JcaContentSignerBuilder("SHA256withECDSA").setProvider(provider).build(key.private)
        return builder.build(signer).encoded
    }

    private fun privateKeyPem(): ByteArray {
        val key = certificateKey?.private ?: throw AcmeException("certificate key missing")
        val encoded = Base64.getMimeEncoder(64, "\n".toByteArray(StandardCharsets.US_ASCII)).encodeToString(key.encoded)
        return "-----BEGIN PRIVATE KEY-----\n$encoded\n-----END PRIVATE KEY-----\n"
            .toByteArray(StandardCharsets.US_ASCII)
    }

    // ---- JWS ----

    private data class HttpResponse(
        val code: Int,
        val body: ByteArray,
        val location: String?,
        val nonce: String?,
    )

    private fun ensureNonce() {
        if (nonce != null) return
        val response = request(directory.getString("newNonce"), "HEAD", null, emptyMap())
        nonce = response.nonce ?: throw AcmeException("ACME newNonce returned no Replay-Nonce")
    }

    private fun postJwsAsGet(url: String): HttpResponse = postJws(url, null, useJwk = false)

    private fun postJws(url: String, payload: String?, useJwk: Boolean): HttpResponse {
        repeat(3) { attempt ->
            ensureNonce()
            val protected = JSONObject()
                .put("alg", "ES256")
                .put("nonce", nonce ?: throw AcmeException("ACME nonce missing"))
                .put("url", url)
            if (useJwk) protected.put("jwk", jwk)
            else protected.put("kid", accountUrl ?: throw AcmeException("ACME account URL missing"))
            val protected64 = b64(protected.toString().toByteArray(StandardCharsets.UTF_8))
            val payload64 = if (payload == null) "" else b64(payload.toByteArray(StandardCharsets.UTF_8))
            val signature = sign("$protected64.$payload64")
            // ACME requires the flattened JSON serialization, not the compact
            // "protected.payload.signature" form. Let's Encrypt answers the compact form with
            // "Unable to validate JWS ... Parse error reading JWS".
            val jws = JSONObject()
                .put("protected", protected64)
                .put("payload", payload64)
                .put("signature", signature)
                .toString()
                .toByteArray(StandardCharsets.UTF_8)
            val response = request(
                url,
                "POST",
                jws,
                mapOf("Content-Type" to "application/jose+json", "Accept" to "application/json, application/problem+json"),
            )
            nonce = response.nonce
            if (response.code in 200..299) return response
            val problem = problemType(response)
            if (attempt < 2 && problem == "urn:ietf:params:acme:error:badNonce") {
                nonce = null
                return@repeat
            }
            val detail = problemDetail(response)
            log("ACME HTTP ${response.code} at $url: $detail")
            throw AcmeException(
                "ACME request failed (HTTP ${response.code}) at $url: $detail",
                detail,
            )
        }
        throw AcmeException("ACME request failed after a nonce retry")
    }

    private fun sign(input: String): String {
        val signer = Signature.getInstance("SHA256withECDSA", provider)
        signer.initSign(keyPair.private)
        signer.update(input.toByteArray(StandardCharsets.US_ASCII))
        val der = signer.sign()
        val sequence = ASN1Sequence.getInstance(der)
        val r = ASN1Integer.getInstance(sequence.getObjectAt(0)).value
        val s = ASN1Integer.getInstance(sequence.getObjectAt(1)).value
        return b64(fixed32(r) + fixed32(s))
    }

    private fun request(url: String, method: String, body: ByteArray?, headers: Map<String, String>): HttpResponse {
        val connection = openConnection(url).apply {
            requestMethod = method
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            useCaches = false
            setRequestProperty("Cache-Control", "no-cache")
            setRequestProperty("User-Agent", "WheelPlay/1.0")
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
        }
        try {
            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(body) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val responseBody = stream?.use { input ->
                ByteArrayOutputStream().use { output ->
                    val buffer = ByteArray(8192)
                    while (output.size() < MAX_RESPONSE_BYTES) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
            } ?: ByteArray(0)
            return HttpResponse(
                code = code,
                body = responseBody,
                location = connection.getHeaderField("Location"),
                nonce = connection.getHeaderField("Replay-Nonce"),
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun get(url: String): HttpResponse = request(url, "GET", null, mapOf("Accept" to "application/json"))

    private fun problemType(response: HttpResponse): String? = runCatching {
        JSONObject(response.body.toString(StandardCharsets.UTF_8)).optString("type")
    }.getOrNull()

    private fun problemDetail(response: HttpResponse): String = runCatching {
        val json = JSONObject(response.body.toString(StandardCharsets.UTF_8))
        listOf(json.optString("type"), json.optString("detail")).filter { it.isNotBlank() }.joinToString(": ")
    }.getOrDefault("HTTP ${response.code}")

    // ---- helpers ----

    private fun publicJwk(key: ECPublicKey): JSONObject {
        val x = fixed32(key.w.affineX)
        val y = fixed32(key.w.affineY)
        return JSONObject().put("crv", "P-256").put("kty", "EC").put("x", b64(x)).put("y", b64(y))
    }

    private fun thumbprint(jwk: JSONObject): String {
        // RFC 7638 canonical form: required members in lexicographic order, no whitespace.
        val canonical = """{"crv":"P-256","kty":"EC","x":"${jwk.getString("x")}","y":"${jwk.getString("y")}"}"""
        return b64(sha256(canonical.toByteArray(StandardCharsets.UTF_8)))
    }

    private fun fixed32(value: BigInteger): ByteArray {
        val bytes = value.toByteArray()
        val out = ByteArray(32)
        val source = if (bytes.size > 32) bytes.copyOfRange(bytes.size - 32, bytes.size) else bytes
        source.copyInto(out, 32 - source.size)
        return out
    }

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun b64(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    companion object {
        const val PRODUCTION_DIRECTORY = "https://acme-v02.api.letsencrypt.org/directory"
        const val STAGING_DIRECTORY = "https://acme-staging-v02.api.letsencrypt.org/directory"
        private const val DEFAULT_DNS_WAIT_MILLIS = 60_000L
        private const val DEFAULT_POLL_INTERVAL_MILLIS = 5_000L
        private const val DEFAULT_POLL_TIMEOUT_MILLIS = 5 * 60_000L
        private const val MAX_RESPONSE_BYTES = 512 * 1024
    }
}
