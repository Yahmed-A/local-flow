package com.jaryah.localflow

import kotlin.math.sqrt

/**
 * Kotlin port of the desktop app's text rules: clean() from app.py,
 * arabic_phrases.py and formatting.py. Keep the three in step.
 */
object TextRules {

    // Unicode \w and \b as in Python. Android's ICU regex already works that way and
    // rejects the (?U) flag, so it is only added on the desktop JVM (unit tests).
    private val U = if (System.getProperty("java.vendor").orEmpty().contains("Android")) "" else "(?U)"

    // ---------- clean (app.py) ----------

    private val FILLERS = Regex("$U(?i)\\b(?:um+|uh+|erm+|uhm+)\\b[,.]?\\s*")
    private val REPEATS = Regex("$U(\\b\\S+(?:\\s+\\S+){0,3}?)(?:\\s+\\1\\b){3,}")
    private val HALLUCINATIONS = setOf(
        "thank you.", "thanks for watching!", "thank you for watching.",
        "you", ".", "bye.", "subtitles by the amara.org community")

    fun clean(raw: String): String {
        var text = FILLERS.replace(raw, "").trim()
        // cut Whisper repetition loops ("640 640 640 640 ...") down to one
        text = REPEATS.replace(text, "$1")
        text = text.replace(Regex("[ \\t]+"), " ")
        return if (text.lowercase() in HALLUCINATIONS) "" else text
    }

    // ---------- arabic_phrases.py ----------

    const val S = "[\\s\\-'’]*"                                // optional space / hyphen / apostrophe
    private const val ALLAH = "(?:a?l{1,2})$S?a+h"                 // allah, llah, -lah
    private const val WA = "wa?$S"
    const val SALAM = "(?:a[sl]{0,2}$S)?sala+m(?:u|un)?"           // assalamu, as-salamu, salaam
    const val ALAIKUM = "[ae]?la[iy]?[kc]um"                       // alaikum, alaykum, alikum
    private const val RAHMA = "ra[hḥ]mat$S?(?:u|ul)?$S?${ALLAH}i?"
    private const val BARAKA = "bara?ka+t(?:u)?(?:hu?)?"
    // groups 1 and 2: "wa rahmatullahi", "wa barakatuh" (every other group is non-capturing)
    const val TAIL = "($S$WA$RAHMA)?($S$WA$BARAKA)?"

    private fun greeting(arabic: String): (MatchResult) -> String = { m ->
        var out = arabic
        if (m.groupValues[1].isNotEmpty()) out += " ورحمة الله"
        if (m.groupValues[2].isNotEmpty()) out += " وبركاته"
        out
    }

    private fun fixed(s: String): (MatchResult) -> String = { s }

    private val PHRASES: List<Pair<Regex, (MatchResult) -> String>> = listOf(
        "$WA$ALAIKUM$S$SALAM$TAIL" to greeting("وعليكم السلام"),
        "$SALAM$S$ALAIKUM$TAIL" to greeting("السلام عليكم"),
        "bis$S?mi?$S?l{1,2}a+h(?:i)?$S(?:[aei]?r$S)?ra[hḥ]ma+ni?$S(?:[aei]?r$S)?ra[hḥ][ie]+m" to fixed("بسم الله الرحمن الرحيم"),
        "bis$S?mi?$S?l{1,2}a+h" to fixed("بسم الله"),
        "in${S}sha$S?a?$S?$ALLAH" to fixed("إن شاء الله"),
        "ma${S}sha$S?a?$S?$ALLAH" to fixed("ما شاء الله"),
        "al$S?[hḥ]amdu?$S?li$S?l?$ALLAH" to fixed("الحمد لله"),
        "sub[hḥ]a+na?$S?$ALLAH" to fixed("سبحان الله"),
        "${ALLAH}u?${S}akbar" to fixed("الله أكبر"),
        "astagh?firu?$S?$ALLAH" to fixed("أستغفر الله"),
        "jaza(?:k|ki|kum)$S?${ALLAH}u?${S}k?hai?y?ra?n?" to fixed("جزاك الله خيرا"),
        "ba+rak$S?${ALLAH}u?${S}f[ie]+(?:k|kum|ki)" to fixed("بارك الله فيك"),
        "la${S}ila+ha${S}il{1,2}a?$S?$ALLAH" to fixed("لا إله إلا الله"),
        "sal{1,2}a?$S?${ALLAH}u?$S[ae]?la[iy]hi?${S}wa$S?sal{1,2}am" to fixed("صلى الله عليه وسلم"),
        "rad[iy]a?$S?${ALLAH}u?$S[ae]?n(?:hu|ha|hum)" to fixed("رضي الله عنه"),
    ).map { (p, r) -> Regex("$U(?i)(?<![\\w])$p(?![\\w])") to r }

    fun toArabic(text: String): String =
        PHRASES.fold(text) { t, (pat, rep) -> pat.replace(t, rep) }

    // ---------- formatting.py ----------

    private const val LRM = "‎"
    private const val RLM = "‏"
    private val ARABIC = Regex("[؀-ۿݐ-ݿﭐ-﷿ﹰ-﻿]")
    private val LATIN = Regex("[A-Za-z]")
    private val SENT_END = Regex("[.!?؟]['\"”’)]*$")
    private val SENTENCES = Regex("(?<=[.!?؟])\\s+")

    const val PAUSE_SECONDS = 0.9   // silence this long between sentences starts a new paragraph
    private const val MAX_SENTENCES = 5

    private val GREETING = Regex(
        "$U(?i)^\\s*(?:(?:و?)?السلام عليكم(?: ورحمة الله)?(?: وبركاته)?|$SALAM$S$ALAIKUM$TAIL" +
        "|(?:hi|hello|hey|dear|salaam|salam|greetings|good (?:morning|afternoon|evening))\\b)")
    private val NOT_A_NAME = setOf("i", "we", "hope", "just", "can", "could", "please", "so", "this",
        "the", "it", "you", "how", "what", "is", "are", "i'm", "i've", "thanks", "thank")
    private val CLOSING = Regex(
        "$U(?i)^(?:thanks|thank you|many thanks|thanks again|kind regards|best regards|warm regards|regards" +
        "|best wishes|all the best|best|cheers|sincerely|yours sincerely|yours faithfully|take care" +
        "|speak soon|talk soon|wassalam|was-?salam|ma'?a ?salama" +
        "|جزاك الله خيرا|جزاكم الله خيرا|بارك الله فيك|والسلام عليكم|والسلام|مع السلامة|شكرا)\\b")
    private val COMMANDS = listOf(  // the comma before a spoken command becomes a full stop
        Regex("$U(?i)([,،])?\\s*\\b(?:new|next) paragraph\\b[,.!]?\\s*") to "\n\n",
        Regex("$U(?i)([,،])?\\s*\\bnew line\\b[,.!]?\\s*") to "\n",
        Regex("([,،])?\\s*فقرة جديدة[,.،]?\\s*") to "\n\n",
        Regex("([,،])?\\s*سطر جديد[,.،]?\\s*") to "\n",
    )

    data class Segment(val start: Double, val end: Double, val text: String)

    /** (start, end) seconds of every quiet stretch at least minLen long. */
    fun silences(audio: FloatArray, rate: Int = 16000, minLen: Double = PAUSE_SECONDS): List<Pair<Double, Double>> {
        val frame = (rate * 0.03).toInt()
        val n = audio.size / frame
        if (n < 2) return emptyList()
        val rms = DoubleArray(n) { i ->
            var sum = 0.0
            for (j in i * frame until (i + 1) * frame) sum += audio[j] * audio[j]
            sqrt(sum / frame)
        }
        val sorted = rms.sorted()
        val pos = 0.9 * (n - 1)
        val lo = pos.toInt()
        val p90 = sorted[lo] + (sorted[minOf(lo + 1, n - 1)] - sorted[lo]) * (pos - lo)
        val threshold = maxOf(0.006, 0.12 * p90)
        val out = mutableListOf<Pair<Double, Double>>()
        var start = -1
        for (i in 0..n) {
            val q = i < n && rms[i] < threshold
            if (q && start < 0) start = i
            else if (!q && start >= 0) {
                if ((i - start) * 0.03 >= minLen) out += (start * 0.03) to (i * 0.03)
                start = -1
            }
        }
        return out
    }

    /** Join Whisper segments, breaking where the speaker paused after a sentence. */
    fun paragraphs(segments: List<Segment>, audio: FloatArray): List<String> {
        val sil = silences(audio)
        val paras = mutableListOf(mutableListOf<String>())
        segments.forEachIndexed { i, seg ->
            val text = seg.text.trim()
            if (text.isEmpty()) return@forEachIndexed
            val cur = paras.last()
            if (cur.isNotEmpty() && SENT_END.containsMatchIn(cur.last())) {
                val prevEnd = segments[i - 1].end
                val paused = sil.any { (s, e) -> s <= seg.start + 0.4 && e >= prevEnd - 0.4 }
                val n = cur.joinToString(" ").split(SENTENCES).size
                if ((paused && n >= 2) || n >= MAX_SENTENCES) paras += mutableListOf<String>()
            }
            paras.last() += text
        }
        return paras.filter { it.isNotEmpty() }.map { it.joinToString(" ") }
    }

    private fun cap(s0: String): String {
        val s = s0.trimStart()
        return if (s.isNotEmpty() && s[0].isLetter()) s[0].uppercase() + s.substring(1) else s
    }

    private fun words(s: String) = s.trim().split(Regex("\\s+")).count { it.isNotEmpty() }

    fun splitGreeting(text: String): String {
        val m = GREETING.find(text) ?: return text
        val rest = text.substring(m.range.last + 1)
        // take a short name / vocative after the greeting, up to the first comma or stop
        val v = Regex("^((?:[ ,،]*[^\\s,.!?؟،]+){0,3}?)\\s*([,.!،])").find(rest)
        val head: String
        var body: String
        if (v != null && v.groupValues[1].split(Regex("\\s+")).none { it.lowercase().trim('\'') in NOT_A_NAME }) {
            head = text.substring(0, m.range.last + 1 + v.range.last + 1)
            body = rest.substring(v.range.last + 1)
        } else {
            head = text.substring(0, m.range.last + 1)
            body = rest.replaceFirst(Regex("^\\s*[,.!،]"), "")
        }
        body = body.trim()
        val isSalaam = ARABIC.containsMatchIn(head) || Regex("(?i)sala+m").containsMatchIn(head)
        if (body.isEmpty() || (!isSalaam && words(body) < 8)) return text  // short chat message
        return head.trimEnd() + "\n\n" + cap(body)
    }

    fun splitClosing(text: String, name: String = ""): String {
        val paras = text.split("\n\n").toMutableList()
        val last = paras.last()
        val sents = last.split(SENTENCES)
        if (paras.size == 1 && sents.size < 2) return text
        var k = 1
        var tail = sents.last().trim()
        // "Kind regards. Yasir." (Whisper split the name off as its own sentence)
        if (sents.size >= 2 && words(tail) <= 2 && CLOSING.containsMatchIn(sents[sents.size - 2].trim()) &&
            words(sents[sents.size - 2]) <= 4 && !CLOSING.containsMatchIn(tail)) {
            tail = sents[sents.size - 2].trim().trimEnd('.') + ", " + tail.trimEnd('.')
            k = 2
        }
        if (!CLOSING.containsMatchIn(tail) || words(tail) > 5) return text
        // "Kind regards, Yasir." -> name on the next line
        val m = Regex("^(.*?[,،])\\s*([^\\s,.]+(?: [^\\s,.]+)?)[.!]?$").find(tail)
        if (m != null && !CLOSING.containsMatchIn(m.groupValues[2]) && words(m.groupValues[1]) <= 3) {
            tail = m.groupValues[1] + "\n" + m.groupValues[2]
        } else if (name.isNotEmpty()) {  // no name spoken: add the saved sign-off name
            tail = tail.trimEnd('.', '!')
            tail += (if (ARABIC.containsMatchIn(tail.takeLast(1))) "\n" else ",\n") + name
        }
        val before = last.substring(0, last.lastIndexOf(sents[sents.size - k])).trim()
        if (paras.size == 1 && words(before) < 12) return text  // short chat message
        paras[paras.size - 1] = (if (before.isNotEmpty()) "$before\n\n" else "") + tail
        return paras.filter { it.isNotBlank() }.joinToString("\n\n")
    }

    fun applyCommands(text0: String): String {
        var text = text0
        for ((pat, rep) in COMMANDS) {
            text = pat.replace(text) { m -> (if (m.groupValues[1].isNotEmpty()) "." else "") + rep }
        }
        val lines = text.split("\n").map { cap(it.trim()) }
        return lines.joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()
    }

    /** Mostly English -> left-to-right lines, mostly Arabic -> right-to-left lines. */
    fun fixDirection(text: String): String {
        val latin = LATIN.findAll(text).count()
        val arabic = ARABIC.findAll(text).count()
        if (latin == 0 || arabic == 0) return text
        val (mark, main) = if (latin >= arabic) LRM to LATIN else RLM to ARABIC
        return text.split("\n").joinToString("\n") { line ->
            val first = Regex("[A-Za-z؀-ۿ]").find(line)
            if (first != null && !main.matches(first.value)) mark + line else line
        }
    }

    fun formatText(text: String, signoffName: String = ""): String {
        var t = applyCommands(text)
        t = splitGreeting(t)
        t = splitClosing(t, signoffName.trim())
        return fixDirection(t)
    }
}
