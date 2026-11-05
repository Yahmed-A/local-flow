package com.jaryah.localflow

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Test

/** cases.json holds outputs of the desktop Python rules; the Kotlin port must match them. */
class TextRulesTest {
    @Test fun matchesDesktop() {
        val cases = JSONArray(javaClass.classLoader!!.getResource("cases.json")!!.readText())
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val t = TextRules.toArabic(TextRules.clean(c.getString("in")))
            val got = if (t.isEmpty()) "" else TextRules.formatText(t, "Yasir")
            assertEquals("case $i", c.getString("out"), got)
        }
    }

    @Test fun silencesFindsPause() {
        val rate = 16000
        val audio = FloatArray(rate * 3) { i -> if (i in rate until rate * 2) 0f else 0.2f * kotlin.math.sin(i * 0.05f) }
        val s = TextRules.silences(audio)
        assertEquals(1, s.size)
        assertEquals(1.0, s[0].first, 0.05)
        assertEquals(2.0, s[0].second, 0.05)
    }
}
