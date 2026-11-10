package dev.mochi.rootbox.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.mochi.rootbox.AppState
import dev.mochi.rootbox.AppViewModel
import dev.mochi.rootbox.core.TrickyStoreScanner
import ui.feice.mochi.basic.MochiButton
import ui.feice.mochi.basic.MochiTextField
import ui.feice.mochi.basic.Text
import ui.feice.mochi.list.MochiListRow
import ui.feice.mochi.list.mochiListSection
import ui.feice.mochi.list.rows
import ui.feice.mochi.navigation.MochiLazyCompactTitleScaffold
import ui.feice.mochi.theme.MochiTheme

@Composable
fun TargetScreen(
    state: AppState,
    viewModel: AppViewModel,
    navigationIcon: @Composable () -> Unit,
    showToast: (String) -> Unit,
) {
    var manual by remember { mutableStateOf(state.targetPath.orEmpty()) }
    val candidates = state.scan?.candidates.orEmpty()

    MochiLazyCompactTitleScaffold(
        title = "替换目标",
        subtitle = "决定 keybox.xml 写入到哪里",
        navigationIcon = navigationIcon,
    ) {
        mochiListSection(
            key = "candidates",
            header = "已发现的 keybox.xml",
            footer = "选中后所有下载都会写入该路径。替换前会先备份原文件，校验失败会自动回滚。",
        ) {
            if (candidates.isEmpty()) {
                row(key = "none") {
                    MochiListRow(
                        title = "未发现 keybox.xml",
                        subtitle = "确认 Tricky Store 已安装并已重启，或在下方手动指定路径",
                    )
                }
            } else {
                rows(candidates, key = { it.path }) { candidate ->
                    val isDefault = candidate.path == TrickyStoreScanner.PRIMARY_TARGET
                    MochiListRow(
                        title = candidate.path.substringAfterLast('/'),
                        subtitle = buildString {
                            append(candidate.origin)
                            if (isDefault) append(" · 默认路径")
                            append('\n')
                            append(candidate.path)
                        },
                        value = formatBytes(candidate.size),
                        checked = candidate.path == state.targetPath,
                        status = {
                            if (isDefault) {
                                StatusPill(
                                    "默认",
                                    MochiTheme.colorScheme.primaryContainer,
                                    MochiTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                        },
                        onClick = {
                            viewModel.selectTarget(candidate.path)
                            showToast("已切换到 ${candidate.path}")
                        },
                    )
                }
            }
        }

        mochiListSection(
            key = "manual",
            header = "手动指定",
            footer = "只在自动扫描找不到时使用。路径必须以 keybox.xml 结尾。",
        ) {
            row(key = "input") {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 16.dp),
                ) {
                    MochiTextField(
                        manual,
                        { manual = it },
                        placeholder = "/data/adb/tricky_store/keybox.xml",
                    )
                    Spacer(Modifier.height(12.dp))
                    MochiButton(
                        onClick = {
                            val path = manual.trim()
                            if (path.isNotBlank()) {
                                viewModel.selectTarget(path)
                                showToast("已使用手动路径")
                            }
                        },
                    ) { Text("使用此路径") }
                }
            }
        }
    }
}
