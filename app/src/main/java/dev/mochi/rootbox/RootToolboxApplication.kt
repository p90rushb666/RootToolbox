package dev.mochi.rootbox

import android.app.Application
import android.util.Log
import dev.mochi.rootbox.core.CrashStore
import dev.mochi.rootbox.core.DiagLog
import ui.feice.mochi.icons.MochiIconStyle
import ui.feice.mochi.icons.MochiIonicons

class RootToolboxApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                DiagLog.error("crash", "未捕获异常", throwable)
                CrashStore.write(
                    this,
                    DiagLog.dump("崩溃报告 ${BuildConfig.VERSION_NAME}") + "\n\n" + Log.getStackTraceString(throwable),
                )
            }
            previous?.uncaughtException(thread, throwable)
        }
        warmUpIcons()
    }

    /**
     * 图标库是压缩索引，第一次取图标要解压并解析，落在首帧上就是肉眼可见的顿一下。
     * 在进程启动时用后台线程先把首页/底栏用到的图标解析好，首帧直接命中缓存。
     */
    private fun warmUpIcons() {
        val context = applicationContext
        Thread({
            val names = listOf(
                "home", "key", "settings", "shield-checkmark", "cube",
                "document-text", "refresh", "information-circle", "alert-circle",
                "logo-github", "copy", "chevron-forward",
            )
            names.forEach { name ->
                runCatching { MochiIonicons.vector(context, name, MochiIconStyle.Outline) }
                runCatching { MochiIonicons.vector(context, name, MochiIconStyle.Filled) }
            }
        }, "icon-warmup").start()
    }
}
