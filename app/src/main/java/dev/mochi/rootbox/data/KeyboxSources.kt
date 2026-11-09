package dev.mochi.rootbox.data

import dev.mochi.rootbox.core.DiagLog
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime

/**
 * Remote keybox catalogues.
 *
 * evoker.qzz.io publishes a full JSON index plus per-file direct links, so its entries can be
 * downloaded and installed in one tap. keybox.kowx712.cc only exposes its list without
 * authentication; its file endpoint is guarded by a Cloudflare Turnstile challenge, so entries from
 * that source hand off to the browser instead of trying to bypass the challenge.
 */
object KeyboxRepository {

    const val EVOKER_ID = "evoker"
    const val KOWX712_ID = "kowx712"

    const val EVOKER_TITLE = "evoker.qzz.io"
    const val KOWX712_TITLE = "keybox.kowx712.cc"

    private const val EVOKER_INDEX = "https://evoker.qzz.io/keys.json"
    private const val EVOKER_BASE = "https://evoker.qzz.io/"
    private const val KOWX712_INDEX = "https://keybox.kowx712.cc/api/keyboxes"

    suspend fun loadAll(
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): List<SourceResult> = coroutineScope {
        val total = 2
        val done = java.util.concurrent.atomic.AtomicInteger(0)
        fun step() = onProgress(done.incrementAndGet(), total)
        val evoker = async { runSource(EVOKER_ID, EVOKER_TITLE) { loadEvoker() }.also { step() } }
        val kowx = async { runSource(KOWX712_ID, KOWX712_TITLE) { loadKowx712() }.also { step() } }
        listOf(evoker.await(), kowx.await())
    }

    private suspend fun runSource(
        id: String,
        title: String,
        block: suspend () -> List<KeyboxItem>,
    ): SourceResult = try {
        val items = block()
        DiagLog.info("source", "$title 返回 ${items.size} 条")
        SourceResult(id, title, items)
    } catch (t: Throwable) {
        DiagLog.error("source", "$title 拉取失败", t)
        SourceResult(id, title, emptyList(), friendlyNetworkError(t))
    }

    private suspend fun loadEvoker(): List<KeyboxItem> {
        val array = JSONArray(Http.getText(EVOKER_INDEX))
        val items = ArrayList<KeyboxItem>(array.length())
        for (index in 0 until array.length()) {
            val entry = array.optJSONObject(index) ?: continue
            val file = entry.optString("file").ifBlank { null }
            val filename = entry.optString("filename").ifBlank { file?.substringAfterLast('/') ?: "keybox-$index" }
            val green = entry.optInt("green", 0)
            val red = entry.optInt("red", 0)
            val statusCode = entry.optString("status").lowercase()
            val revoked = entry.optBoolean("crl_revoked", false)

            val status = when {
                revoked || statusCode == "revoked" -> KeyboxStatus.REVOKED
                statusCode == "expired" -> KeyboxStatus.EXPIRED
                statusCode == "strong" || statusCode == "valid" -> KeyboxStatus.USABLE
                else -> KeyboxStatus.UNKNOWN
            }
            val statusLabel = when {
                revoked && statusCode != "strong" -> "已吊销"
                statusCode == "strong" -> "Strong"
                statusCode == "valid" -> "有效"
                statusCode == "revoked" -> "已吊销"
                statusCode == "expired" -> "已过期"
                else -> "未知"
            }
            val greenRating = when {
                green >= 3 -> GreenRating.THREE_GREEN
                green > 0 || red > 0 -> GreenRating.NOT_THREE
                else -> GreenRating.UNVERIFIED
            }
            val rootName = entry.optJSONObject("root")?.optString("name").orEmpty()
            val rootKind = entry.optJSONObject("root")?.optString("kind").orEmpty()
            val bytes = entry.optLong("xml_bytes", 0)
            val serials = entry.optJSONArray("serials")?.length() ?: 0

            items += KeyboxItem(
                uid = "$EVOKER_ID:${entry.optString("sha256_xml", filename)}",
                sourceId = EVOKER_ID,
                sourceTitle = EVOKER_TITLE,
                name = filename,
                updatedAt = parseIso(entry.optString("updated_at")),
                expiresAt = parseIso(entry.optString("expires_at")),
                status = status,
                statusLabel = statusLabel,
                green = greenRating,
                greenDetail = if (green > 0 || red > 0) "来源评级为 绿 $green 项、红 $red 项" else "来源未给出评级",
                detail = buildString {
                    if (rootKind.isNotBlank()) append(if (rootKind.equals("rkp", true)) "RKP" else rootKind)
                    if (rootName.isNotBlank()) append("，").append(rootName)
                    if (bytes > 0) append("，").append(bytes).append("B")
                    if (serials > 0) append("，链 ").append(serials).append(" 项")
                }.removePrefix("，"),
                downloadUrl = file?.let { EVOKER_BASE + it.trimStart('/') },
                certCount = null,
                sizeBytes = bytes.takeIf { it > 0 },
                sha256 = entry.optString("sha256_xml").ifBlank { null },
                sourcePageUrl = "https://evoker.qzz.io/keys",
            )
        }
        return items
    }

    private suspend fun loadKowx712(): List<KeyboxItem> {
        val root = JSONObject(Http.getText(KOWX712_INDEX))
        val array = root.optJSONArray("keyboxes") ?: JSONArray()
        val items = ArrayList<KeyboxItem>(array.length())
        for (index in 0 until array.length()) {
            val entry = array.optJSONObject(index) ?: continue
            val id = entry.optInt("id")
            val statusCode = entry.optString("status").lowercase()
            val status = when (statusCode) {
                "valid" -> KeyboxStatus.USABLE
                "expired" -> KeyboxStatus.EXPIRED
                "revoked" -> KeyboxStatus.REVOKED
                else -> KeyboxStatus.UNKNOWN
            }
            val statusLabel = when (status) {
                KeyboxStatus.USABLE -> "有效"
                KeyboxStatus.EXPIRED -> "已过期"
                KeyboxStatus.REVOKED -> "已吊销"
                KeyboxStatus.UNKNOWN -> "状态未知"
            }
            val isRkp = entry.optBoolean("is_rkp", false)
            val certCount = entry.optInt("cert_count", 0)
            val formats = entry.optString("key_format").ifBlank { null }

            items += KeyboxItem(
                uid = "$KOWX712_ID:$id",
                sourceId = KOWX712_ID,
                sourceTitle = KOWX712_TITLE,
                name = "TAKR $id",
                updatedAt = parseIso(entry.optString("created_at")),
                expiresAt = parseIso(entry.optString("nearest_expiry")),
                status = status,
                statusLabel = statusLabel,
                green = GreenRating.UNVERIFIED,
                greenDetail = "该来源只提供 valid/expired/revoked，没有三绿评级",
                detail = buildString {
                    append(if (isRkp) "RKP" else "Hardware")
                    if (certCount > 0) append("，").append(certCount).append(" 证书")
                    if (formats != null) append("，").append(formats.uppercase())
                },
                downloadUrl = null,
                certCount = certCount.takeIf { it > 0 },
                sizeBytes = null,
                sha256 = null,
                sourcePageUrl = "https://keybox.kowx712.cc/",
            )
        }
        return items
    }

    private fun friendlyNetworkError(t: Throwable): String = when (t) {
        is java.net.UnknownHostException -> "无法解析域名，请检查网络或 DNS 设置"
        is java.net.ConnectException -> "无法建立连接，请检查网络或该站点是否可访问"
        is java.net.SocketTimeoutException -> "连接超时，请稍后重试"
        is Http.HttpException -> "接口返回异常：${t.message}"
        else -> "${t.javaClass.simpleName}: ${t.message}"
    }

    fun parseIso(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        return try {
            OffsetDateTime.parse(raw).toInstant().toEpochMilli()
        } catch (_: Throwable) {
            try {
                Instant.parse(raw).toEpochMilli()
            } catch (_: Throwable) {
                null
            }
        }
    }
}
