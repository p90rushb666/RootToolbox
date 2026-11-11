package dev.mochi.rootbox.core

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 一张证书的展示信息。 */
data class AttestationCert(
    val index: Int,
    val subject: String,
    val issuer: String,
    val algorithm: String,
    val serial: String,
    val notBefore: Long,
    val notAfter: Long,
    val sha256: String,
)

data class AttestationDetail(val label: String, val value: String, val warn: Boolean = false)

enum class AttestationVerdict { VALID, INVALID, UNSUPPORTED }

/**
 * 密钥认证结果。判定只看「证书链能否校验到 Google 认证根 + challenge 是否匹配 + 是否被吊销 +
 * 密钥是否为硬件级」；引导状态、补丁级别作为明细展示，不单独决定有效/无效。
 */
data class AttestationResult(
    val verdict: AttestationVerdict,
    val title: String,
    val reason: String,
    val checkedAt: Long,
    val securityLevel: String? = null,
    val bootState: String? = null,
    val bootStateWarn: Boolean = false,
    val osVersion: String? = null,
    val patchLevel: String? = null,
    val brand: String? = Build.BRAND,
    val device: String? = Build.DEVICE,
    val model: String? = Build.MODEL,
    val fingerprint: String = "",
    val trustRoot: String? = null,
    val challengeTime: Long? = null,
    val certs: List<AttestationCert> = emptyList(),
    val revocation: String? = null,
) {
    val valid: Boolean get() = verdict == AttestationVerdict.VALID
}

object AttestationChecker {

    private const val ALIAS = "rootbox_attestation"
    private const val OID_KEY_DESCRIPTION = "1.3.6.1.4.1.11129.2.1.17"
    private const val REVOCATION_ASSET = "attestation_revoked.json"

    /**
     * Google 硬件密钥认证根证书（RSA, 序列号 f92009e853b6b045）。
     * 取自 Google 官方示例仓库 google/android-key-attestation（Apache-2.0）的测试根证书，
     * 与参考应用 KeyAttestation 使用的信任锚一致。
     */
    private val GOOGLE_ROOT_PEM = """
        -----BEGIN CERTIFICATE-----
        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
        -----END CERTIFICATE-----
    """.trimIndent()

    @Volatile
    private var cachedRoot: X509Certificate? = null

    @Volatile
    private var cachedRevocation: Map<String, String>? = null

    /** 冷启动或用户手动点「重新检测」时调用；超时会返回「无法检测」而不是一直转圈。 */
    suspend fun check(context: Context, timeoutMs: Long): AttestationResult = coroutineScope {
            val started = System.currentTimeMillis()
            // runCheck 是阻塞的 Keystore 调用，不能只靠 withTimeout 打断；
            // 放到独立协程里等，超时就先返回结果，让卡住的线程自己收尾。
            val work = async(Dispatchers.IO) { runCheck(context) }
            val result = withTimeoutOrNull(timeoutMs) { work.await() }
            result ?: AttestationResult(
                verdict = AttestationVerdict.UNSUPPORTED,
                title = "无法检测",
                reason = "密钥认证在 ${timeoutMs / 1000} 秒内没有返回。可能是系统 Keystore 无响应，可在设置里调大超时时间后重试。",
                checkedAt = System.currentTimeMillis(),
                fingerprint = fingerprint(),
                osVersion = "Android ${Build.VERSION.RELEASE}",
                patchLevel = Build.VERSION.SECURITY_PATCH.takeIf { it.isNotBlank() },
            ).also {
                work.cancel()
                DiagLog.warn("attest", "检测超时 ${System.currentTimeMillis() - started}ms")
            }
    }

    private fun runCheck(context: Context): AttestationResult {
        val checkedAt = System.currentTimeMillis()
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val alias = "$ALIAS-${checkedAt}"
        val nonce = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val chain = try {
            runCatching { keyStore.deleteEntry(alias) }
            val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
            generator.initialize(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setAttestationChallenge(nonce)
                    .build(),
            )
            generator.generateKeyPair()
            (keyStore.getCertificateChain(alias) ?: emptyArray())
                .filterIsInstance<X509Certificate>()
        } catch (t: Throwable) {
            DiagLog.error("attest", "生成认证密钥失败", t)
            return AttestationResult(
                verdict = AttestationVerdict.INVALID,
                title = "无效",
                reason = "无法生成认证密钥：${t.javaClass.simpleName}: ${t.message ?: "未知错误"}",
                checkedAt = checkedAt,
                fingerprint = fingerprint(),
                osVersion = "Android ${Build.VERSION.RELEASE}",
            )
        } finally {
            runCatching { keyStore.deleteEntry(alias) }
        }

        if (chain.isEmpty()) {
            return AttestationResult(
                verdict = AttestationVerdict.INVALID,
                title = "无效",
                reason = "系统没有返回证书链，设备可能不支持密钥认证。",
                checkedAt = checkedAt,
                fingerprint = fingerprint(),
                osVersion = "Android ${Build.VERSION.RELEASE}",
            )
        }

        val certs = chain.mapIndexed { index, cert -> cert.toInfo(index) }
        val leaf = chain.first()
        val description = runCatching { parseKeyDescription(leaf) }.getOrNull()

        var chainOk = true
        for (i in 0 until chain.size - 1) {
            val verified = runCatching {
                chain[i].verify(chain[i + 1].publicKey)
                true
            }.getOrDefault(false)
            if (!verified) chainOk = false
        }

        val root = runCatching { rootCertificate() }.getOrNull()
        val anchored = root != null && runCatching {
            val top = chain.last()
            if (top.publicKey.encoded.contentEquals(root.publicKey.encoded)) {
                true
            } else {
                // 有的设备不把根证书放进链里，最后一张由根签发也算通过。
                top.issuerX500Principal == root.subjectX500Principal && runCatching {
                    top.verify(root.publicKey)
                }.isSuccess
            }
        }.getOrDefault(false)

        val challengeOk = description?.challenge?.contentEquals(nonce) ?: false
        val revocation = description?.let { revokedReason(context, leaf.serialNumber) }
        val securityLevel = description?.securityLevel

        val bootState = description?.bootStateText
        val bootWarn = description?.bootState != null && description.bootState != 0

        val verdict: AttestationVerdict
        val title: String
        val reason: String
        when {
            !chainOk -> {
                verdict = AttestationVerdict.INVALID
                title = "无效"
                reason = "证书链签名校验失败，链中至少一张证书不是由上一级签发的。"
            }
            !anchored -> {
                verdict = AttestationVerdict.INVALID
                title = "无效"
                reason = "证书链没有校验到 Google 认证根：根证书未知，或设备使用的是软件模拟的密钥证书。"
            }
            description == null -> {
                verdict = AttestationVerdict.INVALID
                title = "无效"
                reason = "证书里没有 KeyDescription 认证扩展：该设备可能只提供软件证书，或系统未实现密钥认证。"
            }
            !challengeOk -> {
                verdict = AttestationVerdict.INVALID
                title = "无效"
                reason = "认证质询与本次生成的随机数不一致，证书可能是重放或伪造的。"
            }
            revocation != null -> {
                verdict = AttestationVerdict.INVALID
                title = "无效"
                reason = "证书序列号命中 Google 吊销列表（$revocation），该密钥已被官方撤销。"
            }
            securityLevel == 0 -> {
                verdict = AttestationVerdict.INVALID
                title = "无效"
                reason = "密钥由软件层提供（SecurityLevel = Software），不属于硬件密钥认证。"
            }
            else -> {
                verdict = AttestationVerdict.VALID
                title = "有效"
                reason = buildString {
                    append("证书链已校验到 Google 认证根，密钥在 ")
                    append(if (securityLevel == 2) "StrongBox" else "TEE")
                    append(" 中生成，认证质询匹配。")
                    if (bootWarn) append("当前引导状态：${bootState ?: "未知"}。")
                }
            }
        }

        DiagLog.info("attest", "判定=${title} 级别=${securityLevel} 引导=${bootState}")
        return AttestationResult(
            verdict = verdict,
            title = title,
            reason = reason,
            checkedAt = checkedAt,
            securityLevel = when (securityLevel) {
                0 -> "软件（Software）"
                1 -> "TEE"
                2 -> "StrongBox"
                else -> null
            },
            bootState = bootState,
            bootStateWarn = bootWarn,
            osVersion = description?.osVersionText,
            patchLevel = description?.patchLevelText,
            brand = description?.brand ?: Build.BRAND,
            device = description?.device ?: Build.DEVICE,
            model = description?.model ?: Build.MODEL,
            fingerprint = fingerprint(),
            trustRoot = if (anchored) "Google 认证根（序列号 f92009e853b6b045）" else "未通过",
            challengeTime = checkedAt,
            certs = certs,
            revocation = revocation,
        )
    }

    private fun X509Certificate.toInfo(index: Int): AttestationCert = AttestationCert(
        index = index,
        subject = subjectX500Principal.name,
        issuer = issuerX500Principal.name,
        algorithm = publicKey.algorithm + " " + (publicKey as? java.security.interfaces.ECPublicKey)?.params?.curve?.field?.fieldSize?.let { "$it bit" }.orEmpty(),
        serial = serialNumber.toString(16),
        notBefore = notBefore.time,
        notAfter = notAfter.time,
        sha256 = KeyboxMeta.sha256Hex(encoded),
    )

    private fun fingerprint(): String =
        "${Build.BRAND}/${Build.PRODUCT}/${Build.DEVICE}:${Build.VERSION.RELEASE}/${Build.ID}/${Build.TYPE}/${Build.TAGS}"

    private fun rootCertificate(): X509Certificate = cachedRoot ?: synchronized(this) {
        cachedRoot ?: (CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(GOOGLE_ROOT_PEM.toByteArray(Charsets.US_ASCII))) as X509Certificate)
            .also { cachedRoot = it }
    }

    /** 吊销列表按序列号匹配：同时试十进制和十六进制两种写法。 */
    private fun revokedReason(context: Context, serial: BigInteger): String? {
        val table = cachedRevocation ?: synchronized(this) {
            cachedRevocation ?: runCatching {
                val text = context.assets.open(REVOCATION_ASSET).bufferedReader().use { it.readText() }
                val entries = JSONObject(text).optJSONObject("entries") ?: JSONObject()
                buildMap {
                    entries.keys().forEach { key ->
                        put(key.lowercase(Locale.US), entries.optJSONObject(key)?.optString("reason").orEmpty())
                    }
                }
            }.getOrDefault(emptyMap()).also { cachedRevocation = it }
        }
        if (table.isEmpty()) return null
        val dec = serial.toString().lowercase(Locale.US)
        val hex = serial.toString(16).lowercase(Locale.US)
        return table[dec] ?: table[hex]
    }

    // ---------------------------------------------------------------- DER 解析

    private class Der(
        val tagClass: Int,
        val constructed: Boolean,
        val tag: Int,
        val value: ByteArray,
    ) {
        fun children(): List<Der> {
            if (!constructed) return emptyList()
            val list = mutableListOf<Der>()
            var pos = 0
            while (pos < value.size) {
                val (child, next) = readDer(value, pos, value.size)
                list += child
                pos = next
            }
            return list
        }
    }

    private fun readDer(bytes: ByteArray, offset: Int, limit: Int): Pair<Der, Int> {
        var pos = offset
        require(pos < limit) { "DER 越界" }
        val first = bytes[pos].toInt() and 0xFF
        pos++
        val tagClass = first ushr 6
        val constructed = (first and 0x20) != 0
        var tag = first and 0x1F
        if (tag == 0x1F) {
            tag = 0
            while (true) {
                require(pos < limit) { "DER 标签越界" }
                val b = bytes[pos].toInt() and 0xFF
                pos++
                tag = (tag shl 7) or (b and 0x7F)
                if (b and 0x80 == 0) break
            }
        }
        require(pos < limit) { "DER 长度越界" }
        var length = bytes[pos].toInt() and 0xFF
        pos++
        if (length and 0x80 != 0) {
            val count = length and 0x7F
            length = 0
            repeat(count) {
                require(pos < limit) { "DER 长整型长度越界" }
                length = (length shl 8) or (bytes[pos].toInt() and 0xFF)
                pos++
            }
        }
        val end = pos + length
        require(end <= limit) { "DER 内容越界" }
        return Der(tagClass, constructed, tag, bytes.copyOfRange(pos, end)) to end
    }

    private class KeyDescription(
        val securityLevel: Int,
        val challenge: ByteArray,
        val bootState: Int?,
        val deviceLocked: Boolean?,
        val osVersion: Long?,
        val patchLevel: Long?,
        val brand: String?,
        val device: String?,
        val model: String?,
    ) {
        val bootStateText: String?
            get() = when (bootState) {
                0 -> "已锁定（Verified）"
                1 -> "自签名（SelfSigned）"
                2 -> "未验证（Unverified）"
                3 -> "校验失败（Failed）"
                else -> null
            }

        val osVersionText: String?
            get() = osVersion?.let {
                val major = it / 10000
                val minor = (it / 100) % 100
                val patch = it % 100
                if (minor == 0L && patch == 0L) "Android $major" else "Android $major.$minor.$patch"
            }

        val patchLevelText: String?
            get() = patchLevel?.let {
                val year = it / 100
                val month = it % 100
                if (year in 2000..2100 && month in 1..12) {
                    String.format(Locale.US, "%04d-%02d", year, month)
                } else null
            }
    }

    private fun parseKeyDescription(cert: X509Certificate): KeyDescription? {
        val extension = cert.getExtensionValue(OID_KEY_DESCRIPTION) ?: return null
        var (node, _) = readDer(extension, 0, extension.size)
        // getExtensionValue 返回的是包着扩展值的 OCTET STRING，个别实现会再包一层。
        repeat(2) {
            if (node.tag == 4) {
                val (inner, _) = readDer(node.value, 0, node.value.size)
                node = inner
            }
        }
        if (node.tag != 16) return null
        val fields = node.children()
        if (fields.size < 6) return null

        val securityLevel = fields[1].value.lastOrNull()?.toInt() ?: 0
        val challenge = fields[4].value

        val software = fields.getOrNull(6)?.children().orEmpty()
        val tee = fields.getOrNull(7)?.children().orEmpty()
        val auth = tee + software

        // AuthorizationList 的字段都是 [n] EXPLICIT：外层标签的内容是一整段内层 TLV，
        // 必须再解一层，否则会把 INTEGER / SEQUENCE 的标签和长度当成值。
        fun item(tag: Int): Der? = auth.firstOrNull { it.tag == tag }?.let { outer ->
            if (outer.value.isEmpty()) null
            else runCatching { readDer(outer.value, 0, outer.value.size).first }.getOrNull()
        }
        fun longItem(tag: Int): Long? = item(tag)?.value?.let { bytes ->
            var v = 0L
            bytes.forEach { v = (v shl 8) or (it.toLong() and 0xFF) }
            if (bytes.isNotEmpty() && (bytes[0].toInt() and 0x80) != 0) {
                // 负数（时间戳一般不会出现），按补码展开
                var negative = -1L
                repeat(8 - bytes.size) { negative = (negative shl 8) }
                v = v or negative
            }
            v
        }
        fun textItem(tag: Int): String? = item(tag)?.value?.toString(Charsets.UTF_8)?.trim()?.takeIf { it.isNotEmpty() }

        val rootOfTrust = item(704)?.children().orEmpty()
        val deviceLocked = rootOfTrust.getOrNull(1)?.value?.firstOrNull()?.let { it.toInt() != 0 }
        val bootState = rootOfTrust.getOrNull(2)?.value?.lastOrNull()?.toInt()

        return KeyDescription(
            securityLevel = securityLevel,
            challenge = challenge,
            bootState = bootState,
            deviceLocked = deviceLocked,
            osVersion = longItem(705),
            patchLevel = longItem(706),
            brand = textItem(710),
            device = textItem(711),
            model = textItem(717),
        )
    }

    fun formatTime(millis: Long): String = synchronized(DATE) { DATE.format(Date(millis)) }

    private val DATE = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
}
