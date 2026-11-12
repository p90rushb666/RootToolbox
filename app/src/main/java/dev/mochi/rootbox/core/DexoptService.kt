package dev.mochi.rootbox.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import dev.mochi.rootbox.MainActivity
import dev.mochi.rootbox.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * dex2oat 的前台服务：编译期间持有前台通知，降低被系统回收的概率。
 * 通知同时尝试写入小米「焦点通知 / 超级岛」与 ColorOS「流体云」的 extras，
 * 系统不支持时会静默忽略，不影响编译。
 */
class DexoptService : Service() {

    companion object {
        private const val CHANNEL_ID = "dexopt"
        private const val NOTIFICATION_ID = 0x2E01

        fun start(context: Context) {
            val intent = Intent(context, DexoptService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, DexoptService::class.java)) }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var collector: Job? = null
    private var sawRunning = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification(DexoptRuntime(message = "准备编译…")))
        collector = scope.launch {
            DexoptRunner.state.collect { runtime ->
                if (runtime.running) sawRunning = true
                val manager = getSystemService(NotificationManager::class.java)
                runCatching { manager.notify(NOTIFICATION_ID, buildNotification(runtime)) }
                if (sawRunning && !runtime.running) {
                    stopForeground(STOP_FOREGROUND_DETACH)
                    stopSelf()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        collector?.cancel()
        super.onDestroy()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "dex2oat 编译",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "显示 dex2oat 编译进度"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(runtime: DexoptRuntime): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = if (runtime.running) "dex2oat 编译中" else "dex2oat 已结束"
        val text = when {
            runtime.running && runtime.currentLabel != null ->
                "${runtime.currentLabel} · ${runtime.done + runtime.failed}/${runtime.total}"
            runtime.message != null -> runtime.message
            else -> "准备中"
        }
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_dexopt)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(runtime.running)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)

        if (runtime.total > 0) {
            builder.setProgress(runtime.total, runtime.done + runtime.failed, false)
            builder.setSubText("${runtime.done + runtime.failed}/${runtime.total}")
        } else {
            builder.setProgress(0, 0, runtime.running)
        }

        // 注意：这里刻意不写厂商「岛」extras（miui.focus.param / com.oplus.*）。
        // 之前按社区字段写入后，小米 SystemUI 解析通知 extras 时引用到未注册的
        // 图片/动作资源，导致系统界面反复崩溃。纯标准通知即可安全显示进度。
        return builder.build()
    }
}

/** 仅用于提升进程优先级的无障碍服务，不做任何界面操作。 */
class KeepAliveService : android.accessibilityservice.AccessibilityService() {
    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit
}
