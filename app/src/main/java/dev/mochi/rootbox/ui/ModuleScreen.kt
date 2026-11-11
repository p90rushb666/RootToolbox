package dev.mochi.rootbox.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.mochi.rootbox.AppState
import dev.mochi.rootbox.AppViewModel
import dev.mochi.rootbox.core.ModuleLoadState
import ui.feice.mochi.basic.MochiButton
import ui.feice.mochi.basic.MochiButtonStyle
import ui.feice.mochi.basic.MochiIconButton
import ui.feice.mochi.basic.Text
import ui.feice.mochi.icons.MochiIonicon
import ui.feice.mochi.list.MochiListButton
import ui.feice.mochi.list.MochiListRow
import ui.feice.mochi.list.MochiListStatusChip
import ui.feice.mochi.list.mochiListSection
import ui.feice.mochi.navigation.MochiLazyCompactTitleScaffold
import ui.feice.mochi.theme.MochiTheme

@Composable
fun ModuleScreen(
    state: AppState,
    viewModel: AppViewModel,
    onOpenTargets: () -> Unit,
    onOpenBackups: () -> Unit,
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

    LaunchedEffect(Unit) {
        // 进入页面时先给一次静态结论；写完 keybox 后安装卡片也会主动刷新。
        if (state.moduleLoad == null && !state.moduleLoadChecking) viewModel.checkModuleLoad()
    }

    val module = state.scan?.module
    val meta = state.currentMeta
    val candidates = state.scan?.candidates.orEmpty()
    val load = state.moduleLoad
    val backupCount = state.backups?.entries?.size

    MochiLazyCompactTitleScaffold(
        title = "Tricky Store",
        subtitle = "模块与当前 keybox",
        navigationIcon = navigationIcon,
    ) {
        mochiListSection(
            key = "load",
            header = "加载状态",
        ) {
            row(key = "status") {
                // 只保留一个彩色的状态胶囊：之前 value 和胶囊都写「已加载」，同一个词出现两次。
                val loaded = load?.state == ModuleLoadState.LOADED
                val label = when {
                    state.moduleLoadChecking -> "检测中"
                    load == null -> "未检查"
                    loaded -> "已加载"
                    else -> "未加载"
                }
                MochiListRow(
                    title = "加载状态",
                    subtitle = load?.reasons?.firstOrNull()?.takeIf { !state.moduleLoadChecking },
                    status = {
                        MochiListStatusChip(
                            text = label,
                            containerColor = when {
                                state.moduleLoadChecking || load == null -> MochiTheme.colorScheme.surfaceVariant
                                loaded -> MochiTheme.colorScheme.primaryContainer
                                else -> MochiTheme.colorScheme.errorContainer
                            },
                            contentColor = when {
                                state.moduleLoadChecking || load == null -> MochiTheme.colorScheme.onSurfaceVariant
                                loaded -> MochiTheme.colorScheme.onPrimaryContainer
                                else -> MochiTheme.colorScheme.onErrorContainer
                            },
                        )
                    },
                )
            }
            row(key = "recheck") {
                androidx.compose.foundation.layout.Column(
                    androidx.compose.ui.Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                ) {
                    MochiButton(
                        onClick = { viewModel.checkModuleLoad() },
                        style = MochiButtonStyle.Tonal,
                        enabled = !state.moduleLoadChecking,
                    ) { Text(if (state.moduleLoadChecking) "检测中…" else "重新检查") }
                }
            }
        }

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
            key = "backups",
            header = "备份",
            footer = "替换 keybox 时留下的 .bak 文件，可预览、恢复或清理。",
        ) {
            row(key = "entry") {
                MochiListRow(
                    title = "备份中心",
                    subtitle = when {
                        state.root?.granted != true -> "需要 Root 权限才能读取 /data/adb"
                        state.backupsLoading -> "正在扫描…"
                        backupCount == null -> "点击查看"
                        backupCount == 0 -> "没有备份文件"
                        else -> "共 $backupCount 个备份"
                    },
                    value = backupCount?.let { "$it" } ?: "查看",
                    chevron = true,
                    leading = { MochiIonicon("archive") },
                    onClick = onOpenBackups,
                )
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
                    // MochiListRow 的副标题是单行省略，64 位哈希会被截断，这里单独用可换行的等宽文本。
                    androidx.compose.foundation.layout.Column(
                        androidx.compose.ui.Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 12.dp),
                    ) {
                        androidx.compose.foundation.layout.Row(
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        ) {
                            Text("SHA-256", style = MochiTheme.textStyles.bodyLarge)
                            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                            MochiIconButton(
                                onClick = { copyValue(meta.sha256, "已复制 SHA-256") },
                                style = MochiButtonStyle.Tonal,
                                size = 36.dp,
                            ) { MochiIonicon("copy", contentDescription = "复制 SHA-256") }
                        }
                        Text(
                            meta.sha256,
                            style = MochiTheme.textStyles.bodySmall.copy(
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            ),
                            color = MochiTheme.colorScheme.onSurfaceVariant,
                            softWrap = true,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
        }
    }
}
