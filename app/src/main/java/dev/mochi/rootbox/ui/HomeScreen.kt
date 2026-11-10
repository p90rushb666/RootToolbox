package dev.mochi.rootbox.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.mochi.rootbox.AppState
import dev.mochi.rootbox.AppViewModel
import ui.feice.mochi.basic.MochiBarGroup
import ui.feice.mochi.basic.MochiBarGroupItem
import ui.feice.mochi.basic.Text
import ui.feice.mochi.effect.elastic.mochiPressDeform
import ui.feice.mochi.effect.glow.mochiPressGlow
import ui.feice.mochi.effect.glow.rememberMochiGlowState
import ui.feice.mochi.icons.MochiIconStyle
import ui.feice.mochi.icons.MochiIonicon
import ui.feice.mochi.list.MochiListCard
import ui.feice.mochi.list.MochiListDefaults
import ui.feice.mochi.list.MochiListRow
import ui.feice.mochi.list.MochiListStatusChip
import ui.feice.mochi.navigation.MochiLazyLargeTitleScaffold
import ui.feice.mochi.navigation.mochiNavSource
import ui.feice.mochi.theme.MochiDefaults
import ui.feice.mochi.theme.MochiTheme

// 与 RootToolboxApp 里的 nav.push(..., from = ...) 保持一致，用于打开式转场。
private const val SourceEnvironment = "home/environment"
private const val SourceModule = "home/module"
private const val SourceDiagnostics = "home/diagnostics"
private const val SourceIntegrity = "home/integrity"
private const val SourceModuleUpdates = "home/module-updates"

@Composable
fun HomeScreen(
    state: AppState,
    viewModel: AppViewModel,
    onOpenEnvironment: () -> Unit,
    onOpenModule: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenIntegrity: () -> Unit,
    onOpenModuleUpdates: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    val root = state.root
    val module = state.scan?.module
    val scheme = MochiTheme.colorScheme
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
        // Root 权限保留整行卡片（带状态胶囊与 value 模糊过渡）。
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
                                    scheme.primaryContainer
                                } else {
                                    scheme.errorContainer
                                },
                                contentColor = if (granted) {
                                    scheme.onPrimaryContainer
                                } else {
                                    scheme.onErrorContainer
                                },
                            )
                        }
                    },
                    onClick = onOpenEnvironment,
                )
            }
        }

        // 其余入口改成 demo 首页那种每行两个的长方形卡片。
        item(key = "grid-1") {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                HomeGridCard(
                    modifier = Modifier.weight(1f),
                    title = "Tricky Store",
                    summary = "模块状态、备份与替换目标",
                    icon = "cube",
                    iconContainer = scheme.primaryContainer,
                    iconContent = scheme.onPrimaryContainer,
                    sourceKey = SourceModule,
                    badge = if (module?.installed == true) "已识别" else null,
                    onClick = onOpenModule,
                )
                HomeGridCard(
                    modifier = Modifier.weight(1f),
                    title = "完整性检测",
                    summary = "Play Integrity 实时判定",
                    icon = "checkmark-circle",
                    iconContainer = scheme.secondaryContainer,
                    iconContent = scheme.onSecondaryContainer,
                    sourceKey = SourceIntegrity,
                    badge = "需配置",
                    onClick = onOpenIntegrity,
                )
            }
        }

        item(key = "grid-2") {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                HomeGridCard(
                    modifier = Modifier.weight(1f),
                    title = "模块更新",
                    summary = "检查新版本、下载与刷写",
                    icon = "cloud-download",
                    iconContainer = scheme.primaryContainer,
                    iconContent = scheme.onPrimaryContainer,
                    sourceKey = SourceModuleUpdates,
                    badge = state.moduleUpdates.items
                        .count { it.hasUpdate }
                        .takeIf { it > 0 }
                        ?.let { "$it 个更新" },
                    onClick = onOpenModuleUpdates,
                )
                HomeGridCard(
                    modifier = Modifier.weight(1f),
                    title = "诊断",
                    summary = "运行日志、崩溃报告与复制",
                    icon = "document-text",
                    iconContainer = scheme.surfaceVariant,
                    iconContent = scheme.onSurfaceVariant,
                    sourceKey = SourceDiagnostics,
                    onClick = onOpenDiagnostics,
                )
            }
        }
    }
    }
}

/**
 * demo 首页 `CatalogCard` 的写法：圆角容器 + 图标块 + 标题/简介，带按压形变、按压高光和
 * 打开式转场的容器来源标记。用 Row 两个一组而不是嵌套 LazyVerticalGrid，避免破坏大标题脚手架
 * 与下拉刷新。
 */
@Composable
private fun HomeGridCard(
    modifier: Modifier,
    title: String,
    summary: String,
    icon: String,
    iconContainer: Color,
    iconContent: Color,
    sourceKey: String,
    badge: String? = null,
    onClick: () -> Unit,
) {
    val scheme = MochiTheme.colorScheme
    val textStyles = MochiTheme.textStyles
    val shape = MochiTheme.shapes.extraLarge
    val background = MochiDefaults.groupedContainer()
    val glow = rememberMochiGlowState()

    Column(
        modifier
            .mochiNavSource(sourceKey, 28.dp, background)
            .mochiPressDeform()
            .background(background, shape)
            .height(168.dp)
            .mochiPressGlow(glow, shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(44.dp)
                    .background(iconContainer, MochiTheme.shapes.medium),
                contentAlignment = Alignment.Center,
            ) {
                MochiIonicon(
                    icon,
                    style = MochiIconStyle.Filled,
                    tint = iconContent,
                    modifier = Modifier.size(24.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            if (badge != null) {
                StatusPill(badge, scheme.primaryContainer, scheme.onPrimaryContainer)
            }
        }
        Spacer(Modifier.weight(1f))
        Text(title, style = textStyles.titleMedium)
        Text(
            summary,
            style = textStyles.bodySmall,
            color = scheme.onSurfaceVariant,
            maxLines = 2,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
