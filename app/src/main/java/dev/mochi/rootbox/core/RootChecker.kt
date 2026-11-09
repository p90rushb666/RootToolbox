package dev.mochi.rootbox.core

import android.os.Build

data class RootInfo(
    val granted: Boolean,
    val uid: String?,
    val suPath: String?,
    val manager: String,
    val managerDetail: String?,
    val selinux: String?,
    val androidVersion: String,
    val deviceModel: String,
    val message: String?,
)

object RootChecker {

    suspend fun detect(): RootInfo {
        val android = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        val model = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

        val suPath = Shell.exec(arrayOf("sh", "-c", "command -v su"), 3_000)
            .let { if (it.ok) it.out.trim().lineSequence().firstOrNull().orEmpty() else "" }
            .ifBlank { null }

        val idResult = try {
            // Without a su binary the call fails instantly; when it exists the user may need time to
            // tap "allow" in the root manager, so keep the longer timeout only for that case.
            Shell.su("id", timeoutMs = if (suPath == null) 6_000 else 25_000)
        } catch (t: Throwable) {
            ShellResult(-2, "", t.message ?: "su 调用失败")
        }

        val granted = idResult.ok && idResult.out.contains("uid=0")
        val uid = Regex("uid=(\\d+)\\(([^)]*)\\)").find(idResult.out)?.value

        if (!granted) {
            DiagLog.warn("root", "Root 未授权: ${idResult.brief()}")
            val missingSu = suPath == null && (
                idResult.err.contains("No such file", ignoreCase = true) ||
                    idResult.err.contains("error=2") ||
                    idResult.code == -2
                )
            val reason = when {
                idResult.timedOut -> "授权超时，请在 Root 管理器中允许后重新检测"
                missingSu -> "未找到 su，设备可能未 Root 或尚未授权"
                suPath == null -> "未找到 su 命令，设备可能未 Root。"
                else -> "su 被拒绝（退出码 ${idResult.code}），请在 Root 管理器中允许"
            }
            return RootInfo(
                granted = false,
                uid = uid,
                suPath = suPath,
                manager = "未知",
                managerDetail = null,
                selinux = null,
                androidVersion = android,
                deviceModel = model,
                message = reason,
            )
        }

        val probe = Shell.su(
            """
            for d in /data/adb/magisk /data/adb/ksu /data/adb/ap /data/adb/apd /data/adb/ksud; do
              [ -e "${'$'}d" ] && echo "DIR:${'$'}d"
            done
            echo "SELINUX:${'$'}(getenforce 2>/dev/null)"
            """.trimIndent(),
        )
        val dirs = probe.out.lineSequence().filter { it.startsWith("DIR:") }.map { it.removePrefix("DIR:") }.toList()
        val selinux = probe.out.lineSequence().firstOrNull { it.startsWith("SELINUX:") }
            ?.removePrefix("SELINUX:")?.trim()?.ifBlank { null }

        val manager = when {
            dirs.any { it.contains("magisk") } -> "Magisk"
            dirs.any { it.contains("ksu") } || dirs.any { it.contains("ksud") } -> "KernelSU"
            dirs.any { it.contains("/ap") } || dirs.any { it.contains("apd") } -> "APatch"
            else -> "其他 / 未知"
        }
        val detail = dirs.joinToString("\n").ifBlank { null }

        DiagLog.info("root", "Root 已授权 uid=$uid manager=$manager selinux=$selinux")
        return RootInfo(
            granted = true,
            uid = uid,
            suPath = suPath ?: "/system/bin/su",
            manager = manager,
            managerDetail = detail,
            selinux = selinux,
            androidVersion = android,
            deviceModel = model,
            message = null,
        )
    }
}
