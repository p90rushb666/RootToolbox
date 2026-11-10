package dev.mochi.rootbox.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.mochi.rootbox.AppState
import dev.mochi.rootbox.AppViewModel
import dev.mochi.rootbox.data.GreenRating
import dev.mochi.rootbox.data.KeyboxItem
import dev.mochi.rootbox.data.KeyboxRepository
import dev.mochi.rootbox.data.KeyboxStatus
import kotlinx.collections.immutable.persistentListOf
import ui.feice.mochi.basic.MochiActivityIndicator
import ui.feice.mochi.basic.MochiBarIconButton
import ui.feice.mochi.basic.MochiButton
import ui.feice.mochi.basic.MochiButtonStyle
import ui.feice.mochi.basic.MochiDialog
import ui.feice.mochi.basic.MochiDialogAction
import ui.feice.mochi.basic.MochiDialogActionRole
import ui.feice.mochi.basic.MochiSegmentedControl
import ui.feice.mochi.basic.Text
import ui.feice.mochi.icons.MochiIonicon
import ui.feice.mochi.list.MochiListButton
import ui.feice.mochi.list.MochiListCard
import ui.feice.mochi.list.MochiListRow
import ui.feice.mochi.list.mochiListSection
import ui.feice.mochi.list.rows
import ui.feice.mochi.navigation.MochiLazyLargeTitleScaffold
import ui.feice.mochi.theme.MochiTheme

@Composable
fun KeysScreen(
    state: AppState,
    viewModel: AppViewModel,
    navigationIcon: (@Composable () -> Unit)?,
    showToast: (String) -> Unit,
    openBrowser: (String) -> Unit,
    showGreenBadge: Boolean = true,
    onOpenPreview: () -> Unit = {},
) {
    LaunchedEffect(Unit) { viewModel.ensureKeysLoaded() }
    val listState = rememberLazyListState()
    val canScroll = listState.canScrollForward || listState.canScrollBackward
    val libraryRefresh: (suspend () -> Unit)? = if (canScroll) ({ viewModel.refreshKeys() }) else null
    var selected by remember { mutableStateOf<KeyboxItem?>(null) }
    var lastShown by remember { mutableStateOf<KeyboxItem?>(null) }
    if (selected != null) lastShown = selected

    // 本地导入：选一个 xml，同样先进预览页，不直接写。
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) viewModel.prepareLocalImport(uri) }

    // 预览准备好后跳到预览页（只跳一次，避免重组重复 push）。
    var previewOpened by remember { mutableStateOf(false) }
    LaunchedEffect(state.preview) {
        if (state.preview != null && !previewOpened) {
            previewOpened = true
            onOpenPreview()
        } else if (state.preview == null) {
            previewOpened = false
        }
    }

    val allItems = state.allItems
    val usableCount = allItems.count { it.usable }

    ShortContentPullRefresh(
        enabled = !canScroll,
        refreshing = state.keysLoading,
        onRefresh = { viewModel.refreshKeys() },
    ) {
    MochiLazyLargeTitleScaffold(
        title = "密钥库",
        subtitle = if (state.keysLoading) {
            val percent = (state.refreshDone * 100) / state.refreshTotal.coerceAtLeast(1)
            "正在刷新 $percent% · ${state.refreshDone}/${state.refreshTotal} 个来源"
        } else {
            "可用 $usableCount 条，共 ${allItems.size} 条"
        },
        navigationIcon = navigationIcon,
        actions = {
            MochiBarIconButton(
                onClick = { importLauncher.launch(arrayOf("*/*")) },
                navId = "app.import",
                contentKey = "import",
            ) { MochiIonicon("document-attach", contentDescription = "从本地导入 keybox.xml") }
            MochiBarIconButton(
                // Kept enabled while loading: a disabled bar button dims its content, which would
                // wash out the progress ring. loadKeys() itself ignores re-entrant calls.
                onClick = { if (!state.keysLoading) viewModel.loadKeys() },
                enabled = true,
                navId = "app.refresh",
                contentKey = "refresh",
            ) {
                if (state.keysLoading) {
                    MochiActivityIndicator(size = 18.dp)
                } else {
                    MochiIonicon("refresh", contentDescription = "刷新密钥库")
                }
            }
        },
        onRefresh = libraryRefresh,
        listState = listState,
    ) {
        item(key = "mode") {
            MochiSegmentedControl(
                selectedIndex = if (state.showArchived) 1 else 0,
                onSelect = { index ->
                    val wantArchived = index == 1
                    if (wantArchived != state.showArchived) viewModel.toggleArchived()
                },
                items = persistentListOf("可用", "全部"),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        val hasFailure = state.sources.any { it.error != null }
        if (hasFailure && !state.keysLoading) {
            item(key = "retry") {
                MochiListCard {
                    MochiListButton(
                        text = "重新拉取失败的来源",
                        onClick = { viewModel.loadKeys() },
                    )
                }
            }
        }

        if (state.keysLoading && !state.keysLoadedOnce) {
            item(key = "loading") {
                MochiListCard {
                    MochiListRow("正在获取密钥列表", subtitle = "同时请求 evoker.qzz.io 与 keybox.kowx712.cc", loading = true)
                }
            }
        }

        val install = state.install
        if (install != null) {
            item(key = "install") {
                MochiListCard {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 16.dp),
                    ) {
                        Text(install.name, style = MochiTheme.textStyles.bodyLarge)
                        Spacer(Modifier.height(4.dp))
                        Text(install.phase, style = MochiTheme.textStyles.bodyMedium)
                        Spacer(Modifier.height(12.dp))
                        MochiProgressBar(install.fraction)
                        val result = install.result
                        if (result != null) {
                            Spacer(Modifier.height(14.dp))
                            Text(result.title, style = MochiTheme.textStyles.bodyLarge)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                result.detail,
                                style = MochiTheme.textStyles.bodyMedium,
                                color = MochiTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (!install.running) {
                            Spacer(Modifier.height(14.dp))
                            MochiButton(
                                onClick = { viewModel.dismissInstall() },
                                style = MochiButtonStyle.Tonal,
                            ) { Text("关闭") }
                        }
                    }
                }
            }
        }

        state.sources.forEach { source ->
            val visibleRows = allItems
                .filter { it.sourceId == source.sourceId }
                .filter { state.showArchived || it.usable }
                .sortedWith(compareByDescending<KeyboxItem> { it.usable }.thenByDescending { it.updatedAt ?: 0L })

            mochiListSection(
                key = "source-${source.sourceId}",
                header = "${source.sourceTitle}（${visibleRows.size}）",
                footer = when (source.sourceId) {
                    KeyboxRepository.KOWX712_ID -> "该来源的下载接口需要 Cloudflare 人机验证，点击条目会在浏览器中打开。"
                    else -> "点条目先看预览，确认后才写入；替换前会自动备份原文件。"
                },
            ) {
                if (source.error != null) {
                    row(key = "error") {
                        MochiListRow(
                            title = "拉取失败",
                            subtitle = source.error,
                            destructive = true,
                            leading = { MochiIonicon("alert-circle") },
                        )
                    }
                } else if (visibleRows.isEmpty()) {
                    row(key = "empty") {
                        MochiListRow(
                            title = if (state.showArchived) "暂无条目" else "没有可用密钥",
                            subtitle = if (state.showArchived) null else "切换到“全部”查看已过期与已吊销的归档",
                        )
                    }
                } else {
                    rows(visibleRows, key = { it.uid }) { item ->
                        MochiListRow(
                            title = item.name,
                            subtitle = subtitleOf(item),
                            leading = { MochiIonicon("key") },
                            status = {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    if (showGreenBadge) GreenChip(item.green)
                                    StatusChip(item)
                                }
                            },
                            onClick = { selected = item },
                        )
                    }
                }
            }
        }

    }
    }

    val dialogItem = lastShown
    MochiDialog(
        visible = selected != null,
        onDismissRequest = { selected = null },
        title = dialogItem?.name.orEmpty(),
        message = dialogItem?.let { describe(it) },
        actions = persistentListOf(
            MochiDialogAction("取消", MochiDialogActionRole.Cancel),
            MochiDialogAction(
                label = if (dialogItem?.downloadUrl != null) "预览安装" else "浏览器打开",
                role = MochiDialogActionRole.Primary,
                onClick = {
                    val target = dialogItem
                    if (target != null) {
                        if (target.downloadUrl != null) {
                            // 只下载并生成预览，不写设备；确认动作在预览页完成。
                            viewModel.prepareInstall(target)
                        } else {
                            openBrowser(target.sourcePageUrl)
                            showToast("已打开浏览器，通过验证后请手动下载")
                        }
                    }
                    selected = null
                },
            ),
        ),
    )
}

private fun subtitleOf(item: KeyboxItem): String {
    val updated = item.updatedAt?.let { "${formatDateTime(it)} 更新" } ?: "更新时间未知"
    val expires = item.expiresAt?.let { "${formatDate(it)} 到期" }
    return if (expires != null) "$updated，$expires" else updated
}

private fun describe(item: KeyboxItem): String {
    val greenText = when (item.green) {
        GreenRating.THREE_GREEN -> "是（来源评级 3/3）"
        GreenRating.NOT_THREE -> "否（${item.greenDetail ?: "来源评级不足 3/3"}）"
        GreenRating.UNVERIFIED -> "无法判断（${item.greenDetail ?: "来源未提供评级"}）"
    }
    return buildString {
        appendLine("来源：${item.sourceTitle}")
        appendLine("状态：${item.statusLabel}")
        appendLine("三绿：$greenText")
        appendLine("更新：${formatDateTime(item.updatedAt)}（${relativeTime(item.updatedAt)}）")
        appendLine("到期：${formatDate(item.expiresAt)}")
        if (item.detail.isNotBlank()) appendLine("特征：${item.detail}")
        item.sha256?.let { appendLine("SHA-256：${it.take(24)}…") }
        if (item.downloadUrl == null) {
            appendLine()
            append("该来源的下载需要人机验证，只能跳转浏览器手动下载。")
        }
    }
}
