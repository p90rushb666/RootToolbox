package dev.mochi.rootbox.core

/** 四态结论。文字上永远区分「文件写成功」与「模块真的生效」。 */
enum class ModuleLoadState { LOADED, PENDING_REBOOT, NOT_INSTALLED, UNKNOWN }

data class ModuleLoadStatus(
    val state: ModuleLoadState,
    val headline: String,
    val reasons: List<String>,
    val advice: String?,
    val facts: List<Pair<String, String>>,
    val evidenceNote: String?,
    val expectedSha256: String?,
    val actualSha256: String?,
    val checkedAt: Long,
    val needsReboot: Boolean = false,
)

/**
 * Read-only check of whether the Tricky Store module is actually active.
 *
 * Everything here is evidence, never inference: the module directory, the marker files Magisk /
 * KernelSU maintain, the pending-update directory, and the on-disk keybox compared against the hash
 * we just wrote. When the evidence is insufficient the result is [ModuleLoadState.UNKNOWN] — the app
 * must not claim "loaded" just because a file write returned success.
 */
object ModuleLoadChecker {

    const val MODULE_DIR = "/data/adb/modules/tricky_store"
    const val UPDATE_DIR = "/data/adb/modules_update/tricky_store"

    suspend fun check(expectedSha256: String?, root: RootInfo?): ModuleLoadStatus {
        val now = System.currentTimeMillis()
        if (root?.granted != true) {
            return ModuleLoadStatus(
                state = ModuleLoadState.UNKNOWN,
                headline = "状态未知",
                reasons = listOf("未获得 Root 权限，无法读取 /data/adb 下的模块状态。"),
                advice = "在 Root 管理器中授予本应用权限后重新检查。",
                facts = listOf("Root" to (root?.message ?: "未授权")),
                evidenceNote = "缺少读取证据，按规则不给出结论。",
                expectedSha256 = expectedSha256,
                actualSha256 = null,
                checkedAt = now,
            )
        }

        val result = Shell.su(script())
        if (!result.ok) {
            return ModuleLoadStatus(
                state = ModuleLoadState.UNKNOWN,
                headline = "状态未知",
                reasons = listOf("读取模块状态失败：${result.brief()}"),
                advice = "稍后重试；若持续失败，请到诊断页复制日志。",
                facts = emptyList(),
                evidenceNote = null,
                expectedSha256 = expectedSha256,
                actualSha256 = null,
                checkedAt = now,
            )
        }

        val body = result.out
        fun field(name: String): String =
            body.lineSequence().firstOrNull { it.startsWith("$name|") }?.substringAfter("|").orEmpty()

        val moduleDir = field("MODDIR")
        val updateDir = field("UPDATEDIR")
        val disable = field("DISABLE") == "1"
        val remove = field("REMOVE") == "1"
        val updateFlag = field("UPDATEFLAG") == "1"
        val keybox = field("KEYBOX")
        val selinux = field("SELINUX").ifBlank { null }

        val keyboxParts = keybox.split("|")
        val keyboxSize = keyboxParts.getOrNull(0)?.toLongOrNull()
        val keyboxMtime = keyboxParts.getOrNull(1)?.toLongOrNull()?.times(1000)
        val keyboxSha = keyboxParts.getOrNull(2)?.takeIf { it.length == 64 }

        val props = body.substringAfter("PROP_START", "").substringBefore("PROP_END", "")
            .lineSequence()
            .mapNotNull { line ->
                val idx = line.indexOf('=')
                if (idx <= 0) null else line.substring(0, idx).trim() to line.substring(idx + 1).trim()
            }
            .toMap()

        val facts = buildList {
            add("模块目录" to moduleDir.ifBlank { "未发现" })
            if (props.isNotEmpty()) {
                add("模块信息" to buildString {
                    append(props["name"] ?: "Tricky Store")
                    props["version"]?.let { append(" v$it") }
                    props["versionCode"]?.let { append("（code $it）") }
                })
            }
            val markers = buildList {
                if (disable) add("disable")
                if (remove) add("remove")
                if (updateFlag) add("update")
            }
            add("标记文件" to if (markers.isEmpty()) "无" else markers.joinToString("、"))
            add("待重启更新" to if (updateDir.isNotBlank()) "存在 $updateDir" else "无")
            add("keybox.xml" to if (keyboxSize == null) "不存在" else "${keyboxSize}B · ${KeyboxMeta.formatDate(keyboxMtime ?: 0)}")
            keyboxSha?.let { add("设备 SHA-256" to it.take(16) + "…") }
            selinux?.let { add("SELinux" to it) }
            add("Root 方案" to (root.manager + root.managerDetail?.let { d -> "（" + d.replace("\n", "、") + "）" }.orEmpty()))
        }

        val reasons = mutableListOf<String>()
        val state: ModuleLoadState
        var advice: String? = null
        var evidenceNote: String? = null
        var needsReboot = false

        when {
            moduleDir.isBlank() && keyboxSize == null -> {
                state = ModuleLoadState.NOT_INSTALLED
                reasons += "既没有 $MODULE_DIR，也没有 keybox.xml。"
                advice = "刷入 Tricky Store 模块并重启后再检查。"
            }

            updateDir.isNotBlank() || updateFlag -> {
                state = ModuleLoadState.PENDING_REBOOT
                needsReboot = true
                reasons += "存在待生效的模块更新（$UPDATE_DIR 或 update 标记）。"
                reasons += "Magisk / KernelSU 只会在下次启动时把更新目录合并进模块，当前运行的仍是旧版本。"
                advice = "重启设备使其生效，重启后再检查一次。"
            }

            keyboxSize == null -> {
                state = ModuleLoadState.UNKNOWN
                reasons += "模块目录存在，但没有找到可读的 keybox.xml。"
                advice = "确认 Tricky Store 已生成配置；必要时重新刷入模块。"
            }

            disable || remove -> {
                state = ModuleLoadState.UNKNOWN
                reasons += buildString {
                    append("模块被标记为")
                    if (disable) append(" 已禁用（disable）")
                    if (disable && remove) append("、")
                    if (remove) append(" 待卸载（remove）")
                    append("，不会参与加载。")
                }
                advice = "去掉对应的标记文件并重启，或在模块管理器里重新启用。"
            }

            expectedSha256 != null && keyboxSha != null && !keyboxSha.equals(expectedSha256, ignoreCase = true) -> {
                state = ModuleLoadState.UNKNOWN
                reasons += "设备上 keybox.xml 的 SHA-256 与刚写入的值不一致（期望 ${expectedSha256.take(16)}… / 实际 ${keyboxSha.take(16)}…）。"
                reasons += "可能有其它模块或脚本在改写该文件。"
                advice = "重新检查；若持续不一致，先排查是否有其它 keybox 管理类模块。"
            }

            else -> {
                state = ModuleLoadState.LOADED
                reasons += "模块目录存在，未发现 disable / remove / update 标记，模块不在待重启更新状态。"
                if (expectedSha256 != null && keyboxSha != null) {
                    reasons += "设备侧 keybox.xml 的 SHA-256 与刚写入的值一致。"
                } else {
                    reasons += "keybox.xml 可读；本次没有可对照的写入哈希，因此未做一致性校验。"
                }
                evidenceNote = "以上为静态文件证据；本应用不会读取运行中的进程内存，无法证明模块此刻确实已注入。"
            }
        }

        DiagLog.info("module", "加载状态=${state.name} 原因=${reasons.firstOrNull() ?: "-"}")

        return ModuleLoadStatus(
            state = state,
            headline = when (state) {
                ModuleLoadState.LOADED -> "已加载"
                ModuleLoadState.PENDING_REBOOT -> "待重启"
                ModuleLoadState.NOT_INSTALLED -> "未安装"
                ModuleLoadState.UNKNOWN -> "状态未知"
            },
            reasons = reasons,
            advice = advice,
            facts = facts,
            evidenceNote = evidenceNote,
            expectedSha256 = expectedSha256,
            actualSha256 = keyboxSha,
            checkedAt = now,
            needsReboot = needsReboot,
        )
    }

    /** Commands the user may run themselves; the app never executes a reboot on its own. */
    val rebootCommands: List<Pair<String, String>> = listOf(
        "重启设备（推荐）" to "su -c reboot",
        "仅重启 zygote（可能不足以重载模块）" to "su -c 'setprop ctl.restart zygote'",
    )

    private fun script(): String = """
        M=$MODULE_DIR
        U=$UPDATE_DIR
        K=/data/adb/tricky_store/keybox.xml
        [ -d "${'$'}M" ] && echo "MODDIR|${'$'}M" || echo "MODDIR|"
        [ -d "${'$'}U" ] && echo "UPDATEDIR|${'$'}U" || echo "UPDATEDIR|"
        [ -f "${'$'}M/disable" ] && echo "DISABLE|1" || echo "DISABLE|0"
        [ -f "${'$'}M/remove" ] && echo "REMOVE|1" || echo "REMOVE|0"
        [ -f "${'$'}M/update" ] && echo "UPDATEFLAG|1" || echo "UPDATEFLAG|0"
        if [ -f "${'$'}M/module.prop" ]; then echo PROP_START; cat "${'$'}M/module.prop"; echo PROP_END; fi
        if [ -f "${'$'}K" ]; then echo "KEYBOX|${'$'}(stat -c %s "${'$'}K")|${'$'}(stat -c %Y "${'$'}K")|${'$'}(sha256sum "${'$'}K" 2>/dev/null | cut -d' ' -f1)"; else echo "KEYBOX|||"; fi
        echo "SELINUX|${'$'}(getenforce 2>/dev/null)"
        echo LOAD_END
    """.trimIndent()
}
