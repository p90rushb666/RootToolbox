package dev.mochi.rootbox.data

import android.content.Context

enum class DarkMode { SYSTEM, LIGHT, DARK }

/** User-tunable options, persisted in SharedPreferences. */
data class AppSettings(
    val darkMode: DarkMode = DarkMode.SYSTEM,
    val monet: Boolean = true,
    /** ARGB seed colour; null keeps the library default (matcha green). */
    val keyColor: Long? = null,
    val predictiveBack: Boolean = true,
    val autoCheckRoot: Boolean = true,
    /** Re-detect root every time the app comes to the foreground. */
    val refreshOnOpen: Boolean = true,
    /** Show the 三绿 badge on keybox rows. */
    val showGreenBadge: Boolean = true,
    val showArchivedByDefault: Boolean = false,
    /** Last confirmed keybox.xml path; reused on the next launch instead of asking again. */
    val savedTarget: String? = null,
    /** How many `.bak` files the backup centre keeps when pruning. */
    val keepBackups: Int = 5,
    /** Ask for the device credential (PIN / pattern / biometric) right before writing. */
    val requireCredential: Boolean = false,
    /** User-hosted Play Integrity decode endpoint; blank means "not configured". */
    val integrityEndpoint: String = "",
    /** Google Cloud project number used by setCloudProjectNumber; blank means "not configured". */
    val integrityProjectNumber: String = "",
)

class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("root_toolbox_settings", Context.MODE_PRIVATE)

    fun load(): AppSettings = AppSettings(
        darkMode = runCatching {
            DarkMode.valueOf(prefs.getString(KEY_DARK_MODE, DarkMode.SYSTEM.name) ?: DarkMode.SYSTEM.name)
        }.getOrDefault(DarkMode.SYSTEM),
        monet = prefs.getBoolean(KEY_MONET, true),
        keyColor = prefs.getLong(KEY_COLOR, 0L).takeIf { it != 0L },
        predictiveBack = prefs.getBoolean(KEY_PREDICTIVE, true),
        autoCheckRoot = prefs.getBoolean(KEY_AUTO_ROOT, true),
        refreshOnOpen = prefs.getBoolean(KEY_REFRESH_OPEN, true),
        showGreenBadge = prefs.getBoolean(KEY_GREEN_BADGE, true),
        showArchivedByDefault = prefs.getBoolean(KEY_ARCHIVED, false),
        savedTarget = prefs.getString(KEY_TARGET, null)?.takeIf { it.isNotBlank() },
        keepBackups = prefs.getInt(KEY_KEEP_BACKUPS, 5).coerceIn(1, 50),
        requireCredential = prefs.getBoolean(KEY_REQUIRE_CREDENTIAL, false),
        integrityEndpoint = prefs.getString(KEY_INTEGRITY_ENDPOINT, "").orEmpty(),
        integrityProjectNumber = prefs.getString(KEY_INTEGRITY_PROJECT, "").orEmpty(),
    )

    fun save(settings: AppSettings) {
        prefs.edit()
            .putString(KEY_DARK_MODE, settings.darkMode.name)
            .putBoolean(KEY_MONET, settings.monet)
            .putLong(KEY_COLOR, settings.keyColor ?: 0L)
            .putBoolean(KEY_PREDICTIVE, settings.predictiveBack)
            .putBoolean(KEY_AUTO_ROOT, settings.autoCheckRoot)
            .putBoolean(KEY_REFRESH_OPEN, settings.refreshOnOpen)
            .putBoolean(KEY_GREEN_BADGE, settings.showGreenBadge)
            .putBoolean(KEY_ARCHIVED, settings.showArchivedByDefault)
            .putString(KEY_TARGET, settings.savedTarget)
            .putInt(KEY_KEEP_BACKUPS, settings.keepBackups)
            .putBoolean(KEY_REQUIRE_CREDENTIAL, settings.requireCredential)
            .putString(KEY_INTEGRITY_ENDPOINT, settings.integrityEndpoint)
            .putString(KEY_INTEGRITY_PROJECT, settings.integrityProjectNumber)
            .apply()
    }

    private companion object {
        const val KEY_DARK_MODE = "dark_mode"
        const val KEY_MONET = "monet"
        const val KEY_COLOR = "key_color"
        const val KEY_PREDICTIVE = "predictive_back"
        const val KEY_AUTO_ROOT = "auto_check_root"
        const val KEY_REFRESH_OPEN = "refresh_on_open"
        const val KEY_GREEN_BADGE = "show_green_badge"
        const val KEY_ARCHIVED = "show_archived"
        const val KEY_TARGET = "saved_target"
        const val KEY_KEEP_BACKUPS = "keep_backups"
        const val KEY_REQUIRE_CREDENTIAL = "require_credential"
        const val KEY_INTEGRITY_ENDPOINT = "integrity_endpoint"
        const val KEY_INTEGRITY_PROJECT = "integrity_project"
    }
}

/** Seed colours offered in settings. Value is ARGB. */
val SeedColors: List<Pair<String, Long>> = listOf(
    "抹茶" to 0xFF4C6B52,
    "海蓝" to 0xFF31628D,
    "靛紫" to 0xFF61558F,
    "陶橙" to 0xFF8A5A32,
    "绯红" to 0xFF8E4A5B,
)
