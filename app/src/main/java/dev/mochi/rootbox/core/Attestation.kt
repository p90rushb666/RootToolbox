package dev.mochi.rootbox.core

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 证书链里的一张证书，按「根 → 叶」顺序展示。 */
data class AttestationCert(
    val index: Int,
    val subject: String,
    val notBefore: Long,
    val notAfter: Long,
    val serial: String,
    val valid: Boolean,
    val problems: List<String>,
)

/** 根证书种类，对应参考实现的 RootCertificateStatus。 */
enum class RootKind { GOOGLE, GOOGLE_RKP, AOSP, KNOX, OEM, UNKNOWN, FAILED }

enum class BootTone { SAFE, WARN, NEUTRAL }

enum class AttestationVerdict { VALID, INVALID, UNSUPPORTED }

/**
 * 密钥认证结果，结构与参考实现一致：
 * 顶部是根证书等级（例如「谷歌远程密钥配置」），下面是引导加载程序状态，再往下才是证书链与明细。
 *
 * 判定口径：根证书为 Google 硬件根或 Google 远程密钥根 + 证书链签名有效 + 质询匹配 +
 * 未被 Google 吊销 + 密钥在 TEE / StrongBox 中生成 → 有效；其余情况无效并给出原因。
 */
data class AttestationResult(
    val checkedAt: Long,
    val grade: String,
    val gradeSummary: String? = null,
    val rootKind: RootKind? = null,
    val bootTitle: String,
    val bootSummary: String? = null,
    val bootTone: BootTone = BootTone.NEUTRAL,
    val certs: List<AttestationCert> = emptyList(),
    val teeStatus: String? = null,
    val fingerprint: String = "",
    val verifiedBootKey: String? = null,
    val verifiedBootHash: String? = null,
    val verifiedBootState: String? = null,
    val deviceLocked: Boolean? = null,
    val challenge: String? = null,
    /** 吊销校验结论，例如「在线校验通过」「已吊销：KEY_COMPROMISE」「离线校验（内置快照）」。 */
    val revocation: String? = null,
    val securityLevel: Int? = null,
    val verdict: AttestationVerdict = AttestationVerdict.INVALID,
    val error: String? = null,
) {
    val valid: Boolean get() = verdict == AttestationVerdict.VALID
}

object AttestationChecker {

    private const val OID_KEY_DESCRIPTION = "1.3.6.1.4.1.11129.2.1.17"
    private const val REVOCATION_ASSET = "attestation_revoked.json"
    private const val STATUS_URL = "https://android.googleapis.com/attestation/status"

    // 与参考实现完全一致的根公钥（SubjectPublicKeyInfo 的 Base64）。
    // Google 硬件认证根（RSA）
    private const val GOOGLE_ROOT_KEY =
        "MIICIjANBgkqhkiG9w0BAQEFAAOCAg8AMIICCgKCAgEAr7bHgiuxpwHsK7Qui8xU" +
            "FmOr75gvMsd/dTEDDJdSSxtf6An7xyqpRR90PL2abxM1dEqlXnf2tqw1Ne4Xwl5j" +
            "lRfdnJLmN0pTy/4lj4/7tv0Sk3iiKkypnEUtR6WfMgH0QZfKHM1+di+y9TFRtv6y" +
            "//0rb+T+W8a9nsNL/ggjnar86461qO0rOs2cXjp3kOG1FEJ5MVmFmBGtnrKpa73X" +
            "pXyTqRxB/M0n1n/W9nGqC4FSYa04T6N5RIZGBN2z2MT5IKGbFlbC8UrW0DxW7AYI" +
            "mQQcHtGl/m00QLVWutHQoVJYnFPlXTcHYvASLu+RhhsbDmxMgJJ0mcDpvsC4PjvB" +
            "+TxywElgS70vE0XmLD+OJtvsBslHZvPBKCOdT0MS+tgSOIfga+z1Z1g7+DVagf7q" +
            "uvmag8jfPioyKvxnK/EgsTUVi2ghzq8wm27ud/mIM7AY2qEORR8Go3TVB4HzWQgp" +
            "Zrt3i5MIlCaY504LzSRiigHCzAPlHws+W0rB5N+er5/2pJKnfBSDiCiFAVtCLOZ7" +
            "gLiMm0jhO2B6tUXHI/+MRPjy02i59lINMRRev56GKtcd9qO/0kUJWdZTdA2XoS82" +
            "ixPvZtXQpUpuL12ab+9EaDK8Z4RHJYYfCT3Q5vNAXaiWQ+8PTWm2QgBR/bkwSWc+" +
            "NpUFgNPN9PvQi8WEg5UmAGMCAwEAAQ=="

    // Google 远程密钥配置根（RKP，EC P-384）
    private const val GOOGLE_RKP_ROOT_KEY =
        "MHYwEAYHKoZIzj0CAQYFK4EEACIDYgAEI9ojcU7fPlsFCjxy6IRqzgeOoK0b+YsV" +
            "9FPQywiyw8EQRTkJ9u3qwfnI4DGoSLlBqClTXJfgfCcZvs60FikNMHnu4fkRzObf" +
            "gDkU2KNXezT9/RQ+XvNslxPHrHCowhGr"

    // AOSP 软件认证根（EC / RSA）
    private const val AOSP_ROOT_EC_KEY =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE7l1ex+HA220Dpn7mthvsTWpdamgu" +
            "D/9/SQ59dx9EIm29sa/6FsvHrcV30lacqrewLVQBXT5DKyqO107sSHVBpA=="
    private const val AOSP_ROOT_RSA_KEY =
        "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQCia63rbi5EYe/VDoLmt5TRdSMf" +
            "d5tjkWP/96r/C3JHTsAsQ+wzfNes7UA+jCigZtX3hwszl94OuE4TQKuvpSe/lWmg" +
            "MdsGUmX4RFlXYfC78hdLt0GAZMAoDo9Sd47b0ke2RekZyOmLw9vCkT/X11DEHTVm" +
            "+Vfkl5YLCazOkjWFmwIDAQAB"

    // 三星 Knox 认证根（SAKv1 / SAKv2 / SAKMv1）
    private const val KNOX_SAKV1_KEY =
        "MIGbMBAGByqGSM49AgEGBSuBBAAjA4GGAAQBs9Qjr//REhkXW7jUqjY9KNwWac4r" +
            "5+kdUGk+TZjRo1YEa47Axwj6AJsbOjo4QsHiYRiWTELvFeiuBsKqyuF0xyAAKvDo" +
            "fBqrEq1/Ckxo2mz7Q4NQes3g4ahSjtgUSh0k85fYwwHjCeLyZ5kEqgHG9OpOH526" +
            "FFAK3slSUgC8RObbxys="
    private const val KNOX_SAKV2_KEY =
        "MIGbMBAGByqGSM49AgEGBSuBBAAjA4GGAAQBhbGuLrpql5I2WJmrE5kEVZOo+dgA" +
            "46mKrVJf/sgzfzs2u7M9c1Y9ZkCEiiYkhTFE9vPbasmUfXybwgZ2EM30A1ABPd12" +
            "4n3JbEDfsB/wnMH1AcgsJyJFPbETZiy42Fhwi+2BCA5bcHe7SrdkRIYSsdBRaKBo" +
            "ZsapxB0gAOs0jSPRX5M="
    private const val KNOX_SAKMV1_KEY =
        "MIGbMBAGByqGSM49AgEGBSuBBAAjA4GGAAQB9XeEN8lg6p5xvMVWG42P2Qi/aRKX" +
            "2rPRNgK92UlO9O/TIFCKHC1AWCLFitPVEow5W+yEgC2wOiYxgepY85TOoH0AuEkL" +
            "oiC6ldbF2uNVU3rYYSytWAJg3GFKd1l9VLDmxox58Hyw2Jmdd5VSObGiTFQ/SgKs" +
            "n2fbQPtpGlNxgEfd6Y8="

    private val googleKey: ByteArray by lazy { decodeKey(GOOGLE_ROOT_KEY) }
    private val googleRkpKey: ByteArray by lazy { decodeKey(GOOGLE_RKP_ROOT_KEY) }
    private val aospEcKey: ByteArray by lazy { decodeKey(AOSP_ROOT_EC_KEY) }
    private val aospRsaKey: ByteArray by lazy { decodeKey(AOSP_ROOT_RSA_KEY) }
    private val knoxKeys: List<ByteArray> by lazy {
        listOf(KNOX_SAKV1_KEY, KNOX_SAKV2_KEY, KNOX_SAKMV1_KEY).map { decodeKey(it) }
    }

    private fun decodeKey(base64: String): ByteArray = Base64.decode(base64, Base64.DEFAULT)

    @Volatile
    private var oemKeys: List<ByteArray>? = null

    @Volatile
    private var cachedRevocation: Map<String, String>? = null

    /** 冷启动或手动点「重新检测」时调用；超时返回错误结果而不是一直转圈。 */
    suspend fun check(context: Context, timeoutMs: Long): AttestationResult = coroutineScope {
        val started = System.currentTimeMillis()
        // Keystore 是阻塞调用，用独立协程 + 超时，卡住时也能先把结果返回给界面。
        val work = async(Dispatchers.IO) { runCheck(context) }
        val result = withTimeoutOrNull(timeoutMs) { work.await() }
        result ?: errorResult(
            "无法认证",
            "密钥认证在 ${timeoutMs / 1000} 秒内没有返回。可能是系统 Keystore 无响应，可在设置里调大超时时间后重试。",
        ).also {
            work.cancel()
            DiagLog.warn("attest", "检测超时 ${System.currentTimeMillis() - started}ms")
        }
    }

    private fun runCheck(context: Context): AttestationResult {
        val checkedAt = System.currentTimeMillis()
        val challenge = Date(checkedAt).toString()
        val keyStore = runCatching {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        }.getOrElse { t ->
            return errorResult("无法认证", "无法打开 AndroidKeyStore：${brief(t)}")
        }
        val alias = "rootbox_attest_$checkedAt"

        val chain: List<X509Certificate> = try {
            generateChain(context, keyStore, alias, challenge, preferStrongBox = true)
        } catch (t: Throwable) {
            DiagLog.error("attest", "生成认证密钥失败", t)
            return errorResult(
                "无法认证",
                "无法生成认证密钥：${brief(t)}。设备可能不支持密钥认证，或 Keystore 暂时不可用。",
            )
        } finally {
            runCatching { keyStore.deleteEntry(alias) }
        }

        if (chain.isEmpty()) {
            return errorResult("无法认证", "系统没有返回证书链，设备可能不支持密钥认证。")
        }

        // 系统返回的是「叶 → 根」，参考实现会反转成「根 → 叶」展示。
        val ordered = chain.reversed()
        val root = ordered.first()
        val leaf = ordered.last()

        val problems = validateChain(ordered)
        val certs = ordered.mapIndexed { index, cert -> cert.toInfo(index, problems[index]) }

        val description = runCatching { parseKeyDescription(leaf) }.getOrNull()
        val rootKind = runCatching { classifyRoot(context, root) }.getOrElse {
            DiagLog.warn("attest", "根证书状态检查错误：${it.message}")
            RootKind.FAILED
        }

        val grade = when {
            ordered.size < 3 -> "不完整的根证书"
            rootKind == RootKind.GOOGLE -> "谷歌硬件认证根证书"
            rootKind == RootKind.GOOGLE_RKP -> "谷歌远程密钥配置"
            rootKind == RootKind.AOSP -> "AOSP软件认证根证书"
            rootKind == RootKind.KNOX -> "三星Knox认证根证书"
            rootKind == RootKind.OEM -> "OEM认证根证书"
            else -> "未知根证书"
        }
        val gradeSummary = when (grade) {
            "谷歌硬件认证根证书" -> "兼容 GMS 的设备可以使用由 Google 签名的认证密钥。"
            "谷歌远程密钥配置" -> "远程配置的认证密钥有更强的安全性和更高的可用性。"
            "AOSP软件认证根证书" -> "认证密钥的私钥众所周知，证书链可以被篡改。"
            "三星Knox认证根证书" -> "Knox 认证使用三星认证密钥（SAK）签名。"
            "OEM认证根证书" -> "此设备信任该根证书，但它可能不被其它人信任。"
            "未知根证书" -> "根证书不是已知可信的证书颁发机构"
            else -> null
        }

        val boot = bootState(description)
        val softwareLevel = description?.securityLevel == 0
        val bootSummary = if (softwareLevel) "该设备不支持硬件级别的密钥认证。" else null

        val revocation = if (description == null) null else checkRevocation(context, leaf.serialNumber)

        // 参考应用的「可信执行环境」只给出「正常 / 异常」，判定依据是认证数据的标签顺序是否被改写。
        val teeStatus = when {
            description == null -> "未知"
            description.tampered -> "异常"
            else -> "正常"
        }

        val challengeOk = description?.challenge?.contentEquals(challenge.toByteArray()) == true
        val chainOk = problems.all { it.isEmpty() }
        val knownGoogleRoot = rootKind == RootKind.GOOGLE || rootKind == RootKind.GOOGLE_RKP
        val hardware = description?.securityLevel == 1 || description?.securityLevel == 2

        val verdict: AttestationVerdict
        val error: String?
        when {
            description == null -> {
                verdict = AttestationVerdict.INVALID
                error = "证书里没有 KeyDescription 认证扩展：设备可能只提供软件证书。"
            }
            !chainOk -> {
                verdict = AttestationVerdict.INVALID
                error = "证书链校验未通过：" + problems.flatten().distinct().joinToString("；")
            }
            ordered.size < 3 -> {
                verdict = AttestationVerdict.INVALID
                error = "证书链只有 ${ordered.size} 张证书，不完整，无法确认根证书。"
            }
            !knownGoogleRoot -> {
                verdict = AttestationVerdict.INVALID
                error = "根证书不是 Google 认证根，无法确认这是设备的 Google 密钥。"
            }
            !challengeOk -> {
                verdict = AttestationVerdict.INVALID
                error = "认证质询与本次生成的字符串不一致，证书可能被重放。"
            }
            revocation?.revoked == true -> {
                verdict = AttestationVerdict.INVALID
                error = "证书序列号已在 Google 吊销列表中（${revocation.reason}）。"
            }
            !hardware -> {
                verdict = AttestationVerdict.INVALID
                error = "密钥由软件层提供（SecurityLevel = Software），不属于硬件密钥认证。"
            }
            else -> {
                verdict = AttestationVerdict.VALID
                error = null
            }
        }

        DiagLog.info(
            "attest",
            "等级=$grade 引导=${boot.title} 安全级别=${description?.securityLevel} 吊销=${revocation?.text}",
        )

        return AttestationResult(
            checkedAt = checkedAt,
            grade = grade,
            gradeSummary = gradeSummary,
            rootKind = rootKind,
            bootTitle = boot.title,
            bootSummary = bootSummary,
            bootTone = boot.tone,
            certs = certs,
            teeStatus = teeStatus,
            fingerprint = Build.FINGERPRINT.orEmpty(),
            verifiedBootKey = description?.verifiedBootKey?.let { hex(it) },
            verifiedBootHash = description?.verifiedBootHash?.let { hex(it) },
            verifiedBootState = description?.verifiedBootState,
            deviceLocked = description?.deviceLocked,
            challenge = description?.challenge?.toString(Charsets.UTF_8) ?: challenge,
            revocation = revocation?.text,
            securityLevel = description?.securityLevel,
            verdict = verdict,
            error = error,
        )
    }

    // ---------------------------------------------------------------- 生成密钥

    /**
     * 与参考实现一致：EC P-256、SIGN|VERIFY、SHA-256、质询用当前时间字符串。
     * 设备属性认证与 StrongBox 都是「有则用」的能力：某些设备（例如模拟器）会直接报
     * CANNOT_ATTEST_IDS，所以按能力逐级降级重试，避免整次检测失败。
     */
    private fun generateChain(
        context: Context,
        keyStore: KeyStore,
        alias: String,
        challenge: String,
        preferStrongBox: Boolean,
    ): List<X509Certificate> {
        val pm = context.packageManager
        val hasStrongBox = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            pm.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
        val canAttestIds = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            runCatching {
                pm.hasSystemFeature("android.software.device_id_attestation")
            }.getOrDefault(false)

        fun build(strongBox: Boolean, includeProps: Boolean) = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
        )
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setAttestationChallenge(challenge.toByteArray())
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && strongBox) {
                    setIsStrongBoxBacked(true)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && includeProps) {
                    runCatching { setDevicePropertiesAttestationIncluded(true) }
                }
            }
            .build()

        fun generate(strongBox: Boolean, includeProps: Boolean): List<X509Certificate>? {
            runCatching { keyStore.deleteEntry(alias) }
            val generator = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_EC,
                "AndroidKeyStore",
            )
            generator.initialize(build(strongBox, includeProps))
            generator.generateKeyPair()
            val chain = (keyStore.getCertificateChain(alias) ?: emptyArray())
                .filterIsInstance<X509Certificate>()
            return chain.ifEmpty { null }
        }

        val attempts = buildList {
            if (preferStrongBox && hasStrongBox) {
                if (canAttestIds) add(true to true)
                add(true to false)
            }
            if (canAttestIds) add(false to true)
            add(false to false)
        }

        var lastError: Throwable? = null
        for ((strongBox, includeProps) in attempts) {
            val chain = runCatching { generate(strongBox, includeProps) }
                .onFailure {
                    lastError = it
                    DiagLog.warn(
                        "attest",
                        "生成失败 StrongBox=$strongBox 设备属性=$includeProps：${it.message}",
                    )
                }
                .getOrNull()
            if (chain != null) return chain
        }
        lastError?.let { throw it }
        return emptyList()
    }

    // ---------------------------------------------------------------- 链校验

    /** 按「根 → 叶」校验：签名、有效期、CA 约束；返回与证书一一对应的错误列表。 */
    private fun validateChain(ordered: List<X509Certificate>): List<MutableList<String>> {
        val problems = ordered.map { mutableListOf<String>() }

        // 根自签名，其余由上一张（更靠近根的那张）签发。
        ordered.forEachIndexed { index, cert ->
            val issuer = if (index == 0) cert else ordered[index - 1]
            val ok = runCatching {
                cert.verify(issuer.publicKey)
                true
            }.getOrDefault(false)
            if (!ok) {
                problems[index] += if (index == 0) "根证书无效" else "签名验证失败"
            }
        }

        ordered.forEachIndexed { index, cert ->
            val validity = runCatching {
                cert.checkValidity()
                null
            }.getOrElse { t ->
                if (t is java.security.cert.CertificateExpiredException) "证书已过期" else "证书未生效"
            }
            if (validity != null) problems[index] += validity

            val isCa = cert.basicConstraints >= 0
            if (index == ordered.lastIndex) {
                if (isCa) problems[index] += "终端证书不应有CA约束"
            } else {
                if (!isCa) problems[index] += "中间证书应有CA约束"
            }
        }
        return problems
    }

    private fun X509Certificate.toInfo(index: Int, problems: List<String>): AttestationCert =
        AttestationCert(
            index = index,
            subject = subjectX500Principal.name,
            notBefore = notBefore.time,
            notAfter = notAfter.time,
            serial = serialNumber.toString(16),
            valid = problems.isEmpty(),
            problems = problems.toList(),
        )

    // ---------------------------------------------------------------- 根证书识别

    private fun classifyRoot(context: Context, root: X509Certificate): RootKind {
        val key = root.publicKey.encoded
        return when {
            key.contentEquals(googleKey) -> RootKind.GOOGLE
            key.contentEquals(googleRkpKey) -> RootKind.GOOGLE_RKP
            key.contentEquals(aospEcKey) || key.contentEquals(aospRsaKey) -> RootKind.AOSP
            knoxKeys.any { it.contentEquals(key) } -> RootKind.KNOX
            isOemKey(context, key) -> RootKind.OEM
            else -> RootKind.UNKNOWN
        }
    }

    /**
     * OEM 根来自框架资源 `android:array/vendor_required_attestation_certificates`，
     * 与参考实现读取的是同一份数据；移除 Google 根后再比较。
     */
    private fun isOemKey(context: Context, key: ByteArray): Boolean {
        val keys = oemKeys ?: synchronized(this) {
            oemKeys ?: runCatching {
                val id = context.resources.getIdentifier(
                    "vendor_required_attestation_certificates",
                    "array",
                    "android",
                )
                if (id == 0) {
                    emptyList()
                } else {
                    val factory = CertificateFactory.getInstance("X.509")
                    context.resources.getStringArray(id).mapNotNull { text ->
                        runCatching {
                            val normalized = text.replace(Regex("\\s+"), "\n")
                                .replace("-BEGIN\nCERTIFICATE-", "-BEGIN CERTIFICATE-")
                                .replace("-END\nCERTIFICATE-", "-END CERTIFICATE-")
                            (factory.generateCertificate(
                                ByteArrayInputStream(normalized.toByteArray()),
                            ) as X509Certificate).publicKey.encoded
                        }.getOrNull()
                    }.filterNot { it.contentEquals(googleKey) }
                }
            }.getOrElse {
                DiagLog.warn("attest", "获取 OEM 公钥失败：${it.message}")
                emptyList()
            }.also { oemKeys = it }
        }
        return keys.any { it.contentEquals(key) }
    }

    // ---------------------------------------------------------------- 吊销校验

    private data class Revocation(val revoked: Boolean, val reason: String, val text: String)

    /**
     * 联网拉取 Google 官方的吊销列表（https://android.googleapis.com/attestation/status）。
     * 网络不可用时退回内置快照，并在结果里标明数据来源。
     */
    private fun checkRevocation(context: Context, serial: BigInteger): Revocation {
        val key = serial.toString().lowercase(Locale.US)
        val online = runCatching { fetchOnlineStatus() }.getOrNull()
        val table = online ?: loadSnapshot(context)
        val source = if (online != null) "在线校验" else "离线校验（内置快照）"
        val reason = table?.get(key)
        return when {
            table == null -> Revocation(false, "", "无法获取吊销列表")
            reason == null -> Revocation(false, "", "${source}通过")
            else -> Revocation(true, reason.ifBlank { "REVOKED" }, "已吊销：${reason.ifBlank { "REVOKED" }}（$source）")
        }
    }

    private fun fetchOnlineStatus(): Map<String, String> {
        val connection = (URL(STATUS_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 4_000
            readTimeout = 6_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "RootToolbox")
        }
        return try {
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("HTTP ${connection.responseCode}")
            }
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            parseEntries(text)
        } finally {
            connection.disconnect()
        }
    }

    private fun loadSnapshot(context: Context): Map<String, String>? {
        cachedRevocation?.let { return it }
        return synchronized(this) {
            cachedRevocation ?: runCatching {
                val text = context.assets.open(REVOCATION_ASSET).bufferedReader().use { it.readText() }
                parseEntries(text).also { cachedRevocation = it }
            }.getOrNull()
        }
    }

    private fun parseEntries(text: String): Map<String, String> {
        val entries = JSONObject(text).optJSONObject("entries") ?: return emptyMap()
        return buildMap {
            entries.keys().forEach { serial ->
                val item = entries.optJSONObject(serial)
                val status = item?.optString("status").orEmpty()
                if (status.isNotEmpty()) {
                    put(serial.lowercase(Locale.US), item?.optString("reason").orEmpty())
                }
            }
        }
    }

    // ---------------------------------------------------------------- KeyDescription

    private class KeyDescription(
        val securityLevel: Int,
        val challenge: ByteArray,
        val verifiedBootState: String?,
        val deviceLocked: Boolean?,
        val verifiedBootKey: ByteArray?,
        val verifiedBootHash: ByteArray?,
        /** AuthorizationList 的标签顺序被改动过：参考实现用它判断认证数据是否被篡改。 */
        val tampered: Boolean,
    )

    private data class Boot(val title: String, val tone: BootTone)

    private fun bootState(description: KeyDescription?): Boot {
        val locked = description?.deviceLocked
        val state = description?.verifiedBootState
        return when {
            locked == null -> Boot("引导加载程序状态未知", BootTone.NEUTRAL)
            !locked -> Boot("引导加载程序已解锁", BootTone.WARN)
            state != "Verified" -> Boot("信任根由最终用户设置", BootTone.NEUTRAL)
            else -> Boot("引导加载程序已锁定", BootTone.SAFE)
        }
    }

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

        val rootOfTrust = item(704)?.children().orEmpty()
        val verifiedBootKey = rootOfTrust.getOrNull(0)?.value
        val deviceLocked = rootOfTrust.getOrNull(1)?.value?.firstOrNull()?.let { it.toInt() != 0 }
        val bootState = rootOfTrust.getOrNull(2)?.value?.lastOrNull()?.toInt()
        val verifiedBootHash = rootOfTrust.getOrNull(3)?.value

        return KeyDescription(
            securityLevel = securityLevel,
            challenge = challenge,
            tampered = isTagOrderMisordered(tee) || isTagOrderMisordered(software),
            verifiedBootState = when (bootState) {
                0 -> "Verified"
                1 -> "SelfSigned"
                2 -> "Unverified"
                3 -> "Failed"
                else -> null
            },
            deviceLocked = deviceLocked,
            verifiedBootKey = verifiedBootKey,
            verifiedBootHash = verifiedBootHash,
        )
    }

    /** AuthorizationList 的字段本应按标签升序排列；顺序被改动说明证书可能被改写。 */
    private fun isTagOrderMisordered(fields: List<Der>): Boolean {
        val tags = fields.map { it.tag }
        return tags.zipWithNext().any { (a, b) -> a > b }
    }

    // ---------------------------------------------------------------- 工具

    private fun errorResult(grade: String, message: String) = AttestationResult(
        checkedAt = System.currentTimeMillis(),
        grade = grade,
        gradeSummary = message,
        bootTitle = "引导加载程序状态未知",
        bootTone = BootTone.NEUTRAL,
        fingerprint = Build.FINGERPRINT.orEmpty(),
        verdict = AttestationVerdict.UNSUPPORTED,
        error = message,
    )

    private fun brief(t: Throwable): String =
        "${t.javaClass.simpleName}: ${t.message ?: "未知错误"}"

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    private val DATE = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun formatTime(millis: Long): String = synchronized(DATE) { DATE.format(Date(millis)) }
}
