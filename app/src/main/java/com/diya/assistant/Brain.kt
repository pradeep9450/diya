package com.diya.assistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import org.json.JSONObject
import java.io.IOException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException

/** What Diya says back, and whether she should keep listening for your answer. */
data class Reply(
    val speech: String,
    val followUp: Boolean = false,
    val afterSpeech: (() -> Unit)? = null,
)

/**
 * Diya's brain. [Understand] works out what you asked (in English, Hinglish or Hindi);
 * this class carries it out and replies in your language. Anything it doesn't
 * recognise goes to Gemini (free tier), which can also ask Diya to do phone actions.
 */
class Brain(context: Context, private val prefs: Prefs) {
    private val ctx = context.applicationContext
    private val phone = PhoneActions(ctx)
    private val web = WebServices()
    private val history = ArrayDeque<Pair<String, String>>()

    /** Reply in Hindi for this turn. */
    private var hindi = false

    /** Diya asked a question ("For how long?") and is waiting for the answer. */
    private sealed class Pending {
        data class TimerLength(val label: String) : Pending()
        data object AlarmTime : Pending()
        data class WeatherCity(val tomorrow: Boolean) : Pending()
        data object PlayWhat : Pending()
        data class MessageText(val channel: String, val who: String) : Pending()
        data class EventTime(val title: String, val day: LocalDate?, val reminder: Boolean) : Pending()
    }

    private var pending: Pending? = null

    /** Something risky (sending a message) waiting for "yes" / "haan". */
    private var confirm: (suspend () -> Reply)? = null

    private fun r(en: String, hi: String) = if (hindi) hi else en

    suspend fun handle(raw: String): Reply {
        val u = Understand.parse(raw, prefs.hindi)
        hindi = u.hindi || prefs.hindi
        val waitingConfirm = confirm
        confirm = null
        val waiting = pending
        pending = null

        if (waitingConfirm != null) {
            when (u.cmd) {
                Cmd.Yes -> return waitingConfirm()
                Cmd.No, Cmd.Stop -> return Reply(r("Okay, cancelled.", "ठीक है, रद्द कर दिया।"))
                else -> Unit
            }
        }
        if (waiting != null) {
            if (u.cmd == Cmd.Stop || u.cmd == Cmd.No) return Reply(r("Okay.", "ठीक है।"))
            resume(waiting, u, raw)?.let { return it }
        }
        if (u.text.isBlank()) return Reply(r("Yes?", "जी, बोलिए?"), followUp = true)
        val cmd = u.cmd ?: return askGemini(raw.trim())
        return perform(cmd)
    }

    // ---------------- Follow-up answers ----------------

    private suspend fun resume(p: Pending, u: Understand.Result, raw: String): Reply? {
        val t = u.text
        return when (p) {
            is Pending.TimerLength -> {
                var secs = Parse.duration(t)
                if (secs <= 0) Regex("^(\\d{1,3})$").find(t)?.let { secs = it.groupValues[1].toInt() * 60 }
                if (secs > 0) setTimer(secs, p.label) else null
            }
            Pending.AlarmTime -> Parse.clockTime(t)?.let { setAlarm(it) }
            is Pending.WeatherCity ->
                if (u.cmd == null && t.split(" ").size <= 4) weatherFor(Parse.city(t) ?: t.removePrefix("in ").trim(), p.tomorrow) else null
            Pending.PlayWhat -> if (u.cmd == null || u.cmd is Cmd.Play) playQuery((u.cmd as? Cmd.Play)?.query ?: t, null) else null
            is Pending.MessageText -> messageFlow(p.channel, p.who, raw.trim())
            is Pending.EventTime -> {
                val inSecs = Parse.duration(t).takeIf { it > 0 && Regex("\\b(in|after|baad)\\b").containsMatchIn(t) }
                when {
                    p.reminder && inSecs != null -> remindIn(p.title, inSecs)
                    Regex("\\b(all day|whole day|poora din|pura din|पूरा दिन|पूरे दिन)\\b").containsMatchIn(t) ->
                        addEvent(p.title, Parse.day(t) ?: p.day ?: LocalDate.now(), null, p.reminder)
                    else -> Parse.clockTime(t)?.let { ct ->
                        val time = LocalTime.of(ct.hour, ct.minute)
                        addEvent(p.title, Parse.day(t) ?: p.day ?: autoDay(time), time, p.reminder)
                    }
                }
            }
        }
    }

    // ---------------- Carrying out commands ----------------

    private suspend fun perform(cmd: Cmd): Reply = when (cmd) {
        Cmd.Stop -> Reply("")
        Cmd.Yes, Cmd.No -> Reply(r("Okay.", "ठीक है।"))
        is Cmd.SetLanguage -> {
            prefs.language = if (cmd.hindi) "hi" else "en"
            hindi = cmd.hindi
            Reply(
                if (cmd.hindi) "ठीक है, अब मैं हिंदी में सुनूँगी और बोलूँगी।"
                else "Okay, I'll listen and reply in English now. I still understand Hinglish."
            )
        }
        Cmd.Greet -> Reply(r("${greeting()}${you()}! How can I help?", "नमस्ते${you()}! बताइए, मैं क्या मदद करूँ?"))
        Cmd.Thanks -> Reply(r("You're welcome${you()}!", "आपका स्वागत है${you()}!"))
        Cmd.WhoAreYou -> Reply(
            r(
                "I'm Diya, your personal assistant. Diya means a little lamp, and I'm here to light things up for you.",
                "मैं दिया हूँ, आपकी पर्सनल असिस्टेंट। दिया यानी एक छोटा सा दीपक, जो आपकी मदद के लिए हमेशा जलता रहता है।"
            )
        )
        Cmd.MyName -> Reply(
            if (prefs.userName.isBlank()) r("I don't know yet. You can tell me in my settings.", "मुझे अभी आपका नाम नहीं पता। आप मेरी सेटिंग में बता सकते हैं।")
            else r("Your name is ${prefs.userName}.", "आपका नाम ${prefs.userName} है।")
        )
        Cmd.HowAreYou -> Reply(r("I'm doing great, thanks for asking! How can I help?", "मैं बिल्कुल ठीक हूँ, पूछने के लिए शुक्रिया! बताइए, क्या मदद करूँ?"))
        Cmd.Time -> {
            val now = LocalTime.now()
            Reply(r("It's ${enClock(now)}.", "अभी ${hiClock(now)} बजे हैं।"))
        }
        Cmd.Date -> Reply(r("Today is ${enDate(LocalDate.now())}.", "आज ${hiDate(LocalDate.now())} है।"))
        is Cmd.Timer -> cmd.seconds?.let { setTimer(it, cmd.label) } ?: run {
            pending = Pending.TimerLength(cmd.label)
            Reply(r("Sure. For how long?", "ज़रूर। कितनी देर का?"), followUp = true)
        }
        Cmd.TimerCancel -> Reply(r("I can't cancel timers yet. You can stop it from your Clock app.", "मैं अभी टाइमर बंद नहीं कर सकती। आप इसे Clock ऐप से रोक सकते हैं।"))
        is Cmd.Alarm -> cmd.time?.let { setAlarm(it) } ?: run {
            pending = Pending.AlarmTime
            Reply(r("What time should I set it for?", "किस समय का अलार्म लगाऊँ?"), followUp = true)
        }
        Cmd.AlarmCancel -> Reply(r("I can't remove alarms yet. You can do that in your Clock app.", "मैं अभी अलार्म नहीं हटा सकती। आप इसे Clock ऐप में हटा सकते हैं।"))
        is Cmd.Reminder -> reminder(cmd)
        is Cmd.AddEvent -> {
            if (cmd.time == null) {
                pending = Pending.EventTime(cmd.title, cmd.day, reminder = false)
                Reply(r("At what time? You can also say all day.", "किस समय? आप \"पूरा दिन\" भी कह सकते हैं।"), followUp = true)
            } else {
                val time = LocalTime.of(cmd.time.hour, cmd.time.minute)
                addEvent(cmd.title, cmd.day ?: autoDay(time), time, reminder = false)
            }
        }
        is Cmd.ReadCalendar -> readCalendar(cmd.day)
        is Cmd.Weather -> weatherFor(cmd.city, cmd.tomorrow)
        is Cmd.News -> news(cmd.topic)
        Cmd.Battery -> {
            val (level, charging) = phone.battery()
            Reply(
                r(
                    "Your battery is at $level percent" + (if (charging) " and charging." else "."),
                    "बैटरी $level प्रतिशत है" + (if (charging) " और चार्ज हो रही है।" else "।")
                )
            )
        }
        is Cmd.Torch -> Reply(
            if (phone.setTorch(cmd.on)) {
                if (cmd.on) r("Torch on.", "टॉर्च चालू कर दी।") else r("Torch off.", "टॉर्च बंद कर दी।")
            } else {
                r("I couldn't control the torch on this phone.", "इस फ़ोन पर मैं टॉर्च नहीं चला पाई।")
            }
        )
        is Cmd.Volume -> volume(cmd)
        is Cmd.Brightness -> brightness(cmd)
        is Cmd.Remember -> saveNote(cmd.text)
        Cmd.Recall -> {
            val n = prefs.notes
            Reply(
                when {
                    n.isEmpty() -> r("You haven't asked me to remember anything yet.", "आपने अभी तक मुझे कुछ याद रखने को नहीं कहा।")
                    else -> r("You asked me to remember: ", "आपने मुझसे ये याद रखने को कहा था: ") + n.joinToString(". ") + "."
                }
            )
        }
        Cmd.ForgetNotes -> {
            prefs.notes = emptyList()
            Reply(r("Done. I've cleared your notes.", "ठीक है, सारे नोट्स मिटा दिए।"))
        }
        Cmd.MissedCalls -> missedCalls()
        Cmd.AnswerCall -> when {
            !DiyaBus.ringing -> Reply(r("There's no incoming call right now.", "अभी कोई कॉल नहीं आ रही।"))
            phone.answerCall() -> Reply("")
            else -> permission("answering calls", "कॉल उठाने")
        }
        Cmd.RejectCall -> when {
            !DiyaBus.ringing && !DiyaBus.inCall -> Reply(r("There's no call right now.", "अभी कोई कॉल नहीं है।"))
            phone.endCall() -> Reply("")
            else -> permission("ending calls", "कॉल काटने")
        }
        is Cmd.ReadMessages -> readMessages(cmd)
        is Cmd.ReplyTo -> replyTo(cmd.who, cmd.text)
        is Cmd.SendMessage -> messageFlow(cmd.channel, cmd.who, cmd.text)
        is Cmd.ContactNumber -> contactNumber(cmd.who)
        is Cmd.Call -> callWho(cmd.who)
        is Cmd.Navigate -> launched(
            phone.navigate(cmd.place),
            r("Starting directions to ${cmd.place}.", "${cmd.place} का रास्ता दिखा रही हूँ।"),
            "start directions", "रास्ता दिखाने"
        )
        Cmd.WhereAmI -> whereAmI()
        is Cmd.Camera -> launched(
            phone.camera(cmd.selfie),
            if (cmd.selfie) r("Opening the front camera. Smile!", "फ्रंट कैमरा खोल रही हूँ। मुस्कुराइए!") else r("Opening the camera.", "कैमरा खोल रही हूँ।"),
            "open the camera", "कैमरा खोलने"
        )
        is Cmd.Panel -> panel(cmd.kind)
        is Cmd.Dnd -> dnd(cmd.on)
        is Cmd.Play -> cmd.query?.let { playQuery(it, cmd.app) } ?: run {
            pending = Pending.PlayWhat
            Reply(r("What should I play?", "क्या चलाऊँ?"), followUp = true)
        }
        is Cmd.Open -> openApp(cmd.app)
        is Cmd.Search -> launched(
            phone.webSearch(cmd.query),
            r("Here's what Google found for ${cmd.query}.", "${cmd.query} के लिए Google के नतीजे खोल दिए।"),
            "see the results", "नतीजे देखने"
        )
    }

    // ---------------- Timers, alarms, reminders, calendar ----------------

    private fun setTimer(secs: Int, label: String = ""): Reply {
        val en = Parse.speakDuration(secs)
        val hi = hiDuration(secs)
        return launched(
            phone.setTimer(secs, label),
            r("Timer set for $en.", "$hi का टाइमर लगा दिया।"),
            "start your $en timer", "टाइमर लगाने"
        )
    }

    private fun setAlarm(ct: Parse.ClockTime): Reply {
        val time = LocalTime.of(ct.hour, ct.minute)
        return launched(
            phone.setAlarm(ct.hour, ct.minute),
            r("Alarm set for ${enClock(time)}.", "${hiClock(time)} बजे का अलार्म लगा दिया।"),
            "set your alarm", "अलार्म लगाने"
        )
    }

    private fun reminder(c: Cmd.Reminder): Reply {
        val title = c.title.ifBlank { r("your reminder", "आपका रिमाइंडर") }
        if (c.inSeconds != null) return remindIn(title, c.inSeconds)
        if (c.time == null) {
            pending = Pending.EventTime(title, c.day, reminder = true)
            return Reply(
                if (c.day == null) r("When should I remind you?", "कब याद दिलाऊँ?") else r("At what time?", "किस समय?"),
                followUp = true
            )
        }
        val time = LocalTime.of(c.time.hour, c.time.minute)
        return addEvent(title, c.day ?: autoDay(time), time, reminder = true)
    }

    /** Short reminders ("in 10 minutes") use a labelled timer, which rings even offline. */
    private fun remindIn(title: String, secs: Int): Reply = launched(
        phone.setTimer(secs, title),
        r("Okay, I'll remind you to $title in ${Parse.speakDuration(secs)}.", "ठीक है, ${hiDuration(secs)} बाद याद दिला दूँगी: $title।"),
        "start your reminder", "रिमाइंडर लगाने"
    )

    private fun addEvent(title: String, date: LocalDate, time: LocalTime?, reminder: Boolean): Reply {
        val ok = phone.addEvent(Text.cap(title), date, time, reminderMinutes = if (reminder) 0 else 10)
            ?: return permission("your calendar", "कैलेंडर")
        if (!ok) return Reply(r("I couldn't find a calendar on this phone to add it to.", "मुझे इस फ़ोन पर कोई कैलेंडर नहीं मिला।"))
        val whenEn = enWhen(date, time)
        val whenHi = hiWhen(date, time)
        return Reply(
            if (reminder) r("Okay, I'll remind you to $title $whenEn.", "ठीक है, $whenHi याद दिला दूँगी: $title।")
            else r("Added $title to your calendar $whenEn.", "$title को $whenHi के लिए कैलेंडर में जोड़ दिया।")
        )
    }

    private fun readCalendar(day: LocalDate): Reply {
        val events = phone.events(day) ?: return permission("your calendar", "कैलेंडर")
        val dayEn = enDay(day)
        val dayHi = hiDay(day)
        if (events.isEmpty()) return Reply(r("You have nothing on your calendar $dayEn.", "$dayHi आपके कैलेंडर में कुछ नहीं है।"))
        val zone = java.time.ZoneId.systemDefault()
        val parts = events.take(6).map { e ->
            val t = java.time.Instant.ofEpochMilli(e.begin).atZone(zone).toLocalTime()
            if (e.allDay) r("${e.title} all day", "पूरे दिन ${e.title}")
            else r("${e.title} at ${enClock(t)}", "${hiClock(t)} बजे ${e.title}")
        }
        val n = events.size
        return Reply(
            r(
                "You have $n ${if (n == 1) "event" else "events"} $dayEn: ${joinEn(parts)}.",
                "$dayHi आपके $n कार्यक्रम हैं: ${joinHi(parts)}।"
            )
        )
    }

    private fun autoDay(time: LocalTime): LocalDate =
        if (time.isAfter(LocalTime.now())) LocalDate.now() else LocalDate.now().plusDays(1)

    // ---------------- Information ----------------

    private suspend fun weatherFor(city: String?, tomorrow: Boolean): Reply {
        val c = city ?: prefs.city.ifBlank { null }
        if (c != null) return net { web.weather(c, tomorrow, hindi) }
        val loc = phone.location()
        if (loc != null) {
            val name = phone.placeName(loc) ?: r("your area", "आपके इलाके")
            return net { web.weatherAt(loc.latitude, loc.longitude, name, tomorrow, hindi) }
        }
        pending = Pending.WeatherCity(tomorrow)
        return Reply(
            r("Which city? You can also save your home city in my settings.", "कौन सा शहर? आप मेरी सेटिंग में अपना शहर भी सेव कर सकते हैं।"),
            followUp = true
        )
    }

    private suspend fun news(topic: String?): Reply = net {
        val items = web.headlines(topic, hindi)
        if (items.isEmpty()) {
            r("I couldn't find any news right now.", "अभी मुझे कोई खबर नहीं मिली।")
        } else {
            val ordEn = listOf("First", "Second", "Third", "Fourth", "Fifth")
            val ordHi = listOf("पहली", "दूसरी", "तीसरी", "चौथी", "पाँचवीं")
            buildString {
                append(
                    if (topic == null) r("Here are the top headlines. ", "आज की मुख्य खबरें। ")
                    else r("Here's the latest on $topic. ", "$topic की ताज़ा खबरें। ")
                )
                items.take(5).forEachIndexed { i, h -> append(r("${ordEn[i]}: $h. ", "${ordHi[i]}: $h। ")) }
            }.trim()
        }
    }

    private fun volume(c: Cmd.Volume): Reply = when (c.kind) {
        "unmute" -> {
            phone.volume(PhoneActions.Volume.UNMUTE)
            Reply(r("Sound is back on.", "आवाज़ वापस चालू।"))
        }
        "mute" -> Reply(r("Muting now.", "आवाज़ बंद कर रही हूँ।"), afterSpeech = { phone.volume(PhoneActions.Volume.MUTE) })
        "get" -> {
            val p = phone.volume(PhoneActions.Volume.GET)
            Reply(r("Volume is at $p percent.", "आवाज़ $p प्रतिशत पर है।"))
        }
        else -> {
            val p = when (c.kind) {
                "up" -> phone.volume(PhoneActions.Volume.UP)
                "down" -> phone.volume(PhoneActions.Volume.DOWN)
                else -> phone.volume(PhoneActions.Volume.SET, c.value)
            }
            Reply(r("Volume $p percent.", "आवाज़ $p प्रतिशत।"))
        }
    }

    private fun brightness(c: Cmd.Brightness): Reply {
        val kind = when (c.kind) {
            "up" -> PhoneActions.Brightness.UP
            "down" -> PhoneActions.Brightness.DOWN
            else -> PhoneActions.Brightness.SET
        }
        val p = phone.brightness(kind, c.value)
        if (p == null) {
            phone.launch(
                Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${ctx.packageName}")),
                "allow brightness control"
            )
            return Reply(
                r(
                    "First allow me to modify system settings. I've opened that page for you.",
                    "पहले मुझे सिस्टम सेटिंग बदलने की अनुमति दीजिए। मैंने वो पेज खोल दिया है।"
                )
            )
        }
        return Reply(r("Brightness $p percent.", "ब्राइटनेस $p प्रतिशत।"))
    }

    private fun saveNote(text: String): Reply {
        prefs.notes = (prefs.notes + text.trim()).takeLast(50)
        return Reply(r("Okay, I'll remember that.", "ठीक है, याद रखूँगी।"))
    }

    // ---------------- Calls & messages ----------------

    private fun missedCalls(): Reply {
        val calls = phone.missedCalls() ?: return permission("your call log", "कॉल लॉग")
        if (calls.isEmpty()) return Reply(r("No missed calls in the last 24 hours.", "पिछले 24 घंटों में कोई मिस्ड कॉल नहीं है।"))
        val names = calls.distinct().take(4)
        val n = calls.size
        return Reply(
            r(
                "You have $n missed ${if (n == 1) "call" else "calls"}, from ${joinEn(names)}.",
                "आपकी $n मिस्ड कॉल हैं, ${joinHi(names)} की।"
            )
        )
    }

    private fun callWho(who: String): Reply = when (val res = phone.call(who)) {
        is PhoneActions.CallResult.Started -> launched(
            res.launch,
            r("Calling ${res.name}.", "${res.name} को कॉल कर रही हूँ।"),
            "call ${res.name}", "कॉल करने"
        )
        PhoneActions.CallResult.NoContactsPermission -> permission("your contacts", "कॉन्टैक्ट्स")
        PhoneActions.CallResult.NotFound -> Reply(r("I couldn't find $who in your contacts.", "मुझे आपके कॉन्टैक्ट्स में $who नहीं मिला।"))
    }

    private fun contactNumber(who: String): Reply {
        if (!phone.has(Manifest.permission.READ_CONTACTS)) return permission("your contacts", "कॉन्टैक्ट्स")
        val (name, number) = phone.findContact(who)
            ?: return Reply(r("I couldn't find $who in your contacts.", "मुझे आपके कॉन्टैक्ट्स में $who नहीं मिला।"))
        val spoken = number.filter { it.isDigit() || it == '+' }.toList().joinToString(" ")
        return Reply(r("$name's number is $spoken.", "$name का नंबर है: $spoken।"))
    }

    private fun messageFlow(channel: String, who: String, text: String?): Reply {
        if (text.isNullOrBlank()) {
            pending = Pending.MessageText(channel, who)
            return Reply(r("What should I say to $who?", "$who को क्या भेजना है?"), followUp = true)
        }
        val msg = Text.cap(text)
        if (channel == "whatsapp") {
            // Hands-free: reply from the WhatsApp notification if this person messaged recently.
            findChat(who)?.let { chat -> return confirmReply(chat, msg) }
            val (name, number) = phone.findContact(who) ?: return contactProblem(who)
            return launched(
                phone.whatsapp(number, msg, prefs.countryCode),
                r("WhatsApp is open with your message to $name. Just tap send.", "$name के लिए WhatsApp में मैसेज लिख दिया है, बस भेजें दबाइए।"),
                "open WhatsApp", "WhatsApp खोलने"
            )
        }
        if (!phone.has(Manifest.permission.SEND_SMS)) return permission("sending SMS", "SMS भेजने")
        val (name, number) = phone.findContact(who) ?: return contactProblem(who)
        confirm = {
            if (phone.sendSms(number, msg)) Reply(r("Message sent to $name.", "$name को मैसेज भेज दिया।"))
            else Reply(r("Sorry, the message didn't go through.", "माफ़ कीजिए, मैसेज नहीं गया।"))
        }
        return Reply(r("Send \"$msg\" to $name?", "$name को \"$msg\" भेज दूँ?"), followUp = true)
    }

    private fun replyTo(who: String?, text: String): Reply {
        if (!NotificationStore.connected) return notificationAccessNeeded()
        val chat = if (who == null) NotificationStore.latestReplyable() else findChat(who)
        chat ?: return Reply(
            if (who == null) r("I don't see a recent message I can reply to.", "मुझे कोई हाल का मैसेज नहीं दिखा जिसका जवाब दे सकूँ।")
            else r("I don't see a recent message from $who that I can reply to.", "मुझे $who का कोई हाल का मैसेज नहीं दिखा जिसका जवाब दे सकूँ।")
        )
        return confirmReply(chat, Text.cap(text))
    }

    private fun confirmReply(chat: SeenNotification, msg: String): Reply {
        confirm = {
            if (NotificationStore.reply(ctx, chat, msg)) Reply(r("Sent.", "भेज दिया।"))
            else Reply(r("Sorry, ${chat.app} didn't accept the reply.", "माफ़ कीजिए, ${chat.app} ने जवाब नहीं लिया।"))
        }
        return Reply(r("Reply \"$msg\" to ${chat.title} on ${chat.app}?", "${chat.app} पर ${chat.title} को \"$msg\" भेज दूँ?"), followUp = true)
    }

    /** A recent chat notification (with a reply button) from this person. */
    private fun findChat(who: String): SeenNotification? {
        if (!NotificationStore.connected) return null
        val names = buildList {
            add(if (Hindi.hasDevanagari(who)) Translit.toLatin(who) else who)
            phone.findContact(who)?.first?.let { add(it) }
        }
        return NotificationStore.findChat(names)
    }

    private fun readMessages(c: Cmd.ReadMessages): Reply {
        if (NotificationStore.connected) {
            val pkgs = if (c.whatsappOnly) setOf("com.whatsapp", "com.whatsapp.w4b") else null
            val items = NotificationStore.recent(onlyMessages = !c.allNotifications, pkgFilter = pkgs, limit = 5)
            if (items.isEmpty()) return Reply(r("You have no new messages.", "कोई नया मैसेज नहीं है।"))
            val lines = items.map { n ->
                if (n.isMessage) r("${n.title} on ${n.app}: ${n.text}", "${n.app} पर ${n.title}: ${n.text}")
                else "${n.app}: ${n.title}. ${n.text}"
            }
            return Reply(r("Here are your latest messages. ", "आपके नए मैसेज। ") + lines.joinToString(". ") + ".")
        }
        if (!c.whatsappOnly) {
            phone.latestSms(3)?.let { list ->
                if (list.isEmpty()) return Reply(r("You have no text messages.", "कोई SMS नहीं है।"))
                val lines = list.joinToString(". ") { r("From ${it.from}: ${it.body}", "${it.from} से: ${it.body}") }
                return Reply(r("Your latest texts. ", "आपके नए SMS। ") + lines + ".")
            }
        }
        return notificationAccessNeeded()
    }

    private fun notificationAccessNeeded() = Reply(
        r(
            "To read and reply to WhatsApp and other messages, turn on notification access for me in my settings.",
            "WhatsApp और दूसरे मैसेज पढ़ने और जवाब देने के लिए, मेरी सेटिंग में नोटिफिकेशन एक्सेस चालू कीजिए।"
        )
    )

    private fun contactProblem(who: String): Reply =
        if (!phone.has(Manifest.permission.READ_CONTACTS)) permission("your contacts", "कॉन्टैक्ट्स")
        else Reply(r("I couldn't find $who in your contacts.", "मुझे आपके कॉन्टैक्ट्स में $who नहीं मिला।"))

    // ---------------- Places, settings, apps ----------------

    private suspend fun whereAmI(): Reply {
        val loc = phone.location() ?: return Reply(
            if (phone.has(Manifest.permission.ACCESS_COARSE_LOCATION)) r("I couldn't get your location right now. Is location turned on?", "अभी आपकी लोकेशन नहीं मिल पाई। क्या लोकेशन चालू है?")
            else r("I need location permission. Open my settings and tap Grant permissions.", "मुझे लोकेशन की अनुमति चाहिए। मेरी सेटिंग खोलकर Grant permissions दबाइए।")
        )
        val name = phone.placeName(loc) ?: return Reply(r("I found your location but couldn't name the place.", "लोकेशन तो मिल गई, पर जगह का नाम नहीं मिला।"))
        return Reply(r("You're near $name.", "आप $name के पास हैं।"))
    }

    private fun panel(kind: String): Reply {
        val (p, en, hi) = when (kind) {
            "wifi" -> Triple(PhoneActions.Panel.WIFI, "Wi-Fi", "वाई-फ़ाई")
            "bluetooth" -> Triple(PhoneActions.Panel.BLUETOOTH, "Bluetooth", "ब्लूटूथ")
            else -> Triple(PhoneActions.Panel.INTERNET, "mobile data", "मोबाइल डेटा")
        }
        return launched(
            phone.settingsPanel(p),
            r("Android doesn't let apps switch $en directly, so I've opened the $en controls.", "Android ऐप्स को सीधे $hi चालू-बंद नहीं करने देता, इसलिए मैंने $hi की सेटिंग खोल दी है।"),
            "open $en settings", "$hi की सेटिंग खोलने"
        )
    }

    private fun dnd(on: Boolean): Reply {
        val done = phone.doNotDisturb(on)
        if (done == null) {
            phone.launch(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS), "allow Do Not Disturb control")
            return Reply(r("First allow me to control Do Not Disturb. I've opened that page.", "पहले मुझे डू नॉट डिस्टर्ब बदलने की अनुमति दीजिए। मैंने वो पेज खोल दिया है।"))
        }
        if (!done) return Reply(r("I couldn't change Do Not Disturb.", "मैं डू नॉट डिस्टर्ब नहीं बदल पाई।"))
        return Reply(if (on) r("Do not disturb is on.", "डू नॉट डिस्टर्ब चालू कर दिया।") else r("Do not disturb is off.", "डू नॉट डिस्टर्ब बंद कर दिया।"))
    }

    private fun playQuery(q: String, app: String?): Reply {
        val (where, launch) = phone.play(q, app)
        return launched(launch, r("Here's $q on $where.", "$where पर $q चला रही हूँ।"), "play $q", "गाना चलाने")
    }

    private val hindiApps = mapOf(
        "यूट्यूब" to "youtube", "व्हाट्सएप" to "whatsapp", "व्हाट्सऐप" to "whatsapp", "वॉट्सऐप" to "whatsapp",
        "इंस्टाग्राम" to "instagram", "फेसबुक" to "facebook", "गूगल" to "google", "मैप्स" to "maps", "मैप" to "maps",
        "सेटिंग्स" to "settings", "सेटिंग" to "settings", "जीमेल" to "gmail", "क्रोम" to "chrome", "स्पॉटिफाई" to "spotify",
        "कैलकुलेटर" to "calculator", "घड़ी" to "clock", "गैलरी" to "gallery", "फोटोज" to "photos", "पेटीएम" to "paytm",
        "फोनपे" to "phonepe", "टेलीग्राम" to "telegram", "नेटफ्लिक्स" to "netflix", "अमेज़न" to "amazon",
        "फ्लिपकार्ट" to "flipkart", "कॉन्टैक्ट्स" to "contacts", "मैसेज" to "messages", "फोन" to "phone",
        "कैलेंडर" to "calendar", "ट्विटर" to "twitter", "एक्स" to "x", "स्नैपचैट" to "snapchat", "हॉटस्टार" to "hotstar",
    )

    private fun openApp(spoken: String): Reply {
        val name = hindiApps[spoken.trim()] ?: if (Hindi.hasDevanagari(spoken)) Translit.toLatin(spoken) else spoken
        val (label, intent) = phone.findApp(name)
            ?: return Reply(r("I couldn't find an app called $spoken.", "मुझे $spoken नाम का कोई ऐप नहीं मिला।"))
        return launched(phone.launch(intent, "open $label"), r("Opening $label.", "$label खोल रही हूँ।"), "open $label", "$label खोलने")
    }

    // ---------------- Gemini ----------------

    private suspend fun askGemini(question: String): Reply {
        val key = prefs.geminiKey
        if (key.isBlank()) {
            return Reply(
                r(
                    "I can't answer that yet. Add your free Gemini key in my settings and I'll be able to help with questions like that.",
                    "इसका जवाब देने के लिए मुझे Gemini की फ़्री key चाहिए। मेरी सेटिंग में key डाल दीजिए, फिर मैं ऐसे सवालों में भी मदद कर पाऊँगी।"
                )
            )
        }
        return try {
            val raw = web.gemini(key, prefs.geminiModel, systemPrompt(), history.toList(), question)
            geminiAction(raw)?.let { cmd -> return perform(cmd) }
            val answer = Text.forSpeech(raw)
            history.addLast(question to answer)
            while (history.size > 6) history.removeFirst()
            Reply(answer, followUp = answer.endsWith("?"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            Reply(
                when {
                    e.code == 429 -> r("I've reached my free question limit for the moment. Please try again in a little while.", "अभी के लिए मेरी फ़्री सवालों की सीमा पूरी हो गई है। थोड़ी देर बाद फिर पूछिए।")
                    e.code == 401 || e.code == 403 || e.body.contains("API key", ignoreCase = true) ->
                        r("My Gemini key doesn't seem to work. Please check it in settings.", "मेरी Gemini key काम नहीं कर रही। कृपया सेटिंग में जाँच लीजिए।")
                    e.code == 404 -> r("The Gemini model name in my settings isn't available. Try gemini-flash-latest.", "सेटिंग में लिखा Gemini मॉडल उपलब्ध नहीं है। gemini-flash-latest आज़माइए।")
                    else -> r("Gemini returned an error, code ${e.code}. Please try again.", "Gemini से गड़बड़ी आई, कोड ${e.code}। फिर से कोशिश कीजिए।")
                }
            )
        } catch (e: IOException) {
            Reply(r("I can't reach the internet right now. Please check your connection.", "अभी इंटरनेट नहीं मिल रहा। कृपया कनेक्शन जाँच लीजिए।"))
        }
    }

    /** Gemini can ask Diya to do a phone action by answering `ACTION {json}`. */
    private fun geminiAction(raw: String): Cmd? {
        val s = raw.trim().trimStart('`').removePrefix("json").trim()
        if (!s.startsWith("ACTION")) return null
        val start = s.indexOf('{')
        val end = s.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val o = runCatching { JSONObject(s.substring(start, end + 1)) }.getOrNull() ?: return null
        fun str(k: String) = o.optString(k).trim()
        fun dateTime(): LocalDateTime? = runCatching { LocalDateTime.parse(str("start")) }.getOrNull()
        return when (str("action")) {
            "timer" -> Cmd.Timer(o.optInt("seconds").takeIf { it > 0 }, str("label"))
            "alarm" -> Cmd.Alarm(Parse.ClockTime(o.optInt("hour").coerceIn(0, 23), o.optInt("minute").coerceIn(0, 59)))
            "reminder" -> {
                val secs = o.optInt("in_seconds").takeIf { it > 0 }
                val dt = dateTime()
                Cmd.Reminder(str("title"), dt?.toLocalDate(), dt?.let { Parse.ClockTime(it.hour, it.minute) }, secs)
            }
            "event" -> {
                val dt = dateTime()
                Cmd.AddEvent(str("title").ifBlank { "Event" }, dt?.toLocalDate(), dt?.let { Parse.ClockTime(it.hour, it.minute) })
            }
            "call" -> str("who").ifBlank { null }?.let { Cmd.Call(it) }
            "sms", "whatsapp" -> str("who").ifBlank { null }?.let { Cmd.SendMessage(str("action"), it, str("text").ifBlank { null }) }
            "play" -> Cmd.Play(str("query").ifBlank { null }, str("app").lowercase().takeIf { it == "spotify" || it == "youtube music" })
            "open" -> str("app").ifBlank { null }?.let { Cmd.Open(it) }
            "weather" -> Cmd.Weather(str("city").ifBlank { null }, o.optBoolean("tomorrow"))
            "news" -> Cmd.News(str("topic").ifBlank { null })
            "navigate" -> str("place").ifBlank { null }?.let { Cmd.Navigate(it) }
            "note" -> str("text").ifBlank { null }?.let { Cmd.Remember(it) }
            "torch" -> Cmd.Torch(o.optBoolean("on", true))
            "read_messages" -> Cmd.ReadMessages(str("app").lowercase() == "whatsapp", false)
            else -> null
        }
    }

    private fun systemPrompt(): String {
        val now = LocalDateTime.now()
        val who = prefs.userName.ifBlank { "the user" }
        val city = prefs.city.ifBlank { "unknown" }
        return """
            You are Diya, a warm and friendly female voice assistant on $who's Android phone in India.
            Everything you write is read aloud by text-to-speech, so:
            - Answer in one to three short sentences unless the user asks for more detail.
            - Never use markdown, bullet points, lists, emojis, tables or links.
            - Write numbers, units and symbols the way they should be spoken.
            - Language: if the user writes in English, reply in English. If the user writes in Hindi or Hinglish
              (Hindi in English letters), reply in natural, simple Hindi written in Devanagari script.
            Current date and time: ${now.format(DateTimeFormatter.ofPattern("EEEE, yyyy-MM-dd HH:mm", Locale.ENGLISH))}. User's home city: $city.

            You can also control the phone. Only if the user is asking you to DO one of these things,
            reply with exactly one line that starts with ACTION followed by one JSON object, and nothing else:
            ACTION {"action":"timer","seconds":300,"label":"tea"}
            ACTION {"action":"alarm","hour":6,"minute":30}
            ACTION {"action":"reminder","title":"call mom","start":"2026-01-31T18:00"}
            ACTION {"action":"reminder","title":"take medicine","in_seconds":600}
            ACTION {"action":"event","title":"Meeting with Rahul","start":"2026-01-31T17:00"}
            ACTION {"action":"call","who":"Mom"}
            ACTION {"action":"sms","who":"Rahul","text":"I'll be late"}
            ACTION {"action":"whatsapp","who":"Rahul","text":"I'll be late"}
            ACTION {"action":"play","query":"Arijit Singh songs","app":"youtube"}
            ACTION {"action":"open","app":"Instagram"}
            ACTION {"action":"weather","city":"Pune","tomorrow":false}
            ACTION {"action":"news","topic":"cricket"}
            ACTION {"action":"navigate","place":"MG Road metro station"}
            ACTION {"action":"note","text":"keys are in the drawer"}
            ACTION {"action":"torch","on":true}
            ACTION {"action":"read_messages","app":"whatsapp"}
            Use 24-hour local times. Keep names, message text and song names exactly as the user said them.
            For everything else (questions, chat, jokes, translations, advice), just answer normally.
        """.trimIndent()
    }

    // ---------------- Helpers ----------------

    private fun you() = if (prefs.userName.isBlank()) "" else " ${prefs.userName}"

    private fun greeting(): String {
        val h = LocalTime.now().hour
        return when {
            h < 12 -> "Good morning"
            h < 17 -> "Good afternoon"
            else -> "Good evening"
        }
    }

    private fun launched(res: PhoneActions.Launch, done: String, taskEn: String, taskHi: String): Reply = when (res) {
        PhoneActions.Launch.OPENED -> Reply(done)
        PhoneActions.Launch.NOTIFIED -> Reply(r("Tap my notification to $taskEn.", "$taskHi के लिए मेरा नोटिफिकेशन टैप कीजिए।"))
        PhoneActions.Launch.MISSING -> Reply(r("Sorry, I couldn't find an app on this phone to $taskEn.", "माफ़ कीजिए, इस फ़ोन पर $taskHi के लिए कोई ऐप नहीं मिला।"))
    }

    private fun permission(en: String, hi: String) = Reply(
        r(
            "I need permission for $en. Open my settings and tap Grant permissions.",
            "मुझे $hi की अनुमति चाहिए। मेरी सेटिंग खोलकर Grant permissions दबाइए।"
        )
    )

    private suspend fun net(block: suspend () -> String): Reply = try {
        Reply(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Reply(r("I can't reach the internet right now. Please check your connection.", "अभी इंटरनेट नहीं मिल रहा। कृपया कनेक्शन जाँच लीजिए।"))
    }

    // Spoken times and dates

    private val hiLocale = Locale.forLanguageTag("hi-IN")

    private fun enClock(t: LocalTime) = t.format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH))

    /** "शाम के 6:45" / "सुबह के 7" */
    private fun hiClock(t: LocalTime): String {
        val part = when (t.hour) {
            in 4..11 -> "सुबह"
            in 12..15 -> "दोपहर"
            in 16..19 -> "शाम"
            else -> "रात"
        }
        val h12 = (t.hour % 12).let { if (it == 0) 12 else it }
        val clock = if (t.minute == 0) "$h12" else "$h12:${"%02d".format(t.minute)}"
        return "$part के $clock"
    }

    private fun enDate(d: LocalDate) = d.format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.ENGLISH))
    private fun hiDate(d: LocalDate) = d.format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", hiLocale))

    private fun enDay(d: LocalDate): String = when (d) {
        LocalDate.now() -> "today"
        LocalDate.now().plusDays(1) -> "tomorrow"
        else -> "on " + d.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.ENGLISH))
    }

    private fun hiDay(d: LocalDate): String = when (d) {
        LocalDate.now() -> "आज"
        LocalDate.now().plusDays(1) -> "कल"
        else -> d.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", hiLocale)) + " को"
    }

    private fun enWhen(d: LocalDate, t: LocalTime?) = if (t == null) "${enDay(d)}, all day" else "${enDay(d)} at ${enClock(t)}"
    private fun hiWhen(d: LocalDate, t: LocalTime?) = if (t == null) "${hiDay(d)} पूरे दिन" else "${hiDay(d)} ${hiClock(t)} बजे"

    private fun hiDuration(secs: Int): String {
        val h = secs / 3600
        val m = (secs % 3600) / 60
        val s = secs % 60
        return buildList {
            if (h > 0) add("$h ${if (h == 1) "घंटा" else "घंटे"}")
            if (m > 0) add("$m मिनट")
            if (s > 0) add("$s सेकंड")
        }.joinToString(" और ")
    }

    private fun joinEn(items: List<String>) = when (items.size) {
        0 -> ""
        1 -> items[0]
        else -> items.dropLast(1).joinToString(", ") + " and " + items.last()
    }

    private fun joinHi(items: List<String>) = when (items.size) {
        0 -> ""
        1 -> items[0]
        else -> items.dropLast(1).joinToString(", ") + " और " + items.last()
    }
}
