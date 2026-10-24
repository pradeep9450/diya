package com.diya.assistant

import android.Manifest
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.telephony.TelephonyManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.vosk.Model
import kotlin.coroutines.cancellation.CancellationException

/**
 * The heart of Diya. Runs in the background (with a notification, as Android requires),
 * listens for "Hey Diya", hears your command, thinks, and talks back.
 */
class DiyaService : Service() {

    companion object {
        const val ACTION_START = "com.diya.assistant.START"
        const val ACTION_STOP = "com.diya.assistant.STOP"
        const val ACTION_LISTEN = "com.diya.assistant.LISTEN"
        const val ACTION_TEXT = "com.diya.assistant.TEXT"
        const val EXTRA_TEXT = "text"

        fun send(context: Context, action: String, text: String? = null) {
            val running = DiyaBus.status.value != Status.OFF
            if (action == ACTION_STOP && !running) return
            val intent = Intent(context, DiyaService::class.java).setAction(action)
            text?.let { intent.putExtra(EXTRA_TEXT, it) }
            if (running) context.startService(intent) else ContextCompat.startForegroundService(context, intent)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private lateinit var speaker: Speaker
    private lateinit var brain: Brain
    private lateinit var commands: CommandListener
    private var tone: ToneGenerator? = null
    private var model: Model? = null
    private var wake: WakeWordListener? = null
    private var busy = false
    private var job: Job? = null
    private var stopping = false
    private var phoneReceiver: BroadcastReceiver? = null
    private var announcedNumber: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
        if (!goForeground("Starting…")) return
        prefs = Prefs(this)
        speaker = Speaker(this)
        brain = Brain(this, prefs)
        commands = CommandListener(this)
        tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 70) }.getOrNull()
        DiyaBus.status.value = Status.PREPARING
        busy = false
        scope.launch { prepareWakeWord() }
        watchPhoneCalls()
        scope.launch {
            DiyaBus.newMessages.collect { n ->
                if (!prefs.announceMessages) return@collect
                announce(
                    if (prefs.hindi) "${n.app} पर ${n.title} का नया मैसेज: ${n.text}"
                    else "New ${n.app} message from ${n.title}: ${n.text}"
                )
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (stopping || !::brain.isInitialized) return START_NOT_STICKY
        when (intent?.action) {
            ACTION_STOP -> shutdown()
            ACTION_LISTEN -> listenForCommand(withChime = true)
            ACTION_TEXT -> intent.getStringExtra(EXTRA_TEXT)?.let { if (it.isNotBlank()) handleCommand(it) }
        }
        // Not sticky: Android doesn't allow restarting a microphone service from the background.
        return START_NOT_STICKY
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun goForeground(text: String): Boolean = try {
        // Location is included (when allowed) so "weather here" and "where am I" work from the background.
        val type = if (Build.VERSION.SDK_INT >= 30) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                (if (granted(Manifest.permission.ACCESS_COARSE_LOCATION)) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)
        } else 0
        ServiceCompat.startForeground(this, Notifications.ID_SERVICE, Notifications.serviceNotification(this, text), type)
        true
    } catch (e: Exception) {
        DiyaBus.detail.value = "Couldn't start Diya: ${e.message}"
        DiyaBus.status.value = Status.OFF
        stopping = true
        stopSelf()
        false
    }

    // ---------------- Wake word ----------------

    private suspend fun prepareWakeWord() {
        try {
            DiyaBus.detail.value = "Preparing the wake word…"
            val dir = VoskModelManager.ensureModel(this) { pct ->
                DiyaBus.detail.value = "First-time setup: downloading the wake-word model… $pct%"
            }
            DiyaBus.detail.value = "Loading the wake word…"
            val loaded = withContext(Dispatchers.IO) { Model(dir.absolutePath) }
            model = loaded
            wake = WakeWordListener(
                model = loaded,
                extraWords = { prefs.extraWakeWords },
                onHeard = { DiyaBus.heard.value = it },
                onWake = ::onWakeWord,
                onFailure = ::onWakeError,
            )
            DiyaBus.wakeReady.value = true
            DiyaBus.detail.value = ""
            if (!busy) goIdle()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DiyaBus.detail.value = "Wake word unavailable (${e.message}). You can still tap the mic."
            if (!busy) goIdle()
        }
    }

    private fun onWakeWord(command: String) {
        if (busy) return
        if (command.isNotBlank()) handleCommand(command) else listenForCommand(withChime = true)
    }

    private fun onWakeError(message: String) {
        DiyaBus.detail.value = "Wake word paused: $message. Retrying…"
        handler.postDelayed({
            if (!busy && !stopping) {
                DiyaBus.detail.value = ""
                wake?.start()
            }
        }, 5000)
    }

    // ---------------- Conversation flow ----------------

    private fun goIdle() {
        if (stopping) return
        busy = false
        DiyaBus.partial.value = ""
        DiyaBus.status.value = Status.WAITING
        val w = wake
        if (DiyaBus.inCall) {
            // The microphone belongs to the phone call; listen again when it ends.
            Notifications.updateService(this, "Paused during your call")
        } else if (w != null) {
            w.start()
            Notifications.updateService(this, "Say \"Hey Diya\"")
        } else {
            Notifications.updateService(this, "Tap Talk to speak to Diya")
        }
    }

    private fun listenForCommand(withChime: Boolean) {
        busy = true
        wake?.stop()
        speaker.stop()
        job?.cancel()
        if (!commands.isAvailable()) {
            job = scope.launch {
                say("Speech recognition isn't available on this phone. Please install or update the Google app.")
                goIdle()
            }
            return
        }
        DiyaBus.status.value = Status.LISTENING
        Notifications.updateService(this, "Listening…")
        job = scope.launch {
            if (withChime) chime()
            delay(250) // give the wake-word engine a moment to release the microphone
            commands.listen(
                language = if (prefs.hindi) "hi-IN" else "en-IN",
                onPartial = { DiyaBus.partial.value = it },
                onResult = { handleCommand(it) },
                onNothing = { error ->
                    if (error == null) {
                        goIdle()
                    } else {
                        job = scope.launch {
                            say(error)
                            goIdle()
                        }
                    }
                },
            )
        }
    }

    private fun handleCommand(text: String) {
        busy = true
        wake?.stop()
        commands.cancel()
        speaker.stop()
        job?.cancel()
        DiyaBus.partial.value = ""
        DiyaBus.addChat(fromUser = true, text = text)
        DiyaBus.status.value = Status.THINKING
        Notifications.updateService(this, "Thinking…")
        job = scope.launch {
            val reply = try {
                brain.handle(text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Reply("Sorry, something went wrong. ${e.message ?: ""}".trim())
            }
            if (reply.speech.isNotBlank()) {
                DiyaBus.addChat(fromUser = false, text = reply.speech)
                say(reply.speech)
            }
            reply.afterSpeech?.invoke()
            if (reply.followUp) listenForCommand(withChime = false) else goIdle()
        }
    }

    private suspend fun say(text: String) {
        DiyaBus.status.value = Status.SPEAKING
        Notifications.updateService(this, "Speaking…")
        speaker.speak(text)
    }

    /** Speaks something Diya wasn't asked (a caller's name, a new message), if she's free. */
    private fun announce(text: String) {
        if (busy || stopping || DiyaBus.inCall) return
        busy = true
        wake?.stop()
        job = scope.launch {
            DiyaBus.addChat(fromUser = false, text = text)
            say(text)
            goIdle()
        }
    }

    // ---------------- Phone calls ----------------

    private fun watchPhoneCalls() {
        if (!granted(Manifest.permission.READ_PHONE_STATE)) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.getStringExtra(TelephonyManager.EXTRA_STATE)) {
                    TelephonyManager.EXTRA_STATE_RINGING -> {
                        DiyaBus.ringing = true
                        @Suppress("DEPRECATION")
                        val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
                        if (number != null && number != announcedNumber && prefs.announceCalls) {
                            announcedNumber = number
                            val name = PhoneActions(this@DiyaService).contactName(number)
                                ?: number.filter { it.isDigit() }.toList().joinToString(" ")
                            announce(if (prefs.hindi) "$name का कॉल आ रहा है" else "$name is calling")
                        }
                    }
                    TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                        DiyaBus.ringing = false
                        DiyaBus.inCall = true
                        job?.cancel()
                        speaker.stop()
                        commands.cancel()
                        wake?.stop()
                        busy = false
                        goIdle()
                    }
                    TelephonyManager.EXTRA_STATE_IDLE -> {
                        DiyaBus.ringing = false
                        DiyaBus.inCall = false
                        announcedNumber = null
                        if (!busy) goIdle()
                    }
                }
            }
        }
        ContextCompat.registerReceiver(
            this, receiver, IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED
        )
        phoneReceiver = receiver
    }

    private suspend fun chime() {
        tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 120)
        delay(180)
    }

    // ---------------- Shutdown ----------------

    private fun shutdown() {
        stopping = true
        job?.cancel()
        wake?.stop()
        commands.cancel()
        speaker.stop()
        DiyaBus.status.value = Status.OFF
        DiyaBus.detail.value = ""
        DiyaBus.partial.value = ""
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopping = true
        handler.removeCallbacksAndMessages(null)
        phoneReceiver?.let { runCatching { unregisterReceiver(it) } }
        phoneReceiver = null
        DiyaBus.ringing = false
        DiyaBus.inCall = false
        scope.cancel()
        wake?.stop()
        wake = null
        model?.close()
        model = null
        if (::commands.isInitialized) commands.cancel()
        if (::speaker.isInitialized) speaker.shutdown()
        tone?.release()
        DiyaBus.status.value = Status.OFF
        DiyaBus.wakeReady.value = false
        DiyaBus.partial.value = ""
        super.onDestroy()
    }
}
