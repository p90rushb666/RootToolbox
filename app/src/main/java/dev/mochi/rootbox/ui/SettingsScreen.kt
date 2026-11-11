package dev.mochi.rootbox.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.mochi.rootbox.AppViewModel
import dev.mochi.rootbox.data.AppSettings
import dev.mochi.rootbox.data.DarkMode
import dev.mochi.rootbox.data.SeedColors
import kotlinx.collections.immutable.persistentListOf
import ui.feice.mochi.basic.MochiButton
import ui.feice.mochi.basic.MochiChip
import ui.feice.mochi.basic.MochiChipGroup
import ui.feice.mochi.basic.MochiIconButton
import ui.feice.mochi.basic.MochiSegmentedControl
import ui.feice.mochi.basic.MochiSwitch
import ui.feice.mochi.basic.MochiTextField
import ui.feice.mochi.basic.Text
import ui.feice.mochi.icons.MochiIonicon
import ui.feice.mochi.list.MochiListRow
import ui.feice.mochi.list.mochiListSection
import ui.feice.mochi.navigation.LocalMochiBottomBarInset
import ui.feice.mochi.navigation.MochiLazyCompactTitleScaffold
import ui.feice.mochi.theme.MochiTheme

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
            row(key = "auto-root") {
                MochiListRow(
                    title = "启动时检测",
                    subtitle = "每次重新打开 App 才检测 Root 与密钥，切换后台不检测",
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
            row(key = "keep-backups") {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                ) {
                    Text("保留最近 N 个备份", style = MochiTheme.textStyles.bodyLarge)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "批量清理时只保留最新的 N 个 .bak 文件",
                        style = MochiTheme.textStyles.footnote,
                        color = MochiTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        MochiIconButton(
                            onClick = { viewModel.setKeepBackups(settings.keepBackups - 1) },
                            style = ui.feice.mochi.basic.MochiButtonStyle.Tonal,
                            size = 36.dp,
                        ) { MochiIonicon("remove", contentDescription = "减少") }
                        Text("${settings.keepBackups} 个", style = MochiTheme.textStyles.bodyLarge)
                        MochiIconButton(
                            onClick = { viewModel.setKeepBackups(settings.keepBackups + 1) },
                            style = ui.feice.mochi.basic.MochiButtonStyle.Tonal,
                            size = 36.dp,
                        ) { MochiIonicon("add", contentDescription = "增加") }
                    }
                }
            }
        }

        mochiListSection(
            key = "security",
            header = "安全",
            footer = "开启后，每次真正写入前都要验证一次设备锁屏凭据。",
        ) {
            row(key = "credential") {
                MochiListRow(
                    title = "写入前校验设备凭据",
                    subtitle = "使用锁屏 PIN / 图案 / 生物识别",
                    trailing = { MochiSwitch(settings.requireCredential, { viewModel.setRequireCredential(it) }) },
                )
            }
        }

        mochiListSection(
            key = "attestation",
            header = "密钥检测",
            footer = "使用 Android 官方密钥认证：生成一次性密钥、校验证书链到 Google 认证根，并核对登录吊销列表。无需 Root。",
        ) {
            row(key = "details") {
                MochiListRow(
                    title = "显示证书链与明细",
                    subtitle = "关闭后只显示「有效 / 无效」结论",
                    trailing = {
                        MochiSwitch(settings.attestationShowDetails, { viewModel.setAttestationShowDetails(it) })
                    },
                )
            }
            row(key = "timeout") {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                ) {
                    Text("检测超时", style = MochiTheme.textStyles.bodyLarge)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "个别设备 Keystore 可能卡住，超时后显示「无法检测」",
                        style = MochiTheme.textStyles.footnote,
                        color = MochiTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    MochiSegmentedControl(
                        selectedIndex = when (settings.attestationTimeoutSec) {
                            5 -> 0
                            20 -> 2
                            else -> 1
                        },
                        onSelect = { index ->
                            viewModel.setAttestationTimeout(
                                when (index) {
                                    0 -> 5
                                    2 -> 20
                                    else -> 10
                                },
                            )
                        },
                        items = persistentListOf("5 秒", "10 秒", "20 秒"),
                    )
                }
            }
        }

        item(key = "bottom-space") {
            Spacer(Modifier.height(LocalMochiBottomBarInset.current + 24.dp))
        }
    }
}
