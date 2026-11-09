package dev.mochi.rootbox.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.mochi.rootbox.AppState
import dev.mochi.rootbox.AppViewModel
import dev.mochi.rootbox.BuildConfig
import dev.mochi.rootbox.core.CrashStore
import dev.mochi.rootbox.core.DiagLog
import dev.mochi.rootbox.core.LogLevel
import ui.feice.mochi.basic.MochiBarGroup
import ui.feice.mochi.basic.MochiBarGroupItem
import ui.feice.mochi.icons.MochiIonicon
import ui.feice.mochi.list.MochiListRow
import ui.feice.mochi.list.mochiListSection
import ui.feice.mochi.list.rows
import ui.feice.mochi.navigation.MochiLazyCompactTitleScaffold

@Composable
fun DiagnosticsScreen(
    state: AppState,
    viewModel: AppViewModel,
    navigationIcon: @Composable () -> Unit,
    showToast: (String) -> Unit,
) {
    val context = LocalContext.current
    val log by DiagLog.entries.collectAsState()
    var crashReport by remember { mutableStateOf(CrashStore.read(context)) }
    // 倒序只在日志变化时算一次；放在这里是因为下面列表构建器里不能调 remember。
    val orderedLog = remember(log) { log.asReversed() }

    fun copyToClipboard(text: String, label: String) {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText("RootToolbox", text))
        showToast(label)
    }

    MochiLazyCompactTitleScaffold(
        title = "诊断",
        subtitle = "运行环境与日志",
        navigationIcon = navigationIcon,
        actions = {
            MochiBarGroup(navId = "diag.actions") {
                MochiBarGroupItem(
                    onClick = {
                        copyToClipboard(buildReport(state, log, crashReport), "已复制全部日志")
                    },
                    navId = "diag.actions",
                    contentKey = "copy",
                ) { MochiIonicon("copy", contentDescription = "复制全部日志") }
                MochiBarGroupItem(
                    onClick = {
                        val errors = log.filter { it.level != LogLevel.INFO }
                        copyToClipboard(
                            buildReport(state, errors.ifEmpty { log }, crashReport, "仅错误与警告"),
                            "已复制错误日志",
                        )
                    },
                    navId = "diag.actions",
                    contentKey = "copy-errors",
                ) { MochiIonicon("alert-circle", contentDescription = "复制错误日志") }
            }
        },
    ) {
        mochiListSection(key = "env", header = "运行环境") {
            row(key = "version") {
                MochiListRow("应用版本", value = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            }
            row(key = "android") {
                MochiListRow("系统", value = state.root?.androidVersion ?: "未知", subtitle = state.root?.deviceModel)
            }
            row(key = "root") {
                MochiListRow(
                    title = "Root",
                    value = if (state.root?.granted == true) "已授权" else "未授权",
                    subtitle = state.root?.manager?.let { "$it\n${state.root?.message ?: state.root?.uid.orEmpty()}" }
                        ?: state.root?.message,
                )
            }
            row(key = "target") {
                MochiListRow("替换目标", subtitle = state.targetPath ?: "尚未确定")
            }
            row(key = "log-count") {
                MochiListRow("日志条数", value = "${log.size}")
            }
        }

        mochiListSection(
            key = "actions",
            header = "维护",
            footer = "日志保存在内存里，最多 600 条；崩溃报告会额外写入应用私有目录。",
        ) {
            row(key = "clear") {
                MochiListRow(
                    title = "清空日志",
                    destructive = true,
                    onClick = {
                        viewModel.clearLog()
                        showToast("日志已清空")
                    },
                )
            }
            if (crashReport != null) {
                row(key = "crash-clear") {
                    MochiListRow(
                        title = "清除上次崩溃报告",
                        onClick = {
                            CrashStore.clear(context)
                            crashReport = null
                            showToast("崩溃报告已清除")
                        },
                    )
                }
            }
        }

        if (crashReport != null) {
            mochiListSection(
                key = "crash",
                header = "上次崩溃",
                footer = "点上方复制按钮可把这段内容一起导出。",
            ) {
                row(key = "crash-body") {
                    MochiListRow(
                        title = "崩溃报告",
                        subtitle = crashReport?.take(1200),
                        destructive = true,
                    )
                }
            }
        }

        mochiListSection(key = "logs", header = "最近日志", footer = "新的在最上面。") {
            if (log.isEmpty()) {
                row(key = "empty") { MochiListRow("暂无日志") }
            } else {
                rows(count = orderedLog.size, key = { index -> index }) { index ->
                    val entry = orderedLog[index]
                    MochiListRow(
                        // 400 字的长标题会撑成十几行，滚动时每进入一行都要重新排版文本；
                        // 压成单行预览，完整内容仍可复制导出。
                        title = entry.message.replace('\n', ' ').take(120),
                        subtitle = "${DiagLog.clock(entry.time)}  ${entry.level.name}  ${entry.scope}",
                        destructive = entry.level == LogLevel.ERROR,
                    )
                }
            }
        }
    }
}

private fun buildReport(
    state: AppState,
    log: List<dev.mochi.rootbox.core.LogEntry>,
    crashReport: String?,
    filterLabel: String = "全部",
): String {
    val header = buildString {
        appendLine("Root 工具箱诊断报告")
        appendLine("版本：${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("包名：${BuildConfig.APPLICATION_ID}")
        appendLine("系统：${state.root?.androidVersion ?: "未知"} / ${state.root?.deviceModel ?: "未知"}")
        appendLine("Root：${if (state.root?.granted == true) "已授权" else "未授权"} / ${state.root?.manager ?: "未知"}")
        appendLine("替换目标：${state.targetPath ?: "未确定"}")
        appendLine("模块：${if (state.scan?.module?.installed == true) "已安装 ${state.scan?.module?.version ?: ""}" else "未安装"}")
        appendLine("筛选：$filterLabel")
    }
    return buildString {
        appendLine(DiagLog.dump(header))
        if (crashReport != null) {
            appendLine()
            appendLine("===== 上次崩溃 =====")
            appendLine(crashReport)
        }
    }
}
