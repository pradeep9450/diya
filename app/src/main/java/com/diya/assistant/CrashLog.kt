package com.diya.assistant

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/** Saves the last crash to a file so it shows up in Settings → Diagnostics after a restart. */
object CrashLog {
    private fun file(ctx: Context) = File(ctx.filesDir, "last_crash.txt")

    fun install(context: Context) {
        val ctx = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                file(ctx).writeText("${java.time.LocalDateTime.now()} on ${thread.name}\n$sw\n\nRecent events:\n${DiyaLog.all().takeLast(30).joinToString("\n")}")
            }
            previous?.uncaughtException(thread, e)
        }
    }

    fun last(context: Context): String? = runCatching { file(context).takeIf { it.exists() }?.readText() }.getOrNull()
}
