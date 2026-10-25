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
 * Prepares the free offline Vosk model (Indian English) used for "Hey Diya" and
 * for offline speech recognition. The GitHub build packs the model inside the APK,
 * so it works on first launch without internet. Builds without it download it once.
 */
object VoskModelManager {
    private const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-in-0.4.zip"
    private const val ASSET_DIR = "model-en-in"
    private const val VERSION = "en-in-0.4"

    fun hasBundledModel(context: Context): Boolean =
        runCatching { context.assets.list(ASSET_DIR)?.isNotEmpty() == true }.getOrDefault(false)

    suspend fun ensureModel(context: Context, onProgress: (String) -> Unit): File = withContext(Dispatchers.IO) {
        val root = File(context.filesDir, "vosk")
        val marker = File(root, ".ready")
        if (marker.exists() && marker.readText() == VERSION) {
            findModelDir(root)?.let { return@withContext it }
        }
        root.deleteRecursively()
        root.mkdirs()
        if (hasBundledModel(context)) {
            onProgress("First-time setup: unpacking the wake-word model…")
            copyAssets(context, ASSET_DIR, File(root, "model"))
            DiyaLog.add("model", "unpacked bundled model")
        } else {
            val zip = File(context.cacheDir, "vosk-model.zip")
            try {
                download(MODEL_URL, zip) { pct -> onProgress("First-time setup: downloading the wake-word model… $pct%") }
                unzip(zip, root)
            } finally {
                zip.delete()
            }
            DiyaLog.add("model", "downloaded model")
        }
        val dir = findModelDir(root) ?: throw IOException("the wake-word model looks incomplete")
        marker.writeText(VERSION)
        dir
    }

    private fun findModelDir(root: File): File? {
        if (File(root, "am").isDirectory) return root
        root.listFiles()?.forEach { d ->
            if (d.isDirectory && File(d, "am").isDirectory) return d
            d.listFiles()?.firstOrNull { it.isDirectory && File(it, "am").isDirectory }?.let { return it }
        }
        return null
    }

    private fun copyAssets(context: Context, path: String, dest: File) {
        val children = context.assets.list(path).orEmpty()
        if (children.isEmpty()) {
            dest.parentFile?.mkdirs()
            context.assets.open(path).use { input -> FileOutputStream(dest).use { input.copyTo(it) } }
            return
        }
        dest.mkdirs()
        for (c in children) copyAssets(context, "$path/$c", File(dest, c))
    }

    private fun download(url: String, target: File, onProgress: (Int) -> Unit) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
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
