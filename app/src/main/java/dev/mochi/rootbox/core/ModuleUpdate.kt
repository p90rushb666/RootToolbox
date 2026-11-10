package dev.mochi.rootbox.core

import android.content.Context
import dev.mochi.rootbox.data.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/** 设备上已安装的 Magisk / KernelSU / APatch 模块（读自 module.prop）。 */
data class InstalledModule(
    val id: String,
    val name: String,
    val version: String?,
    val versionCode: Long?,
    /** module.prop 里的 updateJson，Magisk 模块更新约定的元数据地址。 */
    val updateJson: String?,
    val propPath: String,
)

/** 对照 updateJson 与 GitHub Release 后得到的一条更新信息。 */
data class ModuleUpdateItem(
    val module: InstalledModule,
    val latestVersion: String? = null,
    val latestVersionCode: Long? = null,
    val zipUrl: String? = null,
    val changelog: String? = null,
    val releaseUrl: String? = null,
    /** 版本信息来源：updateJson / GitHub Release / 两者。 */
    val source: String? = null,
    val hasUpdate: Boolean = false,
    val error: String? = null,
) {
    val canDownload: Boolean get() = zipUrl != null && error == null
}

/** app 私有目录里已下载的模块 zip。 */
data class DownloadedModule(
    val name: String,
    val path: String,
    val size: Long,
    val modifiedAt: Long,
)

object ModuleUpdateChecker {

    /** 一次 su 扫描所有 module.prop，避免每个模块起一个进程。 */
    suspend fun scan(rootAvailable: Boolean): Pair<List<InstalledModule>, String?> {
        if (!rootAvailable) return emptyList<InstalledModule>() to "未获得 Root 权限，无法读取 /data/adb/modules"

        val result = Shell.su(
            """
            for f in /data/adb/modules/*/module.prop; do
              [ -f "${'$'}f" ] || continue
              echo "PROP_START|${'$'}f"
              cat "${'$'}f"
              echo "PROP_END"
            done
            echo SCAN_END
            """.trimIndent(),
        )
        if (!result.ok) return emptyList<InstalledModule>() to "读取模块目录失败：${result.brief()}"

        val modules = mutableListOf<InstalledModule>()
        var currentPath: String? = null
        val props = mutableMapOf<String, String>()
        for (line in result.out.lineSequence()) {
            when {
                line.startsWith("PROP_START|") -> {
                    currentPath = line.substringAfter("PROP_START|")
                    props.clear()
                }

                line == "PROP_END" -> {
                    val id = props["id"].orEmpty().ifBlank {
                        currentPath?.substringBeforeLast('/')?.substringAfterLast('/').orEmpty()
                    }
                    if (id.isNotBlank()) {
                        modules += InstalledModule(
                            id = id,
                            name = props["name"].orEmpty().ifBlank { id },
                            version = props["version"]?.trim(),
                            versionCode = props["versionCode"]?.trim()?.toLongOrNull(),
                            updateJson = props["updateJson"]?.trim()?.takeIf { it.startsWith("http") },
                            propPath = currentPath.orEmpty(),
                        )
                    }
                    currentPath = null
                    props.clear()
                }

                currentPath != null -> {
                    val idx = line.indexOf('=')
                    if (idx > 0) props[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
                }
            }
        }
        DiagLog.info("update", "扫描到 ${modules.size} 个模块")
        return modules.distinctBy { it.id } to null
    }

    /**
     * 逐个对照更新源：先读 module.prop 的 updateJson（Magisk 标准字段），再从 URL 推断
     * GitHub 仓库并读取最新 Release 的 tag 与 zip 资源。两者都没有可靠版本信息时标注原因，
     * 绝不把「读不到」当作「有更新」。
     */
    suspend fun check(modules: List<InstalledModule>): List<ModuleUpdateItem> = withContext(Dispatchers.IO) {
        // 每个模块都要发 1~2 个网络请求，串行会让 N 个模块等 N 倍时间。
        val items = coroutineScope {
            modules.map { module -> async { resolve(module) } }.awaitAll()
        }
        // 有更新的排最前，没有 updateJson / 无法检查的沉到最底。
        items.sortedWith(
            compareByDescending<ModuleUpdateItem> { rank(it) }
                .thenBy { it.module.name.lowercase() },
        )
    }

    private fun rank(item: ModuleUpdateItem): Int = when {
        item.hasUpdate -> 3
        item.module.updateJson.isNullOrBlank() -> 0
        item.error != null -> 1
        else -> 2
    }

    private suspend fun resolve(module: InstalledModule): ModuleUpdateItem {
        if (module.updateJson.isNullOrBlank()) {
            return ModuleUpdateItem(module, error = "模块未提供 updateJson，无法自动检查更新")
        }

        var jsonVersion: String? = null
        var jsonCode: Long? = null
        var jsonZip: String? = null
        var jsonError: String? = null

        try {
            val json = JSONObject(Http.getText(module.updateJson))
            jsonVersion = json.optString("version").takeIf { it.isNotBlank() }
            jsonCode = json.optString("versionCode").toLongOrNull()
            jsonZip = json.optString("zipUrl").takeIf { it.startsWith("http") }
        } catch (t: Throwable) {
            jsonError = "updateJson 读取失败：${t.javaClass.simpleName}: ${t.message}"
            DiagLog.warn("update", "${module.id} updateJson 失败：${t.message}")
        }

        // 对照 GitHub Release：从 updateJson 地址或 zipUrl 里推断 owner/repo。
        var releaseTag: String? = null
        var releaseZip: String? = null
        var releaseUrl: String? = null
        var releaseNote: String? = null
        val repo = repoOf(module.updateJson) ?: jsonZip?.let(::repoOf)
        if (repo != null) {
            try {
                val text = Http.getText("https://api.github.com/repos/$repo/releases/latest")
                val release = JSONObject(text)
                releaseTag = release.optString("tag_name").takeIf { it.isNotBlank() }
                releaseUrl = release.optString("html_url").takeIf { it.isNotBlank() }
                releaseNote = release.optString("name").takeIf { it.isNotBlank() }
                val assets = release.optJSONArray("assets")
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val url = assets.optJSONObject(i)?.optString("browser_download_url").orEmpty()
                        if (url.endsWith(".zip", ignoreCase = true)) {
                            releaseZip = url
                            break
                        }
                    }
                }
            } catch (t: Throwable) {
                // GitHub 只是交叉验证，失败不覆盖 updateJson 的结果。
                DiagLog.warn("update", "${module.id} GitHub Release 查询失败：${t.message}")
            }
        }

        val latestVersion = releaseTag ?: jsonVersion ?: releaseNote
        val latestCode = jsonCode
        val zip = releaseZip ?: jsonZip
        val sources = buildList {
            if (jsonVersion != null || jsonZip != null) add("updateJson")
            if (releaseTag != null || releaseZip != null) add("GitHub Release")
        }
        if (latestVersion == null && latestCode == null && zip == null) {
            return ModuleUpdateItem(module, error = jsonError ?: "更新源未返回版本信息")
        }

        // 只要有任一来源可用就允许下载；updateJson 的失败降级为提示，不阻断用户。
        val hasUpdate = newerThan(module, latestVersion, latestCode)
        return ModuleUpdateItem(
            module = module,
            latestVersion = latestVersion,
            latestVersionCode = latestCode,
            zipUrl = zip,
            changelog = null,
            releaseUrl = releaseUrl,
            source = sources.joinToString(" + ").ifBlank { null },
            hasUpdate = hasUpdate,
        )
    }

    private fun newerThan(module: InstalledModule, version: String?, code: Long?): Boolean {
        if (code != null && module.versionCode != null) return code > module.versionCode
        val current = module.version?.trim()?.removePrefix("v").orEmpty()
        val latest = version?.trim()?.removePrefix("v").orEmpty()
        if (current.isBlank() || latest.isBlank()) return false
        return current != latest
    }

    private val githubRepo = Regex("""github\.com[/:]([^/]+)/([^/#?]+)""")
    private val rawRepo = Regex("""raw\.githubusercontent\.com/([^/]+)/([^/]+)/""")

    fun repoOf(url: String?): String? {
        if (url == null) return null
        val m = githubRepo.find(url)
        if (m != null) return "${m.groupValues[1]}/${m.groupValues[2].removeSuffix(".git")}"
        val r = rawRepo.find(url)
        if (r != null) return "${r.groupValues[1]}/${r.groupValues[2]}"
        return null
    }
}

/** 下载的 zip 只放在 app 私有目录，不写入 /data/adb，也不需要额外权限。 */
object ModuleDownloadStore {

    fun dir(context: Context): File = File(context.filesDir, "module-updates").apply { mkdirs() }

    fun list(context: Context): List<DownloadedModule> =
        dir(context).listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".zip", ignoreCase = true) }
            .map {
                DownloadedModule(
                    name = it.name,
                    path = it.absolutePath,
                    size = it.length(),
                    modifiedAt = it.lastModified(),
                )
            }
            .sortedByDescending { it.modifiedAt }

    suspend fun save(context: Context, fileName: String, bytes: ByteArray): File =
        withContext(Dispatchers.IO) {
            val safe = fileName.replace(Regex("""[^A-Za-z0-9._-]"""), "_")
            val file = File(dir(context), safe)
            file.writeBytes(bytes)
            DiagLog.info("update", "已下载 $safe (${bytes.size}B) 到 app 私有目录")
            file
        }

    fun delete(path: String): Boolean = runCatching { File(path).delete() }.getOrDefault(false)

    fun clear(context: Context): Int {
        val files = list(context)
        return files.count { delete(it.path) }
    }

    fun safeFileName(moduleId: String, version: String?): String =
        "${moduleId.replace(Regex("""[^A-Za-z0-9._-]"""), "_")}-${version ?: "latest"}.zip"

    /** 找出某个模块最近一次下载的 zip（文件名按 `<模块id>-<版本>.zip` 生成）。 */
    fun forModule(files: List<DownloadedModule>, moduleId: String): DownloadedModule? {
        val prefix = moduleId.replace(Regex("""[^A-Za-z0-9._-]"""), "_") + "-"
        return files.firstOrNull { it.name.startsWith(prefix) }
    }
}

/**
 * 生成「打开」用的 content Uri：zip 在 app 私有目录，必须经 FileProvider 临时授权给外部应用。
 * 返回 null 表示文件已不存在。
 */
fun moduleFileUri(context: Context, file: DownloadedModule): android.net.Uri? =
    runCatching {
        androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            File(file.path),
        )
    }.getOrNull()
