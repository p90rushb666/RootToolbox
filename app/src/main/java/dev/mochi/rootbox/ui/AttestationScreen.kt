package dev.mochi.rootbox.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.mochi.rootbox.AppState
import dev.mochi.rootbox.AppViewModel
import dev.mochi.rootbox.core.AttestationChecker
import dev.mochi.rootbox.core.AttestationResult
import dev.mochi.rootbox.core.BootTone
import dev.mochi.rootbox.core.RootKind
import dev.mochi.rootbox.data.AppSettings
import ui.feice.mochi.basic.MochiActivityIndicator
import ui.feice.mochi.basic.Text
import ui.feice.mochi.icons.MochiIonicon
import ui.feice.mochi.list.MochiListRow
import ui.feice.mochi.list.mochiListSection
import ui.feice.mochi.navigation.LocalMochiBottomBarInset
import ui.feice.mochi.navigation.MochiLazyCompactTitleScaffold
import ui.feice.mochi.theme.MochiTheme

private const val KEY_ATTESTATION_PROJECT = "https://github.com/vvb2060/KeyAttestation"

/**
 * 密钥认证：取设备自己的 Google 认证密钥（AndroidKeyStore + attestation），
 * 顶部显示根证书等级（例如「谷歌远程密钥配置」），下面显示引导加载程序是否锁定。
 *
 * 不读取 keybox.xml、不需要 Root；证书链与吊销状态都是软件层校验，其中吊销列表需要联网。
 */
@Composable
fun AttestationScreen(
    state: AppState,
    settings: AppSettings,
    viewModel: AppViewModel,
    navigationIcon: @Composable () -> Unit,
    showToast: (String) -> Unit,
) {
    val result = state.attestation
    val running = state.attestationRunning
    val scheme = MochiTheme.colorScheme
    val context = LocalContext.current
    val showDetails = settings.attestationShowDetails

    MochiLazyCompactTitleScaffold(
        title = "密钥检测",
        subtitle = when {
            running -> "正在向系统 Keystore 申请认证…"
            result == null -> "尚未检测"
            else -> "检测于 ${AttestationChecker.formatTime(result.checkedAt)}"
        },
        navigationIcon = navigationIcon,
    ) {
        if (result == null) {
            item(key = "loading") {
                MochiStatusCard(
                    icon = if (running) "sync" else "shield-checkmark",
                    title = if (running) "检测中" else "未检测",
                    summary = if (running) {
                        "正在生成一次性密钥并校验证书链，请稍候…"
                    } else {
                        "重新打开应用或回到首页点「检测」开始。"
                    },
                    container = scheme.surfaceContainer,
                    content = scheme.onSurfaceVariant,
                    trailing = if (running) {
                        { MochiActivityIndicator(size = 22.dp) }
                    } else null,
                )
            }
        }

        if (result != null) {
            // 顶部第一块：根证书等级，文字与参考实现一致（谷歌远程密钥配置 / 谷歌硬件认证根证书 …）。
            item(key = "grade") {
                val tone = gradeTone(result, scheme)
                MochiStatusCard(
                    icon = when (result.rootKind) {
                        RootKind.GOOGLE, RootKind.GOOGLE_RKP -> "shield-checkmark"
                        RootKind.AOSP -> "warning"
                        RootKind.KNOX, RootKind.OEM -> "alert-circle"
                        else -> "alert-circle"
                    },
                    title = result.grade,
                    summary = result.gradeSummary,
                    container = tone.first,
                    content = tone.second,
                )
            }

            // 紧接着的第二块：引导加载程序是否锁定。
            item(key = "boot") {
                val tone = when (result.bootTone) {
                    BootTone.SAFE -> scheme.primaryContainer to scheme.onPrimaryContainer
                    BootTone.WARN -> scheme.tertiaryContainer to scheme.onTertiaryContainer
                    BootTone.NEUTRAL -> scheme.surfaceContainer to scheme.onSurfaceVariant
                }
                MochiStatusCard(
                    icon = when (result.bootTone) {
                        BootTone.SAFE -> "lock-closed"
                        BootTone.WARN -> "lock-open"
                        BootTone.NEUTRAL -> "lock-open"
                    },
                    title = result.bootTitle,
                    summary = result.bootSummary,
                    container = tone.first,
                    content = tone.second,
                )
            }

            if (result.error != null) {
                mochiListSection(key = "error", header = "结论") {
                    row(key = "error-value") {
                        MochiListRow(
                            title = "无法确认为有效密钥",
                            subtitle = result.error,
                            destructive = true,
                            leading = { MochiIonicon("alert-circle") },
                        )
                    }
                }
            }

            if (showDetails && result.certs.isNotEmpty()) {
                mochiListSection(
                    key = "chain",
                    header = "证书链",
                    footer = "从该密钥的证书开始，每个证书均由链中下一个证书签名，到根证书为止。",
                ) {
                    result.certs.forEach { cert ->
                        row(key = "cert-${cert.index}") {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 18.dp, vertical = 14.dp),
                            ) {
                                CertLine("使用者", cert.subject)
                                CertLine("不早于", AttestationChecker.formatTime(cert.notBefore))
                                CertLine("不晚于", AttestationChecker.formatTime(cert.notAfter))
                                CertLine("序列号", cert.serial, monospace = true)
                                CertLine(
                                    "有效性",
                                    if (cert.valid) "有效" else "无效 - ${cert.problems.joinToString("; ")}",
                                    color = if (cert.valid) scheme.onSurfaceVariant else scheme.error,
                                )
                            }
                        }
                    }
                }

                mochiListSection(key = "tee", header = "可信执行环境") {
                    row(key = "tee-value") {
                        MochiListRow(
                            title = "可信执行环境",
                            value = result.teeStatus ?: "未知",
                            destructive = result.teeStatus?.startsWith("异常") == true,
                        )
                    }
                }

                mochiListSection(key = "fingerprint", header = "构建指纹") {
                    row(key = "fingerprint-value") {
                        WrappedValueRow("构建指纹", result.fingerprint)
                    }
                }

                mochiListSection(key = "trust-root", header = "信任根") {
                    row(key = "boot-key") {
                        WrappedValueRow(
                            "VerifiedBootKey",
                            result.verifiedBootKey ?: "—",
                            monospace = true,
                        )
                    }
                    row(key = "boot-hash") {
                        WrappedValueRow(
                            "VerifiedBootHash",
                            result.verifiedBootHash ?: "—",
                            monospace = true,
                        )
                    }
                    row(key = "boot-state") {
                        MochiListRow(
                            title = "VerifiedBootState",
                            value = result.verifiedBootState ?: "未知",
                        )
                    }
                    row(key = "device-locked") {
                        MochiListRow(
                            title = "deviceLocked",
                            value = result.deviceLocked?.toString() ?: "未知",
                        )
                    }
                }

                mochiListSection(key = "challenge", header = "认证质询") {
                    row(key = "challenge-value") {
                        WrappedValueRow("认证质询", result.challenge ?: "—")
                    }
                }

                mochiListSection(
                    key = "revocation",
                    header = "吊销状态",
                    footer = "联网比对 Google 官方吊销列表（android.googleapis.com/attestation/status）；网络不可用时使用内置快照，并在结论里标明。",
                ) {
                    row(key = "revocation-value") {
                        MochiListRow(
                            title = "证书吊销校验",
                            subtitle = result.revocation ?: "未校验",
                            value = when {
                                result.revocation == null -> "未知"
                                result.revocation.contains("已吊销") -> "已吊销"
                                result.revocation.startsWith("无法") -> "未知"
                                else -> "通过"
                            },
                            destructive = result.revocation?.contains("已吊销") == true,
                        )
                    }
                }
            }

        }

        mochiListSection(key = "credit", header = "来源") {
            row(key = "note") {
                MochiListRow(
                    title = "检测在本机完成",
                    subtitle = "不读取 keybox.xml、不需要 Root；证书不上传，仅联网核对吊销列表。",
                )
            }
            // 开源署名放在页面最底部，符合参考项目的要求。
            row(key = "project") {
                MochiListRow(
                    title = "KeyAttestation",
                    subtitle = KEY_ATTESTATION_PROJECT,
                    value = "打开",
                    chevron = true,
                    leading = { MochiIonicon("logo-github") },
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(KEY_ATTESTATION_PROJECT))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }.onFailure { showToast("没有可打开链接的应用") }
                    },
                )
            }
        }

        item(key = "bottom-space") {
            Spacer(Modifier.height(LocalMochiBottomBarInset.current + 24.dp))
        }
    }
}

private fun gradeTone(
    result: AttestationResult,
    scheme: ui.feice.mochi.theme.MochiColorScheme,
): Pair<Color, Color> = when {
    result.error != null && result.grade == "无法认证" -> scheme.errorContainer to scheme.onErrorContainer
    result.rootKind == RootKind.GOOGLE || result.rootKind == RootKind.GOOGLE_RKP ->
        scheme.primaryContainer to scheme.onPrimaryContainer
    result.rootKind == RootKind.UNKNOWN || result.rootKind == RootKind.FAILED ->
        scheme.tertiaryContainer to scheme.onTertiaryContainer
    else -> scheme.tertiaryContainer to scheme.onTertiaryContainer
}

/** 顶部整块状态卡：大图标 + 标题 + 说明，对齐参考应用的样式。 */
@Composable
private fun MochiStatusCard(
    icon: String,
    title: String,
    summary: String?,
    container: Color,
    content: Color,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(container)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        MochiIonicon(icon, tint = content, modifier = Modifier.size(28.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MochiTheme.textStyles.titleMedium, color = content)
            if (!summary.isNullOrBlank()) {
                Text(
                    summary,
                    style = MochiTheme.textStyles.bodySmall,
                    color = content.copy(alpha = 0.85f),
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
        trailing?.invoke()
    }
}

/** 证书链里的一行：标签 + 可换行的值。 */
@Composable
private fun CertLine(
    label: String,
    value: String,
    monospace: Boolean = false,
    color: Color? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    ) {
        Text(
            "$label: ",
            style = MochiTheme.textStyles.bodySmall,
            color = MochiTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = if (monospace) {
                MochiTheme.textStyles.bodySmall.copy(fontFamily = FontFamily.Monospace)
            } else {
                MochiTheme.textStyles.bodySmall
            },
            color = color ?: MochiTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 长文本（哈希、指纹、质询）用可换行的等宽行，避免被列表副标题截断。 */
@Composable
private fun WrappedValueRow(title: String, value: String, monospace: Boolean = false) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 12.dp),
    ) {
        Text(title, style = MochiTheme.textStyles.bodyLarge)
        Text(
            value,
            style = if (monospace) {
                MochiTheme.textStyles.footnote.copy(fontFamily = FontFamily.Monospace)
            } else {
                MochiTheme.textStyles.footnote
            },
            color = MochiTheme.colorScheme.onSurfaceVariant,
            softWrap = true,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
