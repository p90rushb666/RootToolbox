package dev.mochi.rootbox.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.mochi.rootbox.AppState
import dev.mochi.rootbox.AppViewModel
import dev.mochi.rootbox.CatalogUi
import dev.mochi.rootbox.DownloadProgress
import dev.mochi.rootbox.core.CatalogCategory
import dev.mochi.rootbox.core.CatalogDownload
import dev.mochi.rootbox.core.CatalogModule
import dev.mochi.rootbox.core.ModuleRelease
import dev.mochi.rootbox.core.openCatalogFile
import kotlinx.collections.immutable.persistentListOf
import ui.feice.mochi.basic.MochiButton
import ui.feice.mochi.basic.MochiButtonStyle
import ui.feice.mochi.basic.MochiDialog
import ui.feice.mochi.basic.MochiDialogAction
import ui.feice.mochi.basic.MochiDialogActionRole
import ui.feice.mochi.basic.MochiIconButton
import ui.feice.mochi.basic.MochiProgressBar
import ui.feice.mochi.basic.MochiProgressRing
import ui.feice.mochi.basic.Text
import ui.feice.mochi.icons.MochiIonicon
import ui.feice.mochi.list.MochiListRow
import ui.feice.mochi.list.mochiListSection
import ui.feice.mochi.navigation.LocalMochiBottomBarInset
import ui.feice.mochi.navigation.MochiLazyCompactTitleScaffold
import ui.feice.mochi.theme.MochiTheme

/**
 * 模块下载（一级）：顶部显示索引进度，下面是分类列表；索引就绪前分类不可进入。
 */
@Composable
fun ModuleCatalogScreen(
    state: AppState,
    viewModel: AppViewModel,
    navigationIcon: @Composable () -> Unit,
    onOpenCategory: (CatalogCategory) -> Unit,
    onOpenDownloads: () -> Unit,
    showToast: (String) -> Unit,
) {
    LaunchedEffect(Unit) { viewModel.loadCatalog() }
    val ui = state.catalog
    val scheme = MochiTheme.colorScheme

    MochiLazyCompactTitleScaffold(
        title = "模块下载",
        subtitle = when {
            ui.loadingIndex -> "正在加载索引…"
            ui.loadingReleases -> "正在查询版本 ${ui.releaseChecked}/${ui.releaseTotal}"
            else -> "${ui.index.modules.size} 个模块 · 索引来源 ${ui.indexSource}"
        },
        navigationIcon = navigationIcon,
        actions = {
            MochiIconButton(
                onClick = onOpenDownloads,
                style = MochiButtonStyle.Tonal,
                size = 36.dp,
            ) { MochiIonicon("folder-open", contentDescription = "下载管理") }
        },
    ) {
        // 顶部索引状态与进度
        mochiListSection(
            key = "index",
            header = "索引",
            footer = "索引来自项目仓库的 modules/index.json，离线时使用安装包内置副本；版本号由 GitHub Release 查询，缓存 6 小时。",
        ) {
            row(key = "source") {
                MochiListRow(
                    title = "索引来源",
                    subtitle = when {
                        ui.loadingIndex -> "正在联网更新索引…"
                        ui.indexAt > 0 -> "更新于 ${formatDateTime(ui.indexAt)}"
                        else -> "安装包内置副本，尚未联网更新"
                    },
                    value = ui.indexSource,
                    loading = ui.loadingIndex,
                )
            }
            if (ui.loadingReleases) {
                row(key = "release-progress") {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 10.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            MochiProgressRing(
                                progress = if (ui.releaseTotal > 0) {
                                    ui.releaseChecked.toFloat() / ui.releaseTotal.toFloat()
                                } else null,
                                size = 24.dp,
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "查询版本 ${ui.releaseChecked}/${ui.releaseTotal}",
                                style = MochiTheme.textStyles.footnote,
                                color = scheme.onSurfaceVariant,
                            )
                        }
                        if (ui.releaseTotal > 0) {
                            Spacer(Modifier.height(8.dp))
                            MochiProgressBar(
                                ui.releaseChecked.toFloat() / ui.releaseTotal.toFloat(),
                            )
                        }
                    }
                }
            }
        }

        if (ui.error != null) {
            item(key = "error") {
                MochiListRow(
                    title = "提示",
                    subtitle = ui.error,
                    destructive = true,
                    leading = { MochiIonicon("alert-circle") },
                )
            }
        }

        // 分类列表
        mochiListSection(
            key = "categories",
            header = "分类",
            footer = if (!ui.indexReady) "索引加载完成后才可进入分类" else null,
        ) {
            ui.index.grouped().forEach { (category, modules) ->
                row(key = "cat-${category.id}") {
                    MochiListRow(
                        title = category.title,
                        subtitle = "${modules.size} 个模块",
                        chevron = true,
                        enabled = ui.indexReady,
                        leading = { MochiIonicon(categoryIcon(category.id)) },
                        onClick = { onOpenCategory(category) },
                    )
                }
            }
        }

        item(key = "bottom-space") {
            Spacer(Modifier.height(LocalMochiBottomBarInset.current + 24.dp))
        }
    }
}

/** 模块下载（二级）：某个分类下的模块列表，紧凑行 + 下载按钮在最右。 */
@Composable
fun ModuleCategoryScreen(
    category: CatalogCategory,
    state: AppState,
    viewModel: AppViewModel,
    navigationIcon: @Composable () -> Unit,
    showToast: (String) -> Unit,
) {
    val ui = state.catalog
    val context = LocalContext.current
    val modules = ui.index.grouped().firstOrNull { it.first.id == category.id }?.second.orEmpty()

    MochiLazyCompactTitleScaffold(
        title = category.title,
        subtitle = when {
            ui.loadingReleases -> "正在查询版本 ${ui.releaseChecked}/${ui.releaseTotal}"
            else -> "${modules.size} 个模块"
        },
        navigationIcon = navigationIcon,
    ) {
        mochiListSection(key = "modules") {
            modules.forEach { module ->
                row(key = module.id) {
                    CatalogModuleRow(
                        module = module,
                        release = ui.releases[module.repo],
                        progress = ui.downloading[module.id],
                        file = ui.files.firstOrNull { it.name.startsWith("${module.id}-") },
                        onDownload = { viewModel.downloadCatalogModule(module) },
                        onCancel = { viewModel.cancelCatalogDownload(module.id) },
                        onOpen = { file ->
                            if (!openCatalogFile(context, file.path)) {
                                showToast("没有可打开该文件的应用")
                            }
                        },
                    )
                }
            }
        }

        item(key = "bottom-space") {
            Spacer(Modifier.height(LocalMochiBottomBarInset.current + 24.dp))
        }
    }
}

/** 单个模块的紧凑行：名称 + 版本在最左，下载/打开/取消按钮在最右。 */
@Composable
private fun CatalogModuleRow(
    module: CatalogModule,
    release: ModuleRelease?,
    progress: DownloadProgress?,
    file: CatalogDownload?,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onOpen: (CatalogDownload) -> Unit,
) {
    val scheme = MochiTheme.colorScheme
    val textStyles = MochiTheme.textStyles
    val version = release?.tag?.takeIf { it.isNotBlank() }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(module.name, style = textStyles.bodyMedium)
                    if (module.note != null) {
                        Spacer(Modifier.width(6.dp))
                        StatusPill(
                            text = "插件",
                            containerColor = scheme.tertiaryContainer,
                            contentColor = scheme.onTertiaryContainer,
                            compact = true,
                        )
                    }
                }
                Text(
                    buildString {
                        append(module.repo)
                        if (version != null) append(" · $version")
                        release?.asset?.let { append(" · ${formatBytes(it.size)}") }
                    },
                    style = textStyles.footnote,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 2.dp),
                )
                if (release?.error != null && progress == null && file == null) {
                    Text(
                        release.error,
                        style = textStyles.footnote,
                        color = scheme.error,
                        maxLines = 1,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }

            when {
                progress != null -> MochiButton(onClick = onCancel, style = MochiButtonStyle.Tonal) {
                    Text("取消")
                }
                file != null -> MochiButton(
                    onClick = { onOpen(file) },
                    style = MochiButtonStyle.Tonal,
                ) { Text("打开") }
                else -> MochiButton(
                    onClick = onDownload,
                    style = MochiButtonStyle.Filled,
                    enabled = release?.ok == true,
                ) { Text(if (release?.ok == true) "下载" else "…") }
            }
        }

        if (progress != null) {
            DownloadBlock(progress)
        }
    }
}

@Composable
private fun DownloadBlock(progress: DownloadProgress) {
    val animated by animateFloatAsState(
        targetValue = progress.fraction.coerceIn(0f, 1f),
        animationSpec = tween(180),
        label = "catalog-download",
    )
    Column(Modifier.padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MochiProgressRing(
                progress = if (progress.total > 0) animated else null,
                size = 22.dp,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                if (progress.total > 0) {
                    "${formatBytes(progress.read)} / ${formatBytes(progress.total)} · ${(animated * 100).toInt()}%"
                } else {
                    "${formatBytes(progress.read)} 已下载"
                },
                style = MochiTheme.textStyles.footnote,
                color = MochiTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
        }
        if (progress.total > 0) {
            Spacer(Modifier.height(6.dp))
            MochiProgressBar(animated)
        }
    }
}

/** 下载管理：列出 app 私有目录里的安装包，可打开 / 删除 / 一键清空。 */
@Composable
fun ModuleDownloadsScreen(
    state: AppState,
    viewModel: AppViewModel,
    navigationIcon: @Composable () -> Unit,
    showToast: (String) -> Unit,
) {
    LaunchedEffect(Unit) { viewModel.refreshCatalogFiles() }
    val ui: CatalogUi = state.catalog
    val context = LocalContext.current
    val scheme = MochiTheme.colorScheme

    var pendingDelete by remember { mutableStateOf<CatalogDownload?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    MochiLazyCompactTitleScaffold(
        title = "下载管理",
        subtitle = "${ui.files.size} 个文件 · ${formatBytes(ui.files.sumOf { it.size })}",
        navigationIcon = navigationIcon,
    ) {
        mochiListSection(
            key = "files",
            header = "已下载",
            footer = "文件保存在应用私有目录，卸载应用会一起删除；「打开」会以 content:// 临时授权给系统安装器或文件查看器。",
        ) {
            if (ui.files.isEmpty()) {
                row(key = "empty") {
                    MochiListRow(
                        title = "还没有下载文件",
                        subtitle = "去「模块下载」页挑一个模块试试",
                    )
                }
            }
            ui.files.forEach { file ->
                row(key = file.path) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 12.dp),
                    ) {
                        Text(file.name, style = MochiTheme.textStyles.bodyMedium)
                        Text(
                            "${formatBytes(file.size)} · ${formatDateTime(file.modifiedAt)}",
                            style = MochiTheme.textStyles.footnote,
                            color = scheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                        Row(
                            Modifier.padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            MochiButton(
                                onClick = {
                                    if (!openCatalogFile(context, file.path)) {
                                        showToast("没有可打开该文件的应用")
                                    }
                                },
                                style = MochiButtonStyle.Tonal,
                            ) { Text("打开") }
                            MochiButton(
                                onClick = { pendingDelete = file },
                                style = MochiButtonStyle.Tonal,
                            ) { Text("删除") }
                        }
                    }
                }
            }
        }

        if (ui.files.isNotEmpty()) {
            item(key = "clear") {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                ) {
                    MochiButton(
                        onClick = { confirmClear = true },
                        style = MochiButtonStyle.Tonal,
                    ) { Text("清空全部下载") }
                }
            }
        }

        item(key = "bottom-space") {
            Spacer(Modifier.height(LocalMochiBottomBarInset.current + 24.dp))
        }
    }

    MochiDialog(
        visible = pendingDelete != null,
        onDismissRequest = { pendingDelete = null },
        title = "删除这个文件？",
        message = pendingDelete?.let { "${it.name}\n${formatBytes(it.size)}\n\n删除后需要重新下载。" },
        actions = persistentListOf(
            MochiDialogAction("取消", MochiDialogActionRole.Cancel),
            MochiDialogAction(
                label = "删除",
                role = MochiDialogActionRole.Destructive,
                onClick = {
                    val file = pendingDelete
                    pendingDelete = null
                    if (file != null) viewModel.deleteCatalogFile(file)
                },
            ),
        ),
    )

    MochiDialog(
        visible = confirmClear,
        onDismissRequest = { confirmClear = false },
        title = "清空全部下载？",
        message = "将删除 ${ui.files.size} 个文件，共 ${formatBytes(ui.files.sumOf { it.size })}。删除后需要重新下载。",
        actions = persistentListOf(
            MochiDialogAction("取消", MochiDialogActionRole.Cancel),
            MochiDialogAction(
                label = "清空",
                role = MochiDialogActionRole.Destructive,
                onClick = {
                    confirmClear = false
                    viewModel.clearCatalogFiles()
                },
            ),
        ),
    )
}

private fun categoryIcon(id: String): String = when (id) {
    "root" -> "key"
    "zygisk" -> "git-branch"
    "lsposed" -> "cube"
    "hide" -> "lock-closed"
    "meta" -> "layers"
    "tee" -> "shield-checkmark"
    "ad" -> "ban"
    else -> "apps"
}
