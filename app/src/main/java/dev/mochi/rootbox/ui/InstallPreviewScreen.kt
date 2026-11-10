package dev.mochi.rootbox.ui

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.mochi.rootbox.AppState
import dev.mochi.rootbox.AppViewModel
import dev.mochi.rootbox.data.AppSettings
import dev.mochi.rootbox.data.GreenRating
import dev.mochi.rootbox.data.InstallPreview
import dev.mochi.rootbox.data.KeyboxStatus
import kotlinx.collections.immutable.persistentListOf
import ui.feice.mochi.basic.MochiButton
import ui.feice.mochi.basic.MochiButtonStyle
import ui.feice.mochi.basic.MochiDialog
import ui.feice.mochi.basic.MochiDialogAction
import ui.feice.mochi.basic.MochiDialogActionRole
import ui.feice.mochi.basic.Text
import ui.feice.mochi.icons.MochiIonicon
import ui.feice.mochi.list.MochiListRow
import ui.feice.mochi.list.mochiListSection
import ui.feice.mochi.navigation.MochiLazyCompactTitleScaffold
import ui.feice.mochi.theme.MochiTheme

/**
 * 阶段 A 的预览页：把即将写入的东西全部摊开给用户看，点「确认替换」之前不碰设备。
 */
@Composable
fun InstallPreviewScreen(
    state: AppState,
    settings: AppSettings,
    viewModel: AppViewModel,
    navigationIcon: @Composable () -> Unit,
    onDone: () -> Unit,
    showToast: (String) -> Unit,
) {
    val preview = state.preview
    var confirmVisible by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // 离开预览页（返回 / 跳转）即视为放弃：清掉内存里的字节。
    // 否则返回密钥库时预览仍留在 state 里，会被 KeysScreen 再次 push，形成返回死循环。
    DisposableEffect(Unit) {
        onDispose { viewModel.cancelPreview() }
    }

    fun reallyInstall() {
        viewModel.confirmPreview()
        onDone()
    }

    val credentialLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            reallyInstall()
        } else {
            showToast("未通过设备凭据校验，已取消写入")
        }
    }

    fun requestInstall() {
        if (!settings.requireCredential) {
            reallyInstall()
            return
        }
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        if (keyguard == null || !keyguard.isDeviceSecure) {
            showToast("设备未设置锁屏，已跳过凭据校验")
            reallyInstall()
            return
        }
        val intent = keyguard.createConfirmDeviceCredentialIntent(
            "确认替换 keybox.xml",
            "即将修改 /data/adb 下的文件",
        )
        if (intent == null) {
            reallyInstall()
        } else {
            runCatching {
                credentialLauncher.launch(intent)
            }.onFailure {
                showToast("无法打开凭据校验，已取消")
            }
        }
    }

    MochiLazyCompactTitleScaffold(
        title = "安装预览",
        subtitle = if (preview?.canConfirm == true) "尚未写入，请确认后继续" else "仅供查看",
        navigationIcon = navigationIcon,
    ) {
        if (preview == null) {
            item(key = "empty") {
                ui.feice.mochi.list.MochiListCard {
                    MochiListRow(
                        title = "预览已失效",
                        subtitle = "返回列表重新选择一条密钥。",
                    )
                }
            }
            return@MochiLazyCompactTitleScaffold
        }

        mochiListSection(
            key = "source",
            header = "来源",
            footer = "下载内容只保存在内存，未写入磁盘。",
        ) {
            row(key = "origin") {
                MochiListRow(title = "来源", value = preview.origin, subtitle = preview.fileName)
            }
            row(key = "size") {
                MochiListRow("文件大小", value = formatBytes(preview.meta.size.toLong()))
            }
            row(key = "sha") {
                MochiListRow(
                    title = "SHA-256",
                    subtitle = preview.meta.sha256,
                    value = if (preview.expectedSha256 == null) "来源未提供" else "已比对",
                )
            }
        }

        mochiListSection(key = "content", header = "内容") {
            row(key = "device") {
                MochiListRow("DeviceID", value = preview.meta.deviceId ?: "未声明")
            }
            row(key = "algo") {
                MochiListRow("算法", value = preview.meta.algorithm ?: "未声明")
            }
            row(key = "certs") {
                MochiListRow(
                    title = "证书",
                    subtitle = preview.meta.declaredCertificates?.let { "声明 $it 张" },
                    value = "${preview.meta.certificateCount} 张",
                )
            }
            row(key = "expiry") {
                MochiListRow("最早到期", value = preview.meta.expiryText())
            }
            preview.indexItem?.let { item ->
                row(key = "index") {
                    MochiListRow(
                        title = "索引状态",
                        subtitle = "三绿：${greenText(item.green, item.greenDetail)}",
                        value = item.statusLabel,
                        destructive = item.status == KeyboxStatus.REVOKED,
                    )
                }
            }
        }

        mochiListSection(
            key = "device-now",
            header = "设备现状",
            footer = "替换前会把当前文件备份为 keybox.xml.bak.<时间戳>，失败会自动回滚。",
        ) {
            row(key = "current") {
                MochiListRow(
                    title = "当前文件",
                    subtitle = preview.currentSha?.let { "SHA-256 ${it.take(16)}…" } ?: "读取不到（将新建）",
                    value = preview.currentMeta?.deviceId ?: "—",
                )
            }
            if (preview.currentMeta != null && preview.meta.valid) {
                row(key = "diff") {
                    MochiListRow(
                        title = "差异",
                        subtitle = buildString {
                            val currentDevice = preview.currentMeta.deviceId
                            val newDevice = preview.meta.deviceId
                            if (currentDevice != newDevice) {
                                appendLine("DeviceID：${currentDevice ?: "未声明"} → ${newDevice ?: "未声明"}")
                            }
                            appendLine(
                                "证书数：${preview.currentMeta.certificateCount} → ${preview.meta.certificateCount}",
                            )
                            append(
                                "最早到期：${preview.currentMeta.expiryText()} → ${preview.meta.expiryText()}",
                            )
                        },
                    )
                }
            }
        }

        mochiListSection(
            key = "target",
            header = "写入位置",
            footer = "该路径位于 /data/adb，属于系统级目录；写入需要 Root 权限。",
        ) {
            row(key = "path") {
                MochiListRow(title = "完整目标路径", subtitle = preview.targetPath)
            }
        }

        if (preview.issues.isNotEmpty()) {
            item(key = "issues") {
                ui.feice.mochi.list.MochiListCard {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 16.dp),
                    ) {
                        Text(
                            "无法继续（${preview.issues.size}）",
                            style = MochiTheme.textStyles.bodyLarge,
                            color = MochiTheme.colorScheme.error,
                        )
                        preview.issues.forEach { issue ->
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "· $issue",
                                style = MochiTheme.textStyles.bodyMedium,
                                color = MochiTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        if (preview.warnings.isNotEmpty()) {
            item(key = "warnings") {
                ui.feice.mochi.list.MochiListCard {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 16.dp),
                    ) {
                        Text(
                            "提示（${preview.warnings.size}）",
                            style = MochiTheme.textStyles.bodyLarge,
                        )
                        preview.warnings.forEach { warning ->
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "· $warning",
                                style = MochiTheme.textStyles.bodyMedium,
                                color = MochiTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        item(key = "actions") {
            ui.feice.mochi.list.MochiListCard {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 16.dp),
                ) {
                    val enabled = preview.canConfirm
                    MochiButton(
                        onClick = { confirmVisible = true },
                        style = MochiButtonStyle.Filled,
                        enabled = enabled,
                    ) {
                        Text(if (enabled) "确认替换" else "校验未通过，无法替换")
                    }
                    Spacer(Modifier.height(10.dp))
                    MochiButton(
                        onClick = {
                            viewModel.cancelPreview()
                            onDone()
                            showToast("已取消，未修改设备")
                        },
                        style = MochiButtonStyle.Tonal,
                    ) { Text("仅查看，不写入") }
                    if (!enabled) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "请返回列表换一个来源；结构无效或哈希不一致的内容不会写入设备。",
                            style = MochiTheme.textStyles.footnote,
                            color = MochiTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    MochiDialog(
        visible = confirmVisible,
        onDismissRequest = { confirmVisible = false },
        title = "确认替换？",
        message = buildString {
            appendLine("目标路径：")
            appendLine(preview?.targetPath ?: "—")
            appendLine()
            appendLine("这会修改 /data/adb 下的系统级文件；应用会先备份当前文件，再写入并回读校验，失败自动回滚。")
            if (settings.requireCredential) {
                appendLine()
                append("下一步需要验证设备凭据。")
            }
        },
        actions = persistentListOf(
            MochiDialogAction("取消", MochiDialogActionRole.Cancel),
            MochiDialogAction(
                label = "确认替换",
                role = MochiDialogActionRole.Primary,
                onClick = {
                    confirmVisible = false
                    requestInstall()
                },
            ),
        ),
    )
}

private fun greenText(rating: GreenRating, detail: String?): String = when (rating) {
    GreenRating.THREE_GREEN -> "是（来源评级 3/3）"
    GreenRating.NOT_THREE -> "否（${detail ?: "来源评级不足 3/3"}）"
    GreenRating.UNVERIFIED -> "未验证（${detail ?: "来源未提供评级"}）"
}
