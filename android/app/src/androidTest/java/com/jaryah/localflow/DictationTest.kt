package com.jaryah.localflow

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * End-to-end on a device: speech file -> whisper.cpp -> text rules.
 * Push first:  adb push ggml-small-q5_1.bin en.wav ar.wav /data/local/tmp/lf/
 * (16 kHz mono 16-bit WAVs, e.g. `say` + afconvert -f WAVE -d LEI16@16000 -c 1)
 */
@RunWith(AndroidJUnit4::class)
class DictationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File("/data/local/tmp/lf")

    private fun wav(name: String): FloatArray {
        val bytes = File(dir, name).readBytes()
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        while (pos + 8 <= bytes.size) {  // walk chunks to "data"
            val id = String(bytes, pos, 4); val size = buf.getInt(pos + 4)
            if (id == "data") return FloatArray(size / 2) { buf.getShort(pos + 8 + it * 2) / 32768f }
            pos += 8 + size + (size and 1)
        }
        error("no data chunk")
    }

    private fun setUp() {
        val m = Models.byId("small")
        val dest = Models.file(context, m)
        if (!dest.exists()) File(dir, m.file).copyTo(dest)
        Prefs(context).apply { model = "small"; language = "mixed"; smartFormat = true; signoffName = "" }
    }

    @Test fun englishAndArabic() {
        setUp()
        for ((name, expect) in listOf("en.wav" to "book", "ar.wav" to "الكتاب")) {
            val audio = wav(name)
            val t0 = System.currentTimeMillis()
            val text = Dictation.process(context, audio)
            Log.i("LocalFlowTest", "$name ${audio.size / 16000f}s -> ${System.currentTimeMillis() - t0} ms: $text")
            assertTrue("$name: $text", text.contains(expect))
        }
    }
}
