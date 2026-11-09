package dev.mochi.rootbox.ui

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.mochi.rootbox.AppState
import dev.mochi.rootbox.AppViewModel
import ui.feice.mochi.basic.MochiBarGroup
import ui.feice.mochi.basic.MochiBarGroupItem
import ui.feice.mochi.icons.MochiIconStyle
import ui.feice.mochi.icons.MochiIonicon
import ui.feice.mochi.list.MochiListCard
import ui.feice.mochi.list.MochiListDefaults
import ui.feice.mochi.list.MochiListRow
import ui.feice.mochi.list.MochiListStatusChip
import ui.feice.mochi.navigation.MochiLazyLargeTitleScaffold
import ui.feice.mochi.navigation.mochiNavSource
import ui.feice.mochi.theme.MochiTheme

// 与 RootToolboxApp 里的 nav.push(..., from = ...) 保持一致，用于打开式转场。
private const val SourceEnvironment = "home/environment"
private const val SourceModule = "home/module"
private const val SourceDiagnostics = "home/diagnostics"

@Composable
fun HomeScreen(
    state: AppState,
    viewModel: AppViewModel,
    onOpenEnvironment: () -> Unit,
    onOpenModule: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    val root = state.root
    val module = state.scan?.module
    val listState = rememberLazyListState()
    val canScroll = listState.canScrollForward || listState.canScrollBackward
    val libraryRefresh: (suspend () -> Unit)? = if (canScroll) ({ viewModel.refreshAll() }) else null
    val cardColor = MochiListDefaults.colors().container
    val cardCorner = MochiListDefaults.SectionCorner

    ShortContentPullRefresh(
        enabled = !canScroll,
        refreshing = state.rootChecking,
        onRefresh = { viewModel.refreshAll() },
    ) {
    MochiLazyLargeTitleScaffold(
        title = "Root 工具箱",
        subtitle = "Tricky Store keybox 管理",
        actions = {
            MochiBarGroup(navId = "home.actions") {
                MochiBarGroupItem(
                    onClick = { viewModel.refreshRoot() },
                    navId = "home.actions",
                    contentKey = "refresh",
                ) { MochiIonicon("refresh", contentDescription = "重新检测") }
                MochiBarGroupItem(
                    onClick = onOpenAbout,
                    navId = "home.actions",
                    contentKey = "about",
                ) { MochiIonicon("information-circle", contentDescription = "关于") }
            }
        },
        onRefresh = libraryRefresh,
        listState = listState,
    ) {
        item(key = "root-card") {
            MochiListCard(modifier = Modifier.mochiNavSource(SourceEnvironment, cardCorner, cardColor)) {
                MochiListRow(
                    title = "Root 权限",
                    subtitle = when {
                        state.rootChecking -> "正在向 Root 管理器请求授权…"
                        root?.granted == true -> root.uid ?: "已获得 root shell"
                        else -> root?.message ?: "尚未检测"
                    },
                    value = when {
                        state.rootChecking -> "检测中"
                        root?.granted == true -> "已授权"
                        else -> "未授权"
                    },
                    leading = { MochiIonicon("shield-checkmark", style = MochiIconStyle.Filled) },
                    chevron = true,
                    status = {
                        if (!state.rootChecking) {
                            val granted = root?.granted == true
                            MochiListStatusChip(
                                text = if (granted) "可用" else "受限",
                                containerColor = if (granted) {
                                    MochiTheme.colorScheme.primaryContainer
                                } else {
                                    MochiTheme.colorScheme.errorContainer
                                },
                                contentColor = if (granted) {
                                    MochiTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MochiTheme.colorScheme.onErrorContainer
                                },
                            )
                        }
                    },
                    onClick = onOpenEnvironment,
                )
            }
        }

        item(key = "module-card") {
            MochiListCard(modifier = Modifier.mochiNavSource(SourceModule, cardCorner, cardColor)) {
                MochiListRow(
                    title = "Tricky Store",
                    subtitle = when {
                        state.scanning -> "正在扫描 /data/adb…"
                        module?.installed == true -> listOfNotNull(
                            module?.moduleName,
                            module?.version?.let { "v$it" },
                        ).joinToString("，").ifBlank { "已安装" }
                        state.scan == null -> "尚未扫描"
                        else -> "未安装或目录不可读"
                    },
                    value = when {
                        module?.installed == true -> "已安装"
                        state.scan == null -> "—"
                        else -> "未安装"
                    },
                    leading = { MochiIonicon("cube", style = MochiIconStyle.Filled) },
                    chevron = true,
                    status = {
                        if (module?.installed == true) MochiListStatusChip("已识别")
                    },
                    onClick = onOpenModule,
                )
            }
        }

        item(key = "tools-card") {
            MochiListCard(modifier = Modifier.mochiNavSource(SourceDiagnostics, cardCorner, cardColor)) {
                MochiListRow(
                    title = "诊断",
                    subtitle = "查看运行日志，复制错误信息",
                    chevron = true,
                    leading = { MochiIonicon("document-text", style = MochiIconStyle.Filled) },
                    onClick = onOpenDiagnostics,
                )
            }
        }
    }
    }
}
