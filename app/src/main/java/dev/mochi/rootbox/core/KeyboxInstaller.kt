package dev.mochi.rootbox.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class InstallProgress(val phase: String, val fraction: Float)

data class InstallResult(
    val success: Boolean,
    val title: String,
    val detail: String,
    val target: String,
    val backupPath: String?,
    val sha256: String?,
)

/**
 * Replaces keybox.xml with a backup-first, verify-after write.
 *
 * Order of operations: validate locally -> create the temp file as root -> verify the backup ->
 * atomically move the temp file into place -> verify the on-device SHA-256. Any failure after the
 * backup keeps the backup and (when possible) restores the original file.
 */
object KeyboxInstaller {

    private val stampFormat = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    suspend fun install(
        target: String,
        bytes: ByteArray,
        onProgress: suspend (InstallProgress) -> Unit,
    ): InstallResult {
        val meta = KeyboxParser.parse(bytes)
        if (!meta.valid) {
            DiagLog.error("install", "拒绝写入：${meta.error}")
            return InstallResult(
                success = false,
                title = "密钥文件无效",
                detail = "${meta.error}。未对设备做任何修改。",
                target = target,
                backupPath = null,
                sha256 = meta.sha256,
            )
        }

        onProgress(InstallProgress("检查 Root 权限", 0.05f))
        val rootCheck = Shell.su("id")
        if (!rootCheck.ok || !rootCheck.out.contains("uid=0")) {
            return InstallResult(
                success = false,
                title = "Root 权限不可用",
                detail = "无法以 root 执行写入：${rootCheck.brief()}",
                target = target,
                backupPath = null,
                sha256 = meta.sha256,
            )
        }

        val dir = target.substringBeforeLast('/')
        onProgress(InstallProgress("准备目标目录", 0.10f))
        val prepare = Shell.su("mkdir -p ${shQuote(dir)} && chmod 700 ${shQuote(dir)} && echo OK")
        if (!prepare.ok || !prepare.out.contains("OK")) {
            return InstallResult(
                success = false,
                title = "目标目录不可写",
                detail = "mkdir/chmod 失败：${prepare.brief()}",
                target = target,
                backupPath = null,
                sha256 = meta.sha256,
            )
        }

        val exists = Shell.su("[ -f ${shQuote(target)} ] && echo YES || echo NO").out.contains("YES")
        var backupPath: String? = null

        if (exists) {
            onProgress(InstallProgress("备份原文件", 0.25f))
            val stamp = synchronized(stampFormat) { stampFormat.format(Date()) }
            val backup = "$target.bak.$stamp"
            val originalSize = existsSize(target)
            val backupResult = Shell.su("cp -p ${shQuote(target)} ${shQuote(backup)} && echo OK")
            val backupSize = Shell.su("[ -f ${shQuote(backup)} ] && stat -c %s ${shQuote(backup)}").out.trim().toLongOrNull()
            if (!backupResult.ok || !backupResult.out.contains("OK") || originalSize == null || backupSize == null || backupSize != originalSize) {
                DiagLog.error("install", "备份失败 ${backupResult.brief()} original=$originalSize backup=$backupSize")
                return InstallResult(
                    success = false,
                    title = "备份失败，已中止",
                    detail = "无法创建或校验备份 $backup（原 ${originalSize ?: -1}B / 备份 ${backupSize ?: -1}B）。原文件未改动。\n${backupResult.brief()}",
                    target = target,
                    backupPath = null,
                    sha256 = meta.sha256,
                )
            }
            backupPath = backup
            DiagLog.info("install", "备份完成 -> $backup (${backupSize}B)")
        } else {
            DiagLog.warn("install", "目标不存在，将新建 $target")
        }

        val temp = "$target.tmp"
        onProgress(InstallProgress("写入新密钥", 0.55f))
        val write = Shell.suWrite(temp, bytes)
        if (!write.ok) {
            restore(backupPath, target)
            return InstallResult(
                success = false,
                title = "写入失败",
                detail = "写入临时文件失败：${write.brief()}\n${restoreNote(backupPath)}",
                target = target,
                backupPath = backupPath,
                sha256 = meta.sha256,
            )
        }

        onProgress(InstallProgress("替换目标文件", 0.80f))
        val move = Shell.su(
            "chmod 600 ${shQuote(temp)} && chown 0:0 ${shQuote(temp)} && " +
                "mv -f ${shQuote(temp)} ${shQuote(target)} && " +
                "{ restorecon ${shQuote(target)} 2>/dev/null || true; } && echo OK",
        )
        if (!move.ok || !move.out.contains("OK")) {
            Shell.su("rm -f ${shQuote(temp)}")
            restore(backupPath, target)
            return InstallResult(
                success = false,
                title = "替换失败",
                detail = "mv/chmod 失败：${move.brief()}\n${restoreNote(backupPath)}",
                target = target,
                backupPath = backupPath,
                sha256 = meta.sha256,
            )
        }

        onProgress(InstallProgress("校验写入结果", 0.92f))
        val remote = Shell.su("sha256sum ${shQuote(target)} 2>/dev/null || md5sum ${shQuote(target)}")
        val remoteHash = remote.out.trim().split(Regex("\\s+")).firstOrNull().orEmpty()
        val hashKnown = remoteHash.length == 64
        val hashMatches = remoteHash.equals(meta.sha256, ignoreCase = true)
        val size = Shell.su("stat -c %s ${shQuote(target)}").out.trim().toLongOrNull()
        val sizeOk = size == null || size == bytes.size.toLong()

        if ((hashKnown && !hashMatches) || !sizeOk) {
            restore(backupPath, target)
            DiagLog.error("install", "校验失败 remote=$remoteHash local=${meta.sha256} size=$size")
            return InstallResult(
                success = false,
                title = "校验失败，已回滚",
                detail = "写入后的文件与下载内容不一致（设备 ${remoteHash.ifBlank { "无哈希" }} / 本地 ${meta.sha256}）。\n${restoreNote(backupPath)}",
                target = target,
                backupPath = backupPath,
                sha256 = meta.sha256,
            )
        }

        onProgress(InstallProgress("完成", 1f))
        DiagLog.info("install", "替换成功 $target sha256=${meta.sha256} backup=${backupPath ?: "-"}")
        return InstallResult(
            success = true,
            title = "已替换 keybox.xml",
            detail = buildString {
                appendLine("目标：$target")
                appendLine("设备 ID：${meta.deviceId ?: "未声明"}")
                appendLine("证书：${meta.certificateCount} 张 · 最早到期 ${meta.expiryText()}")
                appendLine("SHA-256：${meta.sha256.take(16)}…")
                if (backupPath != null) append("备份：$backupPath") else append("备份：原文件不存在，无备份")
            },
            target = target,
            backupPath = backupPath,
            sha256 = meta.sha256,
        )
    }

    private suspend fun existsSize(target: String): Long? =
        Shell.su("stat -c %s ${shQuote(target)}").out.trim().toLongOrNull()

    private suspend fun restore(backupPath: String?, target: String) {
        if (backupPath == null) return
        val result = Shell.su("cp -p ${shQuote(backupPath)} ${shQuote(target)} && echo OK")
        if (result.out.contains("OK")) {
            DiagLog.warn("install", "已从备份回滚 $target")
        } else {
            DiagLog.error("install", "回滚失败 ${result.brief()}")
        }
    }

    private fun restoreNote(backupPath: String?): String =
        if (backupPath == null) "原文件不存在，无需回滚。" else "已尝试从 $backupPath 回滚，原备份保留。"
}
