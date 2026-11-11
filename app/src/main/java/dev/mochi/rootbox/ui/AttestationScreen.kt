package dev.mochi.rootbox.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.mochi.rootbox.AppState
import dev.mochi.rootbox.AppViewModel
import dev.mochi.rootbox.core.AttestationChecker
import dev.mochi.rootbox.core.AttestationResult
import dev.mochi.rootbox.core.AttestationVerdict
import dev.mochi.rootbox.data.AppSettings
import ui.feice.mochi.basic.MochiActivityIndicator
import ui.feice.mochi.basic.MochiButton
import ui.feice.mochi.basic.MochiButtonStyle
import ui.feice.mochi.basic.Text
import ui.feice.mochi.icons.MochiIconStyle
import ui.feice.mochi.icons.MochiIonicon
import ui.feice.mochi.list.MochiListCard
import ui.feice.mochi.list.MochiListRow
import ui.feice.mochi.list.mochiListSection
import ui.feice.mochi.navigation.LocalMochiBottomBarInset
import ui.feice.mochi.navigation.MochiLazyCompactTitleScaffold
import ui.feice.mochi.theme.MochiTheme

private const val KEY_ATTESTATION_PROJECT = "https://github.com/vvb2060/KeyAttestation"

/**
 * 密钥认证（官方 Android Keystore 认证）：生成一次性密钥、校验证书链到 Google 认证根、
 * 核对吊销列表与认证质询。无需 Root；冷启动自动检测一次，之后只能手动点「重新检测」。
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
        item(key = "hero") {
            MochiListCard {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 18.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val valid = result?.verdict == AttestationVerdict.VALID
                        MochiIonicon(
                            when {
                                running -> "sync"
                                valid -> "shield-checkmark"
                                else -> "alert-circle"
                            },
                            style = MochiIconStyle.Filled,
                            tint = when {
                                running -> scheme.onSurfaceVariant
                                valid -> scheme.primary
                                else -> scheme.error
                            },
                            modifier = Modifier.size(34.dp),
                        )
                        Spacer(Modifier.size(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                when {
                                    running -> "检测中"
                                    result == null -> "未检测"
                                    else -> result.title
                                },
                                style = MochiTheme.textStyles.titleLarge,
                                color = when {
                                    running -> scheme.onSurface
                                    result == null -> scheme.onSurfaceVariant
                                    result.valid -> scheme.primary
                                    else -> scheme.error
                                },
                            )
                            Text(
                                when {
                                    running -> "正在生成密钥并校验证书链，请稍候…"
                                    result == null -> "点下方按钮开始检测；本页不会自动联网。"
                                    else -> result.reason
                                },
                                style = MochiTheme.textStyles.bodySmall,
                                color = scheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MochiButton(
                            onClick = { viewModel.runAttestation() },
                            style = MochiButtonStyle.Filled,
                            enabled = !running,
                            modifier = Modifier.weight(1f),
                        ) { Text(if (running) "检测中…" else if (result == null) "检测" else "重新检测") }
                        if (running) {
                            Spacer(Modifier.size(14.dp))
                            MochiActivityIndicator(size = 24.dp)
                        }
                    }
                }
            }
        }

        if (showDetails && result != null) {
            if (result.certs.isNotEmpty()) {
                mochiListSection(
                    key = "chain",
                    header = "证书链（${result.certs.size}）",
                    footer = "从叶证书到根证书逐级校验签名；根证书必须与 Google 认证根一致。",
                ) {
                    result.certs.forEach { cert ->
                        row(key = "cert-${cert.index}") {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 18.dp, vertical = 12.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "#${cert.index + 1} ${if (cert.index == 0) "叶证书" else if (cert.index == result.certs.lastIndex) "根证书" else "中间证书"}",
                                        style = MochiTheme.textStyles.bodyLarge,
                                    )
                                    Spacer(Modifier.weight(1f))
                                    Text(
                                        cert.algorithm,
                                        style = MochiTheme.textStyles.footnote,
                                        color = scheme.onSurfaceVariant,
                                    )
                                }
                                Text(
                                    "主题：${cert.subject}",
                                    style = MochiTheme.textStyles.footnote,
                                    color = scheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                                Text(
                                    "签发：${cert.issuer}",
                                    style = MochiTheme.textStyles.footnote,
                                    color = scheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                                Text(
                                    "有效期：${AttestationChecker.formatTime(cert.notBefore)} → ${AttestationChecker.formatTime(cert.notAfter)}",
                                    style = MochiTheme.textStyles.footnote,
                                    color = scheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                                Text(
                                    "序列号：${cert.serial}",
                                    style = MochiTheme.textStyles.footnote.copy(fontFamily = FontFamily.Monospace),
                                    color = scheme.onSurfaceVariant,
                                    softWrap = true,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                        }
                    }
                }
            }

            mochiListSection(
                key = "details",
                header = "认证明细",
                footer = "明细来自证书里的 KeyDescription 扩展；引导状态与补丁级别只作参考，不改变上面的有效/无效结论。",
            ) {
                row(key = "tee") {
                    val hardware = result.securityLevel == "TEE" || result.securityLevel == "StrongBox"
                    MochiListRow(
                        title = "可信执行环境",
                        subtitle = result.securityLevel ?: "证书里没有可用的安全级别",
                        value = when {
                            result.securityLevel == null -> "未知"
                            hardware -> "硬件级"
                            else -> "软件级"
                        },
                        destructive = result.securityLevel != null && !hardware,
                    )
                }
                row(key = "boot") {
                    MochiListRow(
                        title = "引导加载程序",
                        subtitle = "来自 RootOfTrust.verifiedBootState",
                        value = result.bootState ?: "未知",
                        destructive = result.bootStateWarn,
                    )
                }
                row(key = "os") {
                    MochiListRow("系统版本", value = result.osVersion ?: "未知")
                }
                row(key = "patch") {
                    MochiListRow("安全补丁", value = result.patchLevel ?: "未知")
                }
                row(key = "device") {
                    MochiListRow(
                        "设备",
                        subtitle = result.fingerprint,
                        value = listOfNotNull(result.brand, result.model).joinToString(" ").ifBlank { "未知" },
                    )
                }
                row(key = "root") {
                    MochiListRow(
                        title = "信任根",
                        subtitle = "证书链最终锚点",
                        value = result.trustRoot ?: "未通过",
                        destructive = result.verdict != AttestationVerdict.VALID,
                    )
                }
                row(key = "challenge") {
                    MochiListRow(
                        title = "认证质询",
                        subtitle = "每次检测生成新的随机数，防止证书重放",
                        value = result.challengeTime?.let { AttestationChecker.formatTime(it) } ?: "—",
                    )
                }
                result.revocation?.let { reason ->
                    row(key = "revoked") {
                        MochiListRow(
                            title = "吊销状态",
                            subtitle = reason,
                            value = "已吊销",
                            destructive = true,
                        )
                    }
                }
            }
        }

        mochiListSection(key = "credit", header = "来源") {
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
            row(key = "note") {
                MochiListRow(
                    title = "检测在本机完成",
                    subtitle = "不上传证书、不使用网络；吊销列表为内置快照。",
                )
            }
        }

        item(key = "bottom-space") {
            Spacer(Modifier.height(LocalMochiBottomBarInset.current + 24.dp))
        }
    }
}
