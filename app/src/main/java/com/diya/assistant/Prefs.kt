package com.diya.assistant

import android.content.Context
import org.json.JSONArray

/** Small wrapper around SharedPreferences for Diya's settings and notes. */
class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("diya", Context.MODE_PRIVATE)

    var userName: String
        get() = sp.getString("user_name", "").orEmpty()
        set(v) = sp.edit().putString("user_name", v.trim()).apply()

    var city: String
        get() = sp.getString("city", "").orEmpty()
        set(v) = sp.edit().putString("city", v.trim()).apply()

    var geminiKey: String
        get() = sp.getString("gemini_key", "").orEmpty()
        set(v) = sp.edit().putString("gemini_key", v.trim()).apply()

    var geminiModel: String
        get() = sp.getString("gemini_model", DEFAULT_MODEL).orEmpty().ifBlank { DEFAULT_MODEL }
        set(v) = sp.edit().putString("gemini_model", v.trim()).apply()

    /** Extra spellings that should count as "Hey Diya", e.g. "hey dear". */
    var extraWakeWords: List<String>
        get() = sp.getString("extra_wake", "").orEmpty()
            .split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        set(v) = sp.edit().putString("extra_wake", v.joinToString(",")).apply()

    /** "en" = English, "hi" = Hindi. Decides which language Diya listens for and replies in. */
    var language: String
        get() = sp.getString("language", "en").orEmpty().ifBlank { "en" }
        set(v) = sp.edit().putString("language", v).apply()

    val hindi: Boolean get() = language == "hi"

    /** Country calling code used for WhatsApp numbers saved without one (91 = India). */
    var countryCode: String
        get() = sp.getString("country_code", "91").orEmpty().filter { it.isDigit() }.ifBlank { "91" }
        set(v) = sp.edit().putString("country_code", v.filter { it.isDigit() }).apply()

    /** Read new WhatsApp / SMS / Telegram messages aloud as they arrive. */
    var announceMessages: Boolean
        get() = sp.getBoolean("announce_messages", false)
        set(v) = sp.edit().putBoolean("announce_messages", v).apply()

    /** Say who is calling when the phone rings. */
    var announceCalls: Boolean
        get() = sp.getBoolean("announce_calls", true)
        set(v) = sp.edit().putBoolean("announce_calls", v).apply()

    /** Keep listening after Diya replies, so you can carry on without "Hey Diya" (like Alexa follow-up). */
    var conversationMode: Boolean
        get() = sp.getBoolean("conversation_mode", true)
        set(v) = sp.edit().putBoolean("conversation_mode", v).apply()

    /** Turn Diya on automatically when the app is opened (remembers if you turned her off). */
    var wantOn: Boolean
        get() = sp.getBoolean("want_on", true)
        set(v) = sp.edit().putBoolean("want_on", v).apply()

    /** "auto" = phone's recognizer with offline backup, "offline" = always offline (no internet needed). */
    var speechEngine: String
        get() = sp.getString("speech_engine", "auto").orEmpty().ifBlank { "auto" }
        set(v) = sp.edit().putString("speech_engine", v).apply()

    var askedOptionalPermissions: Boolean
        get() = sp.getBoolean("asked_optional", false)
        set(v) = sp.edit().putBoolean("asked_optional", v).apply()

    var notes: List<String>
        get() = try {
            val arr = JSONArray(sp.getString("notes", "[]"))
            List(arr.length()) { arr.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }
        set(v) = sp.edit().putString("notes", JSONArray(v).toString()).apply()

    companion object {
        /** Google's alias that always points at the current free Flash model. */
        const val DEFAULT_MODEL = "gemini-flash-latest"
    }
}
