package dev.mochi.rootbox.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.mochi.rootbox.AppState
import dev.mochi.rootbox.AppViewModel
import dev.mochi.rootbox.DownloadProgress
import dev.mochi.rootbox.core.DownloadedModule
import dev.mochi.rootbox.core.ModuleDownloadStore
import dev.mochi.rootbox.core.ModuleUpdateItem
import dev.mochi.rootbox.core.moduleFileUri
import kotlinx.collections.immutable.persistentListOf
import ui.feice.mochi.basic.MochiButton
import ui.feice.mochi.basic.MochiButtonStyle
import ui.feice.mochi.basic.MochiDialog
import ui.feice.mochi.basic.MochiDialogAction
import ui.feice.mochi.basic.MochiDialogActionRole
import ui.feice.mochi.basic.MochiProgressRing
import ui.feice.mochi.basic.Text
import ui.feice.mochi.icons.MochiIonicon
import ui.feice.mochi.list.MochiListRow
import ui.feice.mochi.list.mochiListSection
import ui.feice.mochi.navigation.MochiLazyCompactTitleScaffold
import ui.feice.mochi.theme.MochiTheme

/**
 * 模块更新检查：手动扫描 module.prop 的 updateJson，对照 GitHub Release 提示新版本，
 * 下载到 app 私有目录；下载完成后可打开或删除安装包。不提供刷写入口。
 */
@Composable
fun ModuleUpdateScreen(
    state: AppState,
    viewModel: AppViewModel,
    navigationIcon: @Composable () -> Unit,
    showToast: (String) -> Unit,
) {
    // 只刷新本地临时文件列表；不触发模块扫描，也不联网。
    LaunchedEffect(Unit) { viewModel.refreshDownloadedFiles() }

    // 不自动检查：只有用户点「检查更新」才会扫描与联网。
    val ui = state.moduleUpdates
    val scheme = MochiTheme.colorScheme
    val context = LocalContext.current

    var pendingFile by remember { mutableStateOf<DownloadedModule?>(null) }
    var pendingDelete by remember { mutableStateOf<DownloadedModule?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    fun openFile(file: DownloadedModule) {
        val uri = moduleFileUri(context, file)
        if (uri == null) {
            showToast("文件不存在或已被删除")
            return
        }
        fun intentFor(mime: String) = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intentFor("application/zip")) }
            .onFailure { first ->
                if (first is ActivityNotFoundException) {
                    runCatching { context.startActivity(intentFor("*/*")) }
                        .onFailure { showToast("没有可打开该安装包的应用") }
                } else {
                    showToast("打开失败：${first.message}")
                }
            }
    }

    MochiLazyCompactTitleScaffold(
        title = "模块更新",
        subtitle = when {
            ui.loading -> "正在检查…"
            ui.checkedAt == null -> "点下方按钮手动检查"
            else -> "${ui.items.size} 个模块 · ${formatDateTime(ui.checkedAt)}"
        },
        navigationIcon = navigationIcon,
    ) {
        mochiListSection(
            key = "check",
            header = "更新检查",
            footer = "不会自动联网；点「检查更新」才扫描模块并对照 GitHub Release。",
        ) {
            row(key = "refresh") {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                ) {
                    MochiButton(
                        onClick = { viewModel.loadModuleUpdates() },
                        style = MochiButtonStyle.Filled,
                        enabled = !ui.loading,
                    ) { Text(if (ui.loading) "检查中…" else "检查更新") }
                }
            }
            if (ui.error != null) {
                row(key = "error") {
                    MochiListRow(
                        title = "检查失败",
                        subtitle = ui.error,
                        destructive = true,
                        leading = { MochiIonicon("alert-circle") },
                    )
                }
            }
        }

        mochiListSection(
            key = "modules",
            header = "已安装模块（${ui.items.size}）",
            footer = "有更新的模块排在最前，未提供 updateJson 的模块排在最后。下载进度显示在各模块自己的行里。",
        ) {
            if (ui.items.isEmpty() && !ui.loading) {
                row(key = "empty") {
                    MochiListRow(
                        title = if (ui.error != null) "无法列出模块" else "尚未检查",
                        subtitle = if (ui.error != null) {
                            "获得 Root 权限后点「检查更新」扫描模块目录。"
                        } else {
                            "本页不会自动联网，点「检查更新」开始。"
                        },
                    )
                }
            }
            ui.items.forEach { item ->
                row(key = "module-${item.module.id}") {
                    // 不再整块 mochiBlur：只有 MochiListRow 的 value（版本号）用组件库的
                    // ListBlurText 做模糊替换，标题、标签、按钮、进度保持清晰。
                    Column(Modifier.fillMaxWidth()) {
                        ModuleUpdateRow(item, checking = ui.loading)

                        val progress = ui.downloading[item.module.id]
                        if (progress != null) {
                            DownloadProgressRow(progress, scheme.onSurfaceVariant) {
                                viewModel.cancelModuleDownload(item.module.id)
                            }
                        } else if (!ui.loading) {
                            val downloaded = ModuleDownloadStore.forModule(ui.files, item.module.id)
                            if (downloaded != null) {
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 18.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    MochiButton(
                                        onClick = { openFile(downloaded) },
                                        style = MochiButtonStyle.Tonal,
                                        modifier = Modifier.weight(1f),
                                    ) { Text("打开") }
                                    MochiButton(
                                        onClick = { pendingDelete = downloaded },
                                        style = MochiButtonStyle.Tonal,
                                        modifier = Modifier.weight(1f),
                                    ) { Text("删除") }
                                }
                            } else if (item.canDownload) {
                                MochiButton(
                                    onClick = { viewModel.downloadModuleUpdate(item) },
                                    style = MochiButtonStyle.Tonal,
                                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
                                ) { Text(if (item.hasUpdate) "下载新版本" else "下载当前版本") }
                            }
                        }
                    }
                }
            }
        }

        mochiListSection(
            key = "files",
            header = "临时下载文件（${ui.files.size}）",
            footer = "文件保存在本应用私有目录，不会写入 /data/adb。删除后不可恢复。",
        ) {
            if (ui.files.isEmpty()) {
                row(key = "no-files") {
                    MochiListRow(title = "没有临时文件", subtitle = "下载完成的模块 zip 会出现在这里")
                }
            }
            ui.files.forEach { file ->
                row(key = "file-${file.path}") {
                    MochiListRow(
                        title = file.name,
                        subtitle = "${formatBytes(file.size)} · ${formatDateTime(file.modifiedAt)}",
                        value = "管理",
                        chevron = true,
                        leading = { MochiIonicon("archive") },
                        onClick = { pendingFile = file },
                    )
                }
            }
            if (ui.files.isNotEmpty()) {
                row(key = "clear") {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 12.dp),
                    ) {
                        MochiButton(
                            onClick = { confirmClear = true },
                            style = MochiButtonStyle.Tonal,
                        ) { Text("一键清空临时文件") }
                    }
                }
            }
        }
    }

    // 临时文件管理：只有打开与删除，没有任何刷写入口。
    MochiDialog(
        visible = pendingFile != null,
        onDismissRequest = { pendingFile = null },
        title = "已下载的安装包",
        message = pendingFile?.let { file ->
            "${file.name}\n${formatBytes(file.size)} · ${formatDateTime(file.modifiedAt)}\n\n路径：${file.path}"
        },
        actions = persistentListOf(
            MochiDialogAction("取消", MochiDialogActionRole.Cancel),
            MochiDialogAction(
                label = "删除",
                role = MochiDialogActionRole.Destructive,
                onClick = {
                    val file = pendingFile
                    pendingFile = null
                    if (file != null) pendingDelete = file
                },
            ),
            MochiDialogAction(
                label = "打开",
                role = MochiDialogActionRole.Primary,
                onClick = {
                    val file = pendingFile
                    pendingFile = null
                    if (file != null) openFile(file)
                },
            ),
        ),
    )

    // 删除二次确认
    MochiDialog(
        visible = pendingDelete != null,
        onDismissRequest = { pendingDelete = null },
        title = "删除临时文件？",
        message = pendingDelete?.let { file ->
            "将永久删除本应用私有目录里的安装包：\n${file.path}\n\n删除不可撤销，但不会改动已安装的模块。"
        },
        actions = persistentListOf(
            MochiDialogAction("取消", MochiDialogActionRole.Cancel),
            MochiDialogAction(
                label = "确认删除",
                role = MochiDialogActionRole.Destructive,
                onClick = {
                    val file = pendingDelete
                    pendingDelete = null
                    if (file != null) viewModel.deleteDownloaded(file)
                },
            ),
        ),
    )

    // 一键清空二次确认
    MochiDialog(
        visible = confirmClear,
        onDismissRequest = { confirmClear = false },
        title = "清空全部临时文件？",
        message = "将删除 ${ui.files.size} 个已下载的安装包，无法撤销。已安装的模块不受影响。",
        actions = persistentListOf(
            MochiDialogAction("取消", MochiDialogActionRole.Cancel),
            MochiDialogAction(
                label = "全部删除",
                role = MochiDialogActionRole.Destructive,
                onClick = {
                    confirmClear = false
                    viewModel.clearDownloaded()
                },
            ),
        ),
    )
}

/** 进度直接画在对应模块的行里，不再单独设「下载中」板块。 */
@Composable
private fun DownloadProgressRow(
    progress: DownloadProgress,
    secondaryColor: Color,
    onCancel: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MochiProgressRing(progress = progress.fraction, size = 30.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(progress.name, style = MochiTheme.textStyles.bodyMedium)
                Text(
                    if (progress.total > 0) {
                        "${formatBytes(progress.read)} / ${formatBytes(progress.total)} · ${(progress.fraction * 100).toInt()}%"
                    } else {
                        "${formatBytes(progress.read)} 已下载"
                    },
                    style = MochiTheme.textStyles.footnote,
                    color = secondaryColor,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        MochiProgressBar(progress.fraction)
        Spacer(Modifier.height(10.dp))
        MochiButton(
            onClick = onCancel,
            style = MochiButtonStyle.Tonal,
        ) { Text("取消下载") }
    }
}

@Composable
private fun ModuleUpdateRow(item: ModuleUpdateItem, checking: Boolean) {
    val scheme = MochiTheme.colorScheme
    val status = when {
        checking -> "检查中" to false
        item.module.updateJson.isNullOrBlank() -> "无更新源" to false
        item.error != null && !item.canDownload -> "无法检查" to true
        item.hasUpdate -> "有新版本" to false
        else -> "已是最新" to false
    }
    // 副标题是本地扫描就能确定的当前版本，检查过程中不需要变、也不该被模糊。
    val currentVersion = "当前 ${item.module.version ?: "未知"}"
    // 只有这个 value 会在检查前后改变：MochiListRow 的 value 槽位走组件库的 ListBlurText，
    // 于是仅此处文字做模糊替换（和首页「已授权 → 检测中」一样），其余内容全程清晰。
    val valueText = when {
        checking -> "检测中"
        item.module.updateJson.isNullOrBlank() -> "无源"
        item.error != null -> "失败"
        item.latestVersion != null -> item.latestVersion
        else -> "最新"
    }
    MochiListRow(
        title = item.module.name,
        subtitle = currentVersion,
        value = valueText,
        destructive = status.second,
        status = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                StatusPill(
                    text = status.first,
                    containerColor = if (item.hasUpdate && !checking) {
                        scheme.primaryContainer
                    } else {
                        scheme.surfaceVariant
                    },
                    contentColor = if (item.hasUpdate && !checking) {
                        scheme.onPrimaryContainer
                    } else {
                        scheme.onSurfaceVariant
                    },
                    compact = true,
                )
            }
        },
    )
}
