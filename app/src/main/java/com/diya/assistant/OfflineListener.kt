package com.diya.assistant

import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService

/**
 * Offline speech-to-text with the same free Vosk model as the wake word.
 * Used when the phone's recognizer has network trouble: no internet needed at all.
 * Understands English (Indian accent) best.
 */
class OfflineListener(private val model: () -> Model?) {
    private var service: SpeechService? = null
    private var recognizer: Recognizer? = null
    private var done = true

    fun isAvailable() = model() != null

    fun listen(
        onPartial: (String) -> Unit,
        onResult: (String) -> Unit,
        onFail: (reason: ListenFail, spoke: Boolean, code: Int) -> Unit,
    ) {
        cancel()
        val m = model() ?: return onFail(ListenFail.OTHER, false, -10)
        var partial = ""
        try {
            val rec = Recognizer(m, 16000f)
            recognizer = rec
            val svc = SpeechService(rec, 16000f)
            service = svc
            done = false
            svc.startListening(object : RecognitionListener {
                override fun onPartialResult(hypothesis: String?) {
                    val t = text(hypothesis, "partial")
                    if (t.isNotBlank() && t != partial) {
                        partial = t
                        onPartial(t)
                    }
                }

                override fun onResult(hypothesis: String?) {
                    // Called when you pause; ignore pauses before you've said anything.
                    val t = text(hypothesis, "text")
                    if (t.isNotBlank()) finish { onResult(t) }
                }

                override fun onFinalResult(hypothesis: String?) {
                    val t = text(hypothesis, "text").ifBlank { partial }
                    finish { if (t.isNotBlank()) onResult(t) else onFail(ListenFail.NO_SPEECH, false, 0) }
                }

                override fun onError(exception: Exception?) {
                    finish { onFail(ListenFail.AUDIO, partial.isNotBlank(), -11) }
                }

                override fun onTimeout() {
                    finish { if (partial.isNotBlank()) onResult(partial) else onFail(ListenFail.NO_SPEECH, false, 0) }
                }
            }, 9_000)
        } catch (e: Exception) {
            cancel()
            onFail(ListenFail.AUDIO, false, -12)
        }
    }

    private fun text(json: String?, key: String): String =
        runCatching { JSONObject(json ?: "").optString(key) }.getOrDefault("")
            .replace("[unk]", "").replace(Regex("\\s+"), " ").trim()

    private fun finish(block: () -> Unit) {
        if (done) return
        done = true
        release()
        block()
    }

    fun cancel() {
        done = true
        release()
    }

    private fun release() {
        service?.let {
            runCatching { it.cancel() }
            runCatching { it.shutdown() }
        }
        service = null
        recognizer?.let { runCatching { it.close() } }
        recognizer = null
    }
}
