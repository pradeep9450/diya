package com.diya.assistant

import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Diya's voice: the phone's free built-in text-to-speech, in Indian English and Hindi. */
class Speaker(context: Context) : TextToSpeech.OnInitListener {
    private val ready = CompletableDeferred<Boolean>()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private var english: Locale = Locale.US
    private var current: Locale? = null

    /** False if the phone has no Hindi voice installed (Settings → Install Hindi voice). */
    @Volatile
    var hindiAvailable = false
        private set

    private val tts = TextToSpeech(context.applicationContext, this)

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            ready.complete(false)
            return
        }
        english = if (usable(EN_IN)) EN_IN else Locale.US
        hindiAvailable = usable(HI_IN)
        tts.setLanguage(english)
        current = english
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = finish(utteranceId)

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finish(utteranceId)
            override fun onError(utteranceId: String?, errorCode: Int) = finish(utteranceId)
            override fun onStop(utteranceId: String?, interrupted: Boolean) = finish(utteranceId)
        })
        ready.complete(true)
    }

    private fun usable(l: Locale): Boolean = runCatching {
        tts.isLanguageAvailable(l) >= TextToSpeech.LANG_AVAILABLE
    }.getOrDefault(false)

    /** Hindi text (Devanagari) is spoken with the Hindi voice, everything else with the Indian-English voice. */
    private fun pickVoice(text: String) {
        val want = if (Hindi.hasDevanagari(text) && hindiAvailable) HI_IN else english
        if (want != current) {
            tts.setLanguage(want)
            current = want
        }
    }

    private fun finish(id: String?) {
        if (id != null) pending.remove(id)?.complete(Unit)
    }

    /** Speaks [text] and suspends until Diya has finished talking (or was stopped). */
    suspend fun speak(text: String) {
        if (text.isBlank()) return
        val ok = withTimeoutOrNull(5000) { ready.await() } ?: false
        if (!ok) return
        var clipped = text.take(TextToSpeech.getMaxSpeechInputLength() - 1)
        // No Hindi voice installed: read the Hindi in English letters so it's still understandable.
        if (Hindi.hasDevanagari(clipped) && !hindiAvailable) {
            clipped = clipped.split(Regex("[।.?!]+")).map { Translit.toLatin(it.trim()) }
                .filter { it.isNotBlank() }.joinToString(". ")
        }
        pickVoice(clipped)
        val id = UUID.randomUUID().toString()
        val done = CompletableDeferred<Unit>()
        pending[id] = done
        val params = Bundle().apply { putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC) }
        if (tts.speak(clipped, TextToSpeech.QUEUE_FLUSH, params, id) != TextToSpeech.SUCCESS) {
            pending.remove(id)
            return
        }
        withTimeoutOrNull(clipped.length * 150L + 8000L) { done.await() }
        pending.remove(id)
    }

    fun stop() {
        runCatching { tts.stop() }
        pending.values.forEach { it.complete(Unit) }
        pending.clear()
    }

    companion object {
        private val EN_IN: Locale = Locale.forLanguageTag("en-IN")
        private val HI_IN: Locale = Locale.forLanguageTag("hi-IN")
    }

    fun shutdown() {
        stop()
        runCatching { tts.shutdown() }
    }
}
