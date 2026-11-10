package dev.mochi.rootbox.data

import dev.mochi.rootbox.core.DiagLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

object Http {

    private const val USER_AGENT = "RootToolbox/1.0 (Android; root toolbox)"

    suspend fun getText(url: String): String = String(getBytes(url), Charsets.UTF_8)

    suspend fun getBytes(
        url: String,
        onProgress: ((read: Long, total: Long) -> Unit)? = null,
    ): ByteArray = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/json, application/xml, text/plain, */*")
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                val errorBody = connection.errorStream?.readBytes()?.toString(Charsets.UTF_8)?.take(180).orEmpty()
                throw HttpException("HTTP $code ${connection.responseMessage.orEmpty()}".trim() + if (errorBody.isNotBlank()) " | $errorBody" else "")
            }
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: -1L
            val output = ByteArrayOutputStream(if (total > 0) total.toInt() else 16 * 1024)
            connection.inputStream.use { input ->
                val buffer = ByteArray(16 * 1024)
                var readTotal = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    readTotal += read
                    onProgress?.invoke(readTotal, total)
                }
            }
            val bytes = output.toByteArray()
            DiagLog.info("http", "GET $url -> ${bytes.size}B")
            bytes
        } catch (t: Throwable) {
            DiagLog.error("http", "GET $url 失败", t)
            throw t
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * POSTs [body] as JSON and returns the response text.
     *
     * The body is **never** written to the diagnostics log: it carries an encrypted Play Integrity
     * token and must not be persisted anywhere in the app.
     */
    suspend fun postJson(
        url: String,
        body: ByteArray,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Int = 30_000,
    ): String = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = timeoutMs
                doOutput = true
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                headers.forEach { (key, value) -> setRequestProperty(key, value) }
            }
            connection.outputStream.use { it.write(body) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.readBytes()?.toString(Charsets.UTF_8).orEmpty()
            DiagLog.info("http", "POST ${URL(url).host} -> HTTP $code (${body.size}B 已省略)")
            if (code !in 200..299) {
                throw HttpException("HTTP $code ${connection.responseMessage.orEmpty()}".trim() + if (text.isNotBlank()) " | ${text.take(180)}" else "")
            }
            text
        } catch (t: Throwable) {
            DiagLog.error("http", "POST ${runCatching { URL(url).host }.getOrDefault("?")} 失败: ${t.javaClass.simpleName}: ${t.message}")
            throw t
        } finally {
            connection?.disconnect()
        }
    }

    class HttpException(message: String) : Exception(message)
}
