package dev.mochi.rootbox.core

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Base64
import com.google.android.gms.tasks.Task
import com.google.android.play.core.integrity.model.IntegrityErrorCode
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.IntegrityServiceException
import com.google.android.play.core.integrity.IntegrityTokenRequest
import dev.mochi.rootbox.data.Http
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 失败分类决定了 UI 上给哪一种指引（装 GMS / 开代理 / 改端点配置）。 */
enum class IntegrityFailureKind {
    NOT_CONFIGURED,
    MISSING_GMS,
    PLAY_SERVICE,
    CONFIG,
    NETWORK_GOOGLE,
    NETWORK_ENDPOINT,
    REJECTED,
    UNKNOWN,
}

data class IntegrityFailure(
    val kind: IntegrityFailureKind,
    val title: String,
    val detail: String,
    val code: String? = null,
)

data class IntegrityVerdict(
    val endpoint: String,
    val checkedAt: Long,
    val requestPackageName: String?,
    val appRecognitionVerdict: String?,
    val deviceRecognitionVerdict: List<String>,
    val accountLicensingVerdict: String?,
    val meetsBasicIntegrity: Boolean?,
    val meetsDeviceIntegrity: Boolean?,
    val meetsStrongIntegrity: Boolean?,
    val timestampMillis: Long?,
    val certificateSha256: List<String>,
    val versionCode: String?,
    val rawJson: String,
)

data class IntegrityOutcome(
    val verdict: IntegrityVerdict? = null,
    val failure: IntegrityFailure? = null,
) {
    val ok: Boolean get() = verdict != null
}

/**
 * Play Integrity 检查。
 *
 * 客户端只做两件事：向 Play 服务请求一个**加密** token，然后把它发给用户自己配置的解码端点。
 * token 在本地不可解、也不做任何本地判断——这是 Google 的设计，任何"本地解析 token"的实现都是
 * 伪造。端点返回什么就展示什么，端点不可用时显示"无法判定"而不是猜一个结论。
 */
class IntegrityChecker(private val context: Context) {

    suspend fun check(
        endpoint: String,
        projectNumber: String,
        onPhase: (String) -> Unit = {},
    ): IntegrityOutcome {
        val url = endpoint.trim()
        val project = projectNumber.trim()

        if (url.isBlank()) {
            return fail(
                IntegrityFailureKind.NOT_CONFIGURED,
                "未配置解码服务",
                "Play Integrity 的 token 必须由服务端用 Google Cloud 服务账号解码，App 无法本地判定。请先在设置里填入解码服务端点。",
            )
        }
        if (project.isBlank() || project.toLongOrNull() == null) {
            return fail(
                IntegrityFailureKind.NOT_CONFIGURED,
                "未配置 Google Cloud 项目编号",
                "请求 token 需要 setCloudProjectNumber，请把已与 Play Console 关联的 Cloud 项目编号（纯数字）填进设置。",
            )
        }

        DiagLog.info("integrity", "开始请求 token，端点=${runCatching { java.net.URL(url).host }.getOrDefault("?")}")

        onPhase("正在请求 Play Integrity token…")
        val nonce = newNonce()
        val token = try {
            requestToken(project.toLong(), nonce)
        } catch (t: Throwable) {
            return classifyTokenError(t)
        }

        onPhase("已取得 token，等待服务端解码…")
        val payload = JSONObject().apply {
            put("token", token)
            put("nonce", nonce)
            put("packageName", context.packageName)
            put("certSha256", signingCertificateSha256())
        }

        val responseText = try {
            Http.postJson(url, payload.toString().toByteArray(Charsets.UTF_8))
        } catch (t: Throwable) {
            val kind = when (t) {
                is UnknownHostException, is SocketTimeoutException -> IntegrityFailureKind.NETWORK_ENDPOINT
                is Http.HttpException -> IntegrityFailureKind.NETWORK_ENDPOINT
                is java.net.ConnectException -> IntegrityFailureKind.NETWORK_ENDPOINT
                else -> IntegrityFailureKind.UNKNOWN
            }
            DiagLog.error("integrity", "解码端点请求失败", t)
            return fail(
                kind,
                "解码服务不可达",
                buildString {
                    appendLine("无法连接 $url")
                    appendLine("${t.javaClass.simpleName}: ${t.message}")
                    append("请检查端点地址是否正确、服务是否在运行；当前网络环境可能需要代理 / VPN。")
                },
            )
        }

        return parseResponse(url, responseText)
    }

    private fun parseResponse(endpoint: String, text: String): IntegrityOutcome {
        val json = runCatching { JSONObject(text) }.getOrElse {
            return fail(
                IntegrityFailureKind.REJECTED,
                "解码服务返回了非 JSON 内容",
                "端点返回内容无法解析：${text.take(200)}",
            )
        }

        if (!json.optBoolean("ok", false)) {
            val code = json.optString("code").ifBlank { null }
            val message = json.optString("error").ifBlank { json.optString("message", "服务端拒绝了本次校验") }
            return fail(
                IntegrityFailureKind.REJECTED,
                "服务端校验未通过",
                buildString {
                    appendLine(message)
                    append("服务端根据 nonce / 包名 / 证书摘要 / 时间戳做了校验，未通过时不会返回任何判定结果。")
                },
                code,
            )
        }

        val verdict = json.optJSONObject("verdict") ?: return fail(
            IntegrityFailureKind.REJECTED,
            "服务端响应缺少 verdict",
            "返回体里没有 verdict 字段，无法展示判定结果。",
        )
        val derived = json.optJSONObject("derived")

        val devices = mutableListOf<String>()
        verdict.optJSONArray("deviceRecognitionVerdict")?.let { array ->
            for (i in 0 until array.length()) array.optString(i).takeIf { it.isNotBlank() }?.let(devices::add)
        }
        val certs = mutableListOf<String>()
        verdict.optJSONArray("certificateSha256Digest")?.let { array ->
            for (i in 0 until array.length()) array.optString(i).takeIf { it.isNotBlank() }?.let(certs::add)
        }

        val raw = json.optJSONObject("raw")?.toString(2)
            ?: json.optJSONObject("verdict")?.toString(2)
            ?: text

        return IntegrityOutcome(
            verdict = IntegrityVerdict(
                endpoint = endpoint,
                checkedAt = System.currentTimeMillis(),
                requestPackageName = verdict.optString("requestPackageName").ifBlank { null },
                appRecognitionVerdict = verdict.optString("appRecognitionVerdict").ifBlank { null },
                deviceRecognitionVerdict = devices,
                accountLicensingVerdict = verdict.optString("accountLicensingVerdict").ifBlank { null },
                meetsBasicIntegrity = derived?.optNullableBoolean("meetsBasicIntegrity"),
                meetsDeviceIntegrity = derived?.optNullableBoolean("meetsDeviceIntegrity")
                    ?: devices.contains("MEETS_DEVICE_INTEGRITY").takeIf { devices.isNotEmpty() },
                meetsStrongIntegrity = derived?.optNullableBoolean("meetsStrongIntegrity")
                    ?: devices.contains("MEETS_STRONG_INTEGRITY").takeIf { devices.isNotEmpty() },
                timestampMillis = verdict.optLong("timestampMillis").takeIf { it > 0 },
                certificateSha256 = certs,
                versionCode = verdict.optString("versionCode").ifBlank { null },
                rawJson = raw,
            ),
        )
    }

    private suspend fun requestToken(projectNumber: Long, nonce: String): String {
        val manager = IntegrityManagerFactory.create(context)
        val request = IntegrityTokenRequest.builder()
            .setCloudProjectNumber(projectNumber)
            .setNonce(nonce)
            .build()
        // Play 服务在部分设备 / 镜像上会崩溃且不再回调，Task 永远不完成；没有超时会一直转圈。
        val response = kotlinx.coroutines.withTimeoutOrNull(TOKEN_TIMEOUT_MS) {
            manager.requestIntegrityToken(request).await()
        } ?: throw java.util.concurrent.TimeoutException(
            "Play 服务在 ${TOKEN_TIMEOUT_MS / 1000} 秒内没有返回 token",
        )
        val token = response.token()
        if (token.isBlank()) throw IllegalStateException("Play 服务返回了空 token")
        DiagLog.info("integrity", "已取得 token（${token.length} 字符，不写入日志）")
        return token
    }

    private fun classifyTokenError(t: Throwable): IntegrityOutcome {
        DiagLog.error("integrity", "请求 token 失败", t)
        if (t is IntegrityServiceException) {
            val name = errorCodeName(t.errorCode)
            return when (t.errorCode) {
                IntegrityErrorCode.PLAY_SERVICES_NOT_FOUND,
                IntegrityErrorCode.PLAY_SERVICES_VERSION_OUTDATED,
                IntegrityErrorCode.PLAY_STORE_NOT_FOUND,
                IntegrityErrorCode.PLAY_STORE_VERSION_OUTDATED,
                IntegrityErrorCode.API_NOT_AVAILABLE,
                -> fail(
                    IntegrityFailureKind.MISSING_GMS,
                    "设备缺少可用的 Google Play 服务",
                    "本机没有安装 Google Play 服务 / Play 商店，或版本过旧（Play Integrity 需要可用的 GMS）。\n官方错误码：$name\n${t.message.orEmpty()}",
                    name,
                )

                IntegrityErrorCode.CLOUD_PROJECT_NUMBER_IS_INVALID -> fail(
                    IntegrityFailureKind.CONFIG,
                    "Cloud 项目编号无效",
                    "Play 服务拒绝了当前的 Cloud 项目编号，请确认它属于已与 Play Console 关联的项目。\n官方错误码：$name",
                    name,
                )

                IntegrityErrorCode.NETWORK_ERROR,
                IntegrityErrorCode.GOOGLE_SERVER_UNAVAILABLE,
                -> fail(
                    IntegrityFailureKind.NETWORK_GOOGLE,
                    "无法连接 Google 服务",
                    "请求 token 时网络失败。当前网络环境可能需要代理 / VPN，请确认网络后重试。\n官方错误码：$name",
                    name,
                )

                else -> fail(
                    IntegrityFailureKind.PLAY_SERVICE,
                    "Play 服务拒绝了请求",
                    "${t.message.orEmpty()}\n官方错误码：$name",
                    name,
                )
            }
        }
        val kind = when (t) {
            is UnknownHostException, is SocketTimeoutException -> IntegrityFailureKind.NETWORK_GOOGLE
            else -> IntegrityFailureKind.UNKNOWN
        }
        if (t is java.util.concurrent.TimeoutException) {
            return fail(
                IntegrityFailureKind.PLAY_SERVICE,
                "Play 服务未响应",
                buildString {
                    appendLine(t.message)
                    append("可能是 Play 服务异常，也可能是当前网络无法连接 Google。请稍后重试；若持续失败，请检查设备上的 GMS 是否正常、以及网络是否需要代理 / VPN。")
                },
            )
        }
        val hint = if (kind == IntegrityFailureKind.NETWORK_GOOGLE) {
            "当前网络环境可能需要代理 / VPN，请确认网络后重试。"
        } else {
            "请稍后重试。"
        }
        return fail(kind, "请求 token 失败", "${t.javaClass.simpleName}: ${t.message}\n$hint")
    }

    private companion object {
        const val TOKEN_TIMEOUT_MS = 20_000L
    }

    private fun errorCodeName(code: Int): String = when (code) {
        IntegrityErrorCode.NO_ERROR -> "NO_ERROR"
        IntegrityErrorCode.API_NOT_AVAILABLE -> "API_NOT_AVAILABLE"
        IntegrityErrorCode.PLAY_STORE_NOT_FOUND -> "PLAY_STORE_NOT_FOUND"
        IntegrityErrorCode.NETWORK_ERROR -> "NETWORK_ERROR"
        IntegrityErrorCode.PLAY_STORE_ACCOUNT_NOT_FOUND -> "PLAY_STORE_ACCOUNT_NOT_FOUND"
        IntegrityErrorCode.APP_NOT_INSTALLED -> "APP_NOT_INSTALLED"
        IntegrityErrorCode.PLAY_SERVICES_NOT_FOUND -> "PLAY_SERVICES_NOT_FOUND"
        IntegrityErrorCode.APP_UID_MISMATCH -> "APP_UID_MISMATCH"
        IntegrityErrorCode.TOO_MANY_REQUESTS -> "TOO_MANY_REQUESTS"
        IntegrityErrorCode.CANNOT_BIND_TO_SERVICE -> "CANNOT_BIND_TO_SERVICE"
        IntegrityErrorCode.NONCE_TOO_SHORT -> "NONCE_TOO_SHORT"
        IntegrityErrorCode.NONCE_TOO_LONG -> "NONCE_TOO_LONG"
        IntegrityErrorCode.GOOGLE_SERVER_UNAVAILABLE -> "GOOGLE_SERVER_UNAVAILABLE"
        IntegrityErrorCode.NONCE_IS_NOT_BASE64 -> "NONCE_IS_NOT_BASE64"
        IntegrityErrorCode.PLAY_STORE_VERSION_OUTDATED -> "PLAY_STORE_VERSION_OUTDATED"
        IntegrityErrorCode.PLAY_SERVICES_VERSION_OUTDATED -> "PLAY_SERVICES_VERSION_OUTDATED"
        IntegrityErrorCode.CLOUD_PROJECT_NUMBER_IS_INVALID -> "CLOUD_PROJECT_NUMBER_IS_INVALID"
        IntegrityErrorCode.CLIENT_TRANSIENT_ERROR -> "CLIENT_TRANSIENT_ERROR"
        IntegrityErrorCode.INTERNAL_ERROR -> "INTERNAL_ERROR"
        else -> "ERROR_$code"
    }

    private fun newNonce(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    /** 本应用的签名证书摘要，作为提示信息一起发送；服务端必须拿 Google 返回的值与自己的白名单比对。 */
    fun signingCertificateSha256(): String? = runCatching {
        val pm = context.packageManager
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures
        }
        signatures?.firstOrNull()?.let { signature ->
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }
    }.getOrNull()

    private fun fail(
        kind: IntegrityFailureKind,
        title: String,
        detail: String,
        code: String? = null,
    ): IntegrityOutcome = IntegrityOutcome(
        failure = IntegrityFailure(kind, title, detail, code),
    )

    private fun JSONObject.optNullableBoolean(key: String): Boolean? =
        if (has(key) && !isNull(key)) getBoolean(key) else null
}

/** Minimal Task await so we do not need an extra play-services-coroutines dependency. */
private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { task ->
        if (!continuation.isActive) return@addOnCompleteListener
        val error = task.exception
        if (error != null) {
            continuation.resumeWithException(error)
        } else {
            continuation.resume(task.result)
        }
    }
    continuation.invokeOnCancellation { }
}
