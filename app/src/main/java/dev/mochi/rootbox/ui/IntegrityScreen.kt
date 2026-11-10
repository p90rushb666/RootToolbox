package dev.mochi.rootbox.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.mochi.rootbox.AppState
import dev.mochi.rootbox.AppViewModel
import dev.mochi.rootbox.core.IntegrityFailureKind
import dev.mochi.rootbox.core.IntegrityVerdict
import dev.mochi.rootbox.data.AppSettings
import ui.feice.mochi.basic.MochiActivityIndicator
import ui.feice.mochi.basic.MochiButton
import ui.feice.mochi.basic.MochiButtonStyle
import ui.feice.mochi.basic.Text
import ui.feice.mochi.icons.MochiIonicon
import ui.feice.mochi.list.MochiListCard
import ui.feice.mochi.list.MochiListRow
import ui.feice.mochi.list.mochiListSection
import ui.feice.mochi.navigation.MochiLazyCompactTitleScaffold
import ui.feice.mochi.theme.MochiTheme

/**
 * Play Integrity 结果页。
 *
 * 这里只展示服务端解码回来的字段：token 是加密的，客户端解不开，也不做任何本地推断。
 * 端点没配置、GMS 缺失、网络不通时一律显示「无法判定」，绝不给出三绿结论。
 */
@Composable
fun IntegrityScreen(
    state: AppState,
    settings: AppSettings,
    viewModel: AppViewModel,
    navigationIcon: @Composable () -> Unit,
    showToast: (String) -> Unit,
) {
    val scheme = MochiTheme.colorScheme
    val outcome = state.integrity
    val configured = settings.integrityEndpoint.isNotBlank() && settings.integrityProjectNumber.isNotBlank()
    var rawExpanded by remember { mutableStateOf(false) }

    MochiLazyCompactTitleScaffold(
        title = "完整性检测",
        subtitle = when {
            state.integrityRunning -> state.integrityPhase.ifBlank { "检测中…" }
            outcome?.ok == true -> "已取得 Google 判定"
            outcome != null -> "无法判定"
            else -> "Play Integrity 实时判定"
        },
        navigationIcon = navigationIcon,
    ) {
        if (!configured) {
            item(key = "not-configured") {
                MochiListCard {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 16.dp),
                    ) {
                        Text("未配置解码服务", style = MochiTheme.textStyles.bodyLarge)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            buildString {
                                appendLine("Play Integrity 返回的是加密 token，客户端无法解密，必须由服务端用 Google Cloud 服务账号调用 decodeIntegrityToken。")
                                appendLine()
                                append("请到「设置 → 完整性检测」填入解码服务端点与 Google Cloud 项目编号。在配置之前，本页不会给出任何判定结果。")
                            },
                            style = MochiTheme.textStyles.bodyMedium,
                            color = scheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        MochiButton(
                            onClick = { viewModel.runIntegrityCheck() },
                            style = MochiButtonStyle.Tonal,
                        ) { Text("仍要检查（会提示未配置）") }
                    }
                }
            }
        }

        item(key = "run") {
            MochiListCard {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 16.dp),
                ) {
                    if (state.integrityRunning) {
                        Column(Modifier.fillMaxWidth()) {
                            MochiActivityIndicator(size = 22.dp)
                            Spacer(Modifier.height(10.dp))
                            Text(
                                state.integrityPhase.ifBlank { "检测中…" },
                                style = MochiTheme.textStyles.bodyMedium,
                                color = scheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        MochiButton(
                            onClick = { viewModel.runIntegrityCheck() },
                            style = MochiButtonStyle.Filled,
                            enabled = configured,
                        ) {
                            Text(
                                when {
                                    !configured -> "需要先配置解码服务"
                                    outcome == null -> "开始检测"
                                    else -> "重新检测"
                                },
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "检测会从 Play 服务取一个新的 token，并发送到 ${
                                settings.integrityEndpoint.ifBlank { "（未配置）" }
                            }。每次检测都会重新生成 nonce，服务端会校验它与时间戳。",
                            style = MochiTheme.textStyles.footnote,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        val failure = outcome?.failure
        if (failure != null) {
            item(key = "failure") {
                MochiListCard {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 16.dp),
                    ) {
                        Text(
                            "无法判定",
                            style = MochiTheme.textStyles.footnote,
                            color = scheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            failure.title,
                            style = MochiTheme.textStyles.bodyLarge,
                            color = scheme.error,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            failure.detail,
                            style = MochiTheme.textStyles.bodyMedium,
                            color = scheme.onSurfaceVariant,
                        )
                        if (failure.code != null) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "官方错误码：${failure.code}",
                                style = MochiTheme.textStyles.footnote,
                                color = scheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(14.dp))
                        MochiButton(
                            onClick = { viewModel.runIntegrityCheck() },
                            style = MochiButtonStyle.Tonal,
                        ) { Text("重试") }
                        Spacer(Modifier.height(10.dp))
                        Text(
                            when (failure.kind) {
                                IntegrityFailureKind.NETWORK_GOOGLE,
                                IntegrityFailureKind.NETWORK_ENDPOINT,
                                -> "网络类失败不代表设备未通过判定，只代表这次没拿到结果。"
                                else -> "失败时不会展示任何三绿结论。"
                            },
                            style = MochiTheme.textStyles.footnote,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        val verdict = outcome?.verdict
        if (verdict != null) {
            mochiListSection(
                key = "verdict",
                header = "判定结果",
                footer = "字段原样来自 Google 的 decodeIntegrityToken 响应，App 不做二次推断。",
            ) {
                row(key = "app") {
                    MochiListRow(
                        title = "应用完整性",
                        subtitle = appVerdictExplanation(verdict.appRecognitionVerdict),
                        value = verdict.appRecognitionVerdict ?: "未返回",
                        destructive = verdict.appRecognitionVerdict == "UNRECOGNIZED",
                    )
                }
                row(key = "device") {
                    MochiListRow(
                        title = "设备完整性",
                        subtitle = verdict.deviceRecognitionVerdict.joinToString("、").ifBlank { "未返回任何标签" },
                        value = if (verdict.deviceRecognitionVerdict.contains("MEETS_DEVICE_INTEGRITY")) "通过" else "未通过",
                        destructive = !verdict.deviceRecognitionVerdict.contains("MEETS_DEVICE_INTEGRITY"),
                    )
                }
                row(key = "basic") {
                    MochiListRow("MEETS_BASIC_INTEGRITY", value = triState(verdict.meetsBasicIntegrity))
                }
                row(key = "strong") {
                    MochiListRow("MEETS_STRONG_INTEGRITY", value = triState(verdict.meetsStrongIntegrity))
                }
                verdict.accountLicensingVerdict?.let { licensing ->
                    row(key = "licensing") {
                        MochiListRow("账号许可", value = licensing)
                    }
                }
            }

            mochiListSection(
                key = "source",
                header = "判定来源",
                footer = "侧载安装时 appRecognitionVerdict 通常不是 PLAY_RECOGNIZED，这不等于设备完整性失败。",
            ) {
                row(key = "time") {
                    MochiListRow("判定时间", value = formatDateTime(verdict.timestampMillis ?: verdict.checkedAt))
                }
                row(key = "endpoint") {
                    MochiListRow(
                        title = "解码端点",
                        subtitle = verdict.endpoint,
                        value = "已配置",
                    )
                }
                verdict.requestPackageName?.let {
                    row(key = "pkg") { MochiListRow("requestPackageName", value = it) }
                }
                verdict.versionCode?.let {
                    row(key = "vc") { MochiListRow("versionCode", value = it) }
                }
                if (verdict.certificateSha256.isNotEmpty()) {
                    row(key = "cert") {
                        MochiListRow(
                            title = "证书摘要",
                            subtitle = verdict.certificateSha256.joinToString("\n"),
                        )
                    }
                }
            }

            mochiListSection(key = "raw") {
                row(key = "toggle") {
                    MochiListRow(
                        title = "原始 JSON",
                        subtitle = if (rawExpanded) "点击收起" else "点击展开服务端返回的完整响应",
                        chevron = true,
                        onClick = { rawExpanded = !rawExpanded },
                    )
                }
                if (rawExpanded) {
                    row(key = "body") {
                        Text(
                            verdict.rawJson,
                            style = MochiTheme.textStyles.footnote,
                            color = scheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 18.dp, vertical = 12.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(scheme.surfaceVariant.copy(alpha = 0.5f))
                                .padding(12.dp),
                        )
                    }
                }
            }
        }

        if (outcome != null && verdict == null && failure == null) {
            item(key = "unknown") {
                MochiListCard {
                    MochiListRow(title = "无法判定", subtitle = "本次没有拿到可用结果。")
                }
            }
        }
    }
}

private fun triState(value: Boolean?): String = when (value) {
    true -> "通过"
    false -> "未通过"
    null -> "服务端未返回"
}

private fun appVerdictExplanation(value: String?): String = when (value) {
    "PLAY_RECOGNIZED" -> "Google Play 识别到该应用是已知版本。"
    "UNRECOGNIZED_VERSION" -> "应用包名已知，但版本 / 签名未在 Play 备案。"
    "UNRECOGNIZED" -> "Google 不认识这个应用，通常是侧载或改包导致；设备完整性仍可能通过。"
    null -> "服务端未返回该字段。"
    else -> value
}
