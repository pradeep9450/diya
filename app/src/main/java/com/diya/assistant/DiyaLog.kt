package com.diya.assistant

import android.util.Log
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** A small in-memory log shown in Settings → Diagnostics, so problems can be reported. */
object DiyaLog {
    private val lines = ArrayDeque<String>()
    private val fmt = DateTimeFormatter.ofPattern("HH:mm:ss")

    @Synchronized
    fun add(tag: String, message: String) {
        val line = "${LocalTime.now().format(fmt)} [$tag] $message"
        lines.addLast(line)
        while (lines.size > 120) lines.removeFirst()
        runCatching { Log.i("Diya", line) }
    }

    @Synchronized
    fun all(): List<String> = lines.toList()
}
