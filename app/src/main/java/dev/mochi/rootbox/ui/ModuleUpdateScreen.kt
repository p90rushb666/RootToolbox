package dev.mochi.rootbox.ui

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.mochi.rootbox.AppState
import dev.mochi.rootbox.AppViewModel
import dev.mochi.rootbox.core.DownloadedModule
import dev.mochi.rootbox.core.ModuleUpdateItem
import kotlinx.collections.immutable.persistentListOf
import ui.feice.mochi.basic.MochiButton
import ui.feice.mochi.basic.MochiButtonStyle
import ui.feice.mochi.basic.MochiProgressRing
import ui.feice.mochi.basic.MochiDialog
import ui.feice.mochi.basic.MochiDialogAction
import ui.feice.mochi.basic.MochiDialogActionRole
import ui.feice.mochi.basic.Text
import ui.feice.mochi.icons.MochiIonicon
import ui.feice.mochi.list.MochiListCard
import ui.feice.mochi.list.MochiListRow
import ui.feice.mochi.list.mochiListSection
import ui.feice.mochi.navigation.MochiLazyCompactTitleScaffold
import ui.feice.mochi.theme.MochiTheme

/**
 * 模块更新检查：扫描 module.prop 的 updateJson，对照 GitHub Release 提示新版本，
 * 下载到 app 私有目录，再由用户确认后交给模块管理器刷写。
 */
@Composable
fun ModuleUpdateScreen(
    state: AppState,
    viewModel: AppViewModel,
    navigationIcon: @Composable () -> Unit,
    showToast: (String) -> Unit,
) {
    LaunchedEffect(Unit) { viewModel.loadModuleUpdates() }

    val ui = state.moduleUpdates
    val scheme = MochiTheme.colorScheme

    var pendingFlash by remember { mutableStateOf<DownloadedModule?>(null) }
    var pendingDelete by remember { mutableStateOf<DownloadedModule?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    MochiLazyCompactTitleScaffold(
        title = "模块更新",
        subtitle = when {
            ui.loading -> "正在对照更新源…"
            ui.checkedAt == null -> "尚未检查"
            else -> "${ui.items.size} 个模块 · ${formatDateTime(ui.checkedAt)}"
        },
        navigationIcon = navigationIcon,
    ) {
        mochiListSection(
            key = "check",
            header = "更新检查",
            footer = "读取每个模块 module.prop 里的 updateJson，并对照对应仓库的 GitHub Release。",
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
                    ) { Text(if (ui.loading) "检查中…" else "重新检查") }
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

        if (ui.downloading.isNotEmpty()) {
            mochiListSection(key = "progress", header = "下载中") {
                ui.downloading.forEach { (id, progress) ->
                    row(key = "dl-$id") {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 18.dp, vertical = 14.dp),
                        ) {
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                MochiProgressRing(
                                    progress = progress.fraction,
                                    size = 34.dp,
                                )
                                Spacer(Modifier.width(14.dp))
                                Column {
                                    Text(progress.name, style = MochiTheme.textStyles.bodyLarge)
                                    Text(
                                        if (progress.total > 0) {
                                            "${formatBytes(progress.read)} / ${formatBytes(progress.total)} · ${(progress.fraction * 100).toInt()}%"
                                        } else {
                                            "${formatBytes(progress.read)} 已下载"
                                        },
                                        style = MochiTheme.textStyles.footnote,
                                        color = scheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            MochiProgressBar(progress.fraction)
                        }
                    }
                }
            }
        }

        mochiListSection(
            key = "modules",
            header = "已安装模块（${ui.items.size}）",
            footer = if (ui.items.isEmpty() && ui.error == null && !ui.loading) "没有读取到模块更新信息。" else null,
        ) {
            if (ui.items.isEmpty() && !ui.loading) {
                row(key = "empty") {
                    MochiListRow(
                        title = if (ui.error != null) "无法列出模块" else "暂无模块",
                        subtitle = if (ui.error != null) {
                            "获得 Root 权限后点「重新检查」扫描模块目录。"
                        } else {
                            "点上方「重新检查」扫描模块目录"
                        },
                    )
                }
            }
            ui.items.forEach { item ->
                row(key = "module-${item.module.id}") {
                    ModuleUpdateRow(item)
                }
                if (!item.changelog.isNullOrBlank()) {
                    row(key = "log-${item.module.id}") {
                        MochiListRow(
                            title = "更新说明",
                            subtitle = item.changelog.take(300),
                        )
                    }
                }
                if (item.canDownload) {
                    row(key = "dl-btn-${item.module.id}") {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 18.dp, vertical = 12.dp),
                        ) {
                            MochiButton(
                                onClick = { viewModel.downloadModuleUpdate(item) },
                                style = MochiButtonStyle.Tonal,
                            ) { Text(if (item.hasUpdate) "下载新版本" else "下载当前版本") }
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
                        onClick = { pendingFlash = file },
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

        ui.flashResult?.let { (ok, detail) ->
            item(key = "flash-result") {
                MochiListCard {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 16.dp),
                    ) {
                        Text(
                            if (ok) "刷写命令已执行" else "刷写失败",
                            style = MochiTheme.textStyles.bodyLarge,
                            color = if (ok) scheme.primary else scheme.error,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            detail,
                            style = MochiTheme.textStyles.bodyMedium,
                            color = scheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        MochiButton(
                            onClick = { viewModel.dismissFlashResult() },
                            style = MochiButtonStyle.Tonal,
                        ) { Text("关闭") }
                    }
                }
            }
        }
    }

    // 管理入口：刷写 / 删除，二选一，都要求二次确认。
    MochiDialog(
        visible = pendingFlash != null,
        onDismissRequest = { pendingFlash = null },
        title = "管理已下载的模块",
        message = pendingFlash?.let { file ->
            buildString {
                appendLine(file.name)
                appendLine("路径：${file.path}")
                appendLine()
                appendLine("「刷写」会把安装包交给设备上的模块管理器（Magisk / KernelSU / APatch），并修改 /data/adb/modules；模块通常需要重启设备才会生效，本应用不会自动重启。")
                append("「删除文件」只删除本应用私有目录里的安装包，不改动系统。")
            }
        },
        actions = persistentListOf(
            MochiDialogAction("取消", MochiDialogActionRole.Cancel),
            MochiDialogAction(
                label = "删除文件",
                role = MochiDialogActionRole.Destructive,
                onClick = {
                    val file = pendingFlash
                    pendingFlash = null
                    if (file != null) pendingDelete = file
                },
            ),
            MochiDialogAction(
                label = "刷写模块",
                role = MochiDialogActionRole.Primary,
                onClick = {
                    val file = pendingFlash
                    pendingFlash = null
                    if (file != null) {
                        if (state.root?.granted != true) {
                            showToast("需要 Root 权限才能刷写模块")
                        } else {
                            viewModel.flashModule(file)
                        }
                    }
                },
            ),
        ),
    )

    // 单文件删除二次确认（二级菜单）
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

    // 一键清空二次确认（二级菜单）
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

@Composable
private fun ModuleUpdateRow(item: ModuleUpdateItem) {
    val scheme = MochiTheme.colorScheme
    val status = when {
        item.error != null && !item.canDownload -> "无法检查" to true
        item.hasUpdate -> "有新版本" to false
        else -> "已是最新" to false
    }
    MochiListRow(
        title = item.module.name,
        subtitle = buildString {
            append("当前：${item.module.version ?: "未知"}")
            if (item.module.versionCode != null) append("（${item.module.versionCode}）")
            item.latestVersion?.let { append("\n最新：$it") }
            item.source?.let { append("\n来源：$it") }
            item.error?.let { append("\n$it") }
        },
        destructive = status.second,
        status = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusPill(
                    status.first,
                    if (item.hasUpdate) scheme.primaryContainer else scheme.surfaceVariant,
                    if (item.hasUpdate) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
                )
            }
        },
    )
}
