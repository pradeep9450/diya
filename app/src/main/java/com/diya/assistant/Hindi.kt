package com.diya.assistant

/**
 * Lets Diya understand Hindi (देवनागरी) and Hinglish ("kal ka mausam batao").
 *
 * It rewrites a Hindi command into the simple English form that [Brain]'s rules
 * understand, e.g. "5 मिनट का टाइमर लगाओ" -> "5 minutes का timer लगाओ".
 * Anything it can't map is left alone and goes to Gemini, which understands Hindi well.
 *
 * Note: Java/Kotlin's \b doesn't work with Devanagari, so everything here matches on spaces.
 */
object Hindi {

    fun hasDevanagari(s: String): Boolean = s.any { it in 'ऀ'..'ॿ' }

    /** Words that clearly mark Hinglish ("kal ka mausam batao"). One is enough. */
    private val strongMarkers = setOf(
        "karo", "kardo", "batao", "bata", "kya", "hai", "hain", "bajao", "chalao", "kholo", "lagao", "bhejo",
        "kitne", "kitna", "kitni", "kaun", "kaisa", "kaisi", "kaise", "hoga", "hogi", "padho", "sunao", "rakho",
        "rakhna", "yaad", "mujhe", "mera", "meri", "mere", "tum", "aap", "zara", "jara", "thoda", "dikhao", "uthao",
        "kaato", "kato", "dijiye", "kijiye", "mausam", "baje", "abhi", "aaj", "nahi", "nahin", "haan", "chahiye",
        "dilana", "milao", "bolo", "ruko", "chup", "shukriya", "dhanyavaad", "dhanyawad", "namaste",
        "kahan", "kaha", "hoon", "hun", "jalao", "bujhao", "badhao", "ghatao", "sunao", "kholo", "lagao", "kijiye"
    )

    /** Words that only mark Hinglish when there are at least two ("ka", "band", "kal"...). */
    private val weakMarkers = setOf(
        "ka", "ki", "ke", "ko", "mein", "mai", "se", "pe", "par", "kar", "laga", "bhej", "band", "chalu", "kal", "do", "dena"
    )

    fun isHinglish(t: String): Boolean {
        val words = t.split(" ")
        return words.any { it in strongMarkers } || words.count { it in weakMarkers } >= 2
    }

    /** True when the text is Hindi (Devanagari) or Hinglish, or Diya is in Hindi mode. */
    fun applies(t: String, hindiMode: Boolean): Boolean = hindiMode || hasDevanagari(t) || isHinglish(t)

    /** [t] is already lower-cased and cleaned by [Text.normalize]. */
    fun toCommand(t0: String): String {
        val t = digits(t0).replace(Regex("[।॥]"), " ").replace(Regex("\\s+"), " ").trim()
        if (t.isEmpty()) return t

        exact.forEach { (phrases, command) -> if (t in phrases) return command }
        for ((re, build) in patterns) {
            re.find(t)?.let { m -> build(m)?.let { return it } }
        }
        for ((phrases, command) in contains) {
            if (phrases.any { has(t, it) }) return command(t)
        }
        return words(t)
    }

    // ---------------- helpers ----------------

    private fun has(t: String, phrase: String) = " $t ".contains(" $phrase ")

    private fun hasAny(t: String, vararg phrases: String) = phrases.any { has(t, it) }

    private fun digits(s: String): String = buildString {
        for (c in s) append(if (c in '०'..'९') '0' + (c - '०') else c)
    }

    private val numberWords = mapOf(
        "एक" to 1, "दो" to 2, "तीन" to 3, "चार" to 4, "पांच" to 5, "पाँच" to 5, "छह" to 6, "छः" to 6,
        "सात" to 7, "आठ" to 8, "नौ" to 9, "दस" to 10, "ग्यारह" to 11, "बारह" to 12, "पंद्रह" to 15,
        "बीस" to 20, "पच्चीस" to 25, "तीस" to 30, "चालीस" to 40, "पैंतालीस" to 45, "पचास" to 50, "साठ" to 60,
        "ek" to 1, "do" to 2, "teen" to 3, "char" to 4, "paanch" to 5, "panch" to 5, "chhe" to 6, "saat" to 7,
        "aath" to 8, "nau" to 9, "das" to 10, "pandrah" to 15, "bees" to 20, "tees" to 30, "chalis" to 40
    )

    private val units = "मिनट|मिनिट|सेकंड|सेकेंड|घंटे|घंटा|घंटों|बजे|minute|minutes|second|seconds|ghante|ghanta|baje|min"

    /** General word-by-word translation for times, dates and common nouns. */
    fun words(t0: String): String {
        var t = t0
        // number words only when followed by a unit, because "दो" also means "give" (कर दो)
        val nw = numberWords.keys.joinToString("|")
        t = Regex("(?<=^|\\s)($nw)(?=\\s+(?:$units)(?:\\s|$))").replace(t) { numberWords[it.value].toString() }
        // साढ़े 6 = 6:30, सवा 6 = 6:15, पौने 6 = 5:45
        t = Regex("(?:साढ़े|साढे|sadhe|saade) (\\d{1,2})").replace(t) { "${it.groupValues[1]}:30" }
        t = Regex("(?:सवा|sawa|sava) (\\d{1,2})").replace(t) { "${it.groupValues[1]}:15" }
        t = Regex("(?:पौने|paune) (\\d{1,2})").replace(t) {
            val h = it.groupValues[1].toInt()
            "${if (h == 1) 12 else h - 1}:45"
        }
        var s = " $t "
        for ((from, to) in wordMap) s = s.replace(" $from ", " $to ")
        return s.replace(Regex("\\s+"), " ").trim()
    }

    // Longest phrases first so "आधा घंटा" wins over "घंटा".
    private val wordMap: List<Pair<String, String>> = listOf(
        "आधा घंटा" to "30 minutes", "आधे घंटे" to "30 minutes", "aadha ghanta" to "30 minutes",
        "डेढ़ घंटा" to "90 minutes", "डेढ़ घंटे" to "90 minutes",
        "परसों" to "day after tomorrow", "parso" to "day after tomorrow",
        "अगले" to "next", "अगला" to "next",
        "मिनट" to "minutes", "मिनिट" to "minutes", "मिनटों" to "minutes",
        "सेकंड" to "seconds", "सेकेंड" to "seconds",
        "घंटे" to "hours", "घंटा" to "hours", "घंटों" to "hours", "ghante" to "hours", "ghanta" to "hours",
        "टाइमर" to "timer", "अलार्म" to "alarm", "alaram" to "alarm",
        "सुबह" to "morning", "subah" to "morning", "सवेरे" to "morning",
        "शाम" to "evening", "shaam" to "evening", "sham" to "evening",
        "रात" to "night", "raat" to "night",
        "दोपहर" to "afternoon", "dopahar" to "afternoon",
        "बजे" to "oclock", "baje" to "oclock", "बज" to "oclock",
        "कल" to "tomorrow", "kal" to "tomorrow",
        "आज" to "today", "aaj" to "today",
        "मौसम" to "weather", "mausam" to "weather", "तापमान" to "temperature", "टेंपरेचर" to "temperature",
        "बारिश" to "rain", "baarish" to "rain", "barish" to "rain",
        "सोमवार" to "monday", "मंगलवार" to "tuesday", "बुधवार" to "wednesday", "गुरुवार" to "thursday",
        "शुक्रवार" to "friday", "शनिवार" to "saturday", "रविवार" to "sunday", "इतवार" to "sunday",
        "कैलेंडर" to "calendar", "मीटिंग" to "meeting",
        "बंद" to "off", "band" to "off", "चालू" to "on", "chalu" to "on", "ऑन" to "on", "ऑफ" to "off",
    )

    private val stopPhrases = setOf(
        "रुको", "रुक जाओ", "बस", "बस करो", "चुप", "चुप हो जाओ", "रहने दो", "कुछ नहीं", "छोड़ो", "बंद करो",
        "ruko", "ruk jao", "bas", "bas karo", "chup", "chup ho jao", "rehne do", "kuch nahi", "chhodo"
    )

    private val exact: List<Pair<Set<String>, String>> = listOf(
        stopPhrases to "stop",
        setOf("हाँ", "हां", "हा", "जी", "जी हाँ", "हाँ भेजो", "हां भेजो", "भेज दो", "भेजो", "ठीक है", "हाँ जी",
            "haan", "ha", "han", "ji", "haan bhejo", "bhej do", "bhejo", "theek hai", "thik hai",
            "हाँ भेज दो", "हां भेज दो", "हाँ कर दो", "haan bhej do", "haan kar do", "ha bhej do", "haan ji bhej do") to "yes",
        setOf("नहीं", "नही", "मत भेजो", "मत करो", "नहीं भेजो", "nahi", "nahin", "mat bhejo", "mat karo") to "no",
        setOf("नमस्ते", "नमस्कार", "राम राम", "प्रणाम", "हेलो", "हैलो", "हाय") to "namaste",
    )

    private val playVerbs = "बजाओ|बजा दो|चलाओ|चला दो|सुनाओ|प्ले करो|प्ले कर दो|play करो|bajao|baja do|chalao|chala do|sunao|play karo|play kar do|lagao gaana"
    private val openVerbs = "खोलो|खोलें|खोल दो|ओपन करो|ओपन कर दो|open करो|kholo|khol do|open karo|open kar do"
    private val doVerbs = "करो|करें|कर दो|भेजो|भेज दो|लगाओ|लगा दो|मिलाओ|karo|kar do|bhejo|bhej do|lagao|laga do|milao"
    private val whatsappWord = "व्हाट्सएप|व्हाट्सऐप|वॉट्सऐप|वाट्सएप|whatsapp|watsapp"
    private val messageWord = "मैसेज|मेसेज|मेसेज|संदेश|एसएमएस|sms|message|msg|text"
    /** "joke sunao", "kahani sunao" are questions for Gemini, not songs to play. */
    private val notPlayable = Regex("(?:^|\\s)(joke|jokes|chutkula|chutkule|kahani|story|kavita|poem|shayari|kuch|news|khabar|khabren|message|messages|notification|जोक|चुटकुला|कहानी|कविता|शायरी|कुछ|खबर|मैसेज)(?:\\s|$)")
    private val timeWords = Regex("(?:^|\\s)(टाइमर|अलार्म|timer|alarm|मिनट|घंटे|बजे)(?:\\s|$)")

    private val patterns: List<Pair<Regex, (MatchResult) -> String?>> = listOf(
        // मम्मी को व्हाट्सएप करो कि मैं आ रहा हूँ
        Regex("^(.+?) (?:को|ko) (?:$whatsappWord)(?: पर| pe| पे| par)?(?: (?:$messageWord))? (?:$doVerbs)(?: (?:कि|की|ki))? (.+)$") to
            { m -> "whatsapp ${m.groupValues[1]} saying ${m.groupValues[2]}" },
        Regex("^(.+?) (?:को|ko) (?:$whatsappWord)(?: पर| pe| पे| par)?(?: (?:$messageWord))? (?:$doVerbs)$") to
            { m -> "whatsapp ${m.groupValues[1]}" },
        // पापा को मैसेज भेजो कि खाना खा लिया
        Regex("^(.+?) (?:को|ko) (?:$messageWord) (?:$doVerbs)(?: (?:कि|की|ki))? (.+)$") to
            { m -> "text ${m.groupValues[1]} saying ${m.groupValues[2]}" },
        Regex("^(.+?) (?:को|ko) (?:$messageWord) (?:$doVerbs)$") to { m -> "text ${m.groupValues[1]}" },
        // मम्मी को फोन लगाओ / call karo
        Regex("^(.+?) (?:को |ko )?(?:कॉल|काल|call|फोन|फ़ोन|phone) (?:$doVerbs)$") to
            { m -> "call ${m.groupValues[1]}" },
        // कैलेंडर में डालो: "कल 5 बजे डॉक्टर अपॉइंटमेंट कैलेंडर में डालो"
        Regex("^(.+?) (?:कैलेंडर|calendar) (?:में|mein|me|par|पर) (?:डालो|डाल दो|जोड़ो|जोड़ दो|जोडो|add करो|daalo|daal do|jodo|add karo|add kar do)$") to
            { m -> "add ${words(m.groupValues[1])} to calendar" },
        // शाम 6 बजे दवाई लेने की याद दिलाना
        Regex("^(.+?) (?:की |का |के |ki |ka |ke )?(?:याद दिलाना|याद दिला देना|याद दिलाओ|रिमाइंड करना|रिमाइंड करो|yaad dilana|yaad dila dena|remind karna|remind karo)$") to
            { m -> "remind me ${words(m.groupValues[1])}" },
        // याद रखो कि चाबी दराज में है / चाबी दराज में है याद रखना
        Regex("^(?:याद रखो|याद रखना|याद रख लो|याद रखिए|yaad rakho|yaad rakhna|yaad rakh lo)(?: (?:कि|की|ki))? (.+)$") to
            { m -> "remember ${m.groupValues[1]}" },
        Regex("^(.+?) (?:याद रखो|याद रखना|याद रख लो|याद रखिए|yaad rakho|yaad rakhna|yaad rakh lo)$") to
            { m -> "remember ${m.groupValues[1]}" },
        // दिल्ली का मौसम कैसा है / pune mein kal barish hogi
        Regex("^(.+?) (?:में|का|की|के|mein|mai|ka|ki|ke) (?:.*?)(?:मौसम|तापमान|टेंपरेचर|बारिश|mausam|weather|temperature|baarish|barish)(?:\\s|$)") to
            { m ->
                val tomorrow = hasAny(m.value, "कल", "kal")
                val city = m.groupValues[1].split(" ")
                    .filterNot { it in setOf("आज", "कल", "aaj", "kal", "अभी", "abhi", "यहाँ", "यहां", "yahan") }
                    .joinToString(" ").trim()
                when {
                    city.isEmpty() -> "weather" + if (tomorrow) " tomorrow" else ""
                    else -> "weather in $city" + if (tomorrow) " tomorrow" else ""
                }
            },
        // क्रिकेट की खबरें
        Regex("^(.+?) (?:की|के|का|ki|ke|ka) (?:खबर|खबरें|ख़बरें|समाचार|न्यूज़|न्यूज|news|khabar|khabren)(?:\\s|$)") to
            { m ->
                val topic = m.groupValues[1]
                if (topic in setOf("आज", "aaj", "ताज़ा", "ताजा", "taaza", "आज की ताज़ा", "दिन", "देश", "आज की")) "news"
                else "news about $topic"
            },
        // इंडिया गेट का रास्ता बताओ / इंडिया गेट ले चलो
        Regex("^(.+?) (?:का रास्ता|ka rasta|का रूट)(?: बताओ| दिखाओ| batao| dikhao)?$") to
            { m -> "navigate to ${m.groupValues[1]}" },
        Regex("^(.+?) (?:ले चलो|le chalo|नेविगेट करो|navigate karo)$") to { m -> "navigate to ${m.groupValues[1]}" },
        // यूट्यूब खोलो
        Regex("^(.+?) (?:$openVerbs)$") to { m ->
            val what = m.groupValues[1]
            when {
                hasAny(what, "टॉर्च", "torch", "फ्लैशलाइट") -> "torch on"
                hasAny(what, "कैमरा", "camera") -> "open camera"
                else -> "open $what"
            }
        },
        // अरिजीत सिंह के गाने बजाओ
        Regex("^(.+?) (?:$playVerbs)$") to { m ->
            val what = m.groupValues[1]
            if (timeWords.containsMatchIn(what) || notPlayable.containsMatchIn(what)) null else "play $what"
        },
        // यूट्यूब पर सर्च करो
        Regex("^(.+?) (?:के बारे में |ke bare mein |ke baare mein )?(?:सर्च करो|गूगल करो|ढूंढो|खोजो|search karo|google karo|dhundo)$") to
            { m -> "google ${m.groupValues[1]}" },
    )

    private val contains: List<Pair<List<String>, (String) -> String>> = listOf(
        listOf("हिंदी में बात", "हिंदी में बोलो", "हिंदी बोलो", "hindi mein baat", "hindi me baat", "hindi mein bolo", "hindi me bolo") to { _ -> "speak hindi" },
        listOf("इंग्लिश में बात", "अंग्रेजी में बात", "इंग्लिश में बोलो", "english mein baat", "english me baat", "english mein bolo", "english me bolo") to { _ -> "speak english" },
        listOf("कितने बजे", "कितना बजा", "टाइम क्या", "क्या टाइम", "समय क्या", "क्या समय", "टाइम बताओ", "समय बताओ",
            "kitne baje", "kitna baja", "time kya", "kya time", "samay kya", "time batao") to { _ -> "what time is it" },
        listOf("तारीख", "कौन सा दिन", "कौनसा दिन", "क्या दिन है", "date kya", "kya date", "tarikh", "kaun sa din", "kaunsa din") to { _ -> "what's the date" },
        listOf("फोन उठाओ", "फ़ोन उठाओ", "कॉल उठाओ", "उठा लो", "phone uthao", "call uthao", "utha lo") to { _ -> "answer the call" },
        listOf("फोन काटो", "फ़ोन काटो", "कॉल काटो", "काट दो", "phone kaato", "call kaato", "kaat do", "phone kato", "call kato") to { _ -> "reject the call" },
        listOf("मिस्ड कॉल", "मिस कॉल", "किसने कॉल", "किसका कॉल", "किसका फोन", "किसने फोन", "missed call", "kisne call", "kiska call", "kisne phone") to { _ -> "missed calls" },
        listOf("व्हाट्सएप मैसेज", "व्हाट्सएप के मैसेज", "whatsapp message", "whatsapp ke message") to { t ->
            if (hasAny(t, "पढ़ो", "पढ़", "सुनाओ", "बताओ", "padho", "sunao", "batao", "dikhao")) "read my whatsapp messages" else words(t)
        },
        listOf("नोटिफिकेशन", "नोटिफ़िकेशन", "notification", "notifications") to { t ->
            if (hasAny(t, "पढ़ो", "सुनाओ", "बताओ", "दिखाओ", "क्या", "padho", "sunao", "batao", "kya")) "read my notifications" else words(t)
        },
        listOf("मैसेज पढ़ो", "मेसेज पढ़ो", "मैसेज सुनाओ", "मेसेज सुनाओ", "मैसेज पढ़", "message padho", "messages padho", "sms padho", "message sunao") to { _ -> "read my messages" },
        listOf("मैं कहाँ हूँ", "मैं कहां हूं", "मैं कहाँ हूं", "मेरी लोकेशन", "main kahan hoon", "main kaha hu", "meri location") to { _ -> "where am i" },
        listOf("क्या याद रखने", "क्या याद है", "क्या याद रखा", "मेरे नोट्स", "kya yaad rakhne", "kya yaad hai", "mere notes") to { _ -> "what do you remember" },
        listOf("सब भूल जाओ", "नोट्स मिटा", "नोट्स डिलीट", "sab bhool jao", "notes delete") to { _ -> "forget everything" },
        listOf("बैटरी", "battery") to { _ -> "battery level" },
        listOf("टॉर्च", "फ्लैशलाइट", "फ्लैश लाइट", "torch") to { t -> if (hasAny(t, "बंद", "band", "ऑफ", "off", "बुझा")) "torch off" else "torch on" },
        listOf("वाईफाई", "वाई फाई", "वाईफ़ाई", "wifi") to { _ -> "wifi settings" },
        listOf("ब्लूटूथ", "bluetooth") to { _ -> "bluetooth settings" },
        listOf("मोबाइल डेटा", "इंटरनेट", "mobile data") to { _ -> "mobile data settings" },
        listOf("डिस्टर्ब", "साइलेंट मोड", "साइलेंट", "disturb", "silent mode") to { t ->
            if (hasAny(t, "बंद", "band", "ऑफ", "off", "हटाओ", "hatao")) "do not disturb off" else "do not disturb on"
        },
        listOf("ब्राइटनेस", "रोशनी", "brightness", "roshni") to { t ->
            val n = Regex("(\\d{1,3})").find(t)?.groupValues?.get(1)
            when {
                n != null -> "set brightness to $n percent"
                hasAny(t, "कम", "kam", "धीमी", "dheemi") -> "brightness down"
                else -> "brightness up"
            }
        },
        listOf("आवाज़", "आवाज", "वॉल्यूम", "वॉल्युम", "awaz", "aawaz", "awaaz", "volume", "sound") to { t ->
            val n = Regex("(\\d{1,3})").find(t)?.groupValues?.get(1)
            when {
                n != null -> "set volume to $n"
                hasAny(t, "कम", "धीमी", "धीमा", "धीरे", "kam", "dheemi", "dheere") -> "volume down"
                hasAny(t, "बंद", "band", "म्यूट") -> "mute"
                else -> "volume up"
            }
        },
        listOf("म्यूट") to { _ -> "mute" },
        listOf("सेल्फी", "selfie") to { _ -> "take a selfie" },
        listOf("फोटो खींचो", "फोटो लो", "फ़ोटो खींचो", "photo khicho", "photo kheecho", "photo lo") to { _ -> "take a photo" },
        listOf("तुम कौन हो", "आप कौन हो", "तुम्हारा नाम", "आपका नाम", "तेरा नाम", "tum kaun ho", "aap kaun ho", "tumhara naam", "aapka naam") to { _ -> "who are you" },
        listOf("मेरा नाम क्या", "mera naam kya") to { _ -> "what's my name" },
        listOf("कैसी हो", "कैसे हो", "कैसी हैं", "क्या हाल", "kaisi ho", "kaise ho", "kya haal") to { _ -> "how are you" },
        listOf("धन्यवाद", "शुक्रिया", "थैंक यू", "थैंक्स", "dhanyavaad", "dhanyawad", "shukriya") to { _ -> "thank you" },
        listOf("खबर", "खबरें", "ख़बरें", "समाचार", "न्यूज़", "न्यूज", "khabar", "khabren", "samachar") to { _ -> "news" },
        listOf("कैलेंडर", "आज का प्लान", "आज मेरा प्लान", "कल का प्लान", "मेरी मीटिंग", "मीटिंग है", "aaj ka plan", "kal ka plan", "meri meeting") to { t ->
            "what's on my calendar" + if (hasAny(t, "कल", "kal")) " tomorrow" else " today"
        },
    )
}
