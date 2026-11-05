package com.jaryah.localflow

import android.content.Context
import java.io.File

/** whisper.cpp through JNI, plus the desktop app's per-clip language choice and prompts. */
class Whisper private constructor(private val ctx: Long) {

    companion object {
        init { System.loadLibrary("localflow") }

        @JvmStatic private external fun nativeInit(path: String): Long
        @JvmStatic private external fun nativeFree(ctx: Long)
        @JvmStatic private external fun nativeArabicShare(ctx: Long, audio: FloatArray, threads: Int): Float
        @JvmStatic private external fun nativeTranscribe(ctx: Long, audio: FloatArray, lang: String,
                                                         prompt: String?, threads: Int): Array<String>

        // A code-switched example steers Whisper to keep each language in its own script.
        // No greetings or set phrases in it: on unclear audio Whisper echoes the prompt back.
        const val MIXED_PROMPT = "قال الأستاذ: today we read باب الفاعل في الكتاب, and the homework is الدرس الثالث."
        const val ENGLISH_PROMPT = "Hello, here is a well-punctuated sentence."
        const val AR_SHARE = 0.1f   // mixed mode decodes as Arabic when Arabic gets this share of ar+en

        private var loaded: Whisper? = null
        private var loadedPath: String? = null

        /** The model stays loaded between keyboard sessions; reload only when the file changes. */
        @Synchronized
        fun get(context: Context): Whisper? {
            val file = Models.installed(context) ?: return null
            if (loaded != null && loadedPath == file.path) return loaded
            loaded?.let { nativeFree(it.ctx) }
            loaded = null
            val ctx = nativeInit(file.path)
            if (ctx == 0L) return null
            loaded = Whisper(ctx)
            loadedPath = file.path
            return loaded
        }

        @Synchronized
        fun release() {
            loaded?.let { nativeFree(it.ctx) }
            loaded = null; loadedPath = null
        }

        fun threads(): Int {
            val cores = Runtime.getRuntime().availableProcessors()
            return if (cores >= 8) 6 else maxOf(2, cores - 2)
        }
    }

    class Result(val segments: List<TextRules.Segment>) {
        val text get() = segments.joinToString("") { it.text }
    }

    @Synchronized
    fun transcribe(audio: FloatArray, language: String, vocab: String): Result {
        val threads = threads()
        var lang = language
        var prompt: String? = when (language) {
            "mixed" -> {
                // Whisper forced to English writes spoken Arabic in Latin letters, so pick per clip
                lang = if (nativeArabicShare(ctx, audio, threads) >= AR_SHARE) "ar" else "en"
                MIXED_PROMPT
            }
            "ar" -> null  // any example sentence gets echoed back on unclear audio
            else -> ENGLISH_PROMPT
        }
        if (vocab.isNotBlank() && prompt != null) prompt = "Glossary: ${vocab.trim().split(Regex("\\s+")).joinToString(" ")}. $prompt"
        val raw = nativeTranscribe(ctx, audio, lang, prompt, threads)
        val segs = raw.mapNotNull { line ->
            val parts = line.split('\t', limit = 3)
            if (parts.size < 3) null else TextRules.Segment(parts[0].toDouble(), parts[1].toDouble(), parts[2])
        }.filterNot { g ->
            // drop segments that only echo the prompt back (happens on mumbled or quiet audio)
            val t = g.text.trim()
            t.length > 12 && prompt != null && prompt.contains(t.trim(' ', '.', '،', ','))
        }
        return Result(segs)
    }
}

/** Speech models, downloaded once from Hugging Face into app storage. */
object Models {
    data class Model(val id: String, val file: String, val sizeMb: Int, val label: String, val note: String)

    val ALL = listOf(
        Model("small", "ggml-small-q5_1.bin", 182, "Small", "Recommended. Good Arabic and English, a second or two per sentence."),
        Model("turbo", "ggml-large-v3-turbo-q5_0.bin", 548, "Large turbo", "Same model as the Mac. Most accurate, slower; for flagship phones."),
        Model("base", "ggml-base-q5_1.bin", 57, "Base", "Fastest and smallest. Weak on Arabic."),
    )

    fun url(m: Model) = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/${m.file}"
    fun dir(context: Context) = File(context.filesDir, "models").apply { mkdirs() }
    fun file(context: Context, m: Model) = File(dir(context), m.file)

    fun byId(id: String) = ALL.firstOrNull { it.id == id } ?: ALL[0]

    /** The chosen model if downloaded, else any downloaded one. */
    fun installed(context: Context): File? {
        val chosen = file(context, byId(Prefs(context).model))
        if (chosen.exists()) return chosen
        return ALL.map { file(context, it) }.firstOrNull { it.exists() }
    }
}
