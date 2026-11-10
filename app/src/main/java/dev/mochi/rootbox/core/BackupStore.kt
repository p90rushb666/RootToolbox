package dev.mochi.rootbox.core

import android.util.Base64
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Where the displayed timestamp came from. */
enum class BackupTimeSource { FILENAME, STAT }

/**
 * One `*.bak*` file sitting next to a keybox.xml.
 *
 * [meta] is produced by [KeyboxParser] on the backup's own bytes, so the list can show whether the
 * backup itself is still usable instead of only its name and size.
 */
data class BackupEntry(
    val path: String,
    val fileName: String,
    val timestamp: Long,
    val timeSource: BackupTimeSource,
    val size: Long,
    val sha256: String,
    val meta: KeyboxMeta?,
) {
    val valid: Boolean get() = meta?.valid == true

    /** The keybox.xml this file was created from (`…/keybox.xml.bak.<stamp>` -> `…/keybox.xml`). */
    val targetPath: String get() = path.substringBefore(".bak")
}

data class BackupScan(
    val entries: List<BackupEntry> = emptyList(),
    val directories: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val rootAvailable: Boolean = false,
)

/**
 * Reads and manages the `.bak` files that [KeyboxInstaller] leaves behind.
 *
 * Everything is done in a single `su` round trip: the script walks both candidate directories and
 * prints one `F|path|size|mtime` header plus a base64 body per file, which keeps the listing fast
 * even with dozens of backups (one process spawn instead of one per file).
 */
object BackupStore {

    private val stampSeconds = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
    private val stampMinutes = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US)
    private val nameStamp = Regex("""\.bak\.(\d{8}-\d{4,6})$""")

    /** Directories worth scanning: the active target's folder and the user's saved target's folder. */
    fun directories(targetPath: String?, savedTarget: String?): List<String> = buildList {
        listOfNotNull(targetPath, savedTarget, TrickyStoreScanner.PRIMARY_TARGET)
            .map { it.substringBeforeLast('/', "") }
            .filter { it.startsWith("/") }
            .distinct()
            .forEach { add(it) }
    }.distinct()

    suspend fun scan(targetPath: String?, savedTarget: String?, rootAvailable: Boolean): BackupScan {
        val dirs = directories(targetPath, savedTarget)
        if (!rootAvailable) {
            return BackupScan(directories = dirs, warnings = listOf("未获得 Root 权限，无法读取备份目录"), rootAvailable = false)
        }

        val script = buildString {
            dirs.forEach { dir ->
                appendLine("for f in ${shQuote(dir)}/*.bak ${shQuote(dir)}/*.bak.*; do")
                appendLine("  [ -f \"\$f\" ] || continue")
                appendLine("  printf 'F|%s|%s|%s\\n' \"\$f\" \"\$(stat -c %s \"\$f\" 2>/dev/null)\" \"\$(stat -c %Y \"\$f\" 2>/dev/null)\"")
                appendLine("  base64 \"\$f\" 2>/dev/null | tr -d '\\n'")
                appendLine("  echo")
                appendLine("done")
            }
            appendLine("echo BACKUP_SCAN_END")
        }

        val result = Shell.su(script)
        val warnings = mutableListOf<String>()
        if (!result.ok) {
            warnings += "读取备份目录失败：${result.brief()}"
            DiagLog.warn("backup", "扫描失败 ${result.brief()}")
        }

        val lines = result.out.lineSequence().iterator()
        val entries = mutableListOf<BackupEntry>()
        while (lines.hasNext()) {
            val line = lines.next()
            if (!line.startsWith("F|")) continue
            val parts = line.split("|")
            if (parts.size < 4) continue
            val path = parts[1]
            val size = parts[2].toLongOrNull() ?: 0L
            val statMillis = (parts[3].toLongOrNull() ?: 0L) * 1000L
            val body = if (lines.hasNext()) lines.next() else ""
            val bytes = runCatching { Base64.decode(body, Base64.DEFAULT) }.getOrNull()
            if (bytes == null || bytes.isEmpty()) {
                warnings += "无法读取 $path"
                continue
            }
            val fileName = path.substringAfterLast('/')
            val fromName = timestampFromName(fileName)
            // 文件名里的时间戳精度更高，但文件被复制/重命名过就不可信；偏差超过一天时以 stat 为准。
            val useName = fromName != null && statMillis > 0 && kotlin.math.abs(fromName - statMillis) <= 24 * 60 * 60 * 1000
            entries += BackupEntry(
                path = path,
                fileName = fileName,
                timestamp = when {
                    useName -> fromName!!
                    statMillis > 0 -> statMillis
                    fromName != null -> fromName
                    else -> 0L
                },
                timeSource = if (useName) BackupTimeSource.FILENAME else BackupTimeSource.STAT,
                size = if (size > 0) size else bytes.size.toLong(),
                sha256 = KeyboxMeta.sha256Hex(bytes),
                meta = KeyboxParser.parse(bytes),
            )
        }

        val sorted = entries.sortedByDescending { it.timestamp }
        DiagLog.info("backup", "扫描 ${dirs.size} 个目录，找到 ${sorted.size} 个备份")
        return BackupScan(sorted, dirs, warnings, rootAvailable = true)
    }

    /**
     * Restores [entry] onto [target].
     *
     * Deliberately routed through [KeyboxInstaller.install] instead of `cp`: the current file is
     * backed up first, the write is verified by read-back and rolled back on failure. Restoring an
     * older backup must not be the one operation that skips those guarantees.
     */
    suspend fun restore(target: String, entry: BackupEntry, onProgress: suspend (InstallProgress) -> Unit): InstallResult {
        onProgress(InstallProgress("读取备份文件", 0.02f))
        val read = Shell.su("cat ${shQuote(entry.path)}")
        if (!read.ok || read.out.isEmpty()) {
            return InstallResult(
                success = false,
                title = "无法读取备份",
                detail = "读取 ${entry.path} 失败：${read.brief()}",
                target = target,
                backupPath = null,
                sha256 = null,
            )
        }
        val bytes = read.out.toByteArray(Charsets.UTF_8)
        val actual = KeyboxMeta.sha256Hex(bytes)
        if (!actual.equals(entry.sha256, ignoreCase = true)) {
            return InstallResult(
                success = false,
                title = "备份内容已变化",
                detail = "下载时记录的 SHA-256 与当前文件不一致（期望 ${entry.sha256.take(16)}… / 实际 ${actual.take(16)}…），已中止。",
                target = target,
                backupPath = null,
                sha256 = actual,
            )
        }
        DiagLog.info("backup", "从 ${entry.path} 恢复 -> $target")
        return KeyboxInstaller.install(target, bytes, onProgress)
    }

    suspend fun delete(entries: List<BackupEntry>): Pair<Int, List<String>> {
        if (entries.isEmpty()) return 0 to emptyList()
        val script = buildString {
            entries.forEach { appendLine("rm -f ${shQuote(it.path)}") }
            appendLine("echo BACKUP_DELETE_END")
        }
        val result = Shell.su(script)
        if (!result.ok) {
            DiagLog.error("backup", "删除失败 ${result.brief()}")
            return 0 to listOf(result.brief())
        }
        val failures = mutableListOf<String>()
        val check = Shell.su(entries.joinToString("\n") { "[ -f ${shQuote(it.path)} ] && echo \"LEFT|${it.path}\"" })
        check.out.lineSequence().filter { it.startsWith("LEFT|") }.forEach {
            failures += "仍存在：${it.removePrefix("LEFT|")}"
        }
        val removed = entries.size - failures.size
        DiagLog.info("backup", "删除 $removed/${entries.size} 个备份")
        return removed to failures
    }

    /** Keeps the newest [keep] backups and removes the rest. Returns (removed, kept, failures). */
    suspend fun prune(entries: List<BackupEntry>, keep: Int): Triple<Int, Int, List<String>> {
        val safeKeep = keep.coerceAtLeast(1)
        val (kept, doomed) = entries.sortedByDescending { it.timestamp }.let {
            it.take(safeKeep) to it.drop(safeKeep)
        }
        if (doomed.isEmpty()) return Triple(0, kept.size, emptyList())
        val (removed, failures) = delete(doomed)
        return Triple(removed, kept.size, failures)
    }

    fun timestampFromName(fileName: String): Long? {
        val match = nameStamp.find(fileName) ?: return null
        val value = match.groupValues[1]
        val format = if (value.length == 13) stampSeconds else stampMinutes
        return runCatching { synchronized(format) { format.parse(value)?.time } }.getOrNull()
    }

    fun formatTimestamp(entry: BackupEntry): String =
        synchronized(stampSeconds) { stampSeconds.format(Date(entry.timestamp)) }
}
