package com.diya.assistant

/**
 * Decides whether a piece of text from the offline recognizer is "Hey Diya".
 *
 * The small offline model doesn't know the name "Diya" perfectly, so it may write
 * it as "dia", "deeya" and so on. All of these count. If the screen shows the
 * engine hearing something else when you say "Hey Diya", add that spelling in
 * Settings → Extra wake word spellings.
 */
object WakeWord {
    private val greetings = listOf("hey", "hay", "hai", "hi", "he", "hello", "ok", "okay", "oh", "a")
    private val names = listOf(
        "diya", "dia", "deeya", "diyaa", "deya", "dya", "dea", "diyah", "diya's",
        "dee ya", "di ya", "de ya", "the ya", "thee ya"
    )
    private val joined = listOf("heydiya", "hidiya", "haydiya", "heydia", "hidia")

    data class Match(val trailing: String)

    fun find(text: String, extra: List<String> = emptyList()): Match? {
        val t = normalize(text)
        if (t.isEmpty()) return null
        val regex = build(extra)
        val m = regex.find(t) ?: return null
        return Match(m.groupValues[1].trim())
    }

    fun normalize(text: String): String =
        text.lowercase().replace(Regex("[^a-z' ]"), " ").replace(Regex("\\s+"), " ").trim()

    private var cachedExtra: List<String>? = null
    private var cachedRegex: Regex? = null

    @Synchronized
    private fun build(extra: List<String>): Regex {
        cachedRegex?.let { if (cachedExtra == extra) return it }
        val g = greetings.joinToString("|") { Regex.escape(it) }
        val n = names.joinToString("|") { Regex.escape(it) }
        val whole = (joined + extra.map { normalize(it) }.filter { it.isNotEmpty() })
            .joinToString("|") { Regex.escape(it) }
        val r = Regex("(?:^|\\s)(?:(?:$g)\\s+(?:$n)|$whole)(?=\\s|$)(.*)$")
        cachedExtra = extra
        cachedRegex = r
        return r
    }
}
