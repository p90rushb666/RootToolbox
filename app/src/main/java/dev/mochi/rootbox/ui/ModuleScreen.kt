package dev.mochi.rootbox.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.mochi.rootbox.AppState
import dev.mochi.rootbox.AppViewModel
import ui.feice.mochi.basic.MochiButtonStyle
import ui.feice.mochi.basic.MochiIconButton
import ui.feice.mochi.icons.MochiIonicon
import ui.feice.mochi.list.MochiListButton
import ui.feice.mochi.list.MochiListRow
import ui.feice.mochi.list.mochiListSection
import ui.feice.mochi.navigation.MochiLazyCompactTitleScaffold

@Composable
fun ModuleScreen(
    state: AppState,
    viewModel: AppViewModel,
    onOpenTargets: () -> Unit,
    navigationIcon: @Composable () -> Unit,
    showToast: (String) -> Unit,
) {
    val context = LocalContext.current

    fun copyValue(value: String, label: String) {
        if (value.isBlank()) {
            showToast("没有可复制的内容")
            return
        }
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText("RootToolbox", value))
        showToast(label)
    }

    val module = state.scan?.module
    val meta = state.currentMeta
    val candidates = state.scan?.candidates.orEmpty()

    MochiLazyCompactTitleScaffold(
        title = "Tricky Store",
        subtitle = "模块与当前 keybox",
        navigationIcon = navigationIcon,
    ) {
        mochiListSection(
            key = "module",
            header = "模块",
            footer = if (module?.installed == true) null else "未发现模块目录，确认已刷入并重启。",
        ) {
            row(key = "state") {
                MochiListRow(
                    title = "安装状态",
                    subtitle = module?.installDir,
                    value = when {
                        state.scanning -> "扫描中"
                        module?.installed == true -> "已安装"
                        state.scan == null -> "—"
                        else -> "未安装"
                    },
                )
            }
            row(key = "version") {
                MochiListRow(
                    title = "模块版本",
                    subtitle = module?.moduleName,
                    value = module?.version?.let { "v$it" } ?: "未知",
                )
            }
            row(key = "rescan") {
                MochiListButton("重新扫描模块", onClick = { viewModel.rescan() })
            }
        }

        mochiListSection(
            key = "target",
            header = "替换目标",
            footer = "所有下载都会写入这个路径，替换前自动备份。",
        ) {
            row(key = "path") {
                MochiListRow(
                    title = "keybox.xml",
                    subtitle = state.targetPath ?: "尚未确定",
                    value = "更改",
                    chevron = true,
                    onClick = onOpenTargets,
                )
            }
            row(key = "candidates") {
                MochiListRow(
                    title = "候选路径",
                    subtitle = if (candidates.isEmpty()) "未发现现有文件" else candidates.joinToString("\n") { it.path },
                    value = "${candidates.size} 个",
                )
            }
        }

        mochiListSection(
            key = "current",
            header = "当前密钥",
            footer = if (meta?.valid == false) "当前文件结构不完整，建议换一个有效密钥。" else null,
        ) {
            if (meta == null) {
                row(key = "none") {
                    MochiListRow(
                        title = "尚未读取",
                        subtitle = state.currentError ?: "获得 Root 权限后会自动读取",
                        destructive = state.currentError != null,
                    )
                }
            } else {
                row(key = "device") {
                    MochiListRow(
                        title = "DeviceID",
                        subtitle = meta.deviceId ?: "未声明",
                        trailing = {
                            MochiIconButton(
                                onClick = { copyValue(meta.deviceId.orEmpty(), "已复制 DeviceID") },
                                style = MochiButtonStyle.Tonal,
                                size = 36.dp,
                            ) { MochiIonicon("copy", contentDescription = "复制 DeviceID") }
                        },
                    )
                }
                row(key = "certs") {
                    MochiListRow(
                        title = "证书",
                        subtitle = meta.algorithm?.uppercase(),
                        value = "${meta.certificateCount} 张",
                    )
                }
                row(key = "expiry") {
                    MochiListRow("最早到期", value = meta.expiryText())
                }
                row(key = "size") {
                    MochiListRow("文件大小", value = formatBytes(meta.size.toLong()))
                }
                row(key = "sha") {
                    MochiListRow(
                        title = "SHA-256",
                        subtitle = meta.sha256,
                        trailing = {
                            MochiIconButton(
                                onClick = { copyValue(meta.sha256, "已复制 SHA-256") },
                                style = MochiButtonStyle.Tonal,
                                size = 36.dp,
                            ) { MochiIonicon("copy", contentDescription = "复制 SHA-256") }
                        },
                    )
                }
            }
        }

        if (!state.scan?.warnings.isNullOrEmpty()) {
            mochiListSection(key = "warnings", header = "扫描提示") {
                state.scan?.warnings?.forEachIndexed { index, warning ->
                    row(key = "warn-$index") {
                        MochiListRow(title = warning)
                    }
                }
            }
        }
    }
}
