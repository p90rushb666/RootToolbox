package dev.mochi.rootbox

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.mochi.rootbox.core.BackupEntry
import dev.mochi.rootbox.core.BackupScan
import dev.mochi.rootbox.core.BackupStore
import dev.mochi.rootbox.core.DiagLog
import dev.mochi.rootbox.core.InstallResult
import dev.mochi.rootbox.core.IntegrityChecker
import dev.mochi.rootbox.core.IntegrityOutcome
import dev.mochi.rootbox.core.KeyboxInstaller
import dev.mochi.rootbox.core.KeyboxMeta
import dev.mochi.rootbox.core.KeyboxParser
import dev.mochi.rootbox.core.ModuleFlasher
import dev.mochi.rootbox.core.ModuleLoadChecker
import dev.mochi.rootbox.core.ModuleLoadStatus
import dev.mochi.rootbox.core.ModuleUpdateChecker
import dev.mochi.rootbox.core.ModuleUpdateItem
import dev.mochi.rootbox.core.DownloadedModule
import dev.mochi.rootbox.core.ModuleDownloadStore
import dev.mochi.rootbox.core.RootChecker
import dev.mochi.rootbox.core.RootInfo
import dev.mochi.rootbox.core.Shell
import dev.mochi.rootbox.core.TrickyStoreScan
import dev.mochi.rootbox.core.TrickyStoreScanner
import dev.mochi.rootbox.data.AppSettings
import dev.mochi.rootbox.data.DarkMode
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
    val error: String? = null,
    val checkedAt: Long? = null,
    val files: List<DownloadedModule> = emptyList(),
    /** 正在下载的模块 id -> 进度。 */
    val downloading: Map<String, DownloadProgress> = emptyMap(),
    val flashing: String? = null,
    val flashResult: Pair<Boolean, String>? = null,
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
    val integrity: IntegrityOutcome? = null,
    val integrityRunning: Boolean = false,
    val integrityPhase: String = "",
    val moduleUpdates: ModuleUpdateUi = ModuleUpdateUi(),
) {
    val allItems: List<KeyboxItem> get() = sources.flatMap { it.items }
}

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsStore = SettingsStore(application)
    private val integrityChecker = IntegrityChecker(application)

    private val _settings = MutableStateFlow(settingsStore.load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _state = MutableStateFlow(AppState(showArchived = _settings.value.showArchivedByDefault))
    val state: StateFlow<AppState> = _state.asStateFlow()

    /** 预览阶段下载的字节只存在这里，不落盘、不写日志。 */
    private var pendingBytes: ByteArray? = null

    /** 最近一次成功写入的 SHA-256，用于模块加载检测的对照。 */
    private var lastWrittenSha: String? = null

    fun start() {
        if (_settings.value.autoCheckRoot) refreshRoot()
        else viewModelScope.launch { prepareWithoutRootProbe() }
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

    fun onAppForeground() {
        if (!_settings.value.autoCheckRoot || !_settings.value.refreshOnOpen) return
        if (_state.value.rootChecking) return
        DiagLog.info("ui", "回到前台，自动刷新 Root")
        refreshRoot()
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

    // ---------------------------------------------------------------- 完整性检测

    fun runIntegrityCheck() {
        if (_state.value.integrityRunning) return
        viewModelScope.launch {
            _state.update {
                it.copy(integrityRunning = true, integrity = null, integrityPhase = "准备中…")
            }
            val outcome = integrityChecker.check(
                endpoint = _settings.value.integrityEndpoint,
                projectNumber = _settings.value.integrityProjectNumber,
            ) { phase -> _state.update { it.copy(integrityPhase = phase) } }
            _state.update { it.copy(integrity = outcome, integrityRunning = false, integrityPhase = "") }
            DiagLog.info("integrity", if (outcome.ok) "判定已返回" else "失败：${outcome.failure?.kind}")
        }
    }

    // ---------------------------------------------------------------- 模块更新检查

    fun loadModuleUpdates() {
        if (_state.value.moduleUpdates.loading) return
        viewModelScope.launch { checkModuleUpdates() }
    }

    private suspend fun checkModuleUpdates() {
        _state.update {
            it.copy(moduleUpdates = it.moduleUpdates.copy(loading = true, error = null))
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
                ),
            )
        }
        val items = ModuleUpdateChecker.check(modules)
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

    /** 下载 zip 到 app 私有目录，UI 展示实时进度。 */
    fun downloadModuleUpdate(item: ModuleUpdateItem) {
        val url = item.zipUrl ?: return
        val id = item.module.id
        if (_state.value.moduleUpdates.downloading.containsKey(id)) return
        val fileName = ModuleDownloadStore.safeFileName(id, item.latestVersion)
        viewModelScope.launch {
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
            }
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

    /** 刷写只在 UI 二次确认之后调用；不自动重启。 */
    fun flashModule(file: DownloadedModule) {
        if (_state.value.moduleUpdates.flashing != null) return
        viewModelScope.launch {
            _state.update { state ->
                state.copy(
                    moduleUpdates = state.moduleUpdates.copy(flashing = file.name, flashResult = null),
                )
            }
            val result = ModuleFlasher.flash(file.path)
            _state.update { state ->
                state.copy(
                    moduleUpdates = state.moduleUpdates.copy(
                        flashing = null,
                        flashResult = result,
                    ),
                )
            }
        }
    }

    fun dismissFlashResult() {
        _state.update { state ->
            state.copy(moduleUpdates = state.moduleUpdates.copy(flashResult = null))
        }
    }

    // ---------------------------------------------------------------- 设置

    fun setDarkMode(mode: DarkMode) = updateSettings { it.copy(darkMode = mode) }

    fun setMonet(enabled: Boolean) = updateSettings { it.copy(monet = enabled) }

    fun setKeyColor(color: Long?) = updateSettings { it.copy(keyColor = color) }

    fun setPredictiveBack(enabled: Boolean) = updateSettings { it.copy(predictiveBack = enabled) }

    fun setAutoCheckRoot(enabled: Boolean) = updateSettings { it.copy(autoCheckRoot = enabled) }

    fun setRefreshOnOpen(enabled: Boolean) = updateSettings { it.copy(refreshOnOpen = enabled) }

    fun setShowGreenBadge(enabled: Boolean) = updateSettings { it.copy(showGreenBadge = enabled) }

    fun setKeepBackups(count: Int) = updateSettings { it.copy(keepBackups = count.coerceIn(1, 50)) }

    fun setRequireCredential(enabled: Boolean) = updateSettings { it.copy(requireCredential = enabled) }

    fun setIntegrityEndpoint(value: String) = updateSettings { it.copy(integrityEndpoint = value.trim()) }

    fun setIntegrityProjectNumber(value: String) =
        updateSettings { it.copy(integrityProjectNumber = value.trim()) }

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
