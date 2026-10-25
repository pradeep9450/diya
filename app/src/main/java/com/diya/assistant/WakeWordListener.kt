package com.diya.assistant

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer

/**
 * Listens continuously and offline for "Hey Diya" using the two-step check in [WakeWord]:
 * a fast phrase detector, then a full-vocabulary re-check of the last few seconds of audio.
 * Wakes about half a second after you finish saying "Hey Diya". If you carry straight on
 * with a command, it keeps listening offline until you stop and passes the command along.
 */
class WakeWordListener(
    private val model: Model,
    private val rules: WakeRules,
    private val extraWords: () -> List<String>,
    private val onHeard: (String) -> Unit,
    /** Called with the command if you said it in the same breath ("Hey Diya, what's the time"), else null. */
    private val onWake: (command: String?) -> Unit,
    private val onFailure: (String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var running = false
    private var thread: Thread? = null

    val active: Boolean get() = running

    fun start() {
        if (running) return
        running = true
        val extra = extraWords()
        thread = Thread({ loop(extra) }, "diya-wake").also { it.start() }
    }

    fun stop() {
        running = false
        val t = thread
        thread = null
        if (t != null && t != Thread.currentThread()) runCatching { t.join(1500) }
    }

    @SuppressLint("MissingPermission")
    private fun loop(extra: List<String>) {
        var recorder: AudioRecord? = null
        var fast: Recognizer? = null
        var check: Recognizer? = null
        try {
            fast = Recognizer(model, RATE.toFloat(), WakeWord.grammarJson(rules, extra))
            val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, RATE * 2) // ~1 s of headroom
            )
            if (recorder.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("microphone not available")
            recorder.startRecording()
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw IllegalStateException("microphone is busy")

            val ring = ShortArray((RATE * rules.verifySeconds).toInt())
            var ringPos = 0
            var ringFilled = 0
            val chunk = ShortArray(RATE / 10) // 0.1 s
            var samples = 0L
            var verifyAt = -1L
            var candidateText = ""
            var lastShown = ""

            while (running) {
                val n = recorder.read(chunk, 0, chunk.size)
                if (n < 0) throw IllegalStateException("microphone read error $n")
                if (n == 0) continue
                for (i in 0 until n) {
                    ring[ringPos] = chunk[i]
                    ringPos = (ringPos + 1) % ring.size
                }
                ringFilled = minOf(ring.size, ringFilled + n)
                samples += n

                val isFinal = fast.acceptWaveForm(chunk, n)
                val text = clean(
                    if (isFinal) JSONObject(fast.result).optString("text")
                    else JSONObject(fast.partialResult).optString("partial")
                )
                if (text.isNotBlank() && text != lastShown) {
                    lastShown = text
                    main.post { onHeard(text) }
                }
                if (isFinal && text.isNotBlank()) DiyaLog.add("wake", "heard \"$text\"")
                if (verifyAt < 0 && WakeWord.candidate(text, rules, extra)) {
                    candidateText = text
                    verifyAt = samples + RATE * rules.verifyDelayMs / 1000
                }
                if (verifyAt in 0..samples) {
                    verifyAt = -1
                    // Step 2: re-check the last seconds with full vocabulary.
                    val audio = ShortArray(ringFilled)
                    val start = (ringPos - ringFilled + ring.size) % ring.size
                    for (i in 0 until ringFilled) audio[i] = ring[(start + i) % ring.size]
                    val c = check ?: Recognizer(model, RATE.toFloat()).also { check = it }
                    c.reset()
                    c.acceptWaveForm(audio, audio.size)
                    val heard = clean(JSONObject(c.finalResult).optString("text"))
                    if (WakeWord.vetoed(heard, rules)) {
                        DiyaLog.add("wake", "\"$candidateText\" ignored — sounded like \"$heard\"")
                        main.post { onHeard("$heard (not Diya)") }
                        fast.reset()
                    } else {
                        DiyaLog.add("wake", "woke: \"$candidateText\" / check \"$heard\"")
                        val oneBreath = WakeWord.afterWake(heard).split(" ").count { it.isNotBlank() } >= 2
                        val command = if (oneBreath) captureRest(recorder, audio, chunk) else null
                        running = false
                        main.post { onWake(command) }
                    }
                }
            }
        } catch (e: Throwable) {
            if (running) {
                running = false
                val msg = e.message ?: e.javaClass.simpleName
                main.post { onFailure(msg) }
            }
        } finally {
            runCatching { recorder?.stop() }
            runCatching { recorder?.release() }
            runCatching { fast?.close() }
            runCatching { check?.close() }
        }
    }

    /**
     * You said the command in the same breath: keep transcribing (offline) until you pause,
     * starting from the buffered audio, and return what came after "Hey Diya".
     */
    private fun captureRest(recorder: AudioRecord, buffered: ShortArray, chunk: ShortArray): String? {
        var rec: Recognizer? = null
        return try {
            rec = Recognizer(model, RATE.toFloat())
            val parts = mutableListOf<String>()
            var i = 0
            while (i < buffered.size) {
                val n = minOf(chunk.size, buffered.size - i)
                if (rec.acceptWaveForm(buffered.copyOfRange(i, i + n), n)) parts += clean(JSONObject(rec.result).optString("text"))
                i += n
            }
            val deadline = System.currentTimeMillis() + 6_000
            while (running && System.currentTimeMillis() < deadline) {
                val n = recorder.read(chunk, 0, chunk.size)
                if (n <= 0) break
                if (rec.acceptWaveForm(chunk, n)) {
                    parts += clean(JSONObject(rec.result).optString("text"))
                    break // you paused: the command is complete
                }
            }
            if (parts.isEmpty() || System.currentTimeMillis() >= deadline) parts += clean(JSONObject(rec.finalResult).optString("text"))
            val full = parts.filter { it.isNotBlank() }.joinToString(" ")
            WakeWord.afterWake(full).ifBlank { null }.also { DiyaLog.add("wake", "same-breath command: \"$full\"") }
        } catch (e: Exception) {
            null
        } finally {
            runCatching { rec?.close() }
        }
    }

    private fun clean(s: String) = s.replace("[unk]", " ").replace(Regex("\\s+"), " ").trim()

    companion object {
        private const val RATE = 16000
    }
}
