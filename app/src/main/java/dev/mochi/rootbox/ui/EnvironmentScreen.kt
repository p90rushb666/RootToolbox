package dev.mochi.rootbox.ui

import androidx.compose.runtime.Composable
import dev.mochi.rootbox.AppState
import dev.mochi.rootbox.AppViewModel
import ui.feice.mochi.list.MochiListButton
import ui.feice.mochi.list.MochiListRow
import ui.feice.mochi.list.MochiListStatusChip
import ui.feice.mochi.list.mochiListSection
import ui.feice.mochi.navigation.MochiLazyCompactTitleScaffold
import ui.feice.mochi.theme.MochiTheme

@Composable
fun EnvironmentScreen(
    state: AppState,
    viewModel: AppViewModel,
    navigationIcon: @Composable () -> Unit,
) {
    val root = state.root
    val granted = root?.granted == true

    MochiLazyCompactTitleScaffold(
        title = "运行环境",
        subtitle = "Root 权限与设备信息",
        navigationIcon = navigationIcon,
    ) {
        mochiListSection(
            key = "root",
            header = "Root 权限",
            footer = if (granted) null else "未授权时无法读取 /data/adb，也无法替换 keybox.xml。",
        ) {
            row(key = "status") {
                MochiListRow(
                    title = "授权状态",
                    subtitle = root?.message,
                    value = when {
                        state.rootChecking -> "检测中"
                        granted -> "已授权"
                        else -> "未授权"
                    },
                    status = {
                        if (!state.rootChecking) {
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
                )
            }
            row(key = "uid") {
                MochiListRow("Shell 身份", value = root?.uid ?: "未知")
            }
            row(key = "su") {
                MochiListRow("su 路径", value = root?.suPath ?: "未找到")
            }
            if (!granted && !state.rootChecking) {
                row(key = "retry") {
                    MochiListButton("重新请求 Root 授权", onClick = { viewModel.refreshRoot() })
                }
            }
        }

        mochiListSection(key = "system", header = "系统") {
            row(key = "manager") {
                MochiListRow(
                    title = "Root 管理方式",
                    subtitle = root?.managerDetail,
                    value = root?.manager ?: "未知",
                )
            }
            row(key = "selinux") {
                MochiListRow("SELinux", value = root?.selinux ?: "未知")
            }
            row(key = "device") {
                MochiListRow(
                    title = "设备",
                    subtitle = root?.deviceModel,
                    value = root?.androidVersion ?: "未知",
                )
            }
        }
    }
}
