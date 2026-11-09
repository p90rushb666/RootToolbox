package dev.mochi.rootbox.core

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class KeyboxMeta(
    val valid: Boolean,
    val keyboxCount: Int,
    val deviceId: String?,
    val algorithm: String?,
    val declaredCertificates: Int?,
    val certificateCount: Int,
    val earliestExpiry: Long?,
    val sha256: String,
    val size: Int,
    val error: String? = null,
) {
    fun expiryText(): String = earliestExpiry?.let { formatDate(it) } ?: "未知"

    companion object {
        private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

        fun formatDate(epochMillis: Long): String = synchronized(dateFormat) {
            dateFormat.format(Date(epochMillis))
        }

        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

/** Parses keybox.xml and validates the pieces Tricky Store actually needs. */
object KeyboxParser {

    fun parse(bytes: ByteArray): KeyboxMeta {
        val sha = KeyboxMeta.sha256Hex(bytes)
        var keyboxCount = 0
        var deviceId: String? = null
        var algorithm: String? = null
        var declaredCerts: Int? = null
        var privateKeys = 0
        var certificates = 0
        val expiryCandidates = mutableListOf<Long>()
        var sawRootTag = false
        var currentTag: String? = null
        val certificateBuffer = StringBuilder()
        val privateKeyBuffer = StringBuilder()

        try {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(ByteArrayInputStream(bytes), null)
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        currentTag = parser.name
                        when (parser.name) {
                            "AndroidAttestation" -> sawRootTag = true
                            "Keybox" -> {
                                keyboxCount++
                                if (deviceId == null) deviceId = parser.getAttributeValue(null, "DeviceID")
                            }
                            "Key" -> if (algorithm == null) algorithm = parser.getAttributeValue(null, "algorithm")
                            "NumberOfCertificates" -> {
                                if (declaredCerts == null) {
                                    declaredCerts = parser.nextText().trim().toIntOrNull()
                                    currentTag = null
                                }
                            }
                            "Certificate" -> certificateBuffer.setLength(0)
                            "PrivateKey" -> privateKeyBuffer.setLength(0)
                        }
                    }

                    XmlPullParser.TEXT -> {
                        when (currentTag) {
                            "Certificate" -> certificateBuffer.append(parser.text)
                            "PrivateKey" -> privateKeyBuffer.append(parser.text)
                        }
                    }

                    XmlPullParser.END_TAG -> {
                        when (parser.name) {
                            "Certificate" -> {
                                certificates++
                                parseCertificate(certificateBuffer.toString())?.let { expiryCandidates += it }
                            }
                            "PrivateKey" -> if (privateKeyBuffer.isNotBlank()) privateKeys++
                        }
                        currentTag = null
                    }
                }
                event = parser.next()
            }
        } catch (t: Throwable) {
            DiagLog.error("keybox", "XML 解析失败", t)
            return KeyboxMeta(
                valid = false,
                keyboxCount = 0,
                deviceId = null,
                algorithm = null,
                declaredCertificates = null,
                certificateCount = 0,
                earliestExpiry = null,
                sha256 = sha,
                size = bytes.size,
                error = "XML 解析失败：${t.javaClass.simpleName}: ${t.message}",
            )
        }

        val error = when {
            !sawRootTag -> "缺少 <AndroidAttestation> 根标签"
            keyboxCount == 0 -> "没有 <Keybox> 节点"
            privateKeys == 0 -> "没有可用的 <PrivateKey>"
            certificates == 0 -> "没有可用的 <Certificate>"
            else -> null
        }

        return KeyboxMeta(
            valid = error == null,
            keyboxCount = keyboxCount,
            deviceId = deviceId,
            algorithm = algorithm,
            declaredCertificates = declaredCerts,
            certificateCount = certificates,
            earliestExpiry = expiryCandidates.minOrNull(),
            sha256 = sha,
            size = bytes.size,
            error = error,
        )
    }

    private fun parseCertificate(pem: String): Long? = try {
        val normalized = pem.replace("\r", "").trim()
        if (!normalized.contains("BEGIN CERTIFICATE")) {
            null
        } else {
            val factory = CertificateFactory.getInstance("X.509")
            val certificate = factory.generateCertificate(
                ByteArrayInputStream(normalized.toByteArray(Charsets.US_ASCII)),
            ) as X509Certificate
            certificate.notAfter.time
        }
    } catch (_: Throwable) {
        null
    }
}
