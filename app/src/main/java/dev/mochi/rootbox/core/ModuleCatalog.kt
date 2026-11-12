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
import java.util.Locale

data class CatalogCategory(val id: String, val title: String, val order: Int)

data class CatalogModule(
    val id: String,
    val name: String,
    val category: String,
    val repo: String,
    val description: String?,
    val note: String?,
)

data class CatalogIndex(
    val version: Int,
    val updatedAt: String?,
    val categories: List<CatalogCategory>,
    val modules: List<CatalogModule>,
) {
    /** 分组后在每个分类内按名称逐字母排序（a→b→c，与通讯录一致）。 */
    fun grouped(): List<Pair<CatalogCategory, List<CatalogModule>>> =
        categories.sortedBy { it.order }.mapNotNull { category ->
            val items = modules
                .filter { it.category == category.id }
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            if (items.isEmpty()) null else category to items
        }
}

data class ReleaseAsset(val name: String, val size: Long, val url: String)

data class ModuleRelease(
    val repo: String,
    val tag: String = "",
    val title: String = "",
    val publishedAt: String? = null,
    val pageUrl: String = "",
    val asset: ReleaseAsset? = null,
    val fetchedAt: Long = 0L,
    val error: String? = null,
) {
    val ok: Boolean get() = error == null && asset != null
}

/**
 * 模块目录：索引来自远程 `modules/index.json`（仓库里的同一份文件同时打包进 APK 作兜底），
 * 最新版本通过 GitHub `releases/latest` 查询，结果本地缓存 6 小时，避免触发 API 限流。
 */
object ModuleCatalogStore {

    /** raw.githubusercontent 在部分网络不可用，按顺序尝试，最后回退内置副本。 */
    private val INDEX_URLS = listOf(
        "https://raw.githubusercontent.com/p90rushb666/RootToolbox/main/modules/index.json",
        "https://cdn.jsdelivr.net/gh/p90rushb666/RootToolbox@main/modules/index.json",
    )

    private const val INDEX_TTL_MS = 24 * 60 * 60 * 1000L
    private const val RELEASE_TTL_MS = 6 * 60 * 60 * 1000L
    private const val ASSET_TTL_MS = 6 * 60 * 60 * 1000L

    private fun dir(context: Context): File =
        File(context.filesDir, "catalog").apply { mkdirs() }

    private fun indexPath(context: Context) = File(dir(context), "index.json")
    private fun releasesPath(context: Context) = File(dir(context), "releases.json")

    // ---------------------------------------------------------------- 索引

    data class IndexResult(val index: CatalogIndex, val source: String, val fetchedAt: Long)

    fun cachedIndex(context: Context): IndexResult? = runCatching {
        val file = indexPath(context)
        if (!file.isFile) return null
        val fetchedAt = file.lastModified()
        IndexResult(parseIndex(file.readText()), "网络", fetchedAt)
    }.getOrNull()

    fun bundledIndex(context: Context): IndexResult = IndexResult(
        parseIndex(context.assets.open("index.json").bufferedReader().use { it.readText() }),
        "内置",
        0L,
    )

    fun loadIndex(context: Context): IndexResult {
        val cached = cachedIndex(context)
        val bundled = runCatching { bundledIndex(context) }.getOrNull()
        return when {
            cached != null -> cached
            bundled != null -> bundled
            else -> IndexResult(CatalogIndex(1, null, emptyList(), emptyList()), "内置", 0L)
        }
    }

    fun isIndexStale(result: IndexResult?, now: Long = System.currentTimeMillis()): Boolean =
        result == null || result.fetchedAt <= 0 || now - result.fetchedAt > INDEX_TTL_MS

    suspend fun refreshIndex(context: Context): IndexResult = withContext(Dispatchers.IO) {
        var lastError: Throwable? = null
        for (url in INDEX_URLS) {
            val result = runCatching {
                val text = Http.getText(url)
                val index = parseIndex(text)
                if (index.modules.isEmpty()) throw IllegalStateException("索引里没有模块")
                indexPath(context).writeText(text)
                DiagLog.info("catalog", "索引已更新：${index.modules.size} 个模块（$url）")
                IndexResult(index, "网络", System.currentTimeMillis())
            }
            result.getOrNull()?.let { return@withContext it }
            lastError = result.exceptionOrNull()
        }
        throw lastError ?: IllegalStateException("索引下载失败")
    }

    private fun parseIndex(text: String): CatalogIndex {
        val root = JSONObject(text)
        val categories = root.optJSONArray("categories")?.let { array ->
            (0 until array.length()).mapNotNull { i ->
                val item = array.optJSONObject(i) ?: return@mapNotNull null
                CatalogCategory(
                    id = item.optString("id"),
                    title = item.optString("title"),
                    order = item.optInt("order", i),
                )
            }
        }.orEmpty()
        val modules = root.optJSONArray("modules")?.let { array ->
            (0 until array.length()).mapNotNull { i ->
                val item = array.optJSONObject(i) ?: return@mapNotNull null
                val repo = item.optString("repo").trim()
                if (repo.isBlank()) return@mapNotNull null
                CatalogModule(
                    id = item.optString("id").ifBlank { repo.replace('/', '-') },
                    name = item.optString("name").ifBlank { repo.substringAfter('/') },
                    category = item.optString("category"),
                    repo = repo,
                    description = item.optString("description").takeIf { it.isNotBlank() },
                    note = item.optString("note").takeIf { it.isNotBlank() },
                )
            }
        }.orEmpty()
        return CatalogIndex(
            version = root.optInt("version", 1),
            updatedAt = root.optString("updatedAt").takeIf { it.isNotBlank() },
            categories = categories,
            modules = modules,
        )
    }

    // ---------------------------------------------------------------- 最新版本

    fun loadReleases(context: Context): Map<String, ModuleRelease> = runCatching {
        val file = releasesPath(context)
        if (!file.isFile) return emptyMap()
        val root = JSONObject(file.readText())
        buildMap {
            root.keys().forEach { repo ->
                val item = root.optJSONObject(repo) ?: return@forEach
                put(repo, parseRelease(repo, item))
            }
        }
    }.getOrDefault(emptyMap())

    /** 只查缓存里没有或已过期的仓库；[force] 为 true 时全部重查。 */
    suspend fun refreshReleases(
        context: Context,
        repos: List<String>,
        force: Boolean,
        onProgress: ((done: Int, total: Int) -> Unit)? = null,
    ): Map<String, ModuleRelease> = withContext(Dispatchers.IO) {
        val cached = loadReleases(context).toMutableMap()
        val now = System.currentTimeMillis()
        val targets = repos.distinct().filter { repo ->
            force || cached[repo]?.let { now - it.fetchedAt > RELEASE_TTL_MS } != false
        }
        var done = 0
        onProgress?.invoke(0, targets.size)
        for (chunk in targets.chunked(4)) {
            val results = coroutineScope {
                chunk.map { repo -> async { repo to queryRelease(repo) } }.awaitAll()
            }
            results.forEach { (repo, release) ->
                cached[repo] = release
                done++
                onProgress?.invoke(done, targets.size)
            }
            saveReleases(context, cached)
        }
        saveReleases(context, cached)
        cached.filterKeys { it in repos }
    }

    private suspend fun queryRelease(repo: String): ModuleRelease {
        val url = "https://api.github.com/repos/$repo/releases/latest"
        return try {
            val text = Http.getText(url)
            val json = JSONObject(text)
            val assets = json.optJSONArray("assets")?.let { array ->
                (0 until array.length()).mapNotNull { i ->
                    val item = array.optJSONObject(i) ?: return@mapNotNull null
                    ReleaseAsset(
                        name = item.optString("name"),
                        size = item.optLong("size"),
                        url = item.optString("browser_download_url"),
                    )
                }
            }.orEmpty()
            val asset = pickAsset(assets)
            ModuleRelease(
                repo = repo,
                tag = json.optString("tag_name"),
                title = json.optString("name").ifBlank { json.optString("tag_name") },
                publishedAt = json.optString("published_at").takeIf { it.isNotBlank() },
                pageUrl = json.optString("html_url"),
                asset = asset,
                fetchedAt = System.currentTimeMillis(),
                error = if (asset == null) "该 Release 没有可下载的 apk / zip" else null,
            )
        } catch (t: Throwable) {
            val message = t.message.orEmpty()
            ModuleRelease(
                repo = repo,
                fetchedAt = System.currentTimeMillis(),
                error = when {
                    message.contains("403") -> "GitHub API 限流，请稍后再试"
                    message.contains("404") -> "仓库或 Release 不存在"
                    else -> "查询失败：${t.javaClass.simpleName}"
                },
            )
        }
    }

    private fun pickAsset(assets: List<ReleaseAsset>): ReleaseAsset? {
        fun usable(name: String) = name.endsWith(".apk", true) || name.endsWith(".zip", true)
        val apks = assets.filter { it.name.endsWith(".apk", true) && usable(it.name) }
        if (apks.isNotEmpty()) return apks.maxByOrNull { it.size }
        val zips = assets.filter { it.name.endsWith(".zip", true) && usable(it.name) }
        return zips.maxByOrNull { it.size }
    }

    private fun parseRelease(repo: String, item: JSONObject): ModuleRelease {
        val assetJson = item.optJSONObject("asset")
        return ModuleRelease(
            repo = repo,
            tag = item.optString("tag"),
            title = item.optString("title"),
            publishedAt = item.optString("publishedAt").takeIf { it.isNotBlank() },
            pageUrl = item.optString("pageUrl"),
            asset = assetJson?.let {
                ReleaseAsset(it.optString("name"), it.optLong("size"), it.optString("url"))
            },
            fetchedAt = item.optLong("fetchedAt"),
            error = item.optString("error").takeIf { it.isNotBlank() },
        )
    }

    private fun saveReleases(context: Context, releases: Map<String, ModuleRelease>) {
        val root = JSONObject()
        releases.forEach { (repo, release) ->
            root.put(repo, JSONObject().apply {
                put("tag", release.tag)
                put("title", release.title)
                put("publishedAt", release.publishedAt ?: "")
                put("pageUrl", release.pageUrl)
                put("fetchedAt", release.fetchedAt)
                put("error", release.error ?: "")
                release.asset?.let { asset ->
                    put("asset", JSONObject().apply {
                        put("name", asset.name)
                        put("size", asset.size)
                        put("url", asset.url)
                    })
                }
            })
        }
        runCatching { releasesPath(context).writeText(root.toString()) }
    }

    fun assetTtlMs(): Long = ASSET_TTL_MS
}

/** 模块下载文件（apk / zip），独立于「模块更新」的临时目录。 */
data class CatalogDownload(
    val name: String,
    val path: String,
    val size: Long,
    val modifiedAt: Long,
)

object CatalogDownloadStore {

    private fun dir(context: Context): File =
        File(context.filesDir, "module-downloads").apply { mkdirs() }

    fun list(context: Context): List<CatalogDownload> =
        dir(context).listFiles().orEmpty()
            .filter { it.isFile }
            .map { CatalogDownload(it.name, it.absolutePath, it.length(), it.lastModified()) }
            .sortedByDescending { it.modifiedAt }

    fun fileName(moduleId: String, tag: String, assetName: String): String {
        val ext = assetName.substringAfterLast('.', "zip").lowercase(Locale.US)
        val safeTag = tag.replace(Regex("""[^A-Za-z0-9._-]"""), "_").ifBlank { "latest" }
        val safeId = moduleId.replace(Regex("""[^A-Za-z0-9._-]"""), "_")
        return "$safeId-$safeTag.$ext"
    }

    fun find(context: Context, moduleId: String): CatalogDownload? =
        list(context).firstOrNull { it.name.startsWith("$moduleId-") }

    suspend fun save(context: Context, fileName: String, bytes: ByteArray): File =
        withContext(Dispatchers.IO) {
            val file = File(dir(context), fileName)
            file.writeBytes(bytes)
            DiagLog.info("catalog", "已下载 $fileName (${bytes.size}B)")
            file
        }

    fun delete(path: String): Boolean = runCatching { File(path).delete() }.getOrDefault(false)

    fun clear(context: Context): Int {
        val files = list(context)
        return files.count { delete(it.path) }
    }
}

/** 把下载目录里的文件通过 FileProvider 交给系统打开。 */
fun openCatalogFile(context: Context, path: String): Boolean = runCatching {
    val file = File(path)
    if (!file.isFile) return false
    val uri = androidx.core.content.FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file,
    )
    val mime = when {
        file.name.endsWith(".apk", true) -> "application/vnd.android.package-archive"
        file.name.endsWith(".zip", true) -> "application/zip"
        else -> "*/*"
    }
    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mime)
        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
    true
}.getOrDefault(false)
