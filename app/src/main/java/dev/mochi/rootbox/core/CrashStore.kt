package dev.mochi.rootbox.core

import android.content.Context
import java.io.File

/** Keeps the last uncaught exception on disk so the diagnostics page can still export it. */
object CrashStore {

    private const val FILE_NAME = "crash-last.log"

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    fun write(context: Context, text: String) {
        runCatching { file(context).writeText(text) }
    }

    fun read(context: Context): String? {
        val f = file(context)
        if (!f.exists()) return null
        return runCatching { f.readText() }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }
}
