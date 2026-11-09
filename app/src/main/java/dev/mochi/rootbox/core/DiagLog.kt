package dev.mochi.rootbox.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class LogLevel { INFO, WARN, ERROR }

data class LogEntry(
    val time: Long,
    val level: LogLevel,
    val scope: String,
    val message: String,
) {
    fun format(): String = "${DiagLog.clock(time)} ${level.name.padEnd(5)} [$scope] $message"
}

/**
 * In-memory diagnostic ring buffer. Every root, network and file operation reports here so the
 * diagnostics page can export a single copyable report.
 */
object DiagLog {

    private const val MAX_ENTRIES = 600

    private val lock = Any()
    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    private val clockFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun clock(time: Long): String = synchronized(clockFormat) { clockFormat.format(Date(time)) }

    fun info(scope: String, message: String) = append(LogLevel.INFO, scope, message)

    fun warn(scope: String, message: String) = append(LogLevel.WARN, scope, message)

    fun error(scope: String, message: String, throwable: Throwable? = null) {
        val detail = if (throwable == null) {
            message
        } else {
            "$message | ${throwable.javaClass.simpleName}: ${throwable.message}"
        }
        append(LogLevel.ERROR, scope, detail)
    }

    private fun append(level: LogLevel, scope: String, message: String) {
        val entry = LogEntry(System.currentTimeMillis(), level, scope, message)
        synchronized(lock) {
            val current = _entries.value
            _entries.value = if (current.size >= MAX_ENTRIES) {
                current.subList(current.size - MAX_ENTRIES + 1, current.size) + entry
            } else {
                current + entry
            }
        }
    }

    fun clear() {
        synchronized(lock) { _entries.value = emptyList() }
    }

    fun dump(header: String): String = buildString {
        appendLine(header)
        appendLine("=".repeat(48))
        val snapshot = _entries.value
        if (snapshot.isEmpty()) {
            appendLine("(无日志)")
        } else {
            snapshot.forEach { appendLine(it.format()) }
        }
    }
}
