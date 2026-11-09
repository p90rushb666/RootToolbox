package dev.mochi.rootbox.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

data class ShellResult(
    val code: Int,
    val out: String,
    val err: String,
    val timedOut: Boolean = false,
) {
    val ok: Boolean get() = code == 0 && !timedOut

    fun brief(): String = buildString {
        append("exit=").append(code)
        if (timedOut) append(" (timeout)")
        if (out.isNotBlank()) append(" | out=").append(out.trim().take(400))
        if (err.isNotBlank()) append(" | err=").append(err.trim().take(400))
    }
}

/**
 * Thin wrapper around `su -c`. Every privileged read/write in the app goes through here so the
 * diagnostics page sees exactly what the device answered.
 */
object Shell {

    const val DEFAULT_TIMEOUT_MS = 20_000L

    suspend fun su(script: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): ShellResult =
        exec(arrayOf("su", "-c", script), timeoutMs)

    suspend fun exec(command: Array<String>, timeoutMs: Long = DEFAULT_TIMEOUT_MS): ShellResult =
        withContext(Dispatchers.IO) {
            val started = System.currentTimeMillis()
            val result = try {
                val process = ProcessBuilder(*command)
                    .redirectErrorStream(false)
                    .start()
                process.outputStream.close()

                val outStream = Drainer(process.inputStream)
                val errStream = Drainer(process.errorStream)
                outStream.start()
                errStream.start()

                val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
                if (!finished) {
                    process.destroy()
                    if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
                }
                outStream.join(2_000)
                errStream.join(2_000)
                ShellResult(
                    code = if (finished) process.exitValue() else -1,
                    out = outStream.text(),
                    err = errStream.text(),
                    timedOut = !finished,
                )
            } catch (t: Throwable) {
                ShellResult(code = -2, out = "", err = "${t.javaClass.simpleName}: ${t.message}")
            }
            DiagLog.info(
                "shell",
                "${command.firstOrNull() ?: "?"} -> ${result.brief()} (${System.currentTimeMillis() - started}ms)",
            )
            result
        }

    /**
     * Streams [bytes] into [path] as root. Writing through stdin keeps the XML out of any shell
     * quoting rules, which matters because keybox.xml contains `$`, quotes and newlines.
     */
    suspend fun suWrite(path: String, bytes: ByteArray): ShellResult = withContext(Dispatchers.IO) {
        val quoted = shQuote(path)
        val result = try {
            val process = ProcessBuilder("su", "-c", "cat > $quoted").start()
            val errStream = Drainer(process.errorStream)
            val outStream = Drainer(process.inputStream)
            errStream.start()
            outStream.start()
            process.outputStream.use { it.write(bytes); it.flush() }
            val finished = process.waitFor(DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroy()
                process.destroyForcibly()
            }
            outStream.join(2_000)
            errStream.join(2_000)
            ShellResult(
                code = if (finished) process.exitValue() else -1,
                out = outStream.text(),
                err = errStream.text(),
                timedOut = !finished,
            )
        } catch (t: Throwable) {
            ShellResult(code = -2, out = "", err = "${t.javaClass.simpleName}: ${t.message}")
        }
        DiagLog.info("shell", "write ${bytes.size}B -> $path ${result.brief()}")
        result
    }

    private class Drainer(private val input: InputStream) : Thread() {
        private val buffer = ByteArrayOutputStream()

        init {
            isDaemon = true
        }

        override fun run() {
            try {
                input.use { stream ->
                    val chunk = ByteArray(8 * 1024)
                    while (true) {
                        val read = stream.read(chunk)
                        if (read < 0) break
                        synchronized(buffer) { buffer.write(chunk, 0, read) }
                    }
                }
            } catch (_: Throwable) {
                // A failed read only costs log detail.
            }
        }

        fun text(): String = synchronized(buffer) { buffer.toString("UTF-8") }
    }
}

/** Quotes a path or value for a `sh -c` command line. */
internal fun shQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
