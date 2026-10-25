package com.diya.assistant

import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Diya's chatbot side: answers easy questions instantly and offline (maths, conversions,
 * jokes, facts, small talk), and recognises questions to look up for free online
 * (Wikipedia, dictionary, currency rates). Pure Kotlin, so it is tested on a computer.
 */
object Knowledge {

    /** Something to look up online. */
    sealed interface Lookup {
        data class Wiki(val topic: String) : Lookup
        data class Define(val word: String) : Lookup
        data class Currency(val amount: Double, val from: String, val to: String) : Lookup
    }

    private fun Regex.hit(t: String) = containsMatchIn(t)

    /** An instant offline answer, or null. [t] is lower-case text from [Text.normalize]. */
    fun local(t: String, hindi: Boolean, rnd: Random = Random.Default): String? {
        fun r(en: String, hi: String) = if (hindi) hi else en
        calculate(t)?.let { (expr, value) ->
            return r("$expr is ${fmt(value)}.", "$expr का जवाब है ${fmt(value)}।")
        }
        convert(t)?.let { return it }
        // Hindi script (regex \\b doesn't work with Devanagari, so check words directly)
        val dev = Hindi.hasDevanagari(t)
        return when {
            dev && (t.contains("चुटकुल") || t.contains("जोक")) -> HI_JOKES.random(rnd)
            dev && (t.contains("रोचक") || t.contains("दिलचस्प")) -> HI_FACTS.random(rnd)
            dev && (t.contains("सुविचार") || t.contains("प्रेरणा")) -> HI_QUOTES.random(rnd)
            dev && t.contains("सिक्का") -> if (rnd.nextBoolean()) "चित आया!" else "पट आया!"
            dev && (t.contains("क्या कर सकती") || t.contains("क्या-क्या कर")) -> local("what can you do", true, rnd)
            Regex("\\b(joke|jokes|chutkula|chutkule|make me laugh|funny|hasao|hansao|जोक|चुटकुला)\\b").hit(t) ->
                if (hindi) HI_JOKES.random(rnd) else EN_JOKES.random(rnd)
            Regex("\\b(fun fact|a fact|interesting fact|something interesting|kuch interesting|rochak|amazing fact|tell me a fact)\\b").hit(t) ->
                if (hindi) HI_FACTS.random(rnd) else EN_FACTS.random(rnd)
            Regex("\\b(motivat\\w*|inspir\\w*|quote|thought of the day|suvichar|prerna)\\b").hit(t) ->
                if (hindi) HI_QUOTES.random(rnd) else EN_QUOTES.random(rnd)
            Regex("\\b(flip|toss) (a |the )?coin\\b|\\bsikka (uchalo|uchhalo)\\b|\\btoss karo\\b").hit(t) ->
                if (rnd.nextBoolean()) r("It's heads!", "चित आया!") else r("It's tails!", "पट आया!")
            Regex("\\broll (a |the )?(dice|die)\\b|\\b(dice|pasa) (roll|phenko|fenko|ghumao)\\b").hit(t) -> {
                val n = rnd.nextInt(1, 7)
                r("You rolled a $n.", "पासे पर $n आया।")
            }
            Regex("\\b(random number|pick a number)\\b").hit(t) -> {
                val m = Regex("between (\\d+) and (\\d+)").find(t)
                val lo = m?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val hi = m?.groupValues?.get(2)?.toIntOrNull() ?: 100
                val (a, b) = if (lo <= hi) lo to hi else hi to lo
                val n = rnd.nextInt(a, b + 1)
                r("Your number is $n.", "आपका नंबर है $n।")
            }
            Regex("\\b(what can you do|what do you do|help me|how can you help|your features|kya kya kar sakti|kya kar sakti ho|tum kya kar sakti)\\b").hit(t) ->
                r(
                    "I can set timers, alarms and reminders, call and message people, read WhatsApp, check weather and news, " +
                        "play music, open apps, do maths, tell jokes and answer questions. Just ask!",
                    "मैं टाइमर, अलार्म और रिमाइंडर लगा सकती हूँ, कॉल और मैसेज कर सकती हूँ, WhatsApp पढ़ सकती हूँ, मौसम और खबरें बता सकती हूँ, " +
                        "गाने चला सकती हूँ, ऐप खोल सकती हूँ, हिसाब लगा सकती हूँ, जोक सुना सकती हूँ और सवालों के जवाब दे सकती हूँ।"
                )
            Regex("\\b(are you (a )?(robot|human|real|ai|machine)|kya tum (robot|insaan|real) ho)\\b").hit(t) ->
                r("I'm an AI assistant living in your phone. Not human, but always happy to help!", "मैं आपके फ़ोन में रहने वाली एक AI असिस्टेंट हूँ। इंसान नहीं, पर मदद के लिए हमेशा तैयार!")
            Regex("\\b(who (made|created|built) you|tumhe kisne banaya|kisne banaya)\\b").hit(t) ->
                r("I was made just for you, as your own personal assistant.", "मुझे खास आपके लिए बनाया गया है, आपकी अपनी पर्सनल असिस्टेंट।")
            Regex("\\b(how old are you|your age|tumhari umar|kitne saal ki)\\b").hit(t) ->
                r("I'm brand new, but I learn something every day!", "मैं अभी बिल्कुल नई हूँ, पर रोज़ कुछ नया सीखती हूँ!")
            Regex("\\b(where do you live|where are you|tum kahan rehti)\\b").hit(t) ->
                r("Right here in your phone, always ready.", "यहीं आपके फ़ोन में, हमेशा तैयार।")
            Regex("\\b(i love you|love you diya|do you love me|i like you|tumse pyar|pyaar karti)\\b").hit(t) ->
                r("Aww, that's sweet! I'm always here for you.", "अरे वाह, कितना प्यारा! मैं हमेशा आपके साथ हूँ।")
            Regex("^(good night|gn|shubh ratri)( diya)?$").hit(t) ->
                r("Good night! Sleep well and sweet dreams.", "शुभ रात्रि! अच्छे से सोइए, मीठे सपने।")
            Regex("^(bye|goodbye|bye bye|see you|tata|alvida|chalo bye)( diya)?$").hit(t) ->
                r("Bye! Call me anytime.", "बाय! जब चाहें बुला लीजिए।")
            Regex("^(what'?s up|whats up|sup|kya chal raha hai|kya ho raha hai)( diya)?$").hit(t) ->
                r("All good here! What can I do for you?", "सब बढ़िया! बताइए, क्या करूँ?")
            Regex("\\b(i'?m bored|i am bored|bore ho raha|bore ho rahi|bor ho raha)\\b").hit(t) ->
                r("How about a joke? Or ask me for a fun fact, or say play some music.", "एक जोक सुनें? या कोई रोचक बात पूछिए, या बोलिए गाने चलाओ।")
            Regex("\\b(i'?m sad|i am sad|feeling sad|feeling low|udaas|dukhi)\\b").hit(t) ->
                r("I'm sorry you're feeling low. I'm here with you. Want a joke or some music to lift your mood?", "आप उदास हैं, सुनकर बुरा लगा। मैं आपके साथ हूँ। मूड अच्छा करने के लिए जोक या गाने सुनें?")
            Regex("\\b(i'?m happy|i am happy|feeling good|khush hoon|bahut khush)\\b").hit(t) ->
                r("That's wonderful! Keep smiling.", "यह तो बहुत अच्छी बात है! ऐसे ही मुस्कुराते रहिए।")
            Regex("^(good job|well done|you'?re (smart|awesome|great|good)|nice|shabash|bahut badhiya|very good)( diya)?$").hit(t) ->
                r("Thank you! That made my day.", "शुक्रिया! आपने तो मेरा दिन बना दिया।")
            Regex("^(sorry|my bad|maaf karo|maaf kijiye)( diya)?$").hit(t) ->
                r("No problem at all!", "कोई बात नहीं!")
            else -> null
        }
    }

    /** A question worth looking up online, or null. */
    fun lookup(t: String): Lookup? {
        currency(t)?.let { return it }
        Regex("^(?:what(?:'s| is) the )?(?:meaning|definition) of (?:the word )?([a-z]+)$").find(t)?.let { return Lookup.Define(it.groupValues[1]) }
        Regex("^what does (?:the word )?([a-z]+) mean$").find(t)?.let { return Lookup.Define(it.groupValues[1]) }
        Regex("^define ([a-z]+)$").find(t)?.let { return Lookup.Define(it.groupValues[1]) }
        Regex("^([a-z]+) (?:ka|ki) (?:matlab|meaning|arth)(?: kya (?:hai|hota hai))?$").find(t)?.let { return Lookup.Define(it.groupValues[1]) }

        val en = Regex(
            "^(?:who is|who was|who are|who were|what is|what are|what was|what were|tell me about|" +
                "tell me something about|search wikipedia for|wikipedia|information about|info about)\\s+(?:the |a |an )?(.+)$"
        ).find(t)?.groupValues?.get(1)
        val hi = Regex(
            "^(.+?)\\s+(?:kaun hai|kaun hain|kaun tha|kaun the|kaun thi|kya hai|kya hain|kya hota hai|kya hote hain|kya tha|" +
                "ke (?:bare|baare) (?:mein|me|main) (?:batao|bataiye|bata do|jankari do)|ki jankari do|" +
                "कौन है|कौन हैं|कौन था|कौन थे|कौन थी|क्या है|क्या हैं|क्या होता है|के बारे में बताओ|के बारे में बताइए)$"
        ).find(t)?.groupValues?.get(1)
        val topic = (en ?: hi)?.trim()?.trimEnd('?')?.removePrefix("the ")?.trim() ?: return null
        if (topic.isBlank() || topic.split(" ").size > 8) return null
        if (topic in setOf("this", "that", "it", "your name", "my name", "the time", "time", "the date", "today", "up", "new")) return null
        return Lookup.Wiki(topic)
    }

    // ---------------- Calculator ----------------

    private val opWords = listOf(
        "multiplied by" to "*", "divided by" to "/", "to the power of" to "^", "to the power" to "^", "raised to" to "^",
        "plus" to "+", "add" to "+", "jod" to "+", "jodo" to "+", "aur" to "+",
        "minus" to "-", "subtract" to "-", "ghata" to "-", "ghatao" to "-", "less" to "-",
        "times" to "*", "into" to "*", "x" to "*", "guna" to "*", "multiply" to "*",
        "over" to "/", "by" to "/", "bhag" to "/", "upon" to "/",
    )

    /** "what is 25 times 4" -> ("25 × 4", 100.0). Null if it isn't a maths question. */
    fun calculate(t0: String): Pair<String, Double>? {
        var t = Parse.numberWords(t0, articles = false)
            .replace(Regex("^(what(?:'s| is)|calculate|compute|how much is|tell me|solve|kitna hai|kitna hota hai)\\s+"), "")
            .replace(Regex("\\s+(kitna hai|kitna hota hai|kitne hote hain|kitna|equals|equal to|is equal to)\\??$"), "")
            .replace("×", "*").replace("÷", "/").trim()
        // percentages: "15 percent of 2000", "15% of 2000"
        Regex("^(\\d+(?:\\.\\d+)?)\\s*(?:%|percent|pratishat)\\s+(?:of|ka)\\s+(\\d+(?:\\.\\d+)?)$").find(t)?.let {
            val p = it.groupValues[1].toDouble()
            val n = it.groupValues[2].toDouble()
            return "${fmt(p)} percent of ${fmt(n)}" to p * n / 100
        }
        Regex("^(?:the )?square root of (\\d+(?:\\.\\d+)?)$").find(t)?.let {
            val n = it.groupValues[1].toDouble()
            return "The square root of ${fmt(n)}" to sqrt(n)
        }
        Regex("^(\\d+(?:\\.\\d+)?) (squared|cubed)$").find(t)?.let {
            val n = it.groupValues[1].toDouble()
            val sq = it.groupValues[2] == "squared"
            return "${fmt(n)} ${it.groupValues[2]}" to if (sq) n * n else n * n * n
        }
        for ((w, op) in opWords) t = t.replace(Regex("(?<![a-z])" + Regex.escape(w) + "(?![a-z])"), " $op ")
        t = t.replace(Regex("\\s+"), " ").trim()
        if (!Regex("^[-\\d.\\s+*/^()]+$").matches(t)) return null
        if (!Regex("\\d").hit(t) || !Regex("\\d\\s*[-+*/^]\\s*[-(\\d]").hit(t)) return null
        val value = Expr(t.replace(" ", "")).parse() ?: return null
        if (value.isNaN() || value.isInfinite()) return null
        val shown = t.replace("*", "×").replace("/", "÷").replace("^", " to the power ").replace(Regex("\\s+"), " ")
        return shown to value
    }

    /** Tiny recursive-descent calculator: + - * / ^ and brackets. */
    private class Expr(val s: String) {
        var i = 0
        fun parse(): Double? = runCatching { val v = sum(); if (i != s.length) null else v }.getOrNull()
        fun sum(): Double {
            var v = product()
            while (i < s.length && (s[i] == '+' || s[i] == '-')) {
                val op = s[i++]
                val r = product()
                v = if (op == '+') v + r else v - r
            }
            return v
        }
        fun product(): Double {
            var v = power()
            while (i < s.length && (s[i] == '*' || s[i] == '/')) {
                val op = s[i++]
                val r = power()
                v = if (op == '*') v * r else v / r
            }
            return v
        }
        fun power(): Double {
            val b = unary()
            if (i < s.length && s[i] == '^') {
                i++
                return b.pow(power())
            }
            return b
        }
        fun unary(): Double {
            if (i < s.length && s[i] == '-') { i++; return -unary() }
            if (i < s.length && s[i] == '(') {
                i++
                val v = sum()
                if (i < s.length && s[i] == ')') i++ else throw IllegalArgumentException()
                return v
            }
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            if (start == i) throw IllegalArgumentException()
            return s.substring(start, i).toDouble()
        }
    }

    fun fmt(v: Double): String {
        if (abs(v - v.roundToLong()) < 1e-9 && abs(v) < 1e15) return v.roundToLong().toString()
        val s = String.format(Locale.US, if (abs(v) >= 1) "%.2f" else "%.4f", v)
        return s.trimEnd('0').trimEnd('.')
    }

    // ---------------- Unit conversion ----------------

    private data class Unit(val dim: String, val toBase: Double, val name: String)

    private val units: Map<String, Unit> = buildMap {
        fun add(names: List<String>, u: Unit) = names.forEach { put(it, u) }
        add(listOf("km", "kms", "kilometer", "kilometers", "kilometre", "kilometres"), Unit("len", 1000.0, "kilometres"))
        add(listOf("m", "meter", "meters", "metre", "metres"), Unit("len", 1.0, "metres"))
        add(listOf("cm", "centimeter", "centimeters", "centimetre", "centimetres"), Unit("len", 0.01, "centimetres"))
        add(listOf("mm", "millimeter", "millimeters"), Unit("len", 0.001, "millimetres"))
        add(listOf("mile", "miles"), Unit("len", 1609.344, "miles"))
        add(listOf("foot", "feet", "ft"), Unit("len", 0.3048, "feet"))
        add(listOf("inch", "inches"), Unit("len", 0.0254, "inches"))
        add(listOf("yard", "yards"), Unit("len", 0.9144, "yards"))
        add(listOf("kg", "kgs", "kilo", "kilos", "kilogram", "kilograms"), Unit("mass", 1.0, "kilograms"))
        add(listOf("g", "gram", "grams", "gm"), Unit("mass", 0.001, "grams"))
        add(listOf("pound", "pounds", "lb", "lbs"), Unit("mass", 0.45359237, "pounds"))
        add(listOf("ounce", "ounces", "oz"), Unit("mass", 0.0283495, "ounces"))
        add(listOf("l", "liter", "liters", "litre", "litres"), Unit("vol", 1.0, "litres"))
        add(listOf("ml", "milliliter", "milliliters", "millilitre", "millilitres"), Unit("vol", 0.001, "millilitres"))
        add(listOf("gallon", "gallons"), Unit("vol", 3.78541, "gallons"))
        add(listOf("celsius", "centigrade", "c"), Unit("temp", 0.0, "degrees Celsius"))
        add(listOf("fahrenheit", "f"), Unit("temp", 0.0, "degrees Fahrenheit"))
    }

    fun convert(t0: String): String? {
        val t = Parse.numberWords(t0, articles = false).replace("degrees ", "").replace("degree ", "")
        val m = Regex("(-?\\d+(?:\\.\\d+)?)\\s*([a-z]+)\\s+(?:in|to|into|mein|me)\\s+([a-z]+)").find(t) ?: return null
        val n = m.groupValues[1].toDouble()
        val from = units[m.groupValues[2]] ?: return null
        val to = units[m.groupValues[3]] ?: return null
        if (from.dim != to.dim || from == to) return null
        val result = if (from.dim == "temp") {
            if (from.name.contains("Celsius")) n * 9 / 5 + 32 else (n - 32) * 5 / 9
        } else n * from.toBase / to.toBase
        return "${fmt(n)} ${from.name} is ${fmt(result)} ${to.name}."
    }

    // ---------------- Currency ----------------

    private val currencies = mapOf(
        "dollar" to "USD", "dollars" to "USD", "usd" to "USD", "rupee" to "INR", "rupees" to "INR", "inr" to "INR",
        "rs" to "INR", "euro" to "EUR", "euros" to "EUR", "eur" to "EUR", "pound sterling" to "GBP", "gbp" to "GBP",
        "dirham" to "AED", "dirhams" to "AED", "aed" to "AED", "yen" to "JPY", "jpy" to "JPY", "riyal" to "SAR",
        "riyals" to "SAR", "sar" to "SAR", "yuan" to "CNY", "cad" to "CAD", "aud" to "AUD", "sgd" to "SGD",
        "taka" to "BDT", "dinar" to "KWD",
    )

    private fun currency(t0: String): Lookup.Currency? {
        val t = Parse.numberWords(t0, articles = false)
        val m = Regex("(\\d+(?:\\.\\d+)?)\\s*(pound sterling|[a-z]+)\\s+(?:in|to|into|mein|me)\\s+(pound sterling|pounds|pound|[a-z]+)").find(t) ?: return null
        val to = currencies[m.groupValues[3]] ?: if (m.groupValues[3].startsWith("pound") && currencies.containsKey(m.groupValues[2])) "GBP" else return null
        val from = currencies[m.groupValues[2]] ?: if (m.groupValues[2].startsWith("pound")) "GBP" else return null
        if (from == to) return null
        return Lookup.Currency(m.groupValues[1].toDouble(), from, to)
    }

    // ---------------- Content ----------------

    private val EN_JOKES = listOf(
        "Why did the computer go to the doctor? Because it had a virus!",
        "Why don't scientists trust atoms? Because they make up everything.",
        "What do you call a fake noodle? An impasta!",
        "Why did the scarecrow win an award? Because he was outstanding in his field.",
        "Why can't a bicycle stand on its own? Because it's two tired.",
        "What did the ocean say to the beach? Nothing, it just waved.",
        "Why was the maths book sad? It had too many problems.",
        "Why did the phone wear glasses? Because it lost its contacts.",
        "What do you call a bear with no teeth? A gummy bear!",
        "Why did the cricket team hire a cook? For the perfect batter.",
        "I told my wifi we needed to talk. Now it won't connect with me.",
        "Why do bees have sticky hair? Because they use honeycombs.",
    )
    private val HI_JOKES = listOf(
        "टीचर: बताओ, सबसे ज़्यादा बर्फ़ कहाँ पाई जाती है? छात्र: जी, फ़्रिज में!",
        "पप्पू: मम्मी, मैं बड़ा होकर पायलट बनूँगा। मम्मी: बेटा, पहले बिस्तर से तो उड़ान भर ले!",
        "डॉक्टर: आपको आराम की ज़रूरत है। मरीज़: पर डॉक्टर साहब, मैं तो पूरा दिन आराम ही करता हूँ। डॉक्टर: तो अब थोड़ा काम भी कर लो!",
        "टीचर: होमवर्क क्यों नहीं किया? छात्र: सर, बिजली चली गई थी। टीचर: तो मोमबत्ती जला लेते! छात्र: माचिस भगवान के कमरे में थी, और भगवान के कमरे में मैं नहा कर ही जाता हूँ!",
        "पत्नी: सुनो, आज खाना बाहर खाते हैं। पति: ठीक है, थाली बालकनी में ले आओ!",
        "बेटा: पापा, मुझे नया फ़ोन चाहिए। पापा: पहले रिज़ल्ट दिखाओ। बेटा: पापा, फ़ोन से ही तो दिखाऊँगा, वो भी पुराने फ़ोन में खुल नहीं रहा!",
        "दोस्त: तुम इतने खुश क्यों हो? पप्पू: आज मैंने पूरी पहेली दो दिन में पूरी कर ली, जबकि डिब्बे पर लिखा था तीन से पाँच साल!",
        "टीचर: पानी का फ़ॉर्मूला बताओ। छात्र: H, I, J, K, L, M, N, O। टीचर: ये क्या है? छात्र: आपने ही तो कहा था H to O!",
    )
    private val EN_FACTS = listOf(
        "Honey never spoils. Archaeologists have found 3000 year old honey in Egyptian tombs that was still edible.",
        "Octopuses have three hearts and blue blood.",
        "A day on Venus is longer than a year on Venus.",
        "Bananas are berries, but strawberries are not.",
        "India has the world's highest cricket ground, in Chail, Himachal Pradesh.",
        "Your brain uses about 20 percent of your body's energy.",
        "The Eiffel Tower can be about 15 centimetres taller in summer because metal expands in the heat.",
        "Sharks existed before trees.",
        "Chess was invented in India, where it was called chaturanga.",
        "A group of flamingos is called a flamboyance.",
    )
    private val HI_FACTS = listOf(
        "शहद कभी ख़राब नहीं होता। मिस्र के पिरामिडों में हज़ारों साल पुराना शहद मिला है जो अब भी खाने लायक था।",
        "ऑक्टोपस के तीन दिल होते हैं और उसका खून नीला होता है।",
        "शतरंज का आविष्कार भारत में हुआ था, जहाँ इसे चतुरंग कहा जाता था।",
        "शुक्र ग्रह पर एक दिन, वहाँ के एक साल से भी लंबा होता है।",
        "केला एक बेरी है, लेकिन स्ट्रॉबेरी बेरी नहीं है।",
        "हमारा दिमाग शरीर की लगभग 20 प्रतिशत ऊर्जा इस्तेमाल करता है।",
        "दुनिया का सबसे ऊँचा क्रिकेट मैदान हिमाचल प्रदेश के चायल में है।",
        "शार्क मछलियाँ पेड़ों से भी पहले से धरती पर हैं।",
    )
    private val EN_QUOTES = listOf(
        "The secret of getting ahead is getting started.",
        "You don't have to be great to start, but you have to start to be great.",
        "Dream, dream, dream. Dreams transform into thoughts, and thoughts result in action. That's A P J Abdul Kalam.",
        "Small steps every day add up to big results.",
        "Believe you can and you're halfway there.",
        "Be the change that you wish to see in the world. That's Mahatma Gandhi.",
    )
    private val HI_QUOTES = listOf(
        "सपने वो नहीं जो सोते समय आते हैं, सपने वो हैं जो आपको सोने नहीं देते। यह कहा था डॉक्टर ए पी जे अब्दुल कलाम ने।",
        "कोशिश करने वालों की कभी हार नहीं होती।",
        "हर दिन छोटे कदम, बड़े नतीजे लाते हैं।",
        "आप वो बदलाव बनिए जो आप दुनिया में देखना चाहते हैं। यह महात्मा गांधी के शब्द हैं।",
        "मंज़िल उन्हीं को मिलती है, जिनके सपनों में जान होती है।",
        "शुरुआत करना ही आगे बढ़ने का राज़ है।",
    )
}
