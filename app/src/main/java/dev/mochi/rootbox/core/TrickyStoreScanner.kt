package dev.mochi.rootbox.core

/** One keybox.xml file that already exists on the device. */
data class KeyboxCandidate(
    val path: String,
    val size: Long,
    val modifiedAt: Long,
    /** Where this path came from, shown to the user when several candidates exist. */
    val origin: String,
)

data class ModuleInfo(
    val installed: Boolean,
    val installDir: String?,
    val moduleName: String?,
    val version: String?,
    val versionCode: String?,
    val description: String?,
)

data class TrickyStoreScan(
    val module: ModuleInfo,
    val candidates: List<KeyboxCandidate>,
    val defaultTarget: String,
    val warnings: List<String>,
)

/**
 * Locates Tricky Store and the keybox.xml it currently uses.
 *
 * The module keeps its live configuration in `/data/adb/tricky_store/` (this is the path documented
 * by Tricky Store itself); older or repacked builds also ship the file inside the module directory.
 * Both locations are probed and the caller decides which one to use when they disagree.
 */
object TrickyStoreScanner {

    const val PRIMARY_TARGET = "/data/adb/tricky_store/keybox.xml"

    private val moduleDirs = listOf(
        "/data/adb/modules/tricky_store",
        "/data/adb/modules_update/tricky_store",
        "/data/adb/modules/teesimulator",
    )

    private val candidatePaths = listOf(
        PRIMARY_TARGET,
        "/data/adb/modules/tricky_store/keybox.xml",
        "/data/adb/modules_update/tricky_store/keybox.xml",
        "/data/adb/modules/teesimulator/keybox.xml",
        // 不再把 *.bak 当成候选：它永远会被下面的过滤掉（曾经是个永不显示的死项），
        // 备份文件现在由 BackupStore 统一管理。
    ).distinct()

    suspend fun scan(): TrickyStoreScan {
        val warnings = mutableListOf<String>()

        val script = buildString {
            appendLine("for d in ${moduleDirs.joinToString(" ")}; do")
            appendLine("  if [ -d \"\$d\" ]; then echo \"DIR|\$d\"; fi")
            appendLine("done")
            appendLine("for f in ${candidatePaths.joinToString(" ")}; do")
            appendLine("  if [ -f \"\$f\" ]; then")
            appendLine("    echo \"FILE|\$f|\$(stat -c %s \"\$f\" 2>/dev/null)|\$(stat -c %Y \"\$f\" 2>/dev/null)\"")
            appendLine("  fi")
            appendLine("done")
            appendLine("echo MDPROP_START")
            appendLine("cat /data/adb/modules/tricky_store/module.prop 2>/dev/null")
            appendLine("echo MDPROP_END")
        }

        val result = Shell.su(script)
        if (!result.ok) {
            warnings += "读取模块目录失败：${result.brief()}"
            DiagLog.warn("scan", "扫描失败 ${result.brief()}")
        }

        val body = result.out
        val dirs = body.lineSequence().filter { it.startsWith("DIR|") }.map { it.removePrefix("DIR|") }.toList()

        val files = body.lineSequence()
            .filter { it.startsWith("FILE|") }
            .mapNotNull { line ->
                val parts = line.split("|")
                if (parts.size < 4) return@mapNotNull null
                val path = parts[1]
                val size = parts[2].toLongOrNull() ?: 0L
                val modified = (parts[3].toLongOrNull() ?: 0L) * 1000L
                KeyboxCandidate(path, size, modified, originOf(path))
            }
            .filterNot { it.path.contains(".bak") }
            .toList()

        val propText = body.substringAfter("MDPROP_START", "").substringBefore("MDPROP_END", "")
        val props = propText.lineSequence()
            .mapNotNull { line ->
                val idx = line.indexOf('=')
                if (idx <= 0) null else line.substring(0, idx).trim() to line.substring(idx + 1).trim()
            }
            .toMap()

        val moduleDir = dirs.firstOrNull { it.endsWith("tricky_store") }
        val installed = moduleDir != null || files.isNotEmpty() || props.isNotEmpty()

        val module = ModuleInfo(
            installed = installed,
            installDir = moduleDir,
            moduleName = props["name"],
            version = props["version"],
            versionCode = props["versionCode"],
            description = props["description"],
        )

        if (!installed) {
            warnings += "未发现 Tricky Store 模块目录或 keybox.xml"
        }
        if (files.size > 1) {
            warnings += "发现 ${files.size} 个 keybox.xml，默认使用 $PRIMARY_TARGET"
        }

        // 默认永远写运行时配置目录；其它候选只做展示，用户可在「替换目标」里改。
        val defaultTarget = PRIMARY_TARGET

        DiagLog.info("scan", "模块=${installed} 目录=${moduleDir ?: "-"} 候选=${files.size} 默认=$defaultTarget")

        return TrickyStoreScan(module, files, defaultTarget, warnings)
    }

    private fun originOf(path: String): String = when {
        path == PRIMARY_TARGET -> "运行时配置目录"
        path.startsWith("/data/adb/modules_update/") -> "待重启的模块更新目录"
        path.contains("teesimulator") -> "TEE Simulator 模块"
        path.startsWith("/data/adb/modules/") -> "模块安装目录"
        else -> "其他路径"
    }
}
