package com.diya.assistant

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/** Why listening ended without text. */
enum class ListenFail { NO_SPEECH, NETWORK, AUDIO, BUSY, PERMISSION, LANGUAGE, STUCK, OTHER }

/**
 * Uses the phone's speech recognizer (Google's when installed) to understand what you say
 * after "Hey Diya". It's the most accurate option and understands English, Hinglish and Hindi.
 * If it fails, Diya falls back to [OfflineListener].
 */
class CommandListener(private val context: Context) {
    private var recognizer: SpeechRecognizer? = null
    private var finished = true
    private val main = Handler(Looper.getMainLooper())
    private var watchdog: Runnable? = null

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    /** Google's recognizer if installed, otherwise the phone's default. */
    fun preferredService(): ComponentName? = runCatching {
        @Suppress("DEPRECATION")
        val services = context.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
        val google = services.firstOrNull { it.serviceInfo.packageName == GOOGLE_APP }
        google?.let { ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) }
    }.getOrNull()

    /**
     * [language] is "hi-IN" or "en-IN". [onFail] gets the reason and whether you had started speaking.
     */
    fun listen(
        language: String,
        onPartial: (String) -> Unit,
        onResult: (String) -> Unit,
        onFail: (reason: ListenFail, spoke: Boolean, code: Int) -> Unit,
    ) {
        cancel()
        val service = preferredService()
        val r = try {
            if (service != null) SpeechRecognizer.createSpeechRecognizer(context, service)
            else SpeechRecognizer.createSpeechRecognizer(context)
        } catch (e: Exception) {
            DiyaLog.add("speech", "couldn't create recognizer: ${e.message}")
            onFail(ListenFail.OTHER, false, -1)
            return
        }
        recognizer = r
        finished = false
        var spoke = false
        var ready = false
        var lastPartial = ""

        fun arm(ms: Long, reason: ListenFail) {
            watchdog?.let { main.removeCallbacks(it) }
            val w = Runnable {
                // If we already heard words, use them instead of failing.
                if (lastPartial.isNotBlank()) finish { onResult(lastPartial) }
                else finish { onFail(reason, spoke, -2) }
            }
            watchdog = w
            main.postDelayed(w, ms)
        }

        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                ready = true
                arm(9_000, ListenFail.NO_SPEECH)
            }

            override fun onBeginningOfSpeech() {
                spoke = true
                arm(15_000, ListenFail.STUCK)
            }

            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                arm(7_000, ListenFail.STUCK)
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.let {
                        if (it.isNotBlank()) {
                            spoke = true
                            lastPartial = it
                            onPartial(it)
                        }
                    }
            }

            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty().ifBlank { lastPartial }
                finish { if (text.isBlank()) onFail(ListenFail.NO_SPEECH, spoke, 0) else onResult(text) }
            }

            override fun onError(error: Int) {
                val reason = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> ListenFail.NO_SPEECH
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER,
                    SpeechRecognizer.ERROR_SERVER_DISCONNECTED, SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> ListenFail.NETWORK
                    SpeechRecognizer.ERROR_AUDIO -> ListenFail.AUDIO
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> ListenFail.BUSY
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> ListenFail.PERMISSION
                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> ListenFail.LANGUAGE
                    else -> ListenFail.OTHER
                }
                // Words already heard are better than an error.
                if (lastPartial.isNotBlank() && reason != ListenFail.PERMISSION) {
                    finish { onResult(lastPartial) }
                } else {
                    finish { onFail(reason, spoke, error) }
                }
            }
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        }
        try {
            r.startListening(intent)
        } catch (e: Exception) {
            finish { onFail(ListenFail.OTHER, false, -3) }
            return
        }
        // Some recognizers never answer at all; don't wait forever.
        main.postDelayed({ if (!finished && !ready) arm(0, ListenFail.STUCK) }, 4_000)
    }

    fun cancel() {
        finished = true
        watchdog?.let { main.removeCallbacks(it) }
        watchdog = null
        val r = recognizer ?: return
        recognizer = null
        runCatching { r.cancel() }
        runCatching { r.destroy() }
    }

    private fun finish(block: () -> Unit) {
        if (finished) return
        finished = true
        watchdog?.let { main.removeCallbacks(it) }
        watchdog = null
        val r = recognizer
        recognizer = null
        // Destroy outside of the recognizer's own callback.
        main.post { runCatching { r?.cancel() }; runCatching { r?.destroy() } }
        block()
    }

    companion object {
        const val GOOGLE_APP = "com.google.android.googlequicksearchbox"
    }
}
