package dev.mochi.rootbox.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.mochi.rootbox.AppState
import dev.mochi.rootbox.AppViewModel
import dev.mochi.rootbox.DexoptUi
import dev.mochi.rootbox.core.DexoptMode
import dev.mochi.rootbox.core.DexoptScope
import dev.mochi.rootbox.core.DexoptThreads
import kotlinx.collections.immutable.persistentListOf
import ui.feice.mochi.basic.MochiButton
import ui.feice.mochi.basic.MochiButtonStyle
import ui.feice.mochi.basic.MochiChip
import ui.feice.mochi.basic.MochiChipGroup
import ui.feice.mochi.basic.MochiDialog
import ui.feice.mochi.basic.MochiDialogAction
import ui.feice.mochi.basic.MochiDialogActionRole
import ui.feice.mochi.basic.MochiProgressBar
import ui.feice.mochi.basic.MochiProgressRing
import ui.feice.mochi.basic.Text
import ui.feice.mochi.icons.MochiIonicon
import ui.feice.mochi.list.MochiListRow
import ui.feice.mochi.list.mochiListSection
import ui.feice.mochi.navigation.LocalMochiBottomBarInset
import ui.feice.mochi.navigation.MochiLazyCompactTitleScaffold
import ui.feice.mochi.theme.MochiTheme

/**
 * dex2oat 编译：选择范围（用户应用 / 全部应用）、模式（快速 / 热点 / 全量）、线程（系统档位 / 2 / 4 / 8 / 全开）。
 * 开始按钮在顶部，下方小字汇总当前设置；进度实时显示并持久化，中断后可继续。
 */
@Composable
fun DexoptScreen(
    state: AppState,
    viewModel: AppViewModel,
    navigationIcon: @Composable () -> Unit,
    showToast: (String) -> Unit,
) {
    LaunchedEffect(Unit) { viewModel.loadDexoptApps() }

    val ui = state.dexopt
    val runtime = ui.runtime
    val context = LocalContext.current
    val scheme = MochiTheme.colorScheme

    var permissionTick by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) permissionTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val notificationGranted = remember(permissionTick) {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
    }
    val batteryIgnored = remember(permissionTick) {
        (context.getSystemService(PowerManager::class.java))
            ?.isIgnoringBatteryOptimizations(context.packageName) == true
    }
    val accessibilityOn = remember(permissionTick) {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        enabled.contains("${context.packageName}/dev.mochi.rootbox.core.KeepAliveService")
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permissionTick++
        if (!granted) showToast("没有通知权限，编译进度只能在应用内查看")
    }

    var confirmClearJob by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }

    val scopeCount = if (ui.scope.includeSystem) ui.apps.size else ui.apps.count { !it.isSystem }

    MochiLazyCompactTitleScaffold(
        title = "dex2oat 编译",
        subtitle = when {
            runtime.running -> "编译中 ${runtime.done + runtime.failed}/${runtime.total}"
            ui.savedJob?.remaining()?.isNotEmpty() == true -> "有未完成任务，可继续"
            else -> "选择参数后点顶部「开始编译」"
        },
        navigationIcon = navigationIcon,
    ) {
        // 开始编译 + 当前设置摘要（最顶部）
        item(key = "start") {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            ) {
                MochiButton(
                    onClick = { viewModel.startDexopt() },
                    style = MochiButtonStyle.Filled,
                    enabled = !runtime.running && scopeCount > 0 && state.root?.granted == true,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        when {
                            state.root?.granted != true -> "需要 Root 权限"
                            runtime.running -> "正在编译…"
                            scopeCount == 0 -> "没有可编译的应用"
                            else -> "开始编译 $scopeCount 个应用"
                        },
                    )
                }
                Text(
                    "范围：${ui.scope.label}（$scopeCount 个） · 模式：${ui.mode.label} · 线程：${ui.threads.label}",
                    style = MochiTheme.textStyles.footnote,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
                if (ui.loadingApps) {
                    Text(
                        "正在读取应用列表…",
                        style = MochiTheme.textStyles.footnote,
                        color = scheme.outline,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }

        // 运行 / 暂停 / 完成状态卡
        DexoptStatusCard(
            ui = ui,
            onPause = { viewModel.pauseDexopt() },
            onResume = { viewModel.resumeDexopt() },
            onDiscard = { confirmDiscard = true },
        )

        mochiListSection(
            key = "scope",
            header = "编译范围",
            footer = "「用户应用」只编译第三方应用；「全部应用」额外包含系统应用，数量更多、耗时更长。",
        ) {
            row(key = "scope-chips") {
                MochiChipGroup(
                    modifier = Modifier.fillMaxWidth(),
                    wrap = true,
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
                ) {
                    DexoptScope.entries.forEach { scope ->
                        val count = if (scope.includeSystem) ui.apps.size else ui.apps.count { !it.isSystem }
                        MochiChip(
                            label = "${scope.label}（$count）",
                            selected = ui.scope == scope,
                            onClick = { viewModel.setDexoptScope(scope) },
                        )
                    }
                }
            }
        }

        mochiListSection(key = "mode", header = "编译模式") {
            row(key = "mode-chips") {
                MochiChipGroup(
                    modifier = Modifier.fillMaxWidth(),
                    wrap = true,
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
                ) {
                    DexoptMode.entries.forEach { mode ->
                        MochiChip(
                            label = mode.label,
                            selected = ui.mode == mode,
                            onClick = { viewModel.setDexoptMode(mode) },
                            leadingIcon = { MochiIonicon(mode.iconName) },
                        )
                    }
                }
            }
            row(key = "mode-desc") {
                MochiListRow(
                    title = ui.mode.label,
                    subtitle = ui.mode.description,
                )
            }
        }

        mochiListSection(
            key = "threads",
            header = "编译线程",
            footer = "线程数通过临时 setprop dalvik.vm.dex2oat-threads 生效，编译结束后自动还原；部分 ROM 会忽略该属性。",
        ) {
            row(key = "thread-chips") {
                MochiChipGroup(
                    modifier = Modifier.fillMaxWidth(),
                    wrap = true,
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
                ) {
                    DexoptThreads.entries.forEach { threads ->
                        MochiChip(
                            label = threads.label,
                            selected = ui.threads == threads,
                            onClick = { viewModel.setDexoptThreads(threads) },
                            leadingIcon = { MochiIonicon(threads.iconName) },
                        )
                    }
                }
            }
        }

        mochiListSection(
            key = "permissions",
            header = "权限与保活",
            footer = "编译中断后可以从头继续；这些权限只是降低被系统杀掉的概率，不授予也能编译。",
        ) {
            row(key = "notif") {
                MochiListRow(
                    title = "通知权限",
                    subtitle = "前台服务需要在通知栏显示进度",
                    value = if (notificationGranted) "已授予" else "未授予",
                    destructive = !notificationGranted,
                    trailing = if (!notificationGranted) {
                        {
                            MochiButton(
                                onClick = {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    } else {
                                        showToast("当前系统不需要单独授予通知权限")
                                    }
                                },
                                style = MochiButtonStyle.Tonal,
                            ) { Text("授予") }
                        }
                    } else null,
                )
            }
            row(key = "battery") {
                MochiListRow(
                    title = "后台无限制",
                    subtitle = "加入电池优化白名单，避免编译被中断",
                    value = if (batteryIgnored) "已加入" else "未加入",
                    destructive = !batteryIgnored,
                    trailing = if (!batteryIgnored) {
                        {
                            MochiButton(
                                onClick = {
                                    val intent = Intent(
                                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                        Uri.parse("package:${context.packageName}"),
                                    )
                                    runCatching { context.startActivity(intent) }
                                        .onFailure { showToast("系统没有提供电池优化设置页") }
                                },
                                style = MochiButtonStyle.Tonal,
                            ) { Text("去设置") }
                        }
                    } else null,
                )
            }
            row(key = "accessibility") {
                MochiListRow(
                    title = "无障碍保活",
                    subtitle = "只用于提升进程优先级，不读取屏幕内容",
                    value = if (accessibilityOn) "已开启" else "未开启",
                    destructive = !accessibilityOn,
                    trailing = {
                        MochiButton(
                            onClick = {
                                runCatching {
                                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                }.onFailure { showToast("无法打开无障碍设置") }
                            },
                            style = MochiButtonStyle.Tonal,
                        ) { Text("去开启") }
                    },
                )
            }
        }

        item(key = "bottom-space") {
            Spacer(Modifier.height(LocalMochiBottomBarInset.current + 24.dp))
        }
    }

    MochiDialog(
        visible = confirmDiscard,
        onDismissRequest = { confirmDiscard = false },
        title = "放弃未完成的任务？",
        message = "已编译的应用记录会被清除，下次需要重新开始。",
        actions = persistentListOf(
            MochiDialogAction("取消", MochiDialogActionRole.Cancel),
            MochiDialogAction(
                label = "放弃",
                role = MochiDialogActionRole.Destructive,
                onClick = {
                    confirmDiscard = false
                    viewModel.clearDexoptJob()
                },
            ),
        ),
    )

    MochiDialog(
        visible = confirmClearJob,
        onDismissRequest = { confirmClearJob = false },
        title = "清除任务记录？",
        message = "只清除完成记录，不会重新编译或还原任何系统设置。",
        actions = persistentListOf(
            MochiDialogAction("取消", MochiDialogActionRole.Cancel),
            MochiDialogAction(
                label = "清除",
                role = MochiDialogActionRole.Destructive,
                onClick = {
                    confirmClearJob = false
                    viewModel.clearDexoptJob()
                },
            ),
        ),
    )
}

private fun LazyListScope.DexoptStatusCard(
    ui: DexoptUi,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onDiscard: () -> Unit,
) {
    val runtime = ui.runtime
    val saved = ui.savedJob

    when {
        runtime.running -> runningItem(runtime, onPause)

        saved == null -> item {
            MochiListRow(
                title = "尚未开始",
                subtitle = "选择范围、模式、线程后，点顶部「开始编译」。",
                leading = { MochiIonicon("cube") },
            )
        }

        saved.finished -> finishedItem(saved, onDiscard)

        else -> pausedItem(saved, onResume, onDiscard)
    }
}

private fun LazyListScope.runningItem(runtime: dev.mochi.rootbox.core.DexoptRuntime, onPause: () -> Unit) {
    item {
        val scheme = MochiTheme.colorScheme
        val fraction = if (runtime.total > 0) {
            (runtime.done + runtime.failed).toFloat() / runtime.total.toFloat()
        } else 0f
        val animated by animateFloatAsState(
            targetValue = fraction.coerceIn(0f, 1f),
            animationSpec = tween(200),
            label = "dexopt-progress",
        )
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MochiProgressRing(progress = animated, size = 34.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(runtime.currentLabel ?: "准备中…", style = MochiTheme.textStyles.bodyLarge)
                    Text(
                        "${runtime.done + runtime.failed} / ${runtime.total} · 失败 ${runtime.failed}",
                        style = MochiTheme.textStyles.footnote,
                        color = scheme.onSurfaceVariant,
                    )
                }
                MochiButton(onClick = onPause, style = MochiButtonStyle.Tonal) { Text("暂停") }
            }
            Spacer(Modifier.height(10.dp))
            MochiProgressBar(animated)
            runtime.currentPackage?.let {
                Text(
                    it,
                    style = MochiTheme.textStyles.footnote.copy(fontFamily = FontFamily.Monospace),
                    color = scheme.outline,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

private fun LazyListScope.pausedItem(
    saved: dev.mochi.rootbox.core.DexoptJob,
    onResume: () -> Unit,
    onDiscard: () -> Unit,
) {
    item {
        val scheme = MochiTheme.colorScheme
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 12.dp),
        ) {
            Text("上次任务未完成", style = MochiTheme.textStyles.bodyLarge)
            Text(
                "已完成 ${saved.done.size} / ${saved.packages.size}，剩余 ${saved.remaining().size} 个应用",
                style = MochiTheme.textStyles.footnote,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MochiButton(onClick = onResume, style = MochiButtonStyle.Filled) { Text("继续") }
                MochiButton(onClick = onDiscard, style = MochiButtonStyle.Tonal) { Text("放弃") }
            }
        }
    }
}

private fun LazyListScope.finishedItem(
    saved: dev.mochi.rootbox.core.DexoptJob,
    onDiscard: () -> Unit,
) {
    item {
        val scheme = MochiTheme.colorScheme
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 12.dp),
        ) {
            Text("编译完成", style = MochiTheme.textStyles.bodyLarge)
            Text(
                "成功 ${saved.done.size} · 失败 ${saved.failed.size}",
                style = MochiTheme.textStyles.footnote,
                color = if (saved.failed.isEmpty()) scheme.onSurfaceVariant else scheme.error,
                modifier = Modifier.padding(top = 2.dp),
            )
            if (saved.failed.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                MochiButton(onClick = onDiscard, style = MochiButtonStyle.Tonal) { Text("清除记录") }
            }
        }
    }

    // 失败详情：展开每个失败应用的具体原因，方便排查。
    saved.failed.forEach { (pkg, error) ->
        item(key = "fail-$pkg") {
            MochiListRow(
                title = saved.labels[pkg] ?: pkg,
                subtitle = error,
                destructive = true,
                leading = { MochiIonicon("alert-circle") },
            )
        }
    }
}

private val DexoptMode.iconName: String
    get() = when (this) {
        DexoptMode.QUICKEN -> "flash-outline"
        DexoptMode.SPEED_PROFILE -> "flame"
        DexoptMode.SPEED -> "speedometer"
    }

private val DexoptThreads.iconName: String
    get() = when (this) {
        DexoptThreads.SYSTEM -> "settings"
        DexoptThreads.ALL -> "flash"
        else -> "options"
    }
