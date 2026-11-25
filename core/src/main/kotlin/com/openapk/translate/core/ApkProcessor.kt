package com.openapk.translate.core

import com.android.apksig.ApkSigner
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

object ApkProcessor {
    private val sigFile = Regex("""(?i)^META-INF/([^/]+\.(SF|RSA|DSA|EC)|MANIFEST\.MF)$""")

    fun readArsc(apk: File): ByteArray? = ZipFile(apk).use { z ->
        val e = z.getEntry("resources.arsc") ?: return null
        z.getInputStream(e).use { it.readBytes() }
    }

    /** Copies [input] into an unsigned zip, replacing resources.arsc when [newArsc] != null. */
    fun rebuild(input: File, newArsc: ByteArray?, unsignedOut: File) {
        ZipFile(input).use { zin ->
            ZipOutputStream(unsignedOut.outputStream().buffered(1 shl 16)).use { zout ->
                val seen = HashSet<String>()
                for (e in zin.entries()) {
                    val name = e.name
                    if (e.isDirectory || sigFile.matches(name) || !seen.add(name)) continue
                    if (name == "resources.arsc" && newArsc != null) {
                        val ne = ZipEntry(name)
                        ne.method = ZipEntry.STORED
                        ne.size = newArsc.size.toLong()
                        ne.compressedSize = newArsc.size.toLong()
                        ne.crc = CRC32().apply { update(newArsc) }.value
                        zout.putNextEntry(ne)
                        zout.write(newArsc)
                        zout.closeEntry()
                        continue
                    }
                    val ne = ZipEntry(name)
                    if (e.method == ZipEntry.STORED || name == "resources.arsc") {
                        ne.method = ZipEntry.STORED
                        ne.size = e.size
                        ne.compressedSize = e.size
                        ne.crc = e.crc
                    } else {
                        ne.method = ZipEntry.DEFLATED
                    }
                    if (e.time > 0) ne.time = e.time
                    zout.putNextEntry(ne)
                    zin.getInputStream(e).use { it.copyTo(zout, 1 shl 16) }
                    zout.closeEntry()
                }
            }
        }
    }

    fun sign(unsigned: File, out: File, keys: SignKeys) {
        val cfg = ApkSigner.SignerConfig.Builder("CERT", keys.key, listOf(keys.cert)).build()
        ApkSigner.Builder(listOf(cfg))
            .setInputApk(unsigned)
            .setOutputApk(out)
            .setMinSdkVersion(24)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(false)
            .setV4SigningEnabled(false)
            .setOtherSignersSignaturesPreserved(false)
            .build()
            .sign()
    }
}

class SignKeys(val key: PrivateKey, val cert: X509Certificate) {
    companion object {
        /** Loads (or creates once) a per-device signing key kept in [dir]. */
        fun loadOrCreate(dir: File): SignKeys {
            val kf = File(dir, "signer.pk8")
            val cf = File(dir, "signer.der")
            if (kf.exists() && cf.exists()) {
                try {
                    val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(kf.readBytes()))
                    val cert = CertificateFactory.getInstance("X.509")
                        .generateCertificate(cf.inputStream()) as X509Certificate
                    return SignKeys(key, cert)
                } catch (_: Exception) { /* regenerate */ }
            }
            dir.mkdirs()
            val k = generate()
            kf.writeBytes(k.key.encoded)
            cf.writeBytes(k.cert.encoded)
            return k
        }

        fun generate(): SignKeys {
            val kpg = KeyPairGenerator.getInstance("RSA")
            kpg.initialize(2048, SecureRandom())
            val kp = kpg.generateKeyPair()
            val der = Der
            val algId = der.seq(der.oid(byteArrayOf(0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 1, 1, 0x0B)), byteArrayOf(5, 0))
            val name = der.seq(der.set(der.seq(der.oid(byteArrayOf(0x55, 0x04, 0x03)), der.tlv(0x0C, "Open APK Translate".toByteArray()))))
            val now = System.currentTimeMillis()
            val validity = der.seq(der.utcTime(Date(now - 86_400_000L)), der.genTime(Date(now + 30L * 365 * 86_400_000L)))
            val serial = BigInteger(63, SecureRandom()).add(BigInteger.ONE)
            val tbs = der.seq(
                der.tlv(0xA0, der.int(BigInteger.valueOf(2))),
                der.int(serial),
                algId,
                name,
                validity,
                name,
                kp.public.encoded,
            )
            val sig = Signature.getInstance("SHA256withRSA").run {
                initSign(kp.private); update(tbs); sign()
            }
            val certDer = der.seq(tbs, algId, der.tlv(0x03, byteArrayOf(0) + sig))
            val cert = CertificateFactory.getInstance("X.509")
                .generateCertificate(certDer.inputStream()) as X509Certificate
            return SignKeys(kp.private, cert)
        }
    }
}

internal object Der {
    fun tlv(tag: Int, body: ByteArray): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(tag)
        val n = body.size
        when {
            n < 0x80 -> o.write(n)
            n < 0x100 -> { o.write(0x81); o.write(n) }
            n < 0x10000 -> { o.write(0x82); o.write(n shr 8); o.write(n and 0xFF) }
            else -> { o.write(0x83); o.write(n shr 16); o.write((n shr 8) and 0xFF); o.write(n and 0xFF) }
        }
        o.write(body)
        return o.toByteArray()
    }

    fun seq(vararg parts: ByteArray) = tlv(0x30, parts.fold(ByteArray(0)) { a, b -> a + b })
    fun set(vararg parts: ByteArray) = tlv(0x31, parts.fold(ByteArray(0)) { a, b -> a + b })
    fun oid(body: ByteArray) = tlv(0x06, body)
    fun int(v: BigInteger) = tlv(0x02, v.toByteArray())
    private fun fmt(p: String, d: Date) =
        SimpleDateFormat(p, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(d).toByteArray()

    fun utcTime(d: Date) = tlv(0x17, fmt("yyMMddHHmmss'Z'", d))
    fun genTime(d: Date) = tlv(0x18, fmt("yyyyMMddHHmmss'Z'", d))
}
