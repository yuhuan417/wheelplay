package com.shilapi.xcertplay.domain

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigInteger
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.Base64
import java.util.Date
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AcmeDns01ClientTest {
    private val provider = BouncyCastleProvider()

    @Test fun dns01AuthorizationAndFinalizeReturnACertificate() {
        val domain = "wheelplay-test.duckdns.org"
        val accountKey = generateKeyPair()
        val accountJwk = AtomicReference<JSONObject>()
        val accountPublic = AtomicReference<PublicKey>()
        val challenged = AtomicBoolean(false)
        val finalized = AtomicBoolean(false)
        val finalizePayload = AtomicReference<JSONObject>()
        val issuedTxt = AtomicReference<String>()
        val cleared = AtomicBoolean(false)
        val logs = mutableListOf<String>()
        var nonce = 0
        val executor = Executors.newCachedThreadPool()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply { this.executor = executor }
        val base = "http://127.0.0.1:${server.address.port}"

        fun respond(exchange: HttpExchange, code: Int, body: String,
                    contentType: String = "application/json", location: String? = null) {
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Replay-Nonce", "nonce-${++nonce}")
            location?.let { exchange.responseHeaders.add("Location", it) }
            exchange.responseHeaders.add("Content-Type", contentType)
            exchange.sendResponseHeaders(code, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }

        fun readJws(exchange: HttpExchange): Pair<JSONObject, ByteArray> {
            val body = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            val jws = JSONObject(body)
            val protected64 = jws.getString("protected")
            val payload64 = jws.getString("payload")
            val signature64 = jws.getString("signature")
            val header = JSONObject(String(Base64.getUrlDecoder().decode(protected64), StandardCharsets.UTF_8))
            val payload = if (payload64.isEmpty()) ByteArray(0) else Base64.getUrlDecoder().decode(payload64)
            val key = accountPublic.get() ?: publicFromJwk(header.getJSONObject("jwk"))
            val verifier = Signature.getInstance("SHA256withECDSA")
            verifier.initVerify(key)
            verifier.update("$protected64.$payload64".toByteArray(StandardCharsets.US_ASCII))
            assertTrue("JWS signature must verify", verifier.verify(rawToDer(Base64.getUrlDecoder().decode(signature64))))
            assertEquals("ES256", header.getString("alg"))
            assertEquals(base + exchange.requestURI.path, header.getString("url"))
            return header to payload
        }

        fun handle(path: String, handler: (HttpExchange) -> Unit) {
            server.createContext(path) { exchange ->
                try {
                    handler(exchange)
                } catch (error: Throwable) {
                    runCatching { respond(exchange, 500, "{\"error\":\"${error.message}\"}") }
                } finally {
                    exchange.close()
                }
            }
        }

        handle("/directory") { exchange ->
            assertEquals("GET", exchange.requestMethod)
            respond(exchange, 200,
                """{"newNonce":"$base/new-nonce","newAccount":"$base/new-account","newOrder":"$base/new-order"}""")
        }
        handle("/new-nonce") { exchange ->
            exchange.responseHeaders.add("Replay-Nonce", "nonce-${++nonce}")
            exchange.sendResponseHeaders(200, -1)
        }
        handle("/new-account") { exchange ->
            val header = readJws(exchange).first
            accountJwk.set(header.getJSONObject("jwk"))
            accountPublic.set(publicFromJwk(accountJwk.get()))
            respond(exchange, 201, """{"status":"valid"}""", location = "$base/account/1")
        }
        var orderAttempts = 0
        handle("/new-order") { exchange ->
            val (header, payload) = readJws(exchange)
            assertTrue(header.has("kid"))
            if (++orderAttempts == 1) {
                // A rejected nonce must be retried with a fresh one.
                respond(exchange, 400,
                    """{"type":"urn:ietf:params:acme:error:badNonce","detail":"stale nonce"}""")
                return@handle
            }
            val json = JSONObject(String(payload, StandardCharsets.UTF_8))
            assertEquals(domain, json.getJSONArray("identifiers").getJSONObject(0).getString("value"))
            respond(exchange, 201,
                """{"status":"pending","authorizations":["$base/authz/1"],"finalize":"$base/finalize/1"}""",
                location = "$base/order/1")
        }
        handle("/authz/1") { exchange ->
            readJws(exchange)
            respond(exchange, 200, if (challenged.get()) {
                """{"status":"valid","challenges":[{"type":"dns-01","token":"test-token","url":"$base/challenge/1"}]}"""
            } else {
                """{"status":"pending","challenges":[{"type":"dns-01","token":"test-token","url":"$base/challenge/1"}]}"""
            })
        }
        handle("/challenge/1") { exchange ->
            val payload = readJws(exchange).second
            assertEquals("{}", String(payload, StandardCharsets.UTF_8))
            challenged.set(true)
            respond(exchange, 200, """{"status":"processing"}""")
        }
        handle("/finalize/1") { exchange ->
            finalizePayload.set(JSONObject(String(readJws(exchange).second, StandardCharsets.UTF_8)))
            finalized.set(true)
            respond(exchange, 200, """{"status":"processing"}""")
        }
        handle("/order/1") { exchange ->
            readJws(exchange)
            respond(exchange, 200, if (finalized.get()) {
                """{"status":"valid","certificate":"$base/cert/1"}"""
            } else {
                """{"status":"pending"}"""
            })
        }
        handle("/cert/1") { exchange ->
            readJws(exchange)
            respond(exchange, 200, certificatePem(domain), contentType = "application/pem-certificate-chain")
        }

        server.start()
        try {
            val client = AcmeDns01Client(
                directoryUrl = "$base/directory",
                log = { logs += it },
                sleep = {},
                dnsWaitMillis = 0,
                pollIntervalMillis = 0,
                pollTimeoutMillis = 5_000,
            )
            val issued = client.issue(
                domain = domain,
                accountKey = accountKey,
                email = "driver@example.com",
                setTxt = { value -> issuedTxt.set(value); true },
                clearTxt = { cleared.set(true) },
            )

            val token = "test-token." + thumbprint(accountJwk.get())
            assertEquals(b64(sha256(token.toByteArray(StandardCharsets.US_ASCII))), issuedTxt.get())
            assertTrue(cleared.get())
            assertTrue(issued.chainPem.toString(StandardCharsets.US_ASCII).contains("BEGIN CERTIFICATE"))
            assertTrue(issued.keyPem.toString(StandardCharsets.US_ASCII).contains("BEGIN PRIVATE KEY"))
            val csr = String(Base64.getUrlDecoder().decode(finalizePayload.get().getString("csr")), StandardCharsets.ISO_8859_1)
            assertTrue("CSR must contain the DNS name", csr.contains(domain))
            assertTrue(logs.any { it.contains("certificate issued") })
            assertEquals("badNonce must be retried once", 2, orderAttempts)
        } finally {
            server.stop(0)
            executor.shutdownNow()
        }
    }

    private fun generateKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    private fun publicFromJwk(jwk: JSONObject): PublicKey {
        val x = Base64.getUrlDecoder().decode(jwk.getString("x"))
        val y = Base64.getUrlDecoder().decode(jwk.getString("y"))
        val params = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }
        val spec = params.getParameterSpec(ECParameterSpec::class.java)
        return KeyFactory.getInstance("EC").generatePublic(
            ECPublicKeySpec(ECPoint(BigInteger(1, x), BigInteger(1, y)), spec),
        )
    }

    private fun rawToDer(signature: ByteArray): ByteArray {
        require(signature.size == 64) { "ES256 signature must be 64 bytes" }
        val r = ASN1Integer(BigInteger(1, signature.copyOfRange(0, 32)))
        val s = ASN1Integer(BigInteger(1, signature.copyOfRange(32, 64)))
        return DERSequence(arrayOf(r, s)).encoded
    }

    private fun thumbprint(jwk: JSONObject): String {
        val canonical = """{"crv":"P-256","kty":"EC","x":"${jwk.getString("x")}","y":"${jwk.getString("y")}"}"""
        return b64(sha256(canonical.toByteArray(StandardCharsets.UTF_8)))
    }

    private fun certificatePem(domain: String): String {
        val key = generateKeyPair()
        val now = System.currentTimeMillis()
        val name = X500Name("CN=$domain")
        val builder = JcaX509v3CertificateBuilder(
            name, BigInteger.valueOf(now), Date(now - 60_000), Date(now + 86_400_000), name, key.public,
        )
        builder.addExtension(Extension.subjectAlternativeName, false,
            GeneralNames(arrayOf(GeneralName(GeneralName.dNSName, domain))).encoded)
        val certificate = JcaX509CertificateConverter().setProvider(provider).getCertificate(
            builder.build(JcaContentSignerBuilder("SHA256withECDSA").setProvider(provider).build(key.private)),
        )
        val encoded = Base64.getMimeEncoder(64, "\n".toByteArray(StandardCharsets.US_ASCII))
            .encodeToString(certificate.encoded)
        return "-----BEGIN CERTIFICATE-----\n$encoded\n-----END CERTIFICATE-----\n"
    }

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
    private fun b64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
