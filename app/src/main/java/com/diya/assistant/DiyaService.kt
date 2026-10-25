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
import android.os.SystemClock
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
import kotlinx.coroutines.withTimeoutOrNull
import org.vosk.Model
import kotlin.coroutines.cancellation.CancellationException

/**
 * The heart of Diya. Runs in the background (with a notification, as Android requires):
 * listens for "Hey Diya", hears your command, thinks, talks back, and keeps the
 * conversation going until you stop talking.
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
            try {
                if (running) context.startService(intent) else ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                DiyaLog.add("service", "couldn't start: ${e.message}")
                DiyaBus.detail.value = "Couldn't start Diya: ${e.message}"
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private lateinit var speaker: Speaker
    private lateinit var brain: Brain
    private lateinit var commands: CommandListener
    private lateinit var offline: OfflineListener
    private var tone: ToneGenerator? = null
    private var model: Model? = null
    private var wake: WakeWordListener? = null
    private var busy = false
    private var job: Job? = null
    private var stopping = false
    private var phoneReceiver: BroadcastReceiver? = null
    private var announcedNumber: String? = null

    /** After repeated network errors from the phone's recognizer, use offline listening for a while. */
    private var onlineFailures = 0
    private var offlineUntil = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
        if (!goForeground("Starting…")) return
        prefs = Prefs(this)
        speaker = Speaker(this)
        brain = Brain(this, prefs)
        commands = CommandListener(this)
        offline = OfflineListener { model }
        tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 70) }.getOrNull()
        DiyaBus.status.value = Status.PREPARING
        DiyaLog.add("service", "started (Android ${Build.VERSION.RELEASE}, ${Build.MANUFACTURER} ${Build.MODEL})")
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
        handler.postDelayed(healthCheck, 15_000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (stopping || !::brain.isInitialized) return START_NOT_STICKY
        when (intent?.action) {
            ACTION_STOP -> shutdown()
            ACTION_LISTEN -> listenForCommand(withChime = true, followUp = false)
            ACTION_TEXT -> intent.getStringExtra(EXTRA_TEXT)?.let { if (it.isNotBlank()) handleCommand(it, fromVoice = false) }
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
        DiyaLog.add("service", "startForeground failed: ${e.message}")
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
            val dir = VoskModelManager.ensureModel(this) { msg -> DiyaBus.detail.value = msg }
            DiyaBus.detail.value = "Loading the wake word…"
            val loaded = withContext(Dispatchers.IO) { Model(dir.absolutePath) }
            model = loaded
            val rules = runCatching { assets.open("wake_rules.json").bufferedReader().use { WakeRules.parse(it.readText()) } }
                .getOrDefault(WakeRules.DEFAULT)
            wake = WakeWordListener(
                model = loaded,
                rules = rules,
                extraWords = { prefs.extraWakeWords },
                onHeard = { DiyaBus.heard.value = it },
                onWake = ::onWakeWord,
                onFailure = ::onWakeError,
            )
            DiyaBus.wakeReady.value = true
            DiyaBus.detail.value = ""
            DiyaLog.add("wake", "ready (${rules.grammar.size} phrases)")
            if (!busy) goIdle()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            DiyaLog.add("wake", "unavailable: ${e.javaClass.simpleName} ${e.message}")
            DiyaBus.detail.value = "Wake word unavailable (${e.message}). You can still tap the mic."
            if (!busy) goIdle()
        }
    }

    private fun onWakeWord(command: String?) {
        if (busy || stopping) return
        when {
            command == null -> listenForCommand(withChime = true, followUp = false)
            // Said in one breath and understood offline: just do it.
            Understand.parse(command).cmd != null || Knowledge.local(Text.normalize(command), false) != null -> {
                chimeThen { handleCommand(command, fromVoice = true) }
            }
            // Heard something after "Hey Diya" but not clearly: ask again.
            else -> {
                busy = true
                job = scope.launch {
                    chime()
                    say(r("Yes? Please say that again.", "जी? फिर से बोलिए।"))
                    listenForCommand(withChime = false, followUp = false)
                }
            }
        }
    }

    private fun chimeThen(block: () -> Unit) {
        busy = true
        job = scope.launch {
            chime()
            block()
        }
    }

    private fun onWakeError(message: String) {
        DiyaLog.add("wake", "paused: $message")
        DiyaBus.detail.value = "Wake word paused: $message. Retrying…"
        handler.postDelayed({
            if (!busy && !stopping && !DiyaBus.inCall) {
                DiyaBus.detail.value = ""
                wake?.start()
            }
        }, 5000)
    }

    /** Makes sure Diya is really listening for "Hey Diya" whenever she's idle. */
    private val healthCheck = object : Runnable {
        override fun run() {
            if (stopping) return
            val w = wake
            if (!busy && !DiyaBus.inCall && w != null && !w.active && DiyaBus.status.value == Status.WAITING) {
                DiyaLog.add("health", "wake word wasn't running, restarting")
                w.start()
            }
            handler.postDelayed(this, 15_000)
        }
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

    private fun useOffline(): Boolean =
        offline.isAvailable() && (prefs.speechEngine == "offline" || !commands.isAvailable() || SystemClock.elapsedRealtime() < offlineUntil)

    /**
     * Listens for one command. [followUp] = conversation mode after a reply: if you
     * say nothing, Diya quietly goes back to waiting for "Hey Diya".
     */
    private fun listenForCommand(withChime: Boolean, followUp: Boolean) {
        busy = true
        wake?.stop()
        speaker.stop()
        job?.cancel()
        commands.cancel()
        offline.cancel()
        if (!commands.isAvailable() && !offline.isAvailable()) {
            job = scope.launch {
                say(r("Speech recognition isn't available on this phone. Please install or update the Google app.", "इस फ़ोन पर स्पीच रिकग्निशन उपलब्ध नहीं है। कृपया Google ऐप इंस्टॉल या अपडेट कीजिए।"))
                goIdle()
            }
            return
        }
        DiyaBus.status.value = Status.LISTENING
        Notifications.updateService(this, "Listening…")
        job = scope.launch {
            if (withChime) chime() else softChime()
            delay(200) // let the wake-word engine release the microphone
            if (useOffline()) listenOffline(followUp) else listenOnline(followUp, retry = true)
        }
    }

    private fun listenOnline(followUp: Boolean, retry: Boolean) {
        commands.listen(
            language = if (prefs.hindi) "hi-IN" else "en-IN",
            onPartial = { DiyaBus.partial.value = it },
            onResult = {
                onlineFailures = 0
                handleCommand(it, fromVoice = true)
            },
            onFail = { reason, spoke, code ->
                DiyaLog.add("speech", "online failed: $reason (code $code, spoke=$spoke)")
                when (reason) {
                    ListenFail.NO_SPEECH -> goIdle()
                    ListenFail.PERMISSION -> scope.launch {
                        say(r("I need microphone permission to hear you.", "मुझे सुनने के लिए माइक्रोफ़ोन की अनुमति चाहिए।"))
                        goIdle()
                    }
                    else -> {
                        if (reason == ListenFail.NETWORK || reason == ListenFail.STUCK) {
                            onlineFailures++
                            if (onlineFailures >= 2) {
                                offlineUntil = SystemClock.elapsedRealtime() + 10 * 60_000L
                                DiyaLog.add("speech", "switching to offline listening for 10 minutes")
                            }
                        }
                        when {
                            // Seamless: you hadn't started talking, so just keep listening offline.
                            offline.isAvailable() && !spoke -> listenOffline(followUp)
                            offline.isAvailable() -> job = scope.launch {
                                say(r("Sorry, I missed that. Please say it again.", "माफ़ कीजिए, सुन नहीं पाई। फिर से बोलिए।"))
                                listenOffline(followUp)
                            }
                            retry -> handler.postDelayed({ if (busy && !stopping) listenOnline(followUp, retry = false) }, 400)
                            else -> job = scope.launch {
                                say(
                                    if (reason == ListenFail.NETWORK) r("I couldn't reach the speech service. Please try again.", "स्पीच सर्विस से जुड़ नहीं पाई। फिर से कोशिश कीजिए।")
                                    else r("Sorry, I couldn't hear you properly. Please try again.", "माफ़ कीजिए, ठीक से सुन नहीं पाई। फिर से कोशिश कीजिए।")
                                )
                                goIdle()
                            }
                        }
                    }
                }
            },
        )
    }

    private fun listenOffline(followUp: Boolean) {
        if (stopping) return
        DiyaBus.status.value = Status.LISTENING
        offline.listen(
            onPartial = { DiyaBus.partial.value = it },
            onResult = { handleCommand(it, fromVoice = true) },
            onFail = { reason, _, code ->
                DiyaLog.add("speech", "offline: $reason (code $code)")
                goIdle()
            },
        )
    }

    private fun handleCommand(text: String, fromVoice: Boolean) {
        DiyaLog.add("heard", (if (fromVoice) "voice: " else "typed: ") + text)
        busy = true
        wake?.stop()
        commands.cancel()
        offline.cancel()
        speaker.stop()
        job?.cancel()
        DiyaBus.partial.value = ""
        DiyaBus.addChat(fromUser = true, text = text)
        DiyaBus.status.value = Status.THINKING
        Notifications.updateService(this, "Thinking…")
        job = scope.launch {
            val reply = try {
                withTimeoutOrNull(35_000) { brain.handle(text) }
                    ?: Reply(r("Sorry, that took too long. Please try again.", "माफ़ कीजिए, बहुत देर हो गई। फिर से कोशिश कीजिए।"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DiyaLog.add("brain", "error: ${e.javaClass.simpleName} ${e.message}")
                Reply(r("Sorry, something went wrong.", "माफ़ कीजिए, कुछ गड़बड़ हो गई।"))
            }
            DiyaLog.add("reply", reply.speech.take(120))
            if (reply.speech.isNotBlank()) {
                DiyaBus.addChat(fromUser = false, text = reply.speech)
                say(reply.speech)
            }
            reply.afterSpeech?.invoke()
            when {
                reply.followUp -> listenForCommand(withChime = false, followUp = false)
                fromVoice && prefs.conversationMode && !reply.endConversation && reply.speech.isNotBlank() ->
                    listenForCommand(withChime = false, followUp = true)
                else -> goIdle()
            }
        }
    }

    private fun r(en: String, hi: String) = if (prefs.hindi) hi else en

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
                        offline.cancel()
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

    private suspend fun softChime() {
        tone?.startTone(ToneGenerator.TONE_PROP_ACK, 80)
        delay(120)
    }

    // ---------------- Shutdown ----------------

    private fun shutdown() {
        stopping = true
        job?.cancel()
        wake?.stop()
        commands.cancel()
        offline.cancel()
        speaker.stop()
        DiyaBus.status.value = Status.OFF
        DiyaBus.detail.value = ""
        DiyaBus.partial.value = ""
        DiyaLog.add("service", "turned off")
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
        if (::offline.isInitialized) offline.cancel()
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
