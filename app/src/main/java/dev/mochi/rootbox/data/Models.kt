package dev.mochi.rootbox.data

enum class KeyboxStatus { USABLE, EXPIRED, REVOKED, UNKNOWN }

enum class GreenRating { THREE_GREEN, NOT_THREE, UNVERIFIED }

data class KeyboxItem(
    val uid: String,
    val sourceId: String,
    val sourceTitle: String,
    val name: String,
    val updatedAt: Long?,
    val expiresAt: Long?,
    val status: KeyboxStatus,
    val statusLabel: String,
    val green: GreenRating,
    val greenDetail: String?,
    val detail: String,
    val downloadUrl: String?,
    val certCount: Int?,
    val sizeBytes: Long?,
    val sha256: String?,
    val sourcePageUrl: String,
) {
    val usable: Boolean get() = status == KeyboxStatus.USABLE
}

data class SourceResult(
    val sourceId: String,
    val sourceTitle: String,
    val items: List<KeyboxItem>,
    val error: String? = null,
)
