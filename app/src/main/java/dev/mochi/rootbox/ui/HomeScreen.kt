package dev.mochi.rootbox.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.mochi.rootbox.AppState
import dev.mochi.rootbox.AppViewModel
import dev.mochi.rootbox.core.AttestationVerdict
import ui.feice.mochi.basic.MochiBarGroup
import ui.feice.mochi.basic.MochiBarGroupItem
import ui.feice.mochi.basic.MochiButton
import ui.feice.mochi.basic.MochiButtonStyle
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
import ui.feice.mochi.navigation.LocalMochiBottomBarInset
import ui.feice.mochi.navigation.MochiLazyLargeTitleScaffold
import ui.feice.mochi.navigation.mochiNavSource
import ui.feice.mochi.theme.MochiDefaults
import ui.feice.mochi.theme.MochiTheme

// 与 RootToolboxApp 里的 nav.push(..., from = ...) 保持一致，用于打开式转场。
private const val SourceEnvironment = "home/environment"
private const val SourceModule = "home/module"
private const val SourceDiagnostics = "home/diagnostics"
private const val SourceAttestation = "home/attestation"
private const val SourceModuleUpdates = "home/module-updates"
private const val SourceModuleCatalog = "home/module-catalog"
private const val SourceDexopt = "home/dexopt"
// 编译进度卡与 grid 里的 dex2oat 卡片都跳 dex2oat 页，但 source key 必须分开，
// 否则两个 mochiNavSource 会在导航注册表里互相覆盖，导致打开式转场从错误的卡片展开。
private const val SourceDexoptStatus = "home/dexopt-status"

@Composable
fun HomeScreen(
    state: AppState,
    viewModel: AppViewModel,
    onOpenEnvironment: () -> Unit,
    onOpenModule: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenAttestation: () -> Unit,
    onOpenModuleUpdates: () -> Unit,
    onOpenModuleCatalog: () -> Unit,
    onOpenDexopt: () -> Unit,
    onOpenDexoptStatus: () -> Unit,
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
                ) {
                    // 只有检测中才启动无限动画，静止时不产生每帧重组。
                    if (state.rootChecking) {
                        val spin = rememberInfiniteTransition(label = "refresh")
                        val angle by spin.animateFloat(
                            initialValue = 0f,
                            targetValue = 360f,
                            animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
                            label = "refresh-angle",
                        )
                        MochiIonicon(
                            "refresh",
                            contentDescription = "重新检测",
                            modifier = Modifier.rotate(angle),
                        )
                    } else {
                        MochiIonicon("refresh", contentDescription = "重新检测")
                    }
                }
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

        // 编译进行中 / 有上次任务时，在首页顶部醒目展示进度，点进 dex2oat 页。
        val dexoptUi = state.dexopt
        val savedJob = dexoptUi.savedJob
        if (dexoptUi.runtime.running || savedJob != null) {
            item(key = "compile-status") {
                MochiListCard(modifier = Modifier.mochiNavSource(SourceDexoptStatus, cardCorner, cardColor)) {
                    MochiListRow(
                        title = "dex2oat 编译",
                        subtitle = when {
                            dexoptUi.runtime.running ->
                                dexoptUi.runtime.currentLabel?.let { "正在编译 $it" } ?: "正在编译…"
                            savedJob?.finished == true ->
                                "已完成：成功 ${savedJob.done.size} · 失败 ${savedJob.failed.size}"
                            else -> "上次未完成：剩余 ${savedJob?.remaining()?.size ?: 0} 个应用"
                        },
                        value = when {
                            dexoptUi.runtime.running -> "${dexoptUi.runtime.done + dexoptUi.runtime.failed}/${dexoptUi.runtime.total}"
                            savedJob?.finished == true -> "完成"
                            else -> "继续"
                        },
                        leading = { MochiIonicon("flash", style = MochiIconStyle.Filled) },
                        chevron = true,
                        status = {
                            if (dexoptUi.runtime.running) {
                                MochiListStatusChip(
                                    text = "编译中",
                                    containerColor = scheme.primaryContainer,
                                    contentColor = scheme.onPrimaryContainer,
                                )
                            } else if (savedJob?.finished == true) {
                                MochiListStatusChip(
                                    text = "完成",
                                    containerColor = scheme.primaryContainer,
                                    contentColor = scheme.onPrimaryContainer,
                                )
                            }
                        },
                        onClick = onOpenDexoptStatus,
                    )
                }
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
                val attestation = state.attestation
                HomeGridCard(
                    modifier = Modifier.weight(1f),
                    title = "密钥检测",
                    summary = "官方密钥认证 · 无需 Root",
                    icon = "shield-checkmark",
                    iconContainer = scheme.primaryContainer,
                    iconContent = scheme.onPrimaryContainer,
                    sourceKey = SourceAttestation,
                    badge = when {
                        state.attestationRunning -> "检测中"
                        attestation == null -> "未检测"
                        attestation.valid -> "有效"
                        attestation.verdict == AttestationVerdict.UNVERIFIED -> "未校验"
                        else -> "无效"
                    },
                    badgeContainer = when {
                        state.attestationRunning || attestation == null -> scheme.surfaceVariant
                        attestation.valid -> scheme.primaryContainer
                        attestation.verdict == AttestationVerdict.UNVERIFIED -> scheme.surfaceVariant
                        else -> scheme.errorContainer
                    },
                    badgeContent = when {
                        state.attestationRunning || attestation == null -> scheme.onSurfaceVariant
                        attestation.valid -> scheme.onPrimaryContainer
                        attestation.verdict == AttestationVerdict.UNVERIFIED -> scheme.onSurfaceVariant
                        else -> scheme.onErrorContainer
                    },
                    action = {
                        MochiButton(
                            onClick = { viewModel.runAttestation() },
                            style = MochiButtonStyle.Tonal,
                            enabled = !state.attestationRunning,
                        ) {
                            Text(
                                when {
                                    state.attestationRunning -> "检测中…"
                                    attestation == null -> "检测"
                                    else -> "重新检测"
                                },
                            )
                        }
                    },
                    onClick = onOpenAttestation,
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
                    summary = "检查新版本、下载与管理",
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
                    title = "模块下载",
                    summary = "常见模块与管理器",
                    icon = "download",
                    iconContainer = scheme.primaryContainer,
                    iconContent = scheme.onPrimaryContainer,
                    sourceKey = SourceModuleCatalog,
                    badge = "GitHub",
                    onClick = onOpenModuleCatalog,
                )
            }
        }

        item(key = "grid-3") {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                HomeGridCard(
                    modifier = Modifier.weight(1f),
                    title = "dex2oat 编译",
                    summary = "应用编译 · 模式与线程",
                    icon = "flash",
                    iconContainer = scheme.primaryContainer,
                    iconContent = scheme.onPrimaryContainer,
                    sourceKey = SourceDexopt,
                    onClick = onOpenDexopt,
                )
                HomeGridCard(
                    modifier = Modifier.weight(1f),
                    title = "诊断",
                    summary = "运行日志、崩溃报告与复制",
                    icon = "document-text",
                    iconContainer = scheme.primaryContainer,
                    iconContent = scheme.onPrimaryContainer,
                    sourceKey = SourceDiagnostics,
                    onClick = onOpenDiagnostics,
                )
            }
        }

        // 底部留出底栏高度 + 额外间距，避免最后一行卡片与底部标签栏重叠。
        item(key = "bottom-space") {
            Spacer(Modifier.height(LocalMochiBottomBarInset.current + 28.dp))
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
    badgeContainer: Color? = null,
    badgeContent: Color? = null,
    action: (@Composable () -> Unit)? = null,
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
            .height(196.dp)
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
                StatusPill(
                    text = badge,
                    containerColor = badgeContainer ?: scheme.primaryContainer,
                    contentColor = badgeContent ?: scheme.onPrimaryContainer,
                )
            }
        }
        Spacer(Modifier.weight(1f))
        Text(title, style = textStyles.titleMedium)
        Text(
            summary,
            style = textStyles.bodySmall,
            color = scheme.onSurfaceVariant,
            maxLines = if (action == null) 2 else 1,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (action != null) {
            Spacer(Modifier.height(8.dp))
            action()
        }
    }
}
