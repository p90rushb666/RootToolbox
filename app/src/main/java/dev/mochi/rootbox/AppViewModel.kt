package dev.mochi.rootbox

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.mochi.rootbox.core.DiagLog
import dev.mochi.rootbox.core.InstallResult
import dev.mochi.rootbox.core.KeyboxInstaller
import dev.mochi.rootbox.core.KeyboxMeta
import dev.mochi.rootbox.core.KeyboxParser
import dev.mochi.rootbox.core.RootChecker
import dev.mochi.rootbox.core.RootInfo
import dev.mochi.rootbox.core.Shell
import dev.mochi.rootbox.core.TrickyStoreScan
import dev.mochi.rootbox.core.TrickyStoreScanner
import dev.mochi.rootbox.data.AppSettings
import dev.mochi.rootbox.data.DarkMode
import dev.mochi.rootbox.data.Http
import dev.mochi.rootbox.data.KeyboxItem
import dev.mochi.rootbox.data.KeyboxRepository
import dev.mochi.rootbox.data.SettingsStore
import dev.mochi.rootbox.data.SourceResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class InstallUi(
    val uid: String,
    val name: String,
    val phase: String,
    val fraction: Float,
    val running: Boolean,
    val result: InstallResult? = null,
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
) {
    val allItems: List<KeyboxItem> get() = sources.flatMap { it.items }
}

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsStore = SettingsStore(application)

    private val _settings = MutableStateFlow(settingsStore.load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _state = MutableStateFlow(AppState(showArchived = _settings.value.showArchivedByDefault))
    val state: StateFlow<AppState> = _state.asStateFlow()

    fun start() {
        if (_settings.value.autoCheckRoot) refreshRoot()
        else viewModelScope.launch { prepareWithoutRootProbe() }
        // 密钥列表推迟到第一次打开「密钥库」标签时再拉，避免和首帧抢资源。
    }

    /** 第一次进入密钥库时按需拉取，之后不再重复触发。 */
    fun ensureKeysLoaded() {
        if (_state.value.keysLoadedOnce || _state.value.keysLoading) return
        loadKeys()
    }

    /** Root detection switched off: still resolve the module state from what is readable. */
    private suspend fun prepareWithoutRootProbe() {
        _state.update { it.copy(rootChecking = false, root = null) }
        scanModule(false)
    }

    fun refreshRoot() {
        viewModelScope.launch { refreshAll() }
    }

    /**
     * Called every time the app returns to the foreground. Re-running root detection keeps the
     * "可用/受限" badge honest: at a cold start the daemon may not have answered yet, so the first
     * probe often reads as "受限" until a later refresh sees it granted.
     */
    fun onAppForeground() {
        if (!_settings.value.autoCheckRoot || !_settings.value.refreshOnOpen) return
        if (_state.value.rootChecking) return
        DiagLog.info("ui", "回到前台，自动刷新 Root")
        refreshRoot()
    }

    /** Suspend so pull-to-refresh keeps its indicator until detection really finished. */
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
        // The path the user confirmed last time wins, so the chooser only appears once. A saved path
        // that no longer exists on the device falls back to the scan result and asks again.
        // 用户手动选过的路径优先，否则一律用运行时配置目录，首次打开不再询问。
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
        viewModelScope.launch { loadCurrentKeybox(path) }
    }

    fun loadKeys() {
        if (_state.value.keysLoading) return
        viewModelScope.launch { refreshKeys() }
    }

    /**
     * Suspend version used by pull-to-refresh: it returns only after both sources answered, so the
     * refresh indicator stays until the list is really up to date.
     */
    suspend fun refreshKeys() {
        if (_state.value.keysLoading) return
        _state.update { it.copy(keysLoading = true, refreshDone = 0, refreshTotal = 2) }
        val sources = KeyboxRepository.loadAll { done, total ->
            _state.update { it.copy(refreshDone = done, refreshTotal = total) }
        }
        _state.update { it.copy(sources = sources, keysLoading = false, keysLoadedOnce = true) }
    }

    fun setDarkMode(mode: DarkMode) = updateSettings { it.copy(darkMode = mode) }

    fun setMonet(enabled: Boolean) = updateSettings { it.copy(monet = enabled) }

    fun setKeyColor(color: Long?) = updateSettings { it.copy(keyColor = color) }

    fun setPredictiveBack(enabled: Boolean) = updateSettings { it.copy(predictiveBack = enabled) }

    fun setAutoCheckRoot(enabled: Boolean) = updateSettings { it.copy(autoCheckRoot = enabled) }

    fun setRefreshOnOpen(enabled: Boolean) = updateSettings { it.copy(refreshOnOpen = enabled) }

    fun setShowGreenBadge(enabled: Boolean) = updateSettings { it.copy(showGreenBadge = enabled) }

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

    fun install(item: KeyboxItem) {
        val url = item.downloadUrl
        if (url == null) {
            _state.update { it.copy(message = "${item.sourceTitle} 需要人机验证，请用浏览器打开") }
            return
        }
        val target = _state.value.targetPath
        if (target.isNullOrBlank()) {
            _state.update { it.copy(message = "尚未确定替换目标，请先在首页选择路径") }
            return
        }
        if (_state.value.install?.running == true) return

        viewModelScope.launch {
            _state.update {
                it.copy(install = InstallUi(item.uid, item.name, "连接下载地址", 0.01f, running = true))
            }
            try {
                val bytes = Http.getBytes(url) { read, total ->
                    val fraction = if (total > 0) (read.toFloat() / total.toFloat()) * 0.45f else 0.01f
                    val label = if (total > 0) {
                        "下载中 ${read / 1024}KB / ${total / 1024}KB"
                    } else {
                        "下载中 ${read / 1024}KB"
                    }
                    _state.update { state ->
                        state.install?.let { state.copy(install = it.copy(phase = label, fraction = fraction.coerceIn(0.01f, 0.45f))) } ?: state
                    }
                }
                updateInstall("校验 keybox 结构", 0.48f)
                val meta = KeyboxParser.parse(bytes)
                if (!meta.valid) {
                    updateInstall("校验失败：${meta.error}", 0.48f, running = false)
                    _state.update {
                        it.copy(
                            install = it.install?.copy(running = false),
                            message = "文件无效：${meta.error}",
                        )
                    }
                    return@launch
                }
                if (item.sha256 != null && !item.sha256.equals(meta.sha256, ignoreCase = true)) {
                    updateInstall("SHA-256 与索引不一致", 0.48f, running = false)
                    _state.update { it.copy(message = "下载内容与来源索引的 SHA-256 不一致，已中止") }
                    return@launch
                }

                val result = KeyboxInstaller.install(target, bytes) { progress ->
                    updateInstall(progress.phase, 0.5f + progress.fraction * 0.5f)
                }
                _state.update {
                    it.copy(
                        install = it.install?.copy(running = false, fraction = 1f, phase = if (result.success) "完成" else result.title, result = result),
                        message = if (result.success) "替换完成" else result.title,
                    )
                }
                DiagLog.info("install", "结果 ${result.title}")
                scanModule(true)
            } catch (t: Throwable) {
                DiagLog.error("install", "下载/替换异常", t)
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

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }

    fun clearLog() = DiagLog.clear()
}

/** Quotes a path for the `su -c` shell. */
internal fun quotePath(path: String): String = "'" + path.replace("'", "'\\''") + "'"
