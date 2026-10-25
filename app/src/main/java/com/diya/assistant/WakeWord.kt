package com.diya.assistant

import org.json.JSONObject

/**
 * "Hey Diya" detection rules, loaded from assets/wake_rules.json.
 *
 * Step 1 (fast): an offline recognizer that can only hear [grammar] phrases. The small model
 * doesn't know the name Diya, so it hears "hey dear", "hey deer", "hey dia"... all count.
 * Step 2 (check): the last couple of seconds are decoded again with full vocabulary. If that
 * confirms "hey/hi" + a Diya-like sound (dear, deer, dia, idea, here…) — otherwise it wasn't Diya.
 *
 * Lab result (ci/wake_test.py, synthetic voices, streaming): ~86% of "Hey Diya" phrases woke Diya,
 * 8 of 367 non-Diya phrases did ("Hi dear", "Hey Riya"), and 0 false wakes in 86 s of conversation.
 */
data class WakeRules(
    val grammar: List<String>,
    val confirm: Regex,
    val block: Regex,
    val verifyDelayMs: Int = 350,
    val verifySeconds: Double = 2.5,
) {
    companion object {
        val DEFAULT = WakeRules(
            grammar = listOf(
                "hey deer", "hi deer", "hey dia", "hi dia", "hey dear", "hi dear", "hey the ya", "hey d ya",
                "hey dee ya", "hey di ya", "hi d ya", "hi dee ya", "hi the ya", "hey ya", "hi ya",
            ),
            confirm = Regex(
                "\\b(hey|hi|he|hay|he'd)\\s+(the\\s+)?(dear|deer|dia|diya|idea|here|beer|cheer|we're|off)\\b|" +
                    "^(hey|hi|he|dear|hey the)$|\\bhaider\\b|" +
                    "^(hey|hi|he|hay)\\s+(what's the (time|weather|date)|what is the (time|weather)|set (a|an|the)|play|call|open|turn (on|off)|remind|kal|aaj|carl|kyle)\\b"
            ),
            block = Regex(
                "\\b(hey|hi|hay|he)\\s+(there|did|dude|daddy|dad|siri|google|go|alexa|cortana|bixby|prior|priya|riya|" +
                    "dinesh|diana|guys|man|buddy|bro|you|your|yaar|yeah)\\b"
            ),
        )

        fun parse(json: String): WakeRules = runCatching {
            val o = JSONObject(json)
            val g = o.getJSONArray("grammar")
            WakeRules(
                grammar = List(g.length()) { WakeWord.normalize(g.getString(it)) }.filter { it.isNotBlank() },
                confirm = Regex(o.getString("confirm")),
                block = Regex(o.getString("block")),
                verifyDelayMs = o.optInt("verifyDelayMs", 350),
                verifySeconds = o.optDouble("verifySeconds", 2.5),
            )
        }.getOrDefault(DEFAULT)
    }
}

object WakeWord {

    /** Grammar JSON for the fast recognizer: wake phrases + your extra spellings + [unk]. */
    fun grammarJson(rules: WakeRules, extra: List<String>): String {
        val items = (rules.grammar + extra.map { normalize(it) }).filter { it.isNotBlank() }.distinct()
        return (items + "[unk]").joinToString(",", "[", "]") { "\"" + it.replace("\"", "") + "\"" }
    }

    /** Step 1: does the fast recognizer's text contain a wake phrase? */
    fun candidate(text: String, rules: WakeRules, extra: List<String> = emptyList()): Boolean {
        val t = " " + normalize(text.replace("[unk]", " ")) + " "
        if (t.isBlank()) return false
        return (rules.grammar + extra.map { normalize(it) }).any { it.isNotBlank() && t.contains(" $it ") }
    }

    /** Step 2: true unless the full-vocabulary check confirms a Diya-like phrase ("hey dear", "hey we're"…). */
    fun vetoed(checkText: String, rules: WakeRules): Boolean {
        val t = normalize(checkText.replace("[unk]", " "))
        return !rules.confirm.containsMatchIn(t) || rules.block.containsMatchIn(t)
    }

    private val wakePrefix = Regex(
        "^(hey|hi|he|hay|he'd|haider)\\s*((the\\s+)?(dear|deer|dia|diya|idea|here|beer|cheer|we're|off)\\b)?\\s*"
    )

    /** "hey dear what's the time" -> "what's the time" (the command said in the same breath). */
    fun afterWake(text: String): String = normalize(text).replace(wakePrefix, "").trim()

    fun normalize(text: String): String =
        text.lowercase().replace(Regex("[^a-z' ]"), " ").replace(Regex("\\s+"), " ").trim()
}
