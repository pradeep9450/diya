package com.diya.assistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Uses the phone's built-in (free) speech recognizer to understand what you say
 * after "Hey Diya". It's far more accurate than the small offline wake-word model.
 */
class CommandListener(private val context: Context) {
    private var recognizer: SpeechRecognizer? = null
    private var finished = true
    private val main = Handler(Looper.getMainLooper())

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    /** [language] is "hi-IN" for Hindi or "en-IN" for English. */
    fun listen(
        language: String,
        onPartial: (String) -> Unit,
        onResult: (String) -> Unit,
        onNothing: (error: String?) -> Unit,
    ) {
        cancel()
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        finished = false
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.let { if (it.isNotBlank()) onPartial(it) }
            }

            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
                finish { if (text.isBlank()) onNothing(null) else onResult(text) }
            }

            override fun onError(error: Int) {
                val message = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                    SpeechRecognizer.ERROR_CLIENT -> null
                    SpeechRecognizer.ERROR_NETWORK,
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                    SpeechRecognizer.ERROR_SERVER -> "I couldn't reach the speech service. Please check your internet."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "I need microphone permission to hear you."
                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                    SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "This phone can't recognise that language yet. Open the Google app once and download Hindi for offline speech."
                    SpeechRecognizer.ERROR_AUDIO -> "The microphone seems to be busy."
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "The speech recognizer is busy. Please try again."
                    else -> null
                }
                finish { onNothing(message) }
            }
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
        r.startListening(intent)
    }

    fun cancel() {
        finished = true
        val r = recognizer ?: return
        recognizer = null
        runCatching { r.cancel() }
        runCatching { r.destroy() }
    }

    private fun finish(block: () -> Unit) {
        if (finished) return
        finished = true
        val r = recognizer
        recognizer = null
        // Destroy outside of the recognizer's own callback.
        main.post { runCatching { r?.destroy() } }
        block()
    }
}
