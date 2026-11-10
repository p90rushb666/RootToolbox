package dev.mochi.rootbox.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import dev.mochi.rootbox.AppState
import dev.mochi.rootbox.AppViewModel
import dev.mochi.rootbox.core.BackupEntry
import dev.mochi.rootbox.core.BackupTimeSource
import kotlinx.collections.immutable.persistentListOf
import ui.feice.mochi.basic.MochiBarIconButton
import ui.feice.mochi.basic.MochiDialog
import ui.feice.mochi.basic.MochiDialogAction
import ui.feice.mochi.basic.MochiDialogActionRole
import ui.feice.mochi.icons.MochiIonicon
import ui.feice.mochi.list.MochiListButton
import ui.feice.mochi.list.MochiListRow
import ui.feice.mochi.list.mochiListSection
import ui.feice.mochi.list.rows
import ui.feice.mochi.navigation.MochiLazyLargeTitleScaffold
import ui.feice.mochi.theme.MochiTheme

/**
 * 备份中心：把「只写不读」的 .bak 文件变成可查看、可恢复、可清理的资产。
 *
 * 恢复不会直接 cp，而是复用 KeyboxInstaller 的备份→写入→回读校验→回滚链路。
 */
@Composable
fun BackupScreen(
    state: AppState,
    viewModel: AppViewModel,
    navigationIcon: @Composable () -> Unit,
    showToast: (String) -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) { viewModel.loadBackups() }

    val backups = state.backups
    val entries = backups?.entries.orEmpty()
    val keep = viewModel.settings.value.keepBackups

    var selected by remember { mutableStateOf<BackupEntry?>(null) }
    var lastShown by remember { mutableStateOf<BackupEntry?>(null) }
    if (selected != null) lastShown = selected

    var pendingRestore by remember { mutableStateOf<BackupEntry?>(null) }
    var pendingDelete by remember { mutableStateOf<List<BackupEntry>?>(null) }
    var pendingPrune by remember { mutableStateOf(false) }

    MochiLazyLargeTitleScaffold(
        title = "备份中心",
        subtitle = when {
            state.root?.granted != true -> "需要 Root 权限"
            state.backupsLoading -> "正在扫描备份目录…"
            backups == null -> "尚未扫描"
            else -> "${entries.size} 个备份 · 保留最近 $keep 个"
        },
        navigationIcon = navigationIcon,
        actions = {
            MochiBarIconButton(
                onClick = { viewModel.loadBackups() },
                navId = "backup.refresh",
                contentKey = "refresh",
            ) { MochiIonicon("refresh", contentDescription = "重新扫描备份") }
        },
        listState = listState,
    ) {
        if (state.root?.granted != true) {
            item(key = "no-root") {
                ui.feice.mochi.list.MochiListCard {
                    androidx.compose.foundation.layout.Column(
                        androidx.compose.ui.Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 16.dp),
                    ) {
                        ui.feice.mochi.basic.Text(
                            "未获得 Root 权限",
                            style = MochiTheme.textStyles.bodyLarge,
                            color = MochiTheme.colorScheme.error,
                        )
                        androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.height(6.dp))
                        ui.feice.mochi.basic.Text(
                            "备份文件位于 /data/adb 下，需要 root 才能列出、恢复或删除。请先在 Root 管理器中授予本应用权限，再回到这里重新扫描。",
                            style = MochiTheme.textStyles.bodyMedium,
                            color = MochiTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        val warnings = backups?.warnings.orEmpty()
        if (warnings.isNotEmpty()) {
            mochiListSection(key = "warnings", header = "扫描提示") {
                warnings.forEachIndexed { index, warning ->
                    row(key = "warn-$index") { MochiListRow(title = warning) }
                }
            }
        }

        if (backups != null && entries.isEmpty() && state.root?.granted == true) {
            item(key = "empty") {
                ui.feice.mochi.list.MochiListCard {
                    MochiListRow(
                        title = "没有备份",
                        subtitle = buildString {
                            appendLine("替换 keybox.xml 时会自动在原目录留下 .bak 文件。")
                            append("当前扫描目录：")
                            append(backups.directories.joinToString("、").ifBlank { "—" })
                        },
                    )
                }
            }
        }

        if (entries.isNotEmpty()) {
            mochiListSection(
                key = "backups",
                header = "备份文件（${entries.size}）",
                footer = "点条目查看详情；恢复会用当前文件再备份一次，然后写入并回读校验。",
            ) {
                rows(entries, key = { it.path }) { entry ->
                    MochiListRow(
                        title = entry.fileName,
                        subtitle = buildString {
                            append(formatDateTime(entry.timestamp))
                            append(" · ")
                            append(formatBytes(entry.size))
                            if (entry.timeSource == BackupTimeSource.STAT) append(" · 时间取自文件属性")
                            append("\nSHA-256 ")
                            append(entry.sha256.take(16))
                            append("…")
                            entry.meta?.let { meta ->
                                append(" · ${meta.certificateCount} 张证书")
                                meta.earliestExpiry?.let { append(" · 到期 ${formatDate(it)}") }
                            }
                        },
                        leading = { MochiIonicon("archive") },
                        status = {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (entry.valid) {
                                    StatusPill("结构有效", MochiTheme.colorScheme.primaryContainer, MochiTheme.colorScheme.onPrimaryContainer)
                                } else {
                                    StatusPill("结构异常", MochiTheme.colorScheme.errorContainer, MochiTheme.colorScheme.onErrorContainer)
                                }
                            }
                        },
                        chevron = true,
                        onClick = { selected = entry },
                    )
                }
            }

            item(key = "prune") {
                ui.feice.mochi.list.MochiListCard {
                    MochiListButton(
                        text = "批量清理：只保留最近 $keep 个",
                        onClick = { pendingPrune = true },
                    )
                }
            }
        }
    }

    // 详情（只读预览）
    val dialogEntry = lastShown
    MochiDialog(
        visible = selected != null,
        onDismissRequest = { selected = null },
        title = dialogEntry?.fileName.orEmpty(),
        message = dialogEntry?.let { describeBackup(it) },
        actions = persistentListOf(
            MochiDialogAction("关闭", MochiDialogActionRole.Cancel),
            MochiDialogAction(
                label = "删除",
                role = MochiDialogActionRole.Destructive,
                onClick = {
                    val entry = dialogEntry
                    selected = null
                    if (entry != null) pendingDelete = listOf(entry)
                },
            ),
            MochiDialogAction(
                label = "恢复",
                role = MochiDialogActionRole.Primary,
                onClick = {
                    val entry = dialogEntry
                    selected = null
                    if (entry != null) pendingRestore = entry
                },
            ),
        ),
    )

    // 恢复二次确认：必须写明目标路径
    MochiDialog(
        visible = pendingRestore != null,
        onDismissRequest = { pendingRestore = null },
        title = "从备份恢复？",
        message = pendingRestore?.let { entry ->
            buildString {
                appendLine("将把下面的备份写回设备：")
                appendLine(entry.path)
                appendLine()
                appendLine("目标路径：")
                appendLine(state.targetPath ?: "（未确定，无法恢复）")
                appendLine()
                append("恢复前会先备份当前的 keybox.xml，写入后回读校验 SHA-256；校验失败会自动回滚。")
            }
        },
        actions = persistentListOf(
            MochiDialogAction("取消", MochiDialogActionRole.Cancel),
            MochiDialogAction(
                label = "确认恢复",
                role = MochiDialogActionRole.Primary,
                onClick = {
                    val entry = pendingRestore
                    pendingRestore = null
                    if (entry != null) {
                        if (state.targetPath.isNullOrBlank()) {
                            showToast("尚未确定替换目标")
                        } else {
                            viewModel.restoreBackup(entry)
                        }
                    }
                },
            ),
        ),
    )

    // 删除二次确认
    MochiDialog(
        visible = pendingDelete != null,
        onDismissRequest = { pendingDelete = null },
        title = if (pendingDelete?.size == 1) "删除这个备份？" else "删除备份？",
        message = pendingDelete?.let { list ->
            buildString {
                appendLine("将永久删除 ${list.size} 个文件，无法撤销：")
                list.take(8).forEach { appendLine("· ${it.path}") }
                if (list.size > 8) appendLine("… 其余 ${list.size - 8} 个")
            }
        },
        actions = persistentListOf(
            MochiDialogAction("取消", MochiDialogActionRole.Cancel),
            MochiDialogAction(
                label = "删除",
                role = MochiDialogActionRole.Destructive,
                onClick = {
                    val list = pendingDelete
                    pendingDelete = null
                    if (list != null) viewModel.deleteBackups(list)
                },
            ),
        ),
    )

    // 批量清理二次确认
    MochiDialog(
        visible = pendingPrune,
        onDismissRequest = { pendingPrune = false },
        title = "批量清理备份？",
        message = buildString {
            appendLine("将按时间倒序保留最近 $keep 个备份，删除更早的 ${(entries.size - keep).coerceAtLeast(0)} 个。")
            appendLine()
            appendLine("扫描目录：")
            backups?.directories?.forEach { appendLine("· $it") }
            appendLine()
            append("删除不可撤销。需要保留更多时可先在设置里调整「保留最近 N 个」。")
        },
        actions = persistentListOf(
            MochiDialogAction("取消", MochiDialogActionRole.Cancel),
            MochiDialogAction(
                label = "清理",
                role = MochiDialogActionRole.Destructive,
                onClick = {
                    pendingPrune = false
                    viewModel.pruneBackups()
                },
            ),
        ),
    )
}

private fun describeBackup(entry: BackupEntry): String = buildString {
    appendLine("文件：${entry.path}")
    appendLine("时间：${formatDateTime(entry.timestamp)}")
    appendLine("大小：${formatBytes(entry.size)}")
    appendLine("SHA-256：")
    appendLine(entry.sha256)
    appendLine()
    val meta = entry.meta
    if (meta == null) {
        append("无法解析该备份的内容。")
    } else {
        appendLine("结构：${if (meta.valid) "有效" else "无效（${meta.error ?: "未知"}）"}")
        appendLine("DeviceID：${meta.deviceId ?: "未声明"}")
        appendLine("算法：${meta.algorithm ?: "未声明"}")
        appendLine("证书：${meta.certificateCount} 张")
        appendLine("最早到期：${meta.expiryText()}")
        appendLine()
        append("恢复目标：由「替换目标」决定，当前为 ${entry.targetPath}")
    }
}
