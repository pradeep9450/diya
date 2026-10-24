package com.diya.assistant

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Downloads the free offline Vosk model (Indian English, about 36 MB) the first
 * time Diya starts. After that the wake word works with no internet at all.
 */
object VoskModelManager {
    private const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-in-0.4.zip"

    suspend fun ensureModel(context: Context, onProgress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        val root = File(context.filesDir, "vosk")
        val marker = File(root, ".ready")
        if (marker.exists()) {
            findModelDir(root)?.let { return@withContext it }
        }
        root.deleteRecursively()
        root.mkdirs()
        val zip = File(context.cacheDir, "vosk-model.zip")
        try {
            download(MODEL_URL, zip, onProgress)
            unzip(zip, root)
        } finally {
            zip.delete()
        }
        val dir = findModelDir(root) ?: throw IOException("the downloaded model looks incomplete")
        marker.createNewFile()
        dir
    }

    private fun findModelDir(root: File): File? {
        if (File(root, "am").isDirectory) return root
        return root.listFiles()?.firstOrNull { it.isDirectory && File(it, "am").isDirectory }
    }

    private fun download(url: String, target: File, onProgress: (Int) -> Unit) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        try {
            if (conn.responseCode !in 200..299) throw IOException("download failed (HTTP ${conn.responseCode})")
            val total = conn.contentLengthLong
            var done = 0L
            var lastPct = -1
            conn.inputStream.use { input ->
                FileOutputStream(target).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress(pct)
                            }
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun unzip(zip: File, dest: File) {
        val destPath = dest.canonicalPath + File.separator
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                val out = File(dest, entry.name)
                if (!out.canonicalPath.startsWith(destPath)) throw IOException("bad zip entry")
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { zis.copyTo(it) }
                }
                zis.closeEntry()
            }
        }
    }
}
