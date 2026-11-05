package com.jaryah.localflow

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.sqrt

class Prefs(context: Context) {
    private val p = context.getSharedPreferences("localflow", Context.MODE_PRIVATE)

    var language: String  // mixed | en | ar | auto
        get() = p.getString("language", "mixed")!!
        set(v) = p.edit().putString("language", v).apply()
    var model: String
        get() = p.getString("model", "small")!!
        set(v) = p.edit().putString("model", v).apply()
    var smartFormat: Boolean
        get() = p.getBoolean("smart_format", true)
        set(v) = p.edit().putBoolean("smart_format", v).apply()
    var signoffName: String
        get() = p.getString("signoff_name", "")!!
        set(v) = p.edit().putString("signoff_name", v).apply()
    var vocab: String
        get() = p.getString("vocab", "")!!
        set(v) = p.edit().putString("vocab", v).apply()
    var layout: String  // en | ar: the letters the keyboard last showed
        get() = p.getString("layout", "en")!!
        set(v) = p.edit().putString("layout", v).apply()
    var voicePanel: Boolean  // the dictation panel was open last time
        get() = p.getBoolean("voice_panel", false)
        set(v) = p.edit().putBoolean("voice_panel", v).apply()

    companion object {
        val LANGUAGES = listOf("mixed" to "Mixed", "en" to "English", "ar" to "عربي", "auto" to "Auto")
    }
}

/** 16 kHz mono float microphone capture on its own thread. */
class Recorder(private val onLevel: (Float) -> Unit) {
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    private val chunks = ArrayList<FloatArray>()

    @SuppressLint("MissingPermission")  // checked by the caller
    fun start(): Boolean {
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_FLOAT, maxOf(min, RATE / 5 * 4))
        } catch (e: Exception) { return false }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return false }
        synchronized(chunks) { chunks.clear() }
        record = rec
        running = true
        rec.startRecording()
        thread = Thread {
            val buf = FloatArray(RATE / 20)  // 50 ms
            while (running) {
                val n = rec.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
                if (n <= 0) continue
                synchronized(chunks) { chunks += buf.copyOf(n) }
                var sum = 0f
                for (i in 0 until n) sum += buf[i] * buf[i]
                onLevel(sqrt(sum / n))
            }
        }.apply { start() }
        return true
    }

    /** Stops and returns everything recorded. */
    fun stop(): FloatArray {
        running = false
        thread?.join(500)
        record?.run { try { stop() } catch (_: Exception) {}; release() }
        record = null
        synchronized(chunks) {
            val out = FloatArray(chunks.sumOf { it.size })
            var pos = 0
            for (c in chunks) { c.copyInto(out, pos); pos += c.size }
            chunks.clear()
            return out
        }
    }

    companion object { const val RATE = 16000 }
}

/** Audio in, finished text out: the same steps as the desktop app's transcribe(). */
object Dictation {
    const val MIN_SECONDS = 0.35
    const val MIN_RMS = 0.0015   // quieter than this is treated as no speech

    fun tooQuiet(audio: FloatArray): Boolean {
        if (audio.size < Recorder.RATE * MIN_SECONDS) return true
        var sum = 0.0
        for (x in audio) sum += x * x
        return sqrt(sum / audio.size) < MIN_RMS
    }

    /** Returns "" when no words were heard; throws when no model is installed. */
    fun process(context: Context, audio: FloatArray): String {
        val prefs = Prefs(context)
        val whisper = Whisper.get(context) ?: throw IllegalStateException("No speech model")
        val r = whisper.transcribe(audio, prefs.language, prefs.vocab)
        val smart = prefs.smartFormat
        val parts = if (smart && r.segments.isNotEmpty()) TextRules.paragraphs(r.segments, audio) else listOf(r.text)
        var text = TextRules.clean(parts.map { TextRules.clean(it) }.filter { it.isNotEmpty() }.joinToString("\n\n"))
        if (prefs.language != "en") text = TextRules.toArabic(text)
        if (smart && text.isNotEmpty()) text = TextRules.formatText(text, prefs.signoffName)
        return text
    }
}
