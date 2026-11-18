package com.ponds.tvremote.tv

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TlsPinTest {
    private val certA = HeldCertificate.Builder().commonName("LG TV A").build()
    private val certB = HeldCertificate.Builder().commonName("LG TV B").build()
    private lateinit var server: MockWebServer
    private fun url() = "wss://${server.hostName}:${server.port}"

    @Before fun setUp() {
        server = MockWebServer()
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(certA).build().sslSocketFactory(), false)
        server.start()
    }
    @After fun tearDown() { server.shutdown() }

    private fun client(pin: String?) = TlsPin(pin).okHttp(OkHttpClient())

    @Test fun firstConnectRecordsPin() = runBlocking {
        FakeTv.pairing().enqueueOn(server)
        val pin = TlsPin(null)
        val r = SsapClient(pin.okHttp(OkHttpClient())).connect(url(), null)
        assertEquals(PairResult.Paired("abc"), r)
        assertEquals(pinFor(certA.certificate), pin.seenPin)
    }

    @Test fun matchingPinConnects() = runBlocking {
        FakeTv.pairing().enqueueOn(server)
        assertEquals(PairResult.Paired("abc"), SsapClient(client(pinFor(certA.certificate))).connect(url(), null))
    }

    @Test fun changedCertIsRejected() = runBlocking {
        FakeTv.pairing().enqueueOn(server)
        val r = SsapClient(client(pinFor(certB.certificate))).connect(url(), null)
        assertTrue("got $r", r is PairResult.CertChanged)
        assertEquals(pinFor(certA.certificate), (r as PairResult.CertChanged).newPin)
    }

    @Test fun pinIsLowercaseHexSha256() =
        assertTrue(pinFor(certA.certificate).matches(Regex("[0-9a-f]{64}")))
}
