package dev.mochi.rootbox.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import dev.mochi.rootbox.BuildConfig
import ui.feice.mochi.basic.Text
import ui.feice.mochi.icons.MochiIonicon
import ui.feice.mochi.list.MochiListCard
import ui.feice.mochi.list.MochiListRow
import ui.feice.mochi.list.mochiListSection
import ui.feice.mochi.navigation.LocalMochiBottomBarInset
import ui.feice.mochi.navigation.MochiLazyCompactTitleScaffold
import ui.feice.mochi.theme.MochiTheme

@Composable
fun AboutScreen(navigationIcon: @Composable () -> Unit) {
    val context = LocalContext.current
    // 用系统里的应用图标，保证和桌面上的 logo 完全一致（自适应图标由系统渲染）。
    // PackageManager 读取图标可能阻塞，放到 IO 线程，避免进入关于页时卡住主线程。
    val appIcon by produceState<ImageBitmap?>(initialValue = null) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                context.packageManager.getApplicationIcon(context.packageName)
                    .toBitmap(192, 192)
                    .asImageBitmap()
            }.getOrNull()
        }
    }
    fun open(url: String) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    MochiLazyCompactTitleScaffold(
        title = "关于",
        navigationIcon = navigationIcon,
    ) {
        item(key = "hero") {
            MochiListCard {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    appIcon?.let { bitmap ->
                        Image(
                            bitmap = bitmap,
                            contentDescription = "Root 工具箱 logo",
                            modifier = Modifier
                                .size(96.dp)
                                .clip(RoundedCornerShape(24.dp)),
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    Text("Root 工具箱", style = MochiTheme.textStyles.titleLarge)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Tricky Store keybox 管理 · v${BuildConfig.VERSION_NAME}",
                        style = MochiTheme.textStyles.bodyMedium,
                        color = MochiTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        mochiListSection(key = "about", header = "关于") {
            row(key = "author") {
                MochiListRow("作者", value = "Zyn")
            }
            row(key = "version") {
                MochiListRow("版本", value = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            }
            row(key = "package") {
                MochiListRow("包名", value = BuildConfig.APPLICATION_ID)
            }
            row(key = "project") {
                MochiListRow(
                    title = "项目地址",
                    subtitle = "https://github.com/p90rushb666/RootToolbox",
                    value = "打开",
                    chevron = true,
                    leading = { MochiIonicon("logo-github") },
                    onClick = { open("https://github.com/p90rushb666/RootToolbox") },
                )
            }
        }

        mochiListSection(
            key = "thanks",
            header = "鸣谢",
            footer = "界面与主题由 mochi-compose 提供；密钥检测参考 KeyAttestation。",
        ) {
            row(key = "keyattestation") {
                MochiListRow(
                    title = "KeyAttestation",
                    subtitle = "密钥认证的参考实现",
                    leading = { MochiIonicon("logo-github") },
                    chevron = true,
                    onClick = { open("https://github.com/vvb2060/KeyAttestation") },
                )
            }
            row(key = "mochi") {
                MochiListRow(
                    title = "mochi-compose",
                    subtitle = "iOS 风格的 Compose 主题与组件库",
                    leading = { MochiIonicon("logo-github") },
                    chevron = true,
                    onClick = { open("https://github.com/feice11/mochi-compose") },
                )
            }
        }

        item(key = "bottom-space") {
            Spacer(Modifier.height(LocalMochiBottomBarInset.current + 24.dp))
        }
    }
}
