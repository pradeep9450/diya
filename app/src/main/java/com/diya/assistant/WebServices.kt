package com.diya.assistant

import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.roundToInt

class HttpException(val code: Int, val body: String) : IOException("HTTP $code")

/** Free online services: Open-Meteo (weather), Google News RSS (news), Gemini (questions). */
class WebServices {

    // ---------- Weather (Open-Meteo: free, no key) ----------

    /** Weather for a named place. */
    suspend fun weather(city: String, tomorrow: Boolean, hindi: Boolean): String {
        val lang = if (hindi) "hi" else "en"
        var place = geocode(city, lang)
        if (place == null && Hindi.hasDevanagari(city)) place = geocode(Translit.toLatin(city), lang)
        if (place == null) {
            return if (hindi) "मुझे $city नाम की जगह नहीं मिली।" else "I couldn't find a place called $city."
        }
        return weatherAt(place.getDouble("latitude"), place.getDouble("longitude"), place.optString("name", city), tomorrow, hindi)
    }

    private suspend fun geocode(name: String, lang: String): JSONObject? {
        if (name.isBlank()) return null
        val geo = JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?name=${enc(name)}&count=1&language=$lang&format=json"))
        return geo.optJSONArray("results")?.optJSONObject(0)
    }

    /** Weather for coordinates (used with the phone's location). */
    suspend fun weatherAt(lat: Double, lon: Double, name: String, tomorrow: Boolean, hindi: Boolean): String {
        val w = JSONObject(
            get(
                "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                    "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code" +
                    "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
                    "&timezone=auto&forecast_days=2"
            )
        )
        val daily = w.getJSONObject("daily")
        val day = if (tomorrow) 1 else 0
        val high = daily.getJSONArray("temperature_2m_max").optDouble(day).roundToInt()
        val low = daily.getJSONArray("temperature_2m_min").optDouble(day).roundToInt()
        val rain = daily.optJSONArray("precipitation_probability_max")?.optInt(day, -1) ?: -1
        val rainText = when {
            rain < 0 -> ""
            rain >= 60 -> if (hindi) " $rain प्रतिशत बारिश की संभावना है, छाता ले जाइए।" else " There's a $rain percent chance of rain, so take an umbrella."
            rain >= 25 -> if (hindi) " $rain प्रतिशत बारिश की संभावना है।" else " There's a $rain percent chance of rain."
            else -> if (hindi) " बारिश की संभावना कम है।" else " Rain is unlikely."
        }
        if (tomorrow) {
            val code = daily.getJSONArray("weather_code").optInt(1)
            return if (hindi) {
                "कल $name में ${describeHi(code)} रहेगा, अधिकतम $high और न्यूनतम $low डिग्री।$rainText"
            } else {
                "Tomorrow in $name: ${describe(code)}, with a high of $high and a low of $low degrees.$rainText"
            }
        }
        val cur = w.getJSONObject("current")
        val temp = cur.getDouble("temperature_2m").roundToInt()
        val feels = cur.getDouble("apparent_temperature").roundToInt()
        val code = cur.optInt("weather_code")
        val feelsDiff = kotlin.math.abs(feels - temp) >= 3
        return if (hindi) {
            val feelsText = if (feelsDiff) " महसूस $feels डिग्री जैसा होगा।" else ""
            "अभी $name में $temp डिग्री है और ${describeHi(code)} है।$feelsText आज अधिकतम $high और न्यूनतम $low डिग्री रहेगा।$rainText"
        } else {
            val feelsText = if (feelsDiff) " It feels like $feels." else ""
            "Right now in $name it's $temp degrees and ${describe(code)}.$feelsText " +
                "Today's high is $high and the low is $low.$rainText"
        }
    }

    private fun describe(code: Int): String = when (code) {
        0 -> "clear"
        1 -> "mostly clear"
        2 -> "partly cloudy"
        3 -> "cloudy"
        45, 48 -> "foggy"
        51, 53, 55 -> "drizzling"
        56, 57 -> "freezing drizzle"
        61 -> "light rain"
        63 -> "raining"
        65 -> "heavy rain"
        66, 67 -> "freezing rain"
        71, 73, 75, 77 -> "snowing"
        80, 81 -> "rain showers"
        82 -> "heavy rain showers"
        85, 86 -> "snow showers"
        95 -> "thunderstorms"
        96, 99 -> "thunderstorms with hail"
        else -> "mixed weather"
    }

    private fun describeHi(code: Int): String = when (code) {
        0 -> "आसमान साफ़"
        1 -> "ज़्यादातर साफ़ मौसम"
        2 -> "हल्के बादल"
        3 -> "बादल"
        45, 48 -> "कोहरा"
        51, 53, 55, 56, 57 -> "बूंदाबांदी"
        61 -> "हल्की बारिश"
        63, 66, 67 -> "बारिश"
        65 -> "तेज़ बारिश"
        71, 73, 75, 77, 85, 86 -> "बर्फ़बारी"
        80, 81 -> "बारिश की बौछारें"
        82 -> "तेज़ बौछारें"
        95 -> "आंधी-तूफ़ान"
        96, 99 -> "ओलों के साथ तूफ़ान"
        else -> "मिला-जुला मौसम"
    }

    // ---------- News (Google News RSS: free, no key) ----------

    suspend fun headlines(topic: String?, hindi: Boolean): List<String> {
        val region = if (hindi) "hl=hi&gl=IN&ceid=IN:hi" else "hl=en-IN&gl=IN&ceid=IN:en"
        val url = if (topic.isNullOrBlank()) {
            "https://news.google.com/rss?$region"
        } else {
            "https://news.google.com/rss/search?q=${enc(topic)}&$region"
        }
        val xml = get(url)
        return withContext(Dispatchers.Default) { parseTitles(xml) }
    }

    private fun parseTitles(xml: String): List<String> {
        val titles = mutableListOf<String>()
        val parser = Xml.newPullParser()
        parser.setInput(xml.reader())
        var inItem = false
        while (parser.eventType != XmlPullParser.END_DOCUMENT && titles.size < 5) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "item" -> inItem = true
                    "title" -> if (inItem) {
                        val t = parser.nextText().substringBeforeLast(" - ").trim()
                        if (t.isNotEmpty()) titles.add(t)
                    }
                }
                XmlPullParser.END_TAG -> if (parser.name == "item") inItem = false
            }
            parser.next()
        }
        return titles
    }

    // ---------- Wikipedia (free, no key) ----------

    data class WikiAnswer(val text: String, val title: String, val inHindi: Boolean)

    /** Short spoken summary of a topic. Hindi Wikipedia when [hindi] and available. */
    suspend fun wikipedia(topic: String, hindi: Boolean): WikiAnswer? {
        if (Hindi.hasDevanagari(topic)) {
            wikiSearch("hi", topic)?.let { t -> wikiSummary("hi", t)?.let { return WikiAnswer(it, t, true) } }
            return null
        }
        val title = wikiSearch("en", topic) ?: return null
        if (hindi) {
            hindiTitle(title)?.let { ht -> wikiSummary("hi", ht)?.let { return WikiAnswer(it, ht, true) } }
        }
        return wikiSummary("en", title)?.let { WikiAnswer(it, title, false) }
    }

    private suspend fun wikiSearch(lang: String, q: String): String? {
        val o = JSONObject(get("https://$lang.wikipedia.org/w/api.php?action=query&list=search&srlimit=3&format=json&utf8=1&srsearch=${enc(q)}"))
        val arr = o.optJSONObject("query")?.optJSONArray("search") ?: return null
        for (i in 0 until arr.length()) {
            val t = arr.getJSONObject(i).optString("title")
            if (t.isNotBlank() && !t.contains("disambiguation", ignoreCase = true)) return t
        }
        return null
    }

    private suspend fun hindiTitle(enTitle: String): String? = runCatching {
        val o = JSONObject(get("https://en.wikipedia.org/w/api.php?action=query&prop=langlinks&lllang=hi&format=json&titles=${enc(enTitle)}"))
        val pages = o.getJSONObject("query").getJSONObject("pages")
        val page = pages.getJSONObject(pages.keys().next())
        page.optJSONArray("langlinks")?.optJSONObject(0)?.optString("*")?.ifBlank { null }
    }.getOrNull()

    private suspend fun wikiSummary(lang: String, title: String): String? {
        val o = JSONObject(get("https://$lang.wikipedia.org/api/rest_v1/page/summary/${enc(title.replace(' ', '_')).replace("+", "%20")}"))
        if (o.optString("type") == "disambiguation") return null
        val extract = o.optString("extract").replace(Regex("\\s*\\([^)]*\\)"), "").trim()
        if (extract.isBlank()) return null
        val sentences = extract.split(Regex("(?<=[.!?।])\\s+")).filter { it.isNotBlank() }
        var out = ""
        for (sen in sentences) {
            if (out.isNotEmpty() && out.length + sen.length > 320) break
            out = if (out.isEmpty()) sen else "$out $sen"
            if (out.length > 160) break
        }
        return out.ifBlank { null }
    }

    // ---------- Dictionary (dictionaryapi.dev: free, no key) ----------

    suspend fun define(word: String): String? = runCatching {
        val arr = JSONArray(get("https://api.dictionaryapi.dev/api/v2/entries/en/${enc(word)}"))
        val meaning = arr.getJSONObject(0).getJSONArray("meanings").getJSONObject(0)
        val pos = meaning.optString("partOfSpeech")
        val def = meaning.getJSONArray("definitions").getJSONObject(0).optString("definition")
        if (def.isBlank()) null else "$word, $pos: $def"
    }.getOrNull()

    // ---------- Currency (open.er-api.com: free, no key) ----------

    suspend fun convertCurrency(amount: Double, from: String, to: String): Double? {
        val o = JSONObject(get("https://open.er-api.com/v6/latest/$from"))
        if (o.optString("result") != "success") return null
        val rate = o.getJSONObject("rates").optDouble(to, Double.NaN)
        return if (rate.isNaN()) null else amount * rate
    }

    // ---------- Gemini (free tier with an API key from Google AI Studio) ----------

    suspend fun gemini(
        key: String,
        model: String,
        system: String,
        history: List<Pair<String, String>>,
        question: String,
    ): String {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/${model.trim()}:generateContent"
        val headers = mapOf("x-goog-api-key" to key)
        fun body(withSearch: Boolean): String = JSONObject().apply {
            put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            val contents = JSONArray()
            history.forEach { (q, a) ->
                contents.put(turn("user", q))
                contents.put(turn("model", a))
            }
            contents.put(turn("user", question))
            put("contents", contents)
            // Lets Gemini look things up on Google for current events (free-tier quota applies).
            if (withSearch) put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
        }.toString()

        val raw = try {
            post(url, body(withSearch = true), headers)
        } catch (e: HttpException) {
            // If search grounding isn't allowed or its quota is used up, ask without it.
            if (e.code == 400 || e.code == 429) post(url, body(withSearch = false), headers) else throw e
        }
        return parseGemini(raw)
    }

    private fun turn(role: String, text: String) = JSONObject()
        .put("role", role)
        .put("parts", JSONArray().put(JSONObject().put("text", text)))

    private fun parseGemini(raw: String): String {
        val o = JSONObject(raw)
        val candidates = o.optJSONArray("candidates")
        if (candidates == null || candidates.length() == 0) return "Sorry, I can't help with that one."
        val parts = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
            ?: return "Sorry, I don't have an answer for that."
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val p = parts.getJSONObject(i)
            if (p.optBoolean("thought")) continue
            sb.append(p.optString("text"))
        }
        return sb.toString().trim().ifBlank { "Sorry, I don't have an answer for that." }
    }

    // ---------- HTTP helpers ----------

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private suspend fun get(url: String, headers: Map<String, String> = emptyMap()): String =
        request(url, null, headers)

    private suspend fun post(url: String, json: String, headers: Map<String, String>): String =
        request(url, json, headers)

    private suspend fun request(url: String, json: String?, headers: Map<String, String>): String =
        withContext(Dispatchers.IO) {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 10_000
            c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", "Diya/2.0 (personal Android voice assistant)")
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            try {
                if (json != null) {
                    c.requestMethod = "POST"
                    c.doOutput = true
                    c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    c.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                }
                val code = c.responseCode
                val stream = if (code in 200..299) c.inputStream else c.errorStream
                val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) throw HttpException(code, body)
                body
            } finally {
                c.disconnect()
            }
        }
}
