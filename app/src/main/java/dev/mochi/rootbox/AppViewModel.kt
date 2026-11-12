package dev.mochi.rootbox

import android.app.Application
import android.net.Uri
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.mochi.rootbox.core.BackupEntry
import dev.mochi.rootbox.core.BackupScan
import dev.mochi.rootbox.core.BackupStore
import dev.mochi.rootbox.core.DexoptApp
import dev.mochi.rootbox.core.DexoptJob
import dev.mochi.rootbox.core.DexoptMode
import dev.mochi.rootbox.core.DexoptPrefs
import dev.mochi.rootbox.core.DexoptReport
import dev.mochi.rootbox.core.DexoptReportStore
import dev.mochi.rootbox.core.DexoptRunner
import dev.mochi.rootbox.core.DexoptRuntime
import dev.mochi.rootbox.core.DexoptScope
import dev.mochi.rootbox.core.DexoptService
import dev.mochi.rootbox.core.DexoptStore
import dev.mochi.rootbox.core.DexoptThreads
import dev.mochi.rootbox.core.DiagLog
import dev.mochi.rootbox.core.listDexoptApps
import dev.mochi.rootbox.core.InstallResult
import dev.mochi.rootbox.core.KeyboxInstaller
import dev.mochi.rootbox.core.KeyboxMeta
import dev.mochi.rootbox.core.KeyboxParser
import dev.mochi.rootbox.core.ModuleLoadChecker
import dev.mochi.rootbox.core.ModuleLoadStatus
import dev.mochi.rootbox.core.ModuleUpdateChecker
import dev.mochi.rootbox.core.CatalogDownload
import dev.mochi.rootbox.core.CatalogDownloadStore
import dev.mochi.rootbox.core.CatalogIndex
import dev.mochi.rootbox.core.CatalogModule
import dev.mochi.rootbox.core.ModuleCatalogStore
import dev.mochi.rootbox.core.ModuleRelease
import dev.mochi.rootbox.core.ModuleUpdateItem
import dev.mochi.rootbox.core.RevocationStore
import dev.mochi.rootbox.core.DownloadedModule
import dev.mochi.rootbox.core.ModuleDownloadStore
import dev.mochi.rootbox.core.AttestationChecker
import dev.mochi.rootbox.core.AttestationResult
import dev.mochi.rootbox.core.RootChecker
import dev.mochi.rootbox.core.RootInfo
import dev.mochi.rootbox.core.Shell
import dev.mochi.rootbox.core.TrickyStoreScan
import dev.mochi.rootbox.core.TrickyStoreScanner
import dev.mochi.rootbox.data.AppSettings
import dev.mochi.rootbox.data.DarkMode
import dev.mochi.rootbox.data.DownloadCancelledException
import dev.mochi.rootbox.data.Http
import dev.mochi.rootbox.data.InstallPreview
import dev.mochi.rootbox.data.KeyboxItem
import dev.mochi.rootbox.data.KeyboxRepository
import dev.mochi.rootbox.data.SettingsStore
import dev.mochi.rootbox.data.SourceResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class InstallUi(
    val uid: String,
    val name: String,
    val phase: String,
    val fraction: Float,
    val running: Boolean,
    val result: InstallResult? = null,
)

/** 模块更新检查页的完整状态。 */
data class ModuleUpdateUi(
    val items: List<ModuleUpdateItem> = emptyList(),
    val loading: Boolean = false,
    /** 检查进度：已完成 / 总数，用于实时进度条。 */
    val checked: Int = 0,
    val total: Int = 0,
    val error: String? = null,
    val checkedAt: Long? = null,
    val files: List<DownloadedModule> = emptyList(),
    /** 正在下载的模块 id -> 进度。 */
    val downloading: Map<String, DownloadProgress> = emptyMap(),
)

/** dex2oat 编译页的状态。 */
data class DexoptUi(
    /** 全部应用（含系统），编译对象按 [scope] 过滤。 */
    val apps: List<DexoptApp> = emptyList(),
    val loadingApps: Boolean = false,
    val scope: DexoptScope = DexoptScope.CURRENT_USER,
    val mode: DexoptMode = DexoptMode.SPEED_PROFILE,
    val threads: DexoptThreads = DexoptThreads.SYSTEM,
    val runtime: DexoptRuntime = DexoptRuntime(),
    val savedJob: DexoptJob? = null,
    val reports: List<DexoptReport> = emptyList(),
    val error: String? = null,
) {
    /** 当前范围要编译的应用。 */
    val scopedApps: List<DexoptApp> get() = apps.filter { scope.includeSystem || !it.isSystem }
}

/** 模块下载页的状态。 */
data class CatalogUi(
    val index: CatalogIndex = CatalogIndex(1, null, emptyList(), emptyList()),
    val indexSource: String = "内置",
    val indexAt: Long = 0L,
    val loadingIndex: Boolean = false,
    /** 索引文件是否已就绪（本地缓存 / 内置副本 / 联网更新任一完成）；就绪前分类不可进入。 */
    val indexReady: Boolean = false,
    val releases: Map<String, ModuleRelease> = emptyMap(),
    val loadingReleases: Boolean = false,
    val releaseChecked: Int = 0,
    val releaseTotal: Int = 0,
    val error: String? = null,
    val downloading: Map<String, DownloadProgress> = emptyMap(),
    val files: List<CatalogDownload> = emptyList(),
)

data class DownloadProgress(
    val name: String,
    val read: Long,
    val total: Long,
    val fraction: Float,
)

data class AppState(
    val rootChecking: Boolean = true,
    val root: RootInfo? = null,
    val scanning: Boolean = false,
    val scan: TrickyStoreScan? = null,
    val targetPath: String? = null,
    val currentMeta: KeyboxMeta? = null,
    val currentError: String? = null,
    val sources: List<SourceResult> = emptyList(),
    val keysLoading: Boolean = false,
    val keysLoadedOnce: Boolean = false,
    val refreshDone: Int = 0,
    val refreshTotal: Int = 2,
    val showArchived: Boolean = false,
    val install: InstallUi? = null,
    val message: String? = null,
    /** 阶段 A 产物：预览中就绪但尚未写入。 */
    val preview: InstallPreview? = null,
    val previewLoading: Boolean = false,
    val backups: BackupScan? = null,
    val backupsLoading: Boolean = false,
    val moduleLoad: ModuleLoadStatus? = null,
    val moduleLoadChecking: Boolean = false,
    val attestation: AttestationResult? = null,
    val attestationRunning: Boolean = false,
    /** 吊销列表缓存：最近一次成功拉取的时间与条目数（0 表示从未拉取）。 */
    val revocationAt: Long = 0L,
    val revocationCount: Int = 0,
    val revocationUpdating: Boolean = false,
    val moduleUpdates: ModuleUpdateUi = ModuleUpdateUi(),
    val catalog: CatalogUi = CatalogUi(),
    val dexopt: DexoptUi = DexoptUi(),
) {
    val allItems: List<KeyboxItem> get() = sources.flatMap { it.items }
}

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsStore = SettingsStore(application)

    private val _settings = MutableStateFlow(settingsStore.load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _state = MutableStateFlow(AppState(showArchived = _settings.value.showArchivedByDefault))
    val state: StateFlow<AppState> = _state.asStateFlow()

    /** 预览阶段下载的字节只存在这里，不落盘、不写日志。 */
    private var pendingBytes: ByteArray? = null

    /** 最近一次成功写入的 SHA-256，用于模块加载检测的对照。 */
    private var lastWrittenSha: String? = null

    init {
        // 恢复上次的编译参数与报告。
        val app = getApplication<Application>()
        _state.update {
            it.copy(
                dexopt = it.dexopt.copy(
                    scope = DexoptPrefs.loadScope(app),
                    mode = DexoptPrefs.loadMode(app),
                    threads = DexoptPrefs.loadThreads(app),
                    reports = DexoptReportStore.load(app),
                ),
            )
        }
        // dex2oat 执行器是单例，界面只镜像它的状态；编译结束（running 由 true 变 false）时刷新报告。
        viewModelScope.launch {
            var wasRunning = false
            DexoptRunner.state.collect { runtime ->
                _state.update { it.copy(dexopt = it.dexopt.copy(runtime = runtime)) }
                if (wasRunning && !runtime.running) {
                    val reports = DexoptReportStore.load(getApplication())
                    _state.update { it.copy(dexopt = it.dexopt.copy(reports = reports)) }
                }
                wasRunning = runtime.running
            }
        }
    }

    private var started = false

    fun start() {
        // 配置变化/重组会再次调用 start；用一次性标记保证只有真正的冷启动才自动检测。
        if (started) return
        started = true
        refreshRevocationInfo()
        _state.update {
            it.copy(dexopt = it.dexopt.copy(savedJob = DexoptStore.load(getApplication())))
        }
        if (_settings.value.autoCheckRoot) {
            refreshRoot()
            runAttestation()
        } else {
            viewModelScope.launch { prepareWithoutRootProbe() }
        }
    }

    /** 第一次进入密钥库时按需拉取，之后不再重复触发。 */
    fun ensureKeysLoaded() {
        if (_state.value.keysLoadedOnce || _state.value.keysLoading) return
        loadKeys()
    }

    private suspend fun prepareWithoutRootProbe() {
        _state.update { it.copy(rootChecking = false, root = null) }
        scanModule(false)
    }

    fun refreshRoot() {
        viewModelScope.launch { refreshAll() }
    }

    suspend fun refreshAll() {
        _state.update { it.copy(rootChecking = true) }
        val info = RootChecker.detect()
        _state.update { it.copy(rootChecking = false, root = info) }
        scanModule(info.granted)
    }

    fun rescan() {
        viewModelScope.launch { scanModule(_state.value.root?.granted == true) }
    }

    private suspend fun scanModule(granted: Boolean) {
        if (!granted) {
            val empty = TrickyStoreScan(
                module = dev.mochi.rootbox.core.ModuleInfo(false, null, null, null, null, null),
                candidates = emptyList(),
                defaultTarget = TrickyStoreScanner.PRIMARY_TARGET,
                warnings = listOf("未获得 Root 权限，无法读取 /data/adb"),
            )
            val saved = _settings.value.savedTarget
            _state.update {
                it.copy(
                    scan = empty,
                    scanning = false,
                    targetPath = saved ?: empty.defaultTarget,
                    currentMeta = null,
                )
            }
            return
        }
        _state.update { it.copy(scanning = true) }
        val scan = TrickyStoreScanner.scan()
        val saved = _settings.value.savedTarget
        val target = saved
            ?: _state.value.targetPath
            ?: scan.defaultTarget
        _state.update {
            it.copy(
                scan = scan,
                scanning = false,
                targetPath = target,
            )
        }
        loadCurrentKeybox(target)
    }

    private suspend fun loadCurrentKeybox(path: String) {
        val result = Shell.su("cat ${quotePath(path)}")
        if (!result.ok) {
            _state.update { it.copy(currentMeta = null, currentError = "读取失败：${result.brief()}") }
            return
        }
        val bytes = result.out.toByteArray(Charsets.UTF_8)
        if (bytes.isEmpty()) {
            _state.update { it.copy(currentMeta = null, currentError = "文件为空或不存在") }
            return
        }
        val meta = KeyboxParser.parse(bytes)
        _state.update { it.copy(currentMeta = meta, currentError = meta.error) }
    }

    fun selectTarget(path: String) {
        updateSettings { it.copy(savedTarget = path) }
        _state.update { it.copy(targetPath = path) }
        viewModelScope.launch {
            loadCurrentKeybox(path)
            loadBackups()
        }
    }

    fun loadKeys() {
        if (_state.value.keysLoading) return
        viewModelScope.launch { refreshKeys() }
    }

    suspend fun refreshKeys() {
        if (_state.value.keysLoading) return
        _state.update { it.copy(keysLoading = true, refreshDone = 0, refreshTotal = 2) }
        val sources = KeyboxRepository.loadAll { done, total ->
            _state.update { it.copy(refreshDone = done, refreshTotal = total) }
        }
        _state.update { it.copy(sources = sources, keysLoading = false, keysLoadedOnce = true) }
    }

    // ---------------------------------------------------------------- 安装预览（阶段 A）

    /** 下载 + 解析 + 校验，只读；不产生任何设备写入。 */
    fun prepareInstall(item: KeyboxItem) {
        val url = item.downloadUrl
        if (url == null) {
            _state.update { it.copy(message = "${item.sourceTitle} 需要人机验证，请用浏览器打开") }
            return
        }
        if (_state.value.previewLoading || _state.value.install?.running == true) return
        val target = _state.value.targetPath
        if (target.isNullOrBlank()) {
            _state.update { it.copy(message = "尚未确定替换目标，请先在首页选择路径") }
            return
        }

        viewModelScope.launch {
            _state.update {
                it.copy(
                    previewLoading = true,
                    install = InstallUi(item.uid, item.name, "下载中", 0.02f, running = true),
                )
            }
            try {
                val bytes = Http.getBytes(url) { read, total ->
                    val fraction = if (total > 0) (read.toFloat() / total.toFloat()) * 0.9f else 0.02f
                    val label = if (total > 0) {
                        "下载中 ${read / 1024}KB / ${total / 1024}KB"
                    } else {
                        "下载中 ${read / 1024}KB"
                    }
                    _state.update { state ->
                        state.install?.let {
                            state.copy(install = it.copy(phase = label, fraction = fraction.coerceIn(0.02f, 0.9f)))
                        } ?: state
                    }
                }
                val preview = buildPreview(
                    bytes = bytes,
                    origin = item.sourceTitle,
                    fileName = item.name.ifBlank { "keybox.xml" },
                    expectedSha256 = item.sha256,
                    item = item,
                    target = target,
                )
                pendingBytes = bytes.takeIf { preview.canConfirm }
                _state.update { it.copy(previewLoading = false, install = null, preview = preview) }
                DiagLog.info("preview", "预览已生成 可确认=${preview.canConfirm} 问题=${preview.issues.size}")
            } catch (t: Throwable) {
                DiagLog.error("preview", "下载失败", t)
                _state.update {
                    it.copy(
                        previewLoading = false,
                        install = null,
                        message = "下载失败：${t.javaClass.simpleName}: ${t.message}",
                    )
                }
            }
        }
    }

    /** 本地导入：用户自己选一个 xml 文件，同样先走预览。 */
    fun prepareLocalImport(uri: Uri) {
        if (_state.value.previewLoading || _state.value.install?.running == true) return
        val target = _state.value.targetPath
        if (target.isNullOrBlank()) {
            _state.update { it.copy(message = "尚未确定替换目标，请先在首页选择路径") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(previewLoading = true) }
            try {
                val bytes = withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use { stream ->
                        val buffer = java.io.ByteArrayOutputStream()
                        val chunk = ByteArray(16 * 1024)
                        var total = 0
                        while (true) {
                            val read = stream.read(chunk)
                            if (read < 0) break
                            total += read
                            if (total > MAX_IMPORT_BYTES) error("文件超过 ${MAX_IMPORT_BYTES / 1024 / 1024}MB")
                            buffer.write(chunk, 0, read)
                        }
                        buffer.toByteArray()
                    } ?: error("无法读取所选文件")
                }
                val name = uri.lastPathSegment?.substringAfterLast('/') ?: "keybox.xml"
                val preview = buildPreview(
                    bytes = bytes,
                    origin = "本地文件",
                    fileName = name,
                    expectedSha256 = null,
                    item = null,
                    target = target,
                )
                pendingBytes = bytes.takeIf { preview.canConfirm }
                _state.update { it.copy(previewLoading = false, preview = preview) }
            } catch (t: Throwable) {
                DiagLog.error("preview", "本地导入失败", t)
                _state.update {
                    it.copy(
                        previewLoading = false,
                        message = "导入失败：${t.javaClass.simpleName}: ${t.message}",
                    )
                }
            }
        }
    }

    /**
     * 阶段 A 的可复用前置流程：解析字节 -> 与索引哈希比对 -> 汇总预览与问题清单。
     * 任何一项不通过都会写进 [InstallPreview.issues]，让 UI 禁止进入确认态。
     */
    private suspend fun buildPreview(
        bytes: ByteArray,
        origin: String,
        fileName: String,
        expectedSha256: String?,
        item: KeyboxItem?,
        target: String,
    ): InstallPreview {
        val meta = KeyboxParser.parse(bytes)
        val current = _state.value.currentMeta
        val currentSha = readCurrentSha(target)
        val issues = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        if (bytes.size > MAX_IMPORT_BYTES) issues += "文件过大（${bytes.size / 1024}KB），超出可接受范围。"
        if (!meta.valid) issues += "结构无效：${meta.error ?: "未知错误"}"
        if (expectedSha256.isNullOrBlank()) {
            warnings += "来源未提供校验值，本次只做了结构与证书校验，无法确认下载内容与索引一致。"
        } else if (!expectedSha256.equals(meta.sha256, ignoreCase = true)) {
            issues += "SHA-256 与来源索引不一致（索引 ${expectedSha256.take(16)}… / 实际 ${meta.sha256.take(16)}…）。"
        }
        if (current != null && current.deviceId != null && meta.deviceId != null &&
            current.deviceId != meta.deviceId
        ) {
            warnings += "DeviceID 将发生变化：${current.deviceId} → ${meta.deviceId}。"
        }
        if (current != null && current.sha256.equals(meta.sha256, ignoreCase = true)) {
            warnings += "内容与设备上现有文件完全一致，替换不会改变任何东西。"
        }
        if (currentSha == null) {
            warnings += "读取不到设备上的当前文件，替换时将新建。"
        }

        return InstallPreview(
            origin = origin,
            fileName = fileName,
            targetPath = target,
            meta = meta,
            expectedSha256 = expectedSha256,
            indexItem = item,
            currentMeta = current,
            currentSha = currentSha,
            issues = issues,
            warnings = warnings,
            sourceItemUid = item?.uid,
        )
    }

    private suspend fun readCurrentSha(path: String): String? {
        if (_state.value.root?.granted != true) return null
        val result = Shell.su("cat ${quotePath(path)} 2>/dev/null")
        if (!result.ok || result.out.isEmpty()) return null
        return KeyboxMeta.sha256Hex(result.out.toByteArray(Charsets.UTF_8))
    }

    fun cancelPreview() {
        pendingBytes = null
        _state.update { it.copy(preview = null) }
        DiagLog.info("preview", "用户取消了预览，未写入任何内容")
    }

    /** 阶段 B：用户确认后才真正落盘，仍走 KeyboxInstaller 的备份 -> 写入 -> 校验 -> 回滚。 */
    fun confirmPreview() {
        val preview = _state.value.preview ?: return
        val bytes = pendingBytes
        if (bytes == null) {
            _state.update { it.copy(message = "预览内容已失效，请重新选择") }
            return
        }
        if (!preview.canConfirm) {
            _state.update { it.copy(message = "预览未通过校验，无法写入") }
            return
        }
        if (_state.value.install?.running == true) return

        _state.update { it.copy(preview = null) }
        viewModelScope.launch {
            _state.update {
                it.copy(
                    install = InstallUi(
                        uid = preview.sourceItemUid ?: "local",
                        name = preview.fileName,
                        phase = "准备写入",
                        fraction = 0.02f,
                        running = true,
                    ),
                )
            }
            try {
                val result = KeyboxInstaller.install(preview.targetPath, bytes) { progress ->
                    updateInstall(progress.phase, 0.05f + progress.fraction * 0.85f)
                }
                pendingBytes = null
                if (result.success) lastWrittenSha = result.sha256
                _state.update {
                    it.copy(
                        install = it.install?.copy(
                            running = false,
                            fraction = 1f,
                            phase = if (result.success) "完成" else result.title,
                            result = result,
                        ),
                        message = if (result.success) "替换完成" else result.title,
                    )
                }
                DiagLog.info("install", "结果 ${result.title}")
                scanModule(true)
                loadBackups()
                checkModuleLoad()
            } catch (t: Throwable) {
                DiagLog.error("install", "写入异常", t)
                _state.update {
                    it.copy(
                        install = it.install?.copy(running = false, phase = "失败：${t.message}", result = null),
                        message = "失败：${t.javaClass.simpleName}: ${t.message}",
                    )
                }
            }
        }
    }

    private fun updateInstall(phase: String, fraction: Float, running: Boolean = true) {
        _state.update { state ->
            state.install?.let { state.copy(install = it.copy(phase = phase, fraction = fraction, running = running)) } ?: state
        }
    }

    fun dismissInstall() {
        if (_state.value.install?.running == true) return
        _state.update { it.copy(install = null) }
    }

    // ---------------------------------------------------------------- 备份中心

    fun loadBackups() {
        if (_state.value.backupsLoading) return
        viewModelScope.launch { refreshBackups() }
    }

    suspend fun refreshBackups() {
        if (_state.value.backupsLoading) return
        _state.update { it.copy(backupsLoading = true) }
        val scan = BackupStore.scan(
            targetPath = _state.value.targetPath,
            savedTarget = _settings.value.savedTarget,
            rootAvailable = _state.value.root?.granted == true,
        )
        _state.update { it.copy(backups = scan, backupsLoading = false) }
    }

    fun restoreBackup(entry: BackupEntry) {
        if (_state.value.install?.running == true) return
        val target = _state.value.targetPath ?: return
        viewModelScope.launch {
            _state.update {
                it.copy(
                    install = InstallUi(
                        uid = entry.path,
                        name = entry.fileName,
                        phase = "从备份恢复",
                        fraction = 0.02f,
                        running = true,
                    ),
                )
            }
            val result = BackupStore.restore(target, entry) { progress ->
                updateInstall(progress.phase, 0.05f + progress.fraction * 0.85f)
            }
            if (result.success) lastWrittenSha = result.sha256
            _state.update {
                it.copy(
                    install = it.install?.copy(
                        running = false,
                        fraction = 1f,
                        phase = if (result.success) "恢复完成" else result.title,
                        result = result,
                    ),
                    message = if (result.success) "已从备份恢复" else result.title,
                )
            }
            loadCurrentKeybox(target)
            refreshBackups()
            checkModuleLoad()
        }
    }

    fun deleteBackups(entries: List<BackupEntry>) {
        if (entries.isEmpty()) return
        viewModelScope.launch {
            val (removed, failures) = BackupStore.delete(entries)
            refreshBackups()
            _state.update {
                it.copy(
                    message = if (failures.isEmpty()) {
                        "已删除 $removed 个备份"
                    } else {
                        "删除 $removed 个，${failures.size} 个失败：${failures.first()}"
                    },
                )
            }
        }
    }

    fun pruneBackups() {
        val entries = _state.value.backups?.entries.orEmpty()
        if (entries.isEmpty()) {
            _state.update { it.copy(message = "没有可清理的备份") }
            return
        }
        val keep = _settings.value.keepBackups
        viewModelScope.launch {
            val (removed, kept, failures) = BackupStore.prune(entries, keep)
            refreshBackups()
            _state.update {
                it.copy(
                    message = if (removed == 0) {
                        "备份数量未超过 $keep 个，无需清理"
                    } else if (failures.isEmpty()) {
                        "已清理 $removed 个，保留最新 $kept 个"
                    } else {
                        "清理 $removed 个，${failures.size} 个失败"
                    },
                )
            }
        }
    }

    // ---------------------------------------------------------------- 模块加载状态

    fun checkModuleLoad() {
        if (_state.value.moduleLoadChecking) return
        viewModelScope.launch {
            _state.update { it.copy(moduleLoadChecking = true) }
            val status = ModuleLoadChecker.check(lastWrittenSha, _state.value.root)
            _state.update { it.copy(moduleLoad = status, moduleLoadChecking = false) }
        }
    }

    // ---------------------------------------------------------------- 密钥认证

    /** 只在冷启动或用户手动点「重新检测」时调用；切换后台不会触发。 */
    fun runAttestation() {
        if (_state.value.attestationRunning) return
        viewModelScope.launch {
            _state.update { it.copy(attestationRunning = true) }
            val timeoutMs = _settings.value.attestationTimeoutSec * 1000L
            val result = AttestationChecker.check(getApplication(), timeoutMs)
            _state.update { it.copy(attestation = result, attestationRunning = false) }
            // 首次检测可能顺带拉到了吊销列表，同步一下缓存元信息。
            refreshRevocationInfo()
        }
    }

    /** 读取本地缓存的元信息（更新时间 / 条数），不联网。 */
    fun refreshRevocationInfo() {
        viewModelScope.launch {
            val snapshot = withContext(Dispatchers.IO) {
                RevocationStore.load(getApplication())
            }
            _state.update {
                it.copy(
                    revocationAt = snapshot?.fetchedAt ?: 0L,
                    revocationCount = snapshot?.count ?: 0,
                )
            }
        }
    }

    private var revocationJob: kotlinx.coroutines.Job? = null

    /**
     * 手动获取最新吊销列表（设置页 / 检测页的「获取最新」）。
     * 需要访问 Google 服务器，国内网络可能需要 VPN；失败时保留原缓存；可取消。
     */
    fun refreshRevocationList() {
        if (_state.value.revocationUpdating) return
        revocationJob = viewModelScope.launch {
            _state.update { it.copy(revocationUpdating = true) }
            try {
                val snapshot = withContext(Dispatchers.IO) { RevocationStore.fetch(getApplication()) }
                _state.update {
                    it.copy(
                        revocationAt = snapshot.fetchedAt,
                        revocationCount = snapshot.count,
                        message = "吊销列表已更新：${snapshot.count} 条",
                    )
                }
                if (_state.value.attestation != null) {
                    // 列表变了，重新算一次结论。
                    runAttestation()
                }
            } catch (t: kotlinx.coroutines.CancellationException) {
                // 用户主动取消：不报错、不改状态。
                throw t
            } catch (t: Throwable) {
                _state.update {
                    it.copy(message = "获取失败：${t.message ?: "网络不可用"}（已保留原缓存）")
                }
            } finally {
                _state.update { it.copy(revocationUpdating = false) }
            }
        }
    }

    /** 取消进行中的吊销列表获取。 */
    fun cancelRevocationRefresh() {
        revocationJob?.cancel()
        revocationJob = null
        _state.update { it.copy(revocationUpdating = false) }
    }

    // ---------------------------------------------------------------- dex2oat 编译

    fun loadDexoptApps() {
        if (_state.value.dexopt.loadingApps) return
        viewModelScope.launch {
            _state.update { it.copy(dexopt = it.dexopt.copy(loadingApps = true, error = null)) }
            val apps = withContext(Dispatchers.IO) {
                runCatching { listDexoptApps(getApplication(), includeSystem = true) }
            }
            _state.update { state ->
                val list = apps.getOrNull().orEmpty()
                state.copy(
                    dexopt = state.dexopt.copy(
                        apps = list,
                        loadingApps = false,
                        error = apps.exceptionOrNull()?.let { "读取应用列表失败：${it.message}" },
                    ),
                )
            }
        }
    }

    fun setDexoptScope(scope: DexoptScope) {
        _state.update { it.copy(dexopt = it.dexopt.copy(scope = scope)) }
        persistDexoptPrefs()
    }

    fun setDexoptMode(mode: DexoptMode) {
        _state.update { it.copy(dexopt = it.dexopt.copy(mode = mode)) }
        persistDexoptPrefs()
    }

    fun setDexoptThreads(threads: DexoptThreads) {
        _state.update { it.copy(dexopt = it.dexopt.copy(threads = threads)) }
        persistDexoptPrefs()
    }

    private fun persistDexoptPrefs() {
        val d = _state.value.dexopt
        DexoptPrefs.save(getApplication(), d.scope, d.mode, d.threads)
    }

    fun startDexopt() {
        val ui = _state.value.dexopt
        if (ui.runtime.running) return
        if (_state.value.root?.granted != true) {
            _state.update { it.copy(message = "dex2oat 需要 Root 权限") }
            return
        }
        val packages = ui.scopedApps.map { it.packageName }
        if (packages.isEmpty()) {
            _state.update { it.copy(message = "没有可编译的应用") }
            return
        }
        val labels = ui.scopedApps.associate { it.packageName to it.label }
        val job = DexoptJob(
            id = System.currentTimeMillis().toString(),
            packages = packages,
            labels = labels,
            mode = ui.mode.name,
            threads = ui.threads.value,
            scope = ui.scope.name,
        )
        DexoptService.start(getApplication())
        DexoptRunner.start(getApplication(), job)
        _state.update { it.copy(dexopt = it.dexopt.copy(savedJob = job, error = null)) }
    }

    fun pauseDexopt() {
        DexoptRunner.cancel(getApplication())
        _state.update { it.copy(dexopt = it.dexopt.copy(savedJob = DexoptStore.load(getApplication()))) }
    }

    /**
     * 无障碍保活自动开启：第一次由用户手动开启，之后若被系统关闭且已有 Root，
     * 直接用 Root 写入 Settings.Secure 重新开启，无需再手动进设置页。
     */
    fun autoEnableAccessibility() {
        if (_state.value.root?.granted != true) return
        viewModelScope.launch {
            val app = getApplication<Application>()
            val component = "${app.packageName}/dev.mochi.rootbox.core.KeepAliveService"
            val current = withContext(Dispatchers.IO) {
                Settings.Secure.getString(
                    app.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                ).orEmpty()
            }
            if (current.split(':').any { it.trim() == component }) return@launch
            val merged = if (current.isBlank()) component else "$current:$component"
            val result = Shell.su(
                "settings put secure enabled_accessibility_services '$merged' && " +
                    "settings put secure accessibility_enabled 1",
            )
            if (result.ok) {
                DiagLog.info("accessibility", "已用 Root 自动开启无障碍保活")
            } else {
                DiagLog.warn("accessibility", "自动开启无障碍失败：${result.brief()}")
            }
        }
    }

    /** 继续上次中断的任务（只跑还没完成的包）。 */
    fun resumeDexopt() {
        val job = DexoptStore.load(getApplication()) ?: return
        if (job.remaining().isEmpty()) {
            _state.update { it.copy(message = "上次的任务已经完成") }
            return
        }
        if (_state.value.root?.granted != true) {
            _state.update { it.copy(message = "dex2oat 需要 Root 权限") }
            return
        }
        DexoptService.start(getApplication())
        DexoptRunner.start(getApplication(), job.copy(status = "running"))
        _state.update { it.copy(dexopt = it.dexopt.copy(savedJob = job)) }
    }

    fun clearDexoptJob() {
        DexoptStore.clear(getApplication())
        _state.update { it.copy(dexopt = it.dexopt.copy(savedJob = null)) }
    }

    // ---------------------------------------------------------------- 模块下载

    /** 用内置/缓存索引把页面填出来（不自动联网），只有手动刷新（force）才联网更新索引；版本查询后台继续。 */
    fun loadCatalog(force: Boolean = false) {
        if (_state.value.catalog.loadingIndex || _state.value.catalog.loadingReleases) return
        viewModelScope.launch {
            val local = withContext(Dispatchers.IO) { ModuleCatalogStore.loadIndex(getApplication()) }
            _state.update {
                it.copy(
                    catalog = it.catalog.copy(
                        index = local.index,
                        indexSource = local.source,
                        indexAt = local.fetchedAt,
                        // 内置/缓存索引立即就绪，分类直接可进，不再自动联网刷新
                        indexReady = true,
                    ),
                )
            }
            if (force) {
                // 仅手动刷新时才联网更新索引；失败静默保留内置/缓存，不弹「failed to connect」之类的错误。
                _state.update { it.copy(catalog = it.catalog.copy(loadingIndex = true)) }
                val refreshed = withContext(Dispatchers.IO) {
                    runCatching { ModuleCatalogStore.refreshIndex(getApplication()) }
                }
                _state.update { state ->
                    val result = refreshed.getOrNull()
                    state.copy(
                        catalog = state.catalog.copy(
                            index = result?.index ?: state.catalog.index,
                            indexSource = result?.source ?: state.catalog.indexSource,
                            indexAt = result?.fetchedAt ?: state.catalog.indexAt,
                            loadingIndex = false,
                        ),
                    )
                }
            }
            fetchCatalogReleases(force)
            refreshCatalogFiles()
        }
    }

    private suspend fun fetchCatalogReleases(force: Boolean) {
        val repos = _state.value.catalog.index.modules.map { it.repo }
        if (repos.isEmpty()) return
        _state.update {
            it.copy(
                catalog = it.catalog.copy(
                    loadingReleases = true,
                    releaseChecked = 0,
                    releaseTotal = repos.size,
                ),
            )
        }
        val cached = withContext(Dispatchers.IO) { ModuleCatalogStore.loadReleases(getApplication()) }
        _state.update { it.copy(catalog = it.catalog.copy(releases = cached)) }
        val result = withContext(Dispatchers.IO) {
            runCatching {
                ModuleCatalogStore.refreshReleases(getApplication(), repos, force) { done, total ->
                    _state.update { state ->
                        state.copy(
                            catalog = state.catalog.copy(
                                releaseChecked = done,
                                releaseTotal = total,
                                releases = ModuleCatalogStore.loadReleases(getApplication()),
                            ),
                        )
                    }
                }
            }
        }
        _state.update { state ->
            state.copy(
                catalog = state.catalog.copy(
                    loadingReleases = false,
                    releases = result.getOrNull() ?: state.catalog.releases,
                    // 版本查询失败静默（保留缓存 / 显示「暂不可用」），不弹网络错误文案。
                ),
            )
        }
    }

    fun refreshCatalogFiles() {
        viewModelScope.launch {
            val files = withContext(Dispatchers.IO) { CatalogDownloadStore.list(getApplication()) }
            _state.update { it.copy(catalog = it.catalog.copy(files = files)) }
        }
    }

    /** 下载模块 / 管理器安装包到 app 私有目录，支持取消与实时进度。 */
    fun downloadCatalogModule(module: CatalogModule) {
        val release = _state.value.catalog.releases[module.repo] ?: return
        val asset = release.asset ?: return
        val id = module.id
        if (_state.value.catalog.downloading.containsKey(id)) return
        val fileName = CatalogDownloadStore.fileName(id, release.tag, asset.name)
        val job = viewModelScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            _state.update { state ->
                state.copy(
                    catalog = state.catalog.copy(
                        downloading = state.catalog.downloading + (
                            id to DownloadProgress(fileName, 0, asset.size, 0f)
                        ),
                    ),
                )
            }
            try {
                val bytes = Http.getBytes(asset.url) { read, total ->
                    val fraction = if (total > 0) read.toFloat() / total.toFloat() else 0f
                    _state.update { state ->
                        val current = state.catalog.downloading[id] ?: return@update state
                        state.copy(
                            catalog = state.catalog.copy(
                                downloading = state.catalog.downloading + (
                                    id to current.copy(read = read, total = total, fraction = fraction)
                                ),
                            ),
                        )
                    }
                }
                if (kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]?.isActive == false) {
                    throw DownloadCancelledException()
                }
                CatalogDownloadStore.save(getApplication(), fileName, bytes)
                _state.update { state ->
                    state.copy(
                        catalog = state.catalog.copy(
                            downloading = state.catalog.downloading - id,
                            files = CatalogDownloadStore.list(getApplication()),
                        ),
                        message = "已下载 $fileName",
                    )
                }
            } catch (t: DownloadCancelledException) {
                _state.update { state ->
                    state.copy(catalog = state.catalog.copy(downloading = state.catalog.downloading - id))
                }
            } catch (t: kotlinx.coroutines.CancellationException) {
                _state.update { state ->
                    state.copy(catalog = state.catalog.copy(downloading = state.catalog.downloading - id))
                }
            } catch (t: Throwable) {
                DiagLog.error("catalog", "$id 下载失败", t)
                _state.update { state ->
                    state.copy(
                        catalog = state.catalog.copy(downloading = state.catalog.downloading - id),
                        message = "下载失败：${t.javaClass.simpleName}: ${t.message}",
                    )
                }
            } finally {
                val self = kotlinx.coroutines.currentCoroutineContext().job
                catalogJobs.remove(id, self)
            }
        }
        catalogJobs[id] = job
        job.start()
    }

    fun cancelCatalogDownload(id: String) {
        val job = catalogJobs.remove(id) ?: return
        job.cancel()
        _state.update { state ->
            state.copy(catalog = state.catalog.copy(downloading = state.catalog.downloading - id))
        }
    }

    fun deleteCatalogFile(file: CatalogDownload) {
        if (CatalogDownloadStore.delete(file.path)) {
            _state.update { state ->
                state.copy(
                    catalog = state.catalog.copy(files = CatalogDownloadStore.list(getApplication())),
                    message = "已删除 ${file.name}",
                )
            }
        } else {
            _state.update { it.copy(message = "删除失败：${file.name}") }
        }
    }

    fun clearCatalogFiles() {
        val count = CatalogDownloadStore.clear(getApplication())
        _state.update { state ->
            state.copy(
                catalog = state.catalog.copy(files = CatalogDownloadStore.list(getApplication())),
                message = "已清空 $count 个文件",
            )
        }
    }

    // ---------------------------------------------------------------- 模块更新检查

    /** 只列本地已下载的 zip：不联网、不扫描模块，进入页面时刷新即可。 */
    fun refreshDownloadedFiles() {
        viewModelScope.launch {
            val files = withContext(Dispatchers.IO) { ModuleDownloadStore.list(getApplication()) }
            _state.update { state ->
                state.copy(moduleUpdates = state.moduleUpdates.copy(files = files))
            }
        }
    }

    fun loadModuleUpdates() {
        if (_state.value.moduleUpdates.loading) return
        viewModelScope.launch { checkModuleUpdates() }
    }

    private suspend fun checkModuleUpdates() {
        _state.update {
            it.copy(moduleUpdates = it.moduleUpdates.copy(loading = true, error = null, checked = 0, total = 0))
        }
        val (modules, scanError) = ModuleUpdateChecker.scan(_state.value.root?.granted == true)
        if (scanError != null) {
            _state.update {
                it.copy(
                    moduleUpdates = it.moduleUpdates.copy(
                        items = emptyList(), loading = false, error = scanError,
                        files = ModuleDownloadStore.list(getApplication()),
                    ),
                )
            }
            return
        }
        // 先把扫到的模块名摆进列表：对照版本时这些文字会按组件库的方式模糊，
        // 结束后再一次性替换成结果，用户能看到「每个模块正在被检查」。
        _state.update {
            it.copy(
                moduleUpdates = it.moduleUpdates.copy(
                    items = modules
                        .sortedWith(
                            compareByDescending<dev.mochi.rootbox.core.InstalledModule> {
                                if (it.updateJson.isNullOrBlank()) 0 else 1
                            }.thenBy { it.name.lowercase() },
                        )
                        .map { ModuleUpdateItem(it) },
                    total = modules.size,
                    checked = 0,
                ),
            )
        }
        val items = ModuleUpdateChecker.check(modules) { done, total ->
            _state.update { state ->
                state.copy(
                    moduleUpdates = state.moduleUpdates.copy(checked = done, total = total),
                )
            }
        }
        _state.update {
            it.copy(
                moduleUpdates = it.moduleUpdates.copy(
                    items = items,
                    loading = false,
                    checkedAt = System.currentTimeMillis(),
                    files = ModuleDownloadStore.list(getApplication()),
                ),
            )
        }
    }

    /** 下载中的 Job，按模块 id 存；IO 线程会读取消集合，所以用并发容器。 */
    private val downloadJobs = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()
    private val catalogJobs = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    /** 下载 zip 到 app 私有目录，UI 展示实时进度。 */
    fun downloadModuleUpdate(item: ModuleUpdateItem) {
        val url = item.zipUrl ?: return
        val id = item.module.id
        if (_state.value.moduleUpdates.downloading.containsKey(id)) return
        val fileName = ModuleDownloadStore.safeFileName(id, item.latestVersion)
        val job = viewModelScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            _state.update { state ->
                state.copy(
                    moduleUpdates = state.moduleUpdates.copy(
                        downloading = state.moduleUpdates.downloading + (
                            id to DownloadProgress(fileName, 0, -1, 0f)
                        ),
                    ),
                )
            }
            try {
                val bytes = Http.getBytes(url) { read, total ->
                        val fraction = if (total > 0) read.toFloat() / total.toFloat() else 0f
                        _state.update { state ->
                            val current = state.moduleUpdates.downloading[id] ?: return@update state
                            state.copy(
                                moduleUpdates = state.moduleUpdates.copy(
                                    downloading = state.moduleUpdates.downloading + (
                                        id to current.copy(read = read, total = total, fraction = fraction)
                                    ),
                                ),
                            )
                        }
                    }
                // 取消点与落盘之间再判一次，避免取消瞬间把半成品写进目录。
                if (kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]?.isActive == false) {
                    throw DownloadCancelledException()
                }
                ModuleDownloadStore.save(getApplication(), fileName, bytes)
                _state.update { state ->
                    state.copy(
                        moduleUpdates = state.moduleUpdates.copy(
                            downloading = state.moduleUpdates.downloading - id,
                            files = ModuleDownloadStore.list(getApplication()),
                        ),
                        message = "已下载 $fileName",
                    )
                }
            } catch (t: DownloadCancelledException) {
                _state.update { state ->
                    state.copy(
                        moduleUpdates = state.moduleUpdates.copy(
                            downloading = state.moduleUpdates.downloading - id,
                        ),
                    )
                }
            } catch (t: kotlinx.coroutines.CancellationException) {
                _state.update { state ->
                    state.copy(
                        moduleUpdates = state.moduleUpdates.copy(
                            downloading = state.moduleUpdates.downloading - id,
                        ),
                    )
                }
            } catch (t: Throwable) {
                DiagLog.error("update", "$id 下载失败", t)
                _state.update { state ->
                    state.copy(
                        moduleUpdates = state.moduleUpdates.copy(
                            downloading = state.moduleUpdates.downloading - id,
                        ),
                        message = "下载失败：${t.javaClass.simpleName}: ${t.message}",
                    )
                }
            } finally {
                // 只移除自己：用户取消后立刻重新下载时，旧协程的 finally 不能删掉新 Job。
                val self = kotlinx.coroutines.currentCoroutineContext().job
                downloadJobs.remove(id, self)
            }
        }
        // LAZY：先把 Job 放进表再启动，保证协程内任何时刻读到的表状态都是完整的。
        downloadJobs[id] = job
        job.start()
    }

    /** 用户点「取消下载」：标记取消并 cancel 协程，让 getBytes 断开连接、不落盘。 */
    fun cancelModuleDownload(id: String) {
        if (!downloadJobs.containsKey(id)) return
        val job = downloadJobs.remove(id)
        job?.cancel()
        _state.update { state ->
            state.copy(
                moduleUpdates = state.moduleUpdates.copy(
                    downloading = state.moduleUpdates.downloading - id,
                ),
                message = "已取消下载",
            )
        }
    }

    fun deleteDownloaded(file: DownloadedModule) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { ModuleDownloadStore.delete(file.path) }
            _state.update { state ->
                state.copy(
                    moduleUpdates = state.moduleUpdates.copy(
                        files = ModuleDownloadStore.list(getApplication()),
                    ),
                    message = if (ok) "已删除 ${file.name}" else "删除失败",
                )
            }
        }
    }

    fun clearDownloaded() {
        viewModelScope.launch {
            val count = withContext(Dispatchers.IO) { ModuleDownloadStore.clear(getApplication()) }
            _state.update { state ->
                state.copy(
                    moduleUpdates = state.moduleUpdates.copy(
                        files = ModuleDownloadStore.list(getApplication()),
                    ),
                    message = "已清空 $count 个临时文件",
                )
            }
        }
    }

    // ---------------------------------------------------------------- 设置

    fun setDarkMode(mode: DarkMode) = updateSettings { it.copy(darkMode = mode) }

    fun setMonet(enabled: Boolean) = updateSettings { it.copy(monet = enabled) }

    fun setKeyColor(color: Long?) = updateSettings { it.copy(keyColor = color) }

    fun setPredictiveBack(enabled: Boolean) = updateSettings { it.copy(predictiveBack = enabled) }

    fun setAutoCheckRoot(enabled: Boolean) = updateSettings { it.copy(autoCheckRoot = enabled) }

    fun setShowGreenBadge(enabled: Boolean) = updateSettings { it.copy(showGreenBadge = enabled) }

    fun setKeepBackups(count: Int) = updateSettings { it.copy(keepBackups = count.coerceIn(1, 50)) }

    fun setRequireCredential(enabled: Boolean) = updateSettings { it.copy(requireCredential = enabled) }

    fun setAttestationShowDetails(enabled: Boolean) =
        updateSettings { it.copy(attestationShowDetails = enabled) }

    fun setAttestationTimeout(seconds: Int) =
        updateSettings { it.copy(attestationTimeoutSec = seconds.coerceIn(5, 30)) }

    fun setShowArchivedDefault(enabled: Boolean) {
        updateSettings { it.copy(showArchivedByDefault = enabled) }
        _state.update { it.copy(showArchived = enabled) }
    }

    private fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val next = transform(_settings.value)
        _settings.value = next
        settingsStore.save(next)
    }

    fun toggleArchived() {
        _state.update { it.copy(showArchived = !it.showArchived) }
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }

    fun clearLog() = DiagLog.clear()

    private companion object {
        const val MAX_IMPORT_BYTES = 5 * 1024 * 1024
    }
}

/** Quotes a path for the `su -c` shell. */
internal fun quotePath(path: String): String = "'" + path.replace("'", "'\\''") + "'"
