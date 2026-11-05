package com.jaryah.localflow

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Setup and settings: microphone, turning the keyboard on, speech model, preferences. */
class SetupActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var micStatus: TextView
    private lateinit var kbStatus: TextView
    private lateinit var modelStatus: TextView
    private lateinit var progress: ProgressBar
    private lateinit var downloadBtn: Button
    private val prefs by lazy { Prefs(this) }

    private fun dpi(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(20), dpi(28), dpi(20), dpi(40))
        }
        setContentView(ScrollView(this).apply { addView(col); setBackgroundColor(BG); fitsSystemWindows = true })

        col.addView(text("Local Flow", 28f, INK).apply { setPadding(0, 0, 0, dpi(4)) })
        col.addView(text("On-device dictation in English and Arabic. Nothing you say leaves the phone.", 15f, MUTED))

        // 1. microphone
        col.addView(heading("1  Microphone"))
        micStatus = text("", 14f, MUTED)
        col.addView(micStatus)
        col.addView(button("Allow microphone") { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1) })

        // 2. keyboard
        col.addView(heading("2  Turn on the keyboard"))
        kbStatus = text("", 14f, MUTED)
        col.addView(kbStatus)
        col.addView(button("Open keyboard settings") { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) })
        col.addView(button("Switch to Local Flow") {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        })

        // 3. model
        col.addView(heading("3  Speech model"))
        val group = RadioGroup(this)
        Models.ALL.forEachIndexed { i, m ->
            group.addView(RadioButton(this).apply {
                id = 100 + i
                text = "${m.label}  ·  ${m.sizeMb} MB\n${m.note}"
                setTextColor(INK)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(dpi(6), dpi(8), 0, dpi(8))
                isChecked = m.id == prefs.model
            })
        }
        group.setOnCheckedChangeListener { _, id ->
            prefs.model = Models.ALL[id - 100].id
            Whisper.release()
            update()
        }
        col.addView(group)
        modelStatus = text("", 14f, MUTED)
        col.addView(modelStatus)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; visibility = View.GONE
        }
        col.addView(progress)
        downloadBtn = button("Download") { Downloader.start(applicationContext, Models.byId(prefs.model)) }
        col.addView(downloadBtn)

        // 4. preferences
        col.addView(heading("4  Preferences"))
        col.addView(text("Language (also on the keyboard: tap the language button)", 14f, MUTED))
        val langs = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        Prefs.LANGUAGES.forEachIndexed { i, (id, label) ->
            langs.addView(RadioButton(this).apply {
                this.id = 200 + i; text = label; setTextColor(INK); isChecked = id == prefs.language
            })
        }
        langs.setOnCheckedChangeListener { _, id -> prefs.language = Prefs.LANGUAGES[id - 200].first }
        col.addView(langs)
        col.addView(text("Mixed picks Arabic or English for each clip and keeps each language in its own script.", 13f, MUTED))

        col.addView(Switch(this).apply {
            text = "Smart paragraphs (greeting line, sign-off, pauses, \"new paragraph\")"
            setTextColor(INK)
            isChecked = prefs.smartFormat
            setPadding(0, dpi(14), 0, dpi(6))
            setOnCheckedChangeListener { _, on -> prefs.smartFormat = on }
        })
        col.addView(field("Sign-off name", prefs.signoffName, "Added after Kind regards or JazakAllahu khayran in longer messages") {
            prefs.signoffName = it
        })
        col.addView(field("Glossary", prefs.vocab, "Names and words Whisper should spell your way, separated by spaces", multi = true) {
            prefs.vocab = it
        })

        // 5. try
        col.addView(heading("5  Try it"))
        col.addView(EditText(this).apply {
            hint = "Tap here, switch to Local Flow, hold the mic and speak"
            setTextColor(INK); setHintTextColor(MUTED)
            minLines = 4
            gravity = Gravity.TOP
            background = box()
            setPadding(dpi(12), dpi(10), dpi(12), dpi(10))
        })
    }

    override fun onResume() {
        super.onResume()
        update()
        main.post(ticker)
    }

    override fun onPause() {
        main.removeCallbacks(ticker)
        super.onPause()
    }

    private val ticker = object : Runnable {
        override fun run() { updateDownload(); main.postDelayed(this, 300) }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        update()
    }

    private fun update() {
        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        micStatus.text = if (mic) "Allowed." else "Not allowed yet."
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val enabled = imm.enabledInputMethodList.any { it.packageName == packageName }
        kbStatus.text = if (enabled) "On. Use the button below or the keyboard switcher to choose it."
                        else "Turn on Local Flow in the list, then come back."
        updateDownload()
    }

    private fun updateDownload() {
        val m = Models.byId(prefs.model)
        val have = Models.file(this, m).exists()
        val d = Downloader.state
        val busy = d.model == m.id && d.running
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        progress.progress = (d.fraction * 1000).toInt()
        downloadBtn.visibility = if (have || busy) View.GONE else View.VISIBLE
        modelStatus.text = when {
            busy -> "Downloading ${m.label}… ${(d.fraction * 100).toInt()}%"
            have -> "${m.label} is ready."
            d.model == m.id && d.error != null -> "Download failed: ${d.error}. Check the connection and try again."
            else -> "Not downloaded. Use Wi-Fi: ${m.sizeMb} MB."
        }
    }

    // ---------- small view helpers ----------

    private fun text(s: String, sp: Float, color: Int) = TextView(this).apply {
        text = s; setTextColor(color); setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setLineSpacing(0f, 1.15f)
    }

    private fun heading(s: String) = text(s, 18f, INK).apply { setPadding(0, dpi(28), 0, dpi(6)) }

    private fun box() = GradientDrawable().apply { setColor(KEY); cornerRadius = dpi(8).toFloat() }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(BG)
        background = GradientDrawable().apply { setColor(INK); cornerRadius = dpi(8).toFloat() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dpi(44))
            .apply { topMargin = dpi(10) }
        setPadding(dpi(18), 0, dpi(18), 0)
        setOnClickListener { onClick() }
    }

    private fun field(label: String, value: String, help: String, multi: Boolean = false, save: (String) -> Unit) =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dpi(12), 0, 0)
            addView(text(label, 14f, INK))
            addView(EditText(context).apply {
                setText(value)
                setTextColor(INK)
                isSingleLine = !multi
                if (multi) minLines = 2
                background = box()
                setPadding(dpi(12), dpi(10), dpi(12), dpi(10))
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun afterTextChanged(s: Editable?) { save(s.toString()) }
                })
            })
            addView(text(help, 12f, MUTED))
        }

    companion object {
        const val BG = LocalFlowIME.BG
        const val KEY = LocalFlowIME.KEY
        const val INK = LocalFlowIME.INK
        const val MUTED = LocalFlowIME.MUTED
    }
}

/** Downloads one model at a time in the background; the setup screen polls its state. */
object Downloader {
    data class State(val model: String? = null, val running: Boolean = false,
                     val fraction: Float = 0f, val error: String? = null)

    @Volatile var state = State()
        private set

    fun start(context: Context, m: Models.Model) {
        if (state.running) return
        state = State(m.id, true)
        Thread {
            val dest = Models.file(context, m)
            val part = File(dest.path + ".part")
            try {
                var url = URL(Models.url(m))
                var conn: HttpURLConnection
                var hops = 0
                while (true) {  // follow redirects to the CDN by hand (they can switch host)
                    conn = url.openConnection() as HttpURLConnection
                    conn.instanceFollowRedirects = false
                    conn.connectTimeout = 15000; conn.readTimeout = 30000
                    val code = conn.responseCode
                    if (code in 300..399 && hops++ < 5) { url = URL(url, conn.getHeaderField("Location")); conn.disconnect(); continue }
                    if (code != 200) throw Exception("HTTP $code")
                    break
                }
                val total = conn.contentLengthLong.takeIf { it > 0 } ?: (m.sizeMb * 1_048_576L)
                var done = 0L
                conn.inputStream.use { input ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(1 shl 16)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            state = state.copy(fraction = done.toFloat() / total)
                        }
                    }
                }
                if (done < total * 0.98) throw Exception("incomplete download")
                part.renameTo(dest)
                state = State(m.id, false, 1f)
            } catch (e: Exception) {
                part.delete()
                state = State(m.id, false, 0f, e.message ?: "error")
            }
        }.start()
    }
}
