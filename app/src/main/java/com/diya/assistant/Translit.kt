package com.diya.assistant

/**
 * Very small Devanagari -> English-letters converter, used so that place names
 * heard in Hindi ("पुणे") can be looked up by the weather service ("pune").
 */
object Translit {
    private val consonants = mapOf(
        'क' to "k", 'ख' to "kh", 'ग' to "g", 'घ' to "gh", 'ङ' to "n",
        'च' to "ch", 'छ' to "chh", 'ज' to "j", 'झ' to "jh", 'ञ' to "n",
        'ट' to "t", 'ठ' to "th", 'ड' to "d", 'ढ' to "dh", 'ण' to "n",
        'त' to "t", 'थ' to "th", 'द' to "d", 'ध' to "dh", 'न' to "n",
        'प' to "p", 'फ' to "ph", 'ब' to "b", 'भ' to "bh", 'म' to "m",
        'य' to "y", 'र' to "r", 'ल' to "l", 'व' to "v", 'श' to "sh", 'ष' to "sh", 'स' to "s", 'ह' to "h",
        'ळ' to "l",
        // precomposed nukta letters (क़ ख़ ग़ ज़ ड़ ढ़ फ़)
        'क़' to "q", 'ख़' to "kh", 'ग़' to "g", 'ज़' to "z", 'ड़' to "r", 'ढ़' to "rh", 'फ़' to "f",
    )
    private val vowels = mapOf(
        'अ' to "a", 'आ' to "a", 'इ' to "i", 'ई' to "i", 'उ' to "u", 'ऊ' to "u", 'ऋ' to "ri",
        'ए' to "e", 'ऐ' to "ai", 'ओ' to "o", 'औ' to "au", 'ऑ' to "o",
    )
    private val signs = mapOf(
        'ा' to "a", 'ि' to "i", 'ी' to "i", 'ु' to "u", 'ू' to "u", 'ृ' to "ri",
        'े' to "e", 'ै' to "ai", 'ो' to "o", 'ौ' to "au", 'ॉ' to "o",
    )
    private const val VIRAMA = '्'
    private const val NUKTA = '़'

    private val aliases = mapOf("dilli" to "delhi", "nai dilli" to "new delhi", "bambai" to "mumbai", "kalkatta" to "kolkata")

    fun toLatin(text: String): String {
        val words = text.split(Regex("\\s+")).map { word(it) }
        val joined = words.joinToString(" ").trim()
        return aliases[joined] ?: joined
    }

    private fun word(w: String): String {
        val sb = StringBuilder()
        var i = 0
        var endsWithInherent = false
        while (i < w.length) {
            val c = w[i]
            val cons = consonants[c]
            when {
                cons != null -> {
                    sb.append(cons)
                    var j = i + 1
                    if (j < w.length && w[j] == NUKTA) j++
                    val next = w.getOrNull(j)
                    endsWithInherent = false
                    when {
                        next == VIRAMA -> j++
                        next != null && signs.containsKey(next) -> { sb.append(signs[next]); j++ }
                        else -> { sb.append('a'); endsWithInherent = true }
                    }
                    i = j
                    continue
                }
                vowels.containsKey(c) -> { sb.append(vowels[c]); endsWithInherent = false }
                c == 'ं' || c == 'ँ' -> { sb.append('n'); endsWithInherent = false }
                c == 'ः' -> sb.append('h')
                c.isLetterOrDigit() && c.code < 128 -> { sb.append(c); endsWithInherent = false }
            }
            i++
        }
        // Hindi drops the final "a" sound: "नगर" is "nagar", not "nagara".
        if (endsWithInherent && sb.length > 2) sb.setLength(sb.length - 1)
        // Inherent "a" before an anusvara-less consonant mid-word is often silent too, but keep it simple.
        return sb.toString()
    }
}
