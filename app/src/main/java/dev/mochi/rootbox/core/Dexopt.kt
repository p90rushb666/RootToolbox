package dev.mochi.rootbox.core

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

/** 待编译应用。 */
data class DexoptApp(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
)

enum class DexoptScope(val label: String, val description: String, val includeSystem: Boolean) {
    CURRENT_USER("用户应用", "只编译第三方（非系统）应用", false),
    ALL_USERS_SYSTEM("全部应用", "包含系统应用，耗时更长", true),
}

enum class DexoptMode(val cmd: String, val label: String, val description: String) {
    QUICKEN("quicken", "快速", "只做必要的校验与快速编译，速度最快"),
    SPEED_PROFILE("speed-profile", "热点", "按配置文件编译热点代码，日常推荐"),
    SPEED("speed", "全量", "全量 AOT 编译，占用空间最大、启动最快"),
}

enum class DexoptThreads(val value: Int, val label: String) {
    SYSTEM(-1, "系统档位"),
    TWO(2, "2 线程"),
    FOUR(4, "4 线程"),
    EIGHT(8, "8 线程"),
    ALL(0, "全开"),
}

/** 一次编译任务；持久化到 files/dexopt/job.json，中断后可继续。 */
data class DexoptJob(
    val id: String,
    val packages: List<String>,
    val labels: Map<String, String>,
    val mode: String,
    val threads: Int,
    val scope: String,
    val done: List<String> = emptyList(),
    val failed: Map<String, String> = emptyMap(),
    val startedAt: Long = System.currentTimeMillis(),
    val status: String = "running",
) {
    val finished: Boolean get() = status == "finished"
    fun remaining(): List<String> = packages.filterNot { it in done || failed.containsKey(it) }
}

data class DexoptRuntime(
    val running: Boolean = false,
    val jobId: String? = null,
    val total: Int = 0,
    val done: Int = 0,
    val failed: Int = 0,
    val currentLabel: String? = null,
    val currentPackage: String? = null,
    val message: String? = null,
    val finishedAt: Long? = null,
)

object DexoptStore {

    private fun file(context: Context): File =
        File(context.filesDir, "dexopt").apply { mkdirs() }.resolve("job.json")

    fun load(context: Context): DexoptJob? = runCatching {
        val f = file(context)
        if (!f.isFile) return null
        val json = JSONObject(f.readText())
        val packages = json.optJSONArray("packages")?.let { array ->
            (0 until array.length()).map { array.optString(it) }
        }.orEmpty()
        if (packages.isEmpty()) return null
        val labels = json.optJSONObject("labels")?.let { obj ->
            buildMap { obj.keys().forEach { put(it, obj.optString(it)) } }
        }.orEmpty()
        val done = json.optJSONArray("done")?.let { array ->
            (0 until array.length()).map { array.optString(it) }
        }.orEmpty()
        val failed = json.optJSONObject("failed")?.let { obj ->
            buildMap { obj.keys().forEach { put(it, obj.optString(it)) } }
        }.orEmpty()
        DexoptJob(
            id = json.optString("id"),
            packages = packages,
            labels = labels,
            mode = json.optString("mode", "speed-profile"),
            threads = json.optInt("threads", -1),
            scope = json.optString("scope", "CURRENT_USER"),
            done = done,
            failed = failed,
            startedAt = json.optLong("startedAt"),
            status = json.optString("status", "paused"),
        )
    }.getOrNull()

    fun save(context: Context, job: DexoptJob) {
        runCatching {
            val json = JSONObject().apply {
                put("id", job.id)
                put("mode", job.mode)
                put("threads", job.threads)
                put("scope", job.scope)
                put("startedAt", job.startedAt)
                put("status", job.status)
                put("packages", org.json.JSONArray(job.packages))
                put("done", org.json.JSONArray(job.done))
                put("labels", JSONObject().apply { job.labels.forEach { (k, v) -> put(k, v) } })
                put("failed", JSONObject().apply { job.failed.forEach { (k, v) -> put(k, v) } })
            }
            file(context).writeText(json.toString())
        }
    }

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }
}

/** 列出可编译的应用（含系统应用）。 */
suspend fun listDexoptApps(context: Context, includeSystem: Boolean): List<DexoptApp> {
    val pm = context.packageManager
    @Suppress("DEPRECATION")
    val packages = pm.getInstalledPackages(0)
    return packages.mapNotNull { info ->
        val appInfo: ApplicationInfo = info.applicationInfo ?: return@mapNotNull null
        val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        if (isSystem && !includeSystem) return@mapNotNull null
        DexoptApp(
            packageName = info.packageName,
            label = runCatching { appInfo.loadLabel(pm).toString() }.getOrDefault(info.packageName),
            isSystem = isSystem,
        )
    }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
}

/**
 * dex2oat 编译执行器：`cmd package compile` + 临时 `setprop dalvik.vm.dex2oat-threads`。
 * 任务逐包执行并持久化，中断后可从剩余包继续。
 */
object DexoptRunner {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(DexoptRuntime())
    val state: StateFlow<DexoptRuntime> = _state.asStateFlow()

    private var runningJob: Job? = null

    @Volatile
    private var cancelled = false

    fun isRunning(): Boolean = runningJob?.isActive == true

    fun start(context: Context, job: DexoptJob) {
        if (isRunning()) return
        cancelled = false
        DexoptStore.save(context, job.copy(status = "running"))
        runningJob = scope.launch { runJob(context.applicationContext, job) }
    }

    fun cancel(context: Context) {
        cancelled = true
        runningJob?.cancel()
        val job = DexoptStore.load(context)
        if (job != null) DexoptStore.save(context, job.copy(status = "paused"))
        _state.value = _state.value.copy(running = false, message = "已暂停")
    }

    private suspend fun runJob(context: Context, initial: DexoptJob) {
        val mode = DexoptMode.entries.firstOrNull { it.name == initial.mode } ?: DexoptMode.SPEED_PROFILE
        val threads = DexoptThreads.entries.firstOrNull { it.value == initial.threads } ?: DexoptThreads.SYSTEM

        val packages = initial.remaining()
        val total = packages.size
        _state.value = DexoptRuntime(
            running = true,
            jobId = initial.id,
            total = total,
            done = initial.done.size,
            failed = initial.failed.size,
            message = "准备编译 ${packages.size} 个应用",
        )

        val threadValue = when (threads) {
            DexoptThreads.SYSTEM -> null
            DexoptThreads.ALL -> Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
            else -> threads.value
        }
        val originalThreads = if (threadValue != null) getProp("dalvik.vm.dex2oat-threads") else null
        if (threadValue != null) {
            Shell.su("setprop dalvik.vm.dex2oat-threads $threadValue")
        }

        var job = initial
        try {
            for ((index, pkg) in packages.withIndex()) {
                if (cancelled) break
                val label = job.labels[pkg] ?: pkg
                _state.value = _state.value.copy(
                    currentPackage = pkg,
                    currentLabel = label,
                    done = job.done.size,
                    failed = job.failed.size,
                )
                var lastError: String? = null
                // `cmd package compile` 不支持 `--user`（会报 unknown option 导致每次都失败），
                // 去掉后默认编译当前用户，命令才能真正执行。
                val result = Shell.su(
                    "cmd package compile -m ${mode.cmd} -f $pkg",
                    timeoutMs = 600_000L,
                )
                if (!result.ok) lastError = result.brief().take(400)
                job = if (lastError == null) {
                    job.copy(done = job.done + pkg)
                } else {
                    job.copy(failed = job.failed + (pkg to lastError))
                }
                DexoptStore.save(context, job)
                _state.value = _state.value.copy(
                    done = job.done.size,
                    failed = job.failed.size,
                    message = "已处理 ${index + 1}/${packages.size}",
                )
            }
        } finally {
            if (threadValue != null) {
                val restore = originalThreads?.takeIf { it.isNotBlank() } ?: ""
                Shell.su(
                    if (restore.isNotEmpty()) {
                        "setprop dalvik.vm.dex2oat-threads $restore"
                    } else {
                        "setprop dalvik.vm.dex2oat-threads 0"
                    },
                )
            }
            val finalJob = job.copy(status = if (cancelled) "paused" else "finished")
            DexoptStore.save(context, finalJob)
            _state.value = _state.value.copy(
                running = false,
                currentLabel = null,
                currentPackage = null,
                finishedAt = System.currentTimeMillis(),
                message = if (cancelled) "已暂停" else "编译完成",
            )
        }
    }

    private fun getProp(name: String): String? {
        // 这里只读属性，用非 root 也能读到一部分；读不到就算了。
        return runCatching {
            val process = ProcessBuilder("getprop", name).start()
            val text = process.inputStream.bufferedReader().use { it.readText() }.trim()
            process.waitFor()
            text.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }
}
