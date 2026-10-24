package com.diya.assistant

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService

/**
 * Listens continuously and offline for "Hey Diya".
 *
 * If you say the wake word and pause, [onWake] is called with an empty command
 * (Diya chimes and listens). If you say it in one go ("Hey Diya what's the time"),
 * [onWake] receives the rest of the sentence.
 */
class WakeWordListener(
    private val model: Model,
    private val extraWords: () -> List<String>,
    private val onHeard: (String) -> Unit,
    private val onWake: (command: String) -> Unit,
    private val onFailure: (String) -> Unit,
) : RecognitionListener {

    private var recognizer: Recognizer? = null
    private var service: SpeechService? = null
    private var active = false
    private var woke = false
    private var trailing = ""
    private val handler = Handler(Looper.getMainLooper())
    private val giveUp = Runnable { fire(trailing) }

    fun start() {
        if (active) return
        try {
            val rec = Recognizer(model, SAMPLE_RATE)
            recognizer = rec
            val svc = SpeechService(rec, SAMPLE_RATE)
            service = svc
            woke = false
            trailing = ""
            active = true
            svc.startListening(this)
        } catch (e: Exception) {
            stop()
            onFailure(e.message ?: "microphone unavailable")
        }
    }

    fun stop() {
        handler.removeCallbacks(giveUp)
        active = false
        service?.let {
            runCatching { it.cancel() }
            runCatching { it.shutdown() }
        }
        service = null
        recognizer?.let { runCatching { it.close() } }
        recognizer = null
    }

    override fun onPartialResult(hypothesis: String?) = inspect(hypothesis, "partial", isFinal = false)

    override fun onResult(hypothesis: String?) = inspect(hypothesis, "text", isFinal = true)

    override fun onFinalResult(hypothesis: String?) {}

    override fun onTimeout() {}

    override fun onError(exception: Exception?) {
        stop()
        onFailure(exception?.message ?: "microphone error")
    }

    private fun inspect(json: String?, key: String, isFinal: Boolean) {
        if (!active || json == null) return
        val text = runCatching { JSONObject(json).optString(key) }.getOrDefault("")
        if (text.isBlank()) return
        onHeard(text)
        val match = WakeWord.find(text, extraWords())
        if (!woke) {
            if (match == null) return
            woke = true
            trailing = match.trailing
            if (isFinal) fire(trailing) else handler.postDelayed(giveUp, 6000)
        } else {
            if (match != null) trailing = match.trailing
            if (isFinal) fire(trailing)
        }
    }

    private fun fire(command: String) {
        if (!active) return
        stop()
        val words = command.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        // One stray word after the wake word is usually noise, so only pass real sentences on.
        onWake(if (words.size >= 2) command.trim() else "")
    }

    companion object {
        private const val SAMPLE_RATE = 16000f
    }
}
