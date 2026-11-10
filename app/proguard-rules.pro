# 保留 mochi 图标资源名（按字符串在运行时查找）
-keep class ui.feice.mochi.icons.** { *; }
-dontwarn ui.feice.mochi.**

# AndroidManifest 里注册的 FileProvider；release 混淆后仍要能按类名实例化
-keep class androidx.core.content.FileProvider { *; }
