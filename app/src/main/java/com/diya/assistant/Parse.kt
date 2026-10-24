package com.diya.assistant

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** Text clean-up helpers. */
object Text {
    fun normalize(s: String): String {
        var t = s.lowercase(Locale.ROOT)
            .replace('’', '\'')
            .replace("a.m.", "am").replace("p.m.", "pm")
            .replace(Regex("[^a-z0-9:%'\\s\\u0900-\\u097F]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        t = t.replace(Regex("^(hey|hi|ok|okay)\\s+(diya|dia|deeya)\\b\\s*"), "")
        repeat(2) {
            t = t.replace(Regex("^(please|can you|could you|would you|will you|diya|zara|jara)\\s+"), "")
        }
        t = t.replace(Regex("\\s+(please|plz|na|naa|zara|jara)$"), "")
        return t.trim()
    }

    /** Makes an AI answer suitable for reading aloud. */
    fun forSpeech(s: String): String = s
        .replace(Regex("\\[([^\\]]+)\\]\\([^)]+\\)"), "$1")
        .replace(Regex("https?://\\S+"), "")
        .replace(Regex("[*_`#>]+"), "")
        .replace(Regex("(?m)^\\s*[-•]\\s+"), "")
        .replace(Regex("\\s*\\n+\\s*"), " ")
        .replace(Regex("\\s{2,}"), " ")
        .trim()

    fun cap(s: String) = s.trim().replaceFirstChar { it.uppercase() }
}

/** Understands durations, clock times, days, cities and messages in spoken commands. */
object Parse {
    data class ClockTime(val hour: Int, val minute: Int)
    data class Message(val channel: String, val who: String, val text: String?)

    private val numbers = mapOf(
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
        "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10,
        "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14, "fifteen" to 15,
        "sixteen" to 16, "seventeen" to 17, "eighteen" to 18, "nineteen" to 19,
        "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60, "ninety" to 90,
        // Hindi / Hinglish
        "ek" to 1, "do" to 2, "teen" to 3, "char" to 4, "chaar" to 4, "paanch" to 5, "panch" to 5, "chhe" to 6,
        "chhah" to 6, "saat" to 7, "aath" to 8, "nau" to 9, "das" to 10, "gyarah" to 11, "barah" to 12,
        "pandrah" to 15, "bees" to 20, "pachees" to 25, "pachchis" to 25, "tees" to 30, "chalis" to 40,
        "chaalis" to 40, "pachas" to 50, "pachaas" to 50
    )

    /** "twenty five minutes" -> "25 minutes"; with [articles], "a minute" -> "1 minute". */
    fun numberWords(t: String, articles: Boolean = true): String {
        val out = mutableListOf<String>()
        for (w in t.split(" ")) {
            val token = numbers[w]?.toString() ?: if (articles && (w == "a" || w == "an")) "1" else w
            val prev = out.lastOrNull()?.toIntOrNull()
            val cur = token.toIntOrNull()
            if (prev != null && cur != null && prev in 20..90 && prev % 10 == 0 && cur in 1..9) {
                out[out.size - 1] = (prev + cur).toString()
            } else {
                out.add(token)
            }
        }
        return out.joinToString(" ")
    }

    /** Total seconds mentioned in [t], or 0. */
    fun duration(t: String): Int {
        var s = t.replace("an hour and a half", "90 minutes")
            .replace("one and a half hours", "90 minutes")
            .replace("half an hour", "30 minutes")
            .replace("half a minute", "30 seconds")
            .replace(Regex("\\baadha ghanta\\b|\\badha ghanta\\b"), "30 minutes")
            .replace(Regex("\\bdedh ghant[ae]\\b"), "90 minutes")
            .replace(Regex("\\bdhai ghant[ae]\\b"), "150 minutes")
        s = numberWords(s)
        var total = 0
        Regex("(\\d+)\\s*(hours?|hrs?|ghant[ae]|ghanton|minutes?|mins?|minat|seconds?|secs?|sekand)\\b").findAll(s).forEach { m ->
            val n = m.groupValues[1].toInt()
            total += when (m.groupValues[2][0]) {
                'h', 'g' -> n * 3600
                'm' -> n * 60
                else -> n
            }
        }
        return total
    }

    fun speakDuration(secs: Int, hinglish: Boolean = false): String {
        val h = secs / 3600
        val m = (secs % 3600) / 60
        val s = secs % 60
        val parts = buildList {
            if (h > 0) add(if (hinglish) "$h ${if (h == 1) "ghanta" else "ghante"}" else plural(h, "hour"))
            if (m > 0) add(if (hinglish) "$m minute" else plural(m, "minute"))
            if (s > 0) add(if (hinglish) "$s second" else plural(s, "second"))
        }
        return parts.joinToString(if (hinglish) " aur " else " and ")
    }

    private fun plural(n: Int, w: String) = if (n == 1) "1 $w" else "$n ${w}s"

    /** "7:30 am", "6 in the morning", "half past 9", "subah 6 baje", "saade 7" -> ClockTime. */
    fun clockTime(t: String): ClockTime? {
        var s = numberWords(t, articles = false).replace(Regex("\\s*(o'clock|oclock|baje)\\b"), "")
        s = s.replace(Regex("\\bdedh baje\\b"), "1:30").replace(Regex("\\bdhai baje\\b"), "2:30")
        s = Regex("(?:half past|saade|sade) (\\d{1,2})").replace(s) { "${it.groupValues[1]}:30" }
        s = Regex("(?:quarter past|sawa|sava) (\\d{1,2})").replace(s) { "${it.groupValues[1]}:15" }
        s = Regex("(?:quarter to|paune|pone) (\\d{1,2})").replace(s) {
            val h = it.groupValues[1].toInt()
            "${if (h == 1) 12 else h - 1}:45"
        }
        val re = Regex("\\b(\\d{1,2})(?:[:.](\\d{2})|\\s(\\d{2})(?=\\s*(?:am|pm)\\b|\\s*$))?\\s*(am|pm)?\\b")
        val matches = re.findAll(s).toList()
        if (matches.isEmpty()) return null
        val m = matches.lastOrNull { it.groupValues[4].isNotEmpty() || it.groupValues[2].isNotEmpty() }
            ?: matches.firstOrNull { s.substring(it.range.last + 1).trimStart().startsWith("baje") }
            ?: matches.last()
        var hour = m.groupValues[1].toInt()
        val minute = m.groupValues[2].ifEmpty { m.groupValues[3] }.ifEmpty { "0" }.toInt()
        var marker = m.groupValues[4]
        if (marker.isEmpty()) {
            marker = when {
                Regex("\\b(morning|subah|savere)\\b").containsMatchIn(s) -> "am"
                Regex("\\b(evening|night|afternoon|tonight|shaam|sham|raat|dopahar|dophar)\\b").containsMatchIn(s) -> "pm"
                else -> ""
            }
        }
        if (marker == "pm" && hour in 1..11) hour += 12
        if (marker == "am" && hour == 12) hour = 0
        if (hour !in 0..23 || minute !in 0..59) return null
        return ClockTime(hour, minute)
    }

    fun hasClockTime(t: String): Boolean =
        Regex(
            "\\b\\d{1,2}(:\\d{2})?\\s*(am|pm)\\b|\\b\\d{1,2}:\\d{2}\\b|\\b(at|by|around)\\s+\\d{1,2}\\b|" +
                "\\b\\d{1,2}\\s*(baje|oclock|o'clock)|\\b(saade|sawa|paune|dedh|dhai)\\b|" +
                "\\b(morning|evening|night|afternoon|tonight|subah|shaam|raat|dopahar)\\s+(at\\s+)?\\d{1,2}\\b|" +
                "\\b\\d{1,2}\\s+(in the )?(morning|evening|night|afternoon)\\b"
        ).containsMatchIn(numberWords(t, articles = false))

    private val weekdays = mapOf(
        "monday" to DayOfWeek.MONDAY, "tuesday" to DayOfWeek.TUESDAY, "wednesday" to DayOfWeek.WEDNESDAY,
        "thursday" to DayOfWeek.THURSDAY, "friday" to DayOfWeek.FRIDAY, "saturday" to DayOfWeek.SATURDAY,
        "sunday" to DayOfWeek.SUNDAY, "somvar" to DayOfWeek.MONDAY, "somwar" to DayOfWeek.MONDAY,
        "mangalvar" to DayOfWeek.TUESDAY, "mangalwar" to DayOfWeek.TUESDAY, "budhvar" to DayOfWeek.WEDNESDAY,
        "budhwar" to DayOfWeek.WEDNESDAY, "guruvar" to DayOfWeek.THURSDAY, "guruwar" to DayOfWeek.THURSDAY,
        "shukravar" to DayOfWeek.FRIDAY, "shukrawar" to DayOfWeek.FRIDAY, "shanivar" to DayOfWeek.SATURDAY,
        "shaniwar" to DayOfWeek.SATURDAY, "ravivar" to DayOfWeek.SUNDAY, "raviwar" to DayOfWeek.SUNDAY,
        "itwar" to DayOfWeek.SUNDAY
    )

    /** "tomorrow", "kal", "on friday" -> date. Null if no day is mentioned. */
    fun day(t: String): LocalDate? {
        val today = LocalDate.now()
        return when {
            Regex("\\b(day after tomorrow|parso|parson)\\b").containsMatchIn(t) -> today.plusDays(2)
            Regex("\\b(tomorrow|kal)\\b").containsMatchIn(t) -> today.plusDays(1)
            Regex("\\b(today|tonight|aaj|aj)\\b").containsMatchIn(t) -> today
            else -> weekdays.entries.firstOrNull { Regex("\\b${it.key}\\b").containsMatchIn(t) }
                ?.let { today.with(TemporalAdjusters.nextOrSame(it.value)) }
        }
    }

    private val notCities = setOf(
        "today", "tomorrow", "now", "right now", "tonight", "the evening", "the morning",
        "the afternoon", "this week", "here", "my area", "my city", "the weekend", "outside",
        "aaj", "kal", "abhi", "yahan", "yaha", "bahar", "aaj ka", "kal ka", "mere area", "mere shehar"
    )

    /** "weather in pune tomorrow" / "delhi ka mausam" -> city. Null if no place is mentioned. */
    fun city(t: String): String? {
        Regex("^(?:aaj |kal |abhi )?([a-z]+(?: [a-z]+)?) (?:ka|ki|ke|mein|me|main) (?:aaj |kal |abhi )?(?:ka |ki )?(?:mausam|weather|temperature|baarish|barish|tapman)")
            .find(t)?.groupValues?.get(1)?.let { if (it !in notCities) return it }
        val markers = Regex("\\b(in|at|for)\\s").findAll(t).toList().asReversed()
        for (m in markers) {
            val c = t.substring(m.range.last + 1)
                .replace(Regex("\\s+(today|tomorrow|now|right now|this week|tonight|this evening|this morning)$"), "")
                .trim()
            if (c.isNotEmpty() && c !in notCities && c.split(" ").size <= 4) return c
        }
        return null
    }

    private val msgWord = "(?:whatsapp|whats app|text|sms|message|msg)"

    /** "send a whatsapp to rahul saying hi" / "rahul ko whatsapp karo ki main late hoon". */
    fun message(t: String): Message? {
        fun channel(s: String) = if (s.contains("whatsapp") || s.contains("whats app")) "whatsapp" else "sms"
        fun cleanWho(s: String) = s.replace(Regex("\\s+on (whatsapp|whats app|sms|text)$"), "").removePrefix("to ").trim()

        Regex("^(?:send |write )?(?:an? )?$msgWord(?: message)? to (.+?)(?: on (?:whatsapp|whats app))?(?: saying| that| ki|:)\\s+(.+)$")
            .find(t)?.let { return Message(channel(t), cleanWho(it.groupValues[1]), it.groupValues[2]) }
        Regex("^(?:send |write )?(?:an? )?$msgWord(?: message)? to (.+?)$")
            .find(t)?.let { return Message(channel(t), cleanWho(it.groupValues[1]), null) }
        Regex("^(?:text|message|whatsapp) (.+?) (?:saying|that) (.+)$")
            .find(t)?.let { return Message(channel(t), cleanWho(it.groupValues[1]), it.groupValues[2]) }
        Regex("^(?:text|message|whatsapp|sms) ([\\p{L} ]+)$")
            .find(t)?.let { return Message(channel(t), cleanWho(it.groupValues[1]), null) }
        Regex("^(.+?) ko $msgWord(?: pe| par)?(?: message| msg)?(?: karo| bhejo| kar do| send karo| bhej do| likho| kardo)?(?: ki| ke| bolo)?(?:\\s+(.*))?$")
            .find(t)?.let {
                val text = it.groupValues[2].trim().ifEmpty { null }
                return Message(channel(t), cleanWho(it.groupValues[1]), text)
            }
        return null
    }
}
