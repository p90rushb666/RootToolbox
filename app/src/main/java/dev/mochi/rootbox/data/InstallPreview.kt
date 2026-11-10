package dev.mochi.rootbox.data

import dev.mochi.rootbox.core.KeyboxMeta

/**
 * 一次「准备写入」的只读预览。阶段 A 的产物，阶段 B 才真正落盘。
 *
 * [issues] 非空时禁止确认（结构无效、哈希与索引不一致、解析失败等）；[warnings] 只提示不拦截。
 */
data class InstallPreview(
    val origin: String,
    val fileName: String,
    val targetPath: String,
    val meta: KeyboxMeta,
    /** 索引声称的 SHA-256；null 表示来源没有提供校验值。 */
    val expectedSha256: String?,
    val indexItem: KeyboxItem?,
    val currentMeta: KeyboxMeta?,
    val currentSha: String?,
    val issues: List<String>,
    val warnings: List<String>,
    val sourceItemUid: String? = null,
) {
    val canConfirm: Boolean get() = issues.isEmpty()
}
