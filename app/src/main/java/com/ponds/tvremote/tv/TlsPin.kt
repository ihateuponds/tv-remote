package com.ponds.tvremote.tv

import okhttp3.OkHttpClient
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/** Lower-case hex SHA-256 of the certificate. */
fun pinFor(cert: X509Certificate): String =
    MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02x".format(it) }

/** Thrown during the TLS handshake when the TV's cert is not the one we pinned. */
class PinMismatch(val seenPin: String) : CertificateException("TV certificate changed")

/**
 * Trust-on-first-use for the TV's self-signed cert. With [pinnedSha256] null any cert is accepted and its
 * pin recorded in [seenPin]; otherwise only the pinned cert is accepted.
 */
class TlsPin(private val pinnedSha256: String?) {
    @Volatile var seenPin: String? = null
        private set

    private val trustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) =
            throw CertificateException("client certs not supported")

        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
            val leaf = chain.firstOrNull() ?: throw CertificateException("empty chain")
            val pin = pinFor(leaf)
            seenPin = pin
            if (pinnedSha256 != null && pin != pinnedSha256) throw PinMismatch(pin)
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    fun okHttp(base: OkHttpClient): OkHttpClient {
        val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), SecureRandom()) }
        return base.newBuilder()
            .sslSocketFactory(ctx.socketFactory, trustManager)
            // The TV's cert names don't match its IP; the pin above is the identity check.
            .hostnameVerifier { _, _ -> true }
            .build()
    }
}
