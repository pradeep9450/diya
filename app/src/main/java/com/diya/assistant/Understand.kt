package com.diya.assistant

import java.time.LocalDate

/** Something you asked Diya to do. Produced by [Understand], carried out by [Brain]. */
sealed interface Cmd {
    data object Stop : Cmd
    data object Yes : Cmd
    data object No : Cmd
    data class SetLanguage(val hindi: Boolean) : Cmd
    data object Greet : Cmd
    data object Thanks : Cmd
    data object WhoAreYou : Cmd
    data object MyName : Cmd
    data object HowAreYou : Cmd
    data object Time : Cmd
    data object Date : Cmd
    data class Timer(val seconds: Int?, val label: String = "") : Cmd
    data object TimerCancel : Cmd
    data class Alarm(val time: Parse.ClockTime?) : Cmd
    data object AlarmCancel : Cmd
    data class Reminder(val title: String, val day: LocalDate?, val time: Parse.ClockTime?, val inSeconds: Int?) : Cmd
    data class AddEvent(val title: String, val day: LocalDate?, val time: Parse.ClockTime?) : Cmd
    data class ReadCalendar(val day: LocalDate) : Cmd
    data class Weather(val city: String?, val tomorrow: Boolean) : Cmd
    data class News(val topic: String?) : Cmd
    data object Battery : Cmd
    data class Torch(val on: Boolean) : Cmd
    data class Volume(val kind: String, val value: Int = 0) : Cmd // up, down, mute, unmute, set, get
    data class Brightness(val kind: String, val value: Int = 0) : Cmd // up, down, set
    data class Remember(val text: String) : Cmd
    data object Recall : Cmd
    data object ForgetNotes : Cmd
    data object MissedCalls : Cmd
    data object AnswerCall : Cmd
    data object RejectCall : Cmd
    data class ReadMessages(val whatsappOnly: Boolean, val allNotifications: Boolean) : Cmd
    data class ReplyTo(val who: String?, val text: String) : Cmd
    data class SendMessage(val channel: String, val who: String, val text: String?) : Cmd
    data class ContactNumber(val who: String) : Cmd
    data class Call(val who: String) : Cmd
    data class Navigate(val place: String) : Cmd
    data object WhereAmI : Cmd
    data class Camera(val selfie: Boolean) : Cmd
    data class Panel(val kind: String) : Cmd // wifi, bluetooth, internet
    data class Dnd(val on: Boolean) : Cmd
    data class Play(val query: String?, val app: String?) : Cmd
    data class Open(val app: String) : Cmd
    data class Search(val query: String) : Cmd
}

/**
 * Turns what you said (English, Hinglish or Hindi) into a [Cmd].
 * Pure Kotlin with no Android code, so it can be tested on a computer.
 */
object Understand {

    data class Result(
        /** Reply in Hindi (you spoke Hindi or Hinglish). */
        val hindi: Boolean,
        /** The simplified English form the rules matched against. */
        val text: String,
        /** What was understood, or null if this should go to Gemini. */
        val cmd: Cmd?,
    )

    fun parse(raw: String, hindiMode: Boolean = false): Result {
        val norm = Text.normalize(raw)
        val hindi = Hindi.hasDevanagari(raw) || Hindi.isHinglish(norm)
        val t = if (hindi || hindiMode) Text.normalize(Hindi.toCommand(norm)) else norm
        return Result(hindi, t, command(t))
    }

    fun command(t: String): Cmd? {
        if (t.isBlank()) return null
        return simple(t)
            ?: language(t)
            ?: smallTalk(t)
            ?: reminder(t)
            ?: timer(t)
            ?: alarm(t)
            ?: addEvent(t)
            ?: readCalendar(t)
            ?: time(t)
            ?: date(t)
            ?: weather(t)
            ?: news(t)
            ?: battery(t)
            ?: torch(t)
            ?: brightness(t)
            ?: volume(t)
            ?: notes(t)
            ?: calls(t)
            ?: readMessages(t)
            ?: reply(t)
            ?: sendMessage(t)
            ?: contactNumber(t)
            ?: call(t)
            ?: navigate(t)
            ?: whereAmI(t)
            ?: camera(t)
            ?: panel(t)
            ?: dnd(t)
            ?: play(t)
            ?: open(t)
            ?: search(t)
    }

    private fun Regex.hit(t: String) = containsMatchIn(t)

    // ---------------- Basics ----------------

    private val stopRe = Regex(
        "^(stop|cancel|never mind|nevermind|shut up|be quiet|quiet|silence|enough|that's all|that is all|" +
            "nothing|go away|ok stop|okay stop|stop it|no thanks|no thank you)$"
    )
    val yesRe = Regex(
        "^(?:(?:haan|ha|han|haa|yes|ji|ok|okay)\\s+)?(yes|yeah|yep|yup|sure|ok|okay|send|send it|do it|go ahead|confirm|correct|right|please do|" +
            "haan|ha|han|haa|haan ji|ji|ji haan|bhej do|bhejo|kar do|theek hai|thik hai|done)( please| ji)?$"
    )
    val noRe = Regex("^(no|nope|nah|don't|dont|do not|nahi|nahin|mat|mat bhejo|mat karo|rehne do)( please| ji)?$")

    private fun simple(t: String): Cmd? = when {
        stopRe.matches(t) -> Cmd.Stop
        yesRe.matches(t) -> Cmd.Yes
        noRe.matches(t) -> Cmd.No
        else -> null
    }

    private fun language(t: String): Cmd? {
        if (Regex("\\b(translate|meaning|matlab|mean)\\b").hit(t)) return null
        val talk = Regex("\\b(speak|talk|reply|respond|switch|change|use|baat|bolo|bol)\\b")
        return when {
            t == "speak hindi" || (talk.hit(t) && Regex("\\bhindi\\b").hit(t)) -> Cmd.SetLanguage(true)
            t == "speak english" || (talk.hit(t) && Regex("\\benglish\\b").hit(t)) -> Cmd.SetLanguage(false)
            else -> null
        }
    }

    private fun smallTalk(t: String): Cmd? = when {
        Regex("^(hi|hello|hey|hii|namaste|namaskar|good (morning|afternoon|evening))( diya)?$").matches(t) -> Cmd.Greet
        Regex("\\b(thank you|thanks|thank u|thx)\\b").hit(t) && t.split(" ").size <= 6 &&
            !Regex("\\b(translate|meaning|say|spell|hindi|english|word)\\b").hit(t) -> Cmd.Thanks
        Regex("\\b(who are you|what('s| is) your name|your name)\\b").hit(t) -> Cmd.WhoAreYou
        Regex("\\bwhat('s| is) my name\\b").hit(t) -> Cmd.MyName
        Regex("^how are you( doing)?( today)?$").matches(t) -> Cmd.HowAreYou
        else -> null
    }

    private val timeRe = Regex("\\b(what('s| is)? the time|what time is it|time is it|time now|current time|tell me the time)\\b|^time$")

    private fun time(t: String): Cmd? {
        if (!timeRe.hit(t)) return null
        if (Regex("\\btime\\b.*\\bin [a-z]").hit(t)) return null // "time in London" -> Gemini
        return Cmd.Time
    }

    private fun date(t: String): Cmd? =
        if (Regex("\\b(what('s| is)? (the |today's )?date|today's date|date today|what day is (it|today)|which day is (it|today)|what is today)\\b").hit(t)) Cmd.Date else null

    // ---------------- Timers, alarms, reminders, calendar ----------------

    private val durationUnit = "(?:hours?|hrs?|minutes?|mins?|seconds?|secs?)"

    private fun reminder(t: String): Cmd? {
        val m = Regex("^(?:remind me|set (?:a )?reminder|reminder|add (?:a )?reminder)(?: to| about| that| for| of)?\\s*(.*)$").find(t)
            ?: return null
        val rest = m.groupValues[1]
        val inSecs = Regex("\\b(?:in|after)\\s+\\S+(?:\\s+\\S+)?\\s+$durationUnit\\b").find(rest)?.value
            ?.let { Parse.duration(it) }?.takeIf { it > 0 }
            ?: Regex("\\b\\S+\\s+$durationUnit\\s+(?:baad|later|from now)\\b").find(rest)?.value
                ?.let { Parse.duration(it) }?.takeIf { it > 0 }
        val time = if (inSecs == null && Parse.hasClockTime(rest)) daytime(rest, Parse.clockTime(rest)) else null
        val day = Parse.day(rest)
        return Cmd.Reminder(cleanTitle(rest), day, time, inSecs)
    }

    private fun timer(t: String): Cmd? {
        if (!Regex("\\btimer\\b").hit(t)) return null
        if (Regex("\\b(cancel|stop|delete|remove|off)\\b").hit(t)) return Cmd.TimerCancel
        var secs = Parse.duration(t)
        if (secs <= 0) {
            Regex("\\b(\\d{1,3})\\b").find(Parse.numberWords(t, articles = false))?.let { secs = it.groupValues[1].toInt() * 60 }
        }
        val label = Regex("\\bfor (?:the |my )?([a-z]+(?: [a-z]+)?)$").find(t)?.groupValues?.get(1)
            ?.takeUnless { Regex("\\d|$durationUnit").hit(it) }.orEmpty()
        return Cmd.Timer(secs.takeIf { it > 0 }, label)
    }

    private fun alarm(t: String): Cmd? {
        if (!Regex("\\balarm\\b|^wake me( up)?\\b").hit(t)) return null
        if (Regex("\\b(cancel|delete|remove|turn off|stop)\\b").hit(t)) return Cmd.AlarmCancel
        if (Regex("\\bin\\s+\\S+\\s+$durationUnit\\b").hit(t)) {
            Parse.duration(t).takeIf { it > 0 }?.let { return Cmd.Timer(it, "alarm") }
        }
        return Cmd.Alarm(Parse.clockTime(t))
    }

    private val calendarNouns = "(?:event|meeting|appointment|calendar|call|lunch|dinner|party|interview|class)"

    private fun addEvent(t: String): Cmd? {
        val adding = Regex("^(?:add|create|schedule|set up|setup|put|book|make|plan)\\b.*\\b$calendarNouns\\b").hit(t) ||
            Regex("\\bto (?:my )?calendar$").hit(t)
        if (!adding) return null
        val body = t.replace(Regex("^(?:add|create|schedule|set up|setup|put|book|make|plan)\\s+(?:an?\\s+|the\\s+|new\\s+)*"), "")
            .replace(Regex("\\s*\\b(?:to|in|on) (?:my )?calendar\\b"), "")
            .replace(Regex("^(?:event|calendar event)\\s+(?:called|named|titled|for)\\s+"), "")
        val time = if (Parse.hasClockTime(body)) daytime(body, Parse.clockTime(body)) else null
        val title = cleanTitle(body).ifBlank { "Meeting" }
        return Cmd.AddEvent(Text.cap(title), Parse.day(body), time)
    }

    private fun readCalendar(t: String): Cmd? {
        if (!Regex("\\b(calendar|schedule|agenda|meetings?|events?|appointments?|plans?)\\b").hit(t)) return null
        if (!Regex("\\b(what|what's|whats|any|do i|my|read|show|tell|check|have)\\b").hit(t)) return null
        return Cmd.ReadCalendar(Parse.day(t) ?: LocalDate.now())
    }

    /**
     * Meetings and reminders "at 5" almost always mean 5 pm, so an hour from 1 to 7
     * without am/pm or morning/evening is moved to the afternoon. (Alarms keep the morning.)
     */
    private fun daytime(s: String, ct: Parse.ClockTime?): Parse.ClockTime? {
        if (ct == null || ct.hour !in 1..7) return ct
        if (Regex("\\b(am|morning|subah)\\b").containsMatchIn(s)) return ct
        return Parse.ClockTime(ct.hour + 12, ct.minute)
    }

    /** Removes times, days and filler words, leaving what the reminder or event is about. */
    fun cleanTitle(s: String): String {
        var x = " $s "
        val patterns = listOf(
            "\\b(?:in|after)\\s+\\S+(?:\\s+\\S+)?\\s+$durationUnit\\b",
            "\\b\\S+\\s+$durationUnit\\s+(?:baad|later|from now)\\b",
            "\\b(?:at|by|around)?\\s*\\d{1,2}(?::\\d{2})?\\s*(?:am|pm)?\\s*(?:oclock|o'clock|baje)?(?=\\s)",
            "\\b(?:half past|quarter past|quarter to|saade|sawa|paune)\\b",
            "\\b(?:day after tomorrow|today|tomorrow|tonight|this morning|this evening|this afternoon)\\b",
            "\\b(?:in the |at |this )?(?:morning|evening|night|afternoon|noon)\\b",
            "\\b(?:on |next |this )?(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\b",
            "^\\s*(?:to|that|about|for|of)\\b",
        )
        for (p in patterns) x = Regex(p).replace(x, " ")
        x = x.replace(Regex("\\s+(?:on|at|for|to|by|की|के|का|को)\\s*$"), " ")
        return x.replace(Regex("\\s+"), " ").trim()
    }

    // ---------------- Information ----------------

    private fun weather(t: String): Cmd? {
        if (!Regex("\\b(weather|temperature|forecast|rain|raining|umbrella|humid|humidity|how hot|how cold|sunny)\\b").hit(t)) return null
        return Cmd.Weather(Parse.city(t), Regex("\\btomorrow\\b").hit(t))
    }

    private fun news(t: String): Cmd? {
        if (!Regex("\\b(news|headlines)\\b").hit(t)) return null
        val filler = setOf("the", "latest", "top", "today's", "any", "some", "me", "todays", "breaking", "today")
        val topic = Regex("\\b(?:news|headlines)\\s+(?:about|on|for|of|from|in)\\s+(.+)$").find(t)?.groupValues?.get(1)
            ?: Regex("^([a-z]+) news$").find(t)?.groupValues?.get(1)?.takeUnless { it in filler }
        return Cmd.News(topic)
    }

    private fun battery(t: String): Cmd? = if (Regex("\\bbattery\\b|\\bcharge level\\b").hit(t)) Cmd.Battery else null

    private fun torch(t: String): Cmd? {
        if (!Regex("\\b(torch|flashlight|flash light)\\b").hit(t)) return null
        return Cmd.Torch(!Regex("\\b(off|disable|stop|band)\\b").hit(t))
    }

    private fun brightness(t: String): Cmd? {
        if (!Regex("\\bbrightness\\b|\\bscreen (brighter|darker|dimmer)\\b").hit(t)) return null
        val n = Regex("\\b(\\d{1,3})\\b").find(t)?.groupValues?.get(1)?.toInt()
        return when {
            n != null -> Cmd.Brightness("set", if (n <= 10 && !Regex("%|percent").hit(t)) n * 10 else n)
            Regex("\\b(max|maximum|full)\\b").hit(t) -> Cmd.Brightness("set", 100)
            Regex("\\b(down|decrease|lower|reduce|less|dim|dimmer|darker|kam)\\b").hit(t) -> Cmd.Brightness("down")
            else -> Cmd.Brightness("up")
        }
    }

    private fun volume(t: String): Cmd? {
        if (Regex("\\bunmute\\b").hit(t)) return Cmd.Volume("unmute")
        if (Regex("^(mute|mute (the )?(sound|volume|phone|media))$").matches(t)) return Cmd.Volume("mute")
        if (!Regex("\\bvolume\\b|\\b(louder|quieter|softer)\\b").hit(t)) return null
        val n = Regex("\\b(\\d{1,3})\\b").find(t)?.groupValues?.get(1)?.toInt()
        return when {
            n != null -> Cmd.Volume("set", if (n <= 10 && !Regex("%|percent").hit(t)) n * 10 else n)
            Regex("\\b(max|maximum|full)\\b").hit(t) -> Cmd.Volume("set", 100)
            Regex("\\b(up|increase|raise|louder|higher|more)\\b").hit(t) -> Cmd.Volume("up")
            Regex("\\b(down|decrease|lower|reduce|quieter|softer|less)\\b").hit(t) -> Cmd.Volume("down")
            else -> Cmd.Volume("get")
        }
    }

    private fun notes(t: String): Cmd? {
        Regex("^(?:remember|note down|note|make a note|take a note|save a note)(?: that| to| of)?\\s+(.+)$").find(t)?.let {
            return Cmd.Remember(it.groupValues[1])
        }
        if (Regex("\\b(what did i (ask|tell) you to remember|what do you remember|(read|show|tell me) my notes|what are my notes|my notes)\\b").hit(t)) {
            return Cmd.Recall
        }
        if (Regex("\\b(forget everything|clear (all |my )?notes|delete (all |my )?notes)\\b").hit(t)) return Cmd.ForgetNotes
        return null
    }

    // ---------------- Phone, messages, contacts ----------------

    private fun calls(t: String): Cmd? = when {
        Regex("\\b(missed calls?|who called( me)?|recent calls|last call)\\b").hit(t) -> Cmd.MissedCalls
        Regex("^(answer|pick up|pickup|accept|receive)( the)?( call| phone)?( please)?$").matches(t) -> Cmd.AnswerCall
        Regex("^(reject|decline|cut|end|hang up|disconnect)( the)?( call| phone)?( please)?$").matches(t) -> Cmd.RejectCall
        else -> null
    }

    private fun readMessages(t: String): Cmd? {
        val asks = Regex("\\b(read|check|any|new|show|unread|latest|recent|what are|what's in|tell me)\\b.*\\b(messages?|notifications?|whatsapp|texts?|sms|msgs?)\\b").hit(t) ||
            Regex("^(my )?(messages|notifications|whatsapp messages)$").matches(t)
        if (!asks) return null
        if (Regex("\\b(send|reply|write)\\b").hit(t)) return null
        return Cmd.ReadMessages(
            whatsappOnly = Regex("\\bwhatsapp\\b").hit(t),
            allNotifications = Regex("\\bnotifications?\\b").hit(t),
        )
    }

    private fun reply(t: String): Cmd? {
        val m = Regex("^reply(?: back)?(?: to)?(?: (.+?))? (?:saying|that|with|:)\\s+(.+)$").find(t) ?: return null
        val who = m.groupValues[1].trim().ifBlank { null }
        return Cmd.ReplyTo(who, m.groupValues[2].trim())
    }

    private fun sendMessage(t: String): Cmd? {
        val m = Parse.message(t) ?: return null
        if (m.who.isBlank()) return null
        return Cmd.SendMessage(m.channel, m.who, m.text?.trim()?.ifBlank { null })
    }

    private fun contactNumber(t: String): Cmd? {
        val m = Regex("^(?:what(?:'s| is) |tell me |give me |read )?([\\p{L} ]+?)'s (?:phone |mobile )?number$").find(t)
            ?: Regex("^(?:what(?:'s| is) )?(?:the )?(?:phone |mobile )?number (?:of|for) ([\\p{L} ]+)$").find(t)
            ?: return null
        return Cmd.ContactNumber(m.groupValues[1].trim())
    }

    private fun call(t: String): Cmd? {
        val m = Regex("^(?:call|phone|dial|ring)\\s+(.+?)(?:\\s+(?:on|from) (?:mobile|phone|cell))?$").find(t) ?: return null
        val who = m.groupValues[1].removePrefix("to ").trim()
        if (who in setOf("back", "me")) return null
        return Cmd.Call(who)
    }

    // ---------------- Places, camera, settings ----------------

    private fun navigate(t: String): Cmd? {
        val m = Regex("^(?:navigate|directions|get directions|take me|route|drive|show me the way|how do i get)(?: me)?(?: to| for)?\\s+(.+)$").find(t)
            ?: return null
        return Cmd.Navigate(m.groupValues[1])
    }

    private fun whereAmI(t: String): Cmd? =
        if (Regex("\\b(where am i|my location|current location|where are we)\\b").hit(t)) Cmd.WhereAmI else null

    private fun camera(t: String): Cmd? = when {
        Regex("\\bselfie\\b").hit(t) -> Cmd.Camera(selfie = true)
        Regex("\\b(take|click|capture|shoot) (a |my |the )?(photo|picture|pic|snap)\\b|^(open )?(the )?camera$").hit(t) -> Cmd.Camera(selfie = false)
        else -> null
    }

    private fun panel(t: String): Cmd? {
        val kind = when {
            Regex("\\b(wifi|wi fi)\\b").hit(t) -> "wifi"
            Regex("\\bbluetooth\\b").hit(t) -> "bluetooth"
            Regex("\\b(mobile data|internet|data connection|hotspot)\\b").hit(t) -> "internet"
            else -> return null
        }
        if (!Regex("\\b(turn|switch|on|off|enable|disable|settings?|open|connect|start|stop)\\b").hit(t)) return null
        return Cmd.Panel(kind)
    }

    private fun dnd(t: String): Cmd? {
        if (!Regex("\\b(do not disturb|dnd|silent mode|silent)\\b").hit(t)) return null
        return Cmd.Dnd(!Regex("\\b(off|disable|stop|exit|remove|band)\\b").hit(t))
    }

    // ---------------- Apps ----------------

    private fun play(t: String): Cmd? {
        val m = Regex("^play(?:\\s+(.*?))?(?:\\s+on\\s+(youtube music|youtube|spotify))?$").find(t) ?: return null
        var q = m.groupValues[1].trim()
            .replace(Regex("\\b(ke|ka|ki)?\\s*(gaana|gaane|gana|gane)\\b"), " songs")
            .replace(Regex("^(some|a|any|koi|zara)\\s+"), "")
            .replace(Regex("\\s+"), " ").trim()
        if (q in setOf("songs", "song", "music", "gaana", "a song")) q = "music"
        return Cmd.Play(q.ifBlank { null }, m.groupValues[2].ifBlank { null })
    }

    private fun open(t: String): Cmd? {
        val m = Regex("^(?:open|launch|start|run)\\s+(?:the\\s+|my\\s+)?(.+?)(?:\\s+app(?:lication)?)?$").find(t) ?: return null
        val name = m.groupValues[1]
        if (name == "camera") return Cmd.Camera(selfie = false)
        return Cmd.Open(name)
    }

    private fun search(t: String): Cmd? {
        val m = Regex("^(?:google|search google for|search the web for|search for|search|look up on google)\\s+(.+)$").find(t) ?: return null
        return Cmd.Search(m.groupValues[1])
    }
}
