package dev.mochi.rootbox.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.mochi.rootbox.AppViewModel
import dev.mochi.rootbox.data.AppSettings
import dev.mochi.rootbox.data.DarkMode
import dev.mochi.rootbox.data.SeedColors
import kotlinx.collections.immutable.persistentListOf
import ui.feice.mochi.basic.MochiChip
import ui.feice.mochi.basic.MochiChipGroup
import ui.feice.mochi.basic.MochiSegmentedControl
import ui.feice.mochi.basic.MochiSwitch
import ui.feice.mochi.list.MochiListRow
import ui.feice.mochi.list.mochiListSection
import ui.feice.mochi.navigation.MochiLazyCompactTitleScaffold

@Composable
fun SettingsScreen(
    settings: AppSettings,
    viewModel: AppViewModel,
    navigationIcon: (@Composable () -> Unit)?,
    onOpenTargets: () -> Unit,
) {
    MochiLazyCompactTitleScaffold(
        title = "设置",
        subtitle = "外观与行为",
        navigationIcon = navigationIcon,
    ) {
        mochiListSection(
            key = "appearance",
            header = "外观",
            footer = if (settings.monet) "开启壁纸取色后主题色不生效。" else "浅色与深色共用同一种主题色。",
        ) {
            row(key = "mode") {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                ) {
                    MochiSegmentedControl(
                        selectedIndex = when (settings.darkMode) {
                            DarkMode.SYSTEM -> 0
                            DarkMode.LIGHT -> 1
                            DarkMode.DARK -> 2
                        },
                        onSelect = { index ->
                            viewModel.setDarkMode(
                                when (index) {
                                    1 -> DarkMode.LIGHT
                                    2 -> DarkMode.DARK
                                    else -> DarkMode.SYSTEM
                                },
                            )
                        },
                        items = persistentListOf("跟随系统", "浅色", "深色"),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            row(key = "monet") {
                MochiListRow(
                    title = "壁纸取色",
                    subtitle = "用系统壁纸自动生成配色",
                    trailing = { MochiSwitch(settings.monet, { viewModel.setMonet(it) }) },
                )
            }
            row(key = "seeds") {
                MochiChipGroup(
                    modifier = Modifier.fillMaxWidth(),
                    wrap = true,
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
                ) {
                    SeedColors.forEach { (name, value) ->
                        MochiChip(
                            label = name,
                            selected = settings.keyColor == value,
                            enabled = !settings.monet,
                            onClick = { viewModel.setKeyColor(value) },
                            leadingIcon = {
                                Canvas(Modifier.size(10.dp)) { drawCircle(Color(value)) }
                            },
                        )
                    }
                }
            }
            if (settings.keyColor != null && !settings.monet) {
                row(key = "reset-color") {
                    MochiListRow("恢复默认主题色", onClick = { viewModel.setKeyColor(null) })
                }
            }
        }

        mochiListSection(key = "general", header = "通用") {
            row(key = "refresh-open") {
                MochiListRow(
                    title = "打开时自动刷新",
                    subtitle = "每次回到前台都重新检测 Root",
                    trailing = { MochiSwitch(settings.refreshOnOpen, { viewModel.setRefreshOnOpen(it) }) },
                )
            }
            row(key = "auto-root") {
                MochiListRow(
                    title = "启动时检测 Root",
                    subtitle = "关闭后首页不主动请求授权",
                    trailing = { MochiSwitch(settings.autoCheckRoot, { viewModel.setAutoCheckRoot(it) }) },
                )
            }
            row(key = "archived") {
                MochiListRow(
                    title = "默认显示归档",
                    subtitle = "密钥库默认展示已过期与已吊销条目",
                    trailing = {
                        MochiSwitch(settings.showArchivedByDefault, { viewModel.setShowArchivedDefault(it) })
                    },
                )
            }
        }

        mochiListSection(key = "interaction", header = "交互") {
            row(key = "predictive") {
                MochiListRow(
                    title = "预测性返回手势",
                    subtitle = "返回时页面跟手缩放",
                    trailing = { MochiSwitch(settings.predictiveBack, { viewModel.setPredictiveBack(it) }) },
                )
            }
            row(key = "green-badge") {
                MochiListRow(
                    title = "显示三绿标识",
                    subtitle = "密钥列表里标注三绿评级",
                    trailing = { MochiSwitch(settings.showGreenBadge, { viewModel.setShowGreenBadge(it) }) },
                )
            }
        }

        mochiListSection(key = "keybox", header = "密钥") {
            row(key = "target") {
                MochiListRow(
                    title = "替换目标",
                    subtitle = "决定 keybox.xml 写入哪里",
                    value = "查看",
                    chevron = true,
                    onClick = onOpenTargets,
                )
            }
        }
    }
}
