package dev.mochi.rootbox.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Google 官方吊销列表（`https://android.googleapis.com/attestation/status`）的本地缓存。
 *
 * 策略（按需求确定）：
 * - 缓存优先：有缓存就立即用它判定，检测不会因为联网而变慢；
 * - 24 小时后视为过期，页面提示「需要获取最新吊销列表」，但不阻止继续查看结论；
 * - 完全没有缓存时（首次安装 / 清数据）才在检测时联网拉一次；
 * - 用户可在设置或检测页手动「获取最新吊销列表」，失败时保留原缓存；
 * - 不再内置出厂快照，数据只来自联网结果与本地缓存。
 */
object RevocationStore {

    private const val STATUS_URL = "https://android.googleapis.com/attestation/status"
    private const val CACHE_FILE = "revocation-status.json"
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 12_000

    /** 缓存有效期：Google 响应的 Cache-Control 就是 max-age=86400。 */
    const val TTL_MS = 24 * 60 * 60 * 1000L

    data class Snapshot(
        /** 拉取成功的时间；0 表示未知。 */
        val fetchedAt: Long,
        /** 证书序列号（十进制） -> 吊销原因。 */
        val entries: Map<String, String>,
    ) {
        val count: Int get() = entries.size

        fun isStale(now: Long = System.currentTimeMillis()): Boolean =
            fetchedAt <= 0 || now - fetchedAt > TTL_MS

        fun ageDays(now: Long = System.currentTimeMillis()): Long =
            if (fetchedAt <= 0) Long.MAX_VALUE else ((now - fetchedAt) / (24 * 60 * 60 * 1000L)).coerceAtLeast(0)

        /** 命中则返回原因（可能为空字符串），未命中返回 null。 */
        fun reasonFor(serial: BigInteger): String? =
            entries[serial.toString().lowercase(Locale.US)]
    }

    private fun cacheFile(context: Context): File =
        File(context.filesDir, CACHE_FILE)

    @Synchronized
    fun load(context: Context): Snapshot? = runCatching {
        val file = cacheFile(context)
        if (!file.isFile) return null
        val root = JSONObject(file.readText())
        val fetchedAt = root.optLong("fetchedAt", 0L)
        val entries = root.optJSONObject("entries") ?: return null
        val map = buildMap {
            entries.keys().forEach { serial ->
                val reason = entries.optString(serial).orEmpty()
                put(serial.lowercase(Locale.US), reason)
            }
        }
        if (map.isEmpty()) null else Snapshot(fetchedAt, map)
    }.getOrNull()

    /** 联网拉取并写入缓存；失败抛异常，原缓存不动。支持协程取消（取消时断开连接）。 */
    suspend fun fetch(context: Context): Snapshot = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        // 协程被取消（用户点「取消」）时断开连接，让阻塞中的 read 立即返回，而不是等超时。
        val completion = currentCoroutineContext()[kotlinx.coroutines.Job]
            ?.invokeOnCompletion { cause ->
                if (cause != null) runCatching { connection?.disconnect() }
            }
        try {
            connection = (URL(STATUS_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "RootToolbox")
            }
            if (currentCoroutineContext()[kotlinx.coroutines.Job]?.isActive == false) {
                throw kotlinx.coroutines.CancellationException("吊销列表获取已取消")
            }
            val text = try {
                val code = connection.responseCode
                if (code !in 200..299) throw IllegalStateException("HTTP $code")
                connection.inputStream.bufferedReader().use { it.readText() }
            } finally {
                connection.disconnect()
            }

            val root = JSONObject(text)
            val entries = root.optJSONObject("entries") ?: throw IllegalStateException("返回内容缺少 entries")
            val map = buildMap {
                entries.keys().forEach { serial ->
                    val item = entries.optJSONObject(serial)
                    val status = item?.optString("status").orEmpty()
                    if (status.isNotEmpty()) {
                        put(serial.lowercase(Locale.US), item?.optString("reason").orEmpty())
                    }
                }
            }
            if (map.isEmpty()) throw IllegalStateException("吊销列表为空")

            val snapshot = Snapshot(System.currentTimeMillis(), map)
            save(context, snapshot)
            DiagLog.info("attest", "吊销列表已更新：${snapshot.count} 条")
            snapshot
        } finally {
            completion?.dispose()
        }
    }

    @Synchronized
    private fun save(context: Context, snapshot: Snapshot) {
        val root = JSONObject().apply {
            put("fetchedAt", snapshot.fetchedAt)
            put("entries", JSONObject().apply {
                snapshot.entries.forEach { (serial, reason) -> put(serial, reason) }
            })
        }
        val target = cacheFile(context)
        val temp = File(target.parentFile, "$CACHE_FILE.tmp")
        temp.writeText(root.toString())
        if (!temp.renameTo(target)) {
            target.writeText(root.toString())
            temp.delete()
        }
    }
}
