package com.tamperdroid.proxy

import android.content.Context
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Security
import java.security.cert.X509Certificate
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * Generates a root CA on first run (stored in app-private files dir) and signs
 * a fresh leaf certificate for every host we intercept, the same way
 * mitmproxy / Burp / HttpCanary do it. The user must manually trust the
 * exported root CA on the device (Settings > Security > Install a certificate)
 * for HTTPS interception to work without TLS errors in the target app/browser.
 */
object CertAuthority {

    private const val CA_ALIAS = "tamperdroid-ca"
    private lateinit var caCert: X509Certificate
    private lateinit var caKey: PrivateKey
    private val leafCache = ConcurrentHashMap<String, Pair<X509Certificate, PrivateKey>>()

    init {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    fun init(context: Context) {
        val dir = context.filesDir
        val caCertFile = File(dir, "ca.crt")
        val caKeyFile = File(dir, "ca.key")

        if (caCertFile.exists() && caKeyFile.exists()) {
            val certBytes = caCertFile.readBytes()
            val keyBytes = caKeyFile.readBytes()
            val cf = java.security.cert.CertificateFactory.getInstance("X.509")
            caCert = cf.generateCertificate(certBytes.inputStream()) as X509Certificate
            val kf = java.security.KeyFactory.getInstance("RSA")
            caKey = kf.generatePrivate(java.security.spec.PKCS8EncodedKeySpec(keyBytes))
        } else {
            val pair = generateKeyPair()
            val cert = generateRootCert(pair)
            caCert = cert
            caKey = pair.private
            caCertFile.writeBytes(cert.encoded)
            caKeyFile.writeBytes(pair.private.encoded)
        }
    }

    /** Path to the DER-encoded CA cert, for the user to export/share and install on the device. */
    fun exportCaCertFile(context: Context): File = File(context.filesDir, "ca.crt")

    private fun generateKeyPair(): KeyPair {
        val gen = KeyPairGenerator.getInstance("RSA")
        gen.initialize(2048)
        return gen.generateKeyPair()
    }

    private fun generateRootCert(pair: KeyPair): X509Certificate {
        val name = X500Name("CN=TamperDroid Root CA, O=TamperDroid, C=XX")
        val serial = BigInteger.valueOf(System.currentTimeMillis())
        val notBefore = Date(System.currentTimeMillis() - 24L * 3600 * 1000)
        val notAfter = Date(System.currentTimeMillis() + 10L * 365 * 24 * 3600 * 1000)

        val builder = JcaX509v3CertificateBuilder(
            name, serial, notBefore, notAfter, name, pair.public
        )
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(true))
        builder.addExtension(
            Extension.keyUsage, true,
            KeyUsage(KeyUsage.keyCertSign or KeyUsage.cRLSign)
        )
        val signer = JcaContentSignerBuilder("SHA256WithRSA").build(pair.private)
        return JcaX509CertificateConverter().getCertificate(builder.build(signer))
    }

    /** Returns (leafCert, leafPrivateKey) for [host], generating + caching on first use. */
    fun leafFor(host: String): Pair<X509Certificate, PrivateKey> {
        return leafCache.getOrPut(host) {
            val pair = generateKeyPair()
            val name = X500Name("CN=$host, O=TamperDroid")
            val serial = BigInteger.valueOf(System.currentTimeMillis() + host.hashCode())
            val notBefore = Date(System.currentTimeMillis() - 24L * 3600 * 1000)
            val notAfter = Date(System.currentTimeMillis() + 825L * 24 * 3600 * 1000)

            val builder = JcaX509v3CertificateBuilder(
                X500Name(caCert.subjectX500Principal.name),
                serial, notBefore, notAfter, name, pair.public
            )
            builder.addExtension(Extension.basicConstraints, false, BasicConstraints(false))
            builder.addExtension(
                Extension.keyUsage, true,
                KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment)
            )
            builder.addExtension(
                Extension.subjectAlternativeName, false,
                GeneralNames(GeneralName(GeneralName.dNSName, host))
            )
            val signer = JcaContentSignerBuilder("SHA256WithRSA").build(caKey)
            val cert = JcaX509CertificateConverter().getCertificate(builder.build(signer))
            cert to pair.private
        }
    }

    /** Builds an SSLContext presenting a leaf cert for [host], signed by our CA, for the client-facing side. */
    fun sslContextFor(host: String): SSLContext {
        val (leaf, leafKey) = leafFor(host)
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, null)
        val chain = arrayOf<java.security.cert.Certificate>(leaf, caCert)
        ks.setKeyEntry("leaf", leafKey, "changeit".toCharArray(), chain)

        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, "changeit".toCharArray())

        val ctx = SSLContext.getInstance("TLS")
        ctx.init(kmf.keyManagers, null, null)
        return ctx
    }
}
