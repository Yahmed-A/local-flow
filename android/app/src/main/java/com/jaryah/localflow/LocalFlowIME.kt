package com.jaryah.localflow

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * The voice keyboard. Hold the mic to talk (release to write), or tap it for
 * hands-free and tap again to finish. Text goes straight into the focused field.
 */
class LocalFlowIME : InputMethodService() {

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()  // one transcription at a time, in order
    private val pending = AtomicInteger(0)

    private var recorder: Recorder? = null
    private var pressAt = 0L
    private var handsFree = false

    private lateinit var status: TextView
    private lateinit var chip: TextView
    private lateinit var mic: FrameLayout
    private lateinit var micIcon: ImageView
    private lateinit var levels: LevelView

    override fun onCreate() {
        super.onCreate()
        worker.execute { try { Whisper.get(this) } catch (_: Throwable) {} }  // warm the model
    }

    override fun onDestroy() {
        recorder?.stop()
        worker.shutdown()
        super.onDestroy()
    }

    // ---------- view ----------

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private fun dpi(v: Int) = dp(v.toFloat()).toInt()

    private fun rounded(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius)
    }

    private fun key(label: String, weight: Float, onClick: (View) -> Unit): TextView = TextView(this).apply {
        text = label
        setTextColor(INK)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        gravity = Gravity.CENTER
        background = rounded(KEY, 8f)
        layoutParams = LinearLayout.LayoutParams(0, dpi(46), weight).apply { setMargins(dpi(3), 0, dpi(3), 0) }
        setOnClickListener { it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); onClick(it) }
    }

    override fun onCreateInputView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dpi(8), dpi(10), dpi(8), dpi(8))
            // keep the keys above the gesture / navigation bar
            setOnApplyWindowInsetsListener { v, insets ->
                val nav = if (Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.navigationBars()).bottom
                          else @Suppress("DEPRECATION") insets.systemWindowInsetBottom
                v.setPadding(dpi(8), dpi(10), dpi(8), dpi(8) + nav)
                insets
            }
        }

        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        status = TextView(this).apply {
            setTextColor(MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dpi(6) }
        }
        chip = TextView(this).apply {
            setTextColor(INK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dpi(12), dpi(5), dpi(12), dpi(5))
            background = rounded(KEY, 14f)
            setOnClickListener { onChip() }
        }
        top.addView(status); top.addView(chip)
        root.addView(top)

        levels = LevelView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpi(28))
                .apply { topMargin = dpi(10) }
        }
        root.addView(levels)

        micIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_mic)
            layoutParams = FrameLayout.LayoutParams(dpi(34), dpi(34), Gravity.CENTER)
        }
        mic = FrameLayout(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(INK) }
            layoutParams = LinearLayout.LayoutParams(dpi(84), dpi(84)).apply {
                gravity = Gravity.CENTER_HORIZONTAL; topMargin = dpi(6); bottomMargin = dpi(12)
            }
            addView(micIcon)
            setOnTouchListener { v, e -> onMicTouch(v, e) }
            contentDescription = "Dictate"
        }
        root.addView(mic)

        val row = LinearLayout(this)
        row.addView(key("ABC", 1.1f) { switchKeyboard() }.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setOnLongClickListener { picker(); true }
            contentDescription = "Switch keyboard"
        })
        row.addView(key(",", 0.8f) { commit(",") })
        row.addView(key("space", 3f) { commit(" ") }.apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); setTextColor(MUTED) })
        row.addView(key(".", 0.8f) { commit(".") })
        row.addView(key("⌫", 1.2f) { backspace() }.apply { setOnTouchListener(repeater { backspace() }) })
        row.addView(key("⏎", 1.2f) { enter() })
        root.addView(row)

        refresh()
        return root
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        refresh()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        if (recorder != null) cancel()
        super.onFinishInputView(finishingInput)
    }

    private fun langLabel() = Prefs.LANGUAGES.first { it.first == Prefs(this).language }.second

    private fun refresh(message: String? = null) {
        if (!::status.isInitialized) return
        val recording = recorder != null
        chip.text = if (recording) "Cancel" else langLabel()
        status.text = message ?: when {
            recording && handsFree -> "Listening. Tap to finish"
            recording -> "Listening. Release to write"
            pending.get() > 0 -> "Writing…"
            !micAllowed() -> "Open Local Flow to allow the microphone"
            Models.installed(this) == null -> "Open Local Flow to download a speech model"
            else -> "Hold to talk, or tap for hands-free"
        }
        (mic.background as GradientDrawable).setColor(if (recording) RED else INK)
        micIcon.setColorFilter(if (recording) INK else BG)
        if (!recording) levels.clear()
    }

    // ---------- mic ----------

    private fun micAllowed() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun openApp() {
        startActivity(Intent(this, SetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun onMicTouch(v: View, e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (recorder != null) {  // second tap ends hands-free
                    if (handsFree) finish()
                    return true
                }
                if (!micAllowed() || Models.installed(this) == null) { openApp(); return true }
                pressAt = SystemClock.uptimeMillis()
                handsFree = false
                begin(v)
            }
            MotionEvent.ACTION_UP -> {
                if (recorder != null && !handsFree) {
                    if (SystemClock.uptimeMillis() - pressAt < TAP_MS) { handsFree = true; refresh() }
                    else finish()
                }
            }
            MotionEvent.ACTION_CANCEL -> if (recorder != null && !handsFree) finish()
        }
        return true
    }

    private fun begin(v: View) {
        val rec = Recorder { level -> main.post { levels.push(level) } }
        if (!rec.start()) { refresh("Couldn't open the microphone"); return }
        recorder = rec
        v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        refresh()
    }

    private fun cancel() {
        recorder?.stop()
        recorder = null
        refresh()
    }

    private fun finish() {
        val audio = recorder?.stop() ?: return
        recorder = null
        mic.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        if (Dictation.tooQuiet(audio)) { refresh("No words heard"); return }
        pending.incrementAndGet()
        refresh()
        worker.execute {
            val result = try { Dictation.process(this, audio) } catch (e: Throwable) { null }
            main.post {
                pending.decrementAndGet()
                when {
                    result == null -> refresh("Something went wrong. Try again")
                    result.isEmpty() -> refresh("No words heard")
                    else -> { insert(result); refresh() }
                }
            }
        }
    }

    private fun onChip() {
        if (recorder != null) { cancel(); return }
        val prefs = Prefs(this)
        val i = Prefs.LANGUAGES.indexOfFirst { it.first == prefs.language }
        prefs.language = Prefs.LANGUAGES[(i + 1) % Prefs.LANGUAGES.size].first
        refresh()
    }

    // ---------- text ----------

    private fun multiLine(): Boolean {
        val t = currentInputEditorInfo?.inputType ?: return true
        return t and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0 || t and InputType.TYPE_TEXT_FLAG_IME_MULTI_LINE != 0
    }

    private fun insert(text0: String) {
        val ic = currentInputConnection ?: return
        var text = if (multiLine()) text0 else text0.replace(Regex("\\s*\n+\\s*"), " ")
        val before = ic.getTextBeforeCursor(1, 0)
        if (!before.isNullOrEmpty() && !before[0].isWhitespace() && text.firstOrNull()?.isLetterOrDigit() == true) {
            text = " $text"
        }
        ic.commitText(text, 1)
    }

    private fun commit(s: String) { currentInputConnection?.commitText(s, 1) }

    private fun backspace() {
        val ic = currentInputConnection ?: return
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) ic.commitText("", 1)
        else {
            // delete a whole surrogate pair / emoji, not half of one
            val before = ic.getTextBeforeCursor(2, 0)
            val n = if (before != null && before.length == 2 && Character.isSurrogatePair(before[0], before[1])) 2 else 1
            ic.deleteSurroundingText(n, 0)
        }
    }

    private fun enter() {
        val ic = currentInputConnection ?: return
        val info = currentInputEditorInfo
        val action = (info?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        val noEnterAction = (info?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0
        if (!multiLine() && !noEnterAction && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(action)
        } else {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }
    }

    private fun switchKeyboard() {
        if (Build.VERSION.SDK_INT >= 28) { if (!switchToPreviousInputMethod()) picker() } else picker()
    }

    private fun picker() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
    }

    /** Touch listener that repeats an action while held (backspace). */
    private fun repeater(action: () -> Unit) = object : View.OnTouchListener {
        val tick = object : Runnable { override fun run() { action(); main.postDelayed(this, 60) } }
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    action(); main.postDelayed(tick, 400); v.isPressed = true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { main.removeCallbacks(tick); v.isPressed = false }
            }
            return true
        }
    }

    companion object {
        const val TAP_MS = 300L
        const val BG = 0xFF1C1B19.toInt()
        const val KEY = 0xFF2E2D2A.toInt()
        const val INK = 0xFFEDEAE3.toInt()
        const val MUTED = 0xFF9C978D.toInt()
        const val RED = 0xFFC8412F.toInt()
    }
}

/** A scrolling row of bars showing the microphone level. */
class LevelView(context: Context) : View(context) {
    private val values = FloatArray(40)
    private var head = 0
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = LocalFlowIME.MUTED }
    private val rect = RectF()

    fun push(level: Float) {
        values[head] = level
        head = (head + 1) % values.size
        invalidate()
    }

    fun clear() { values.fill(0f); invalidate() }

    override fun onDraw(canvas: Canvas) {
        val n = values.size
        val gap = width / n.toFloat()
        val bar = gap * 0.45f
        for (i in 0 until n) {
            val v = values[(head + i) % n]
            val h = maxOf(bar, minOf(1f, v * 12f) * height)
            val x = i * gap + (gap - bar) / 2
            rect.set(x, (height - h) / 2, x + bar, (height + h) / 2)
            canvas.drawRoundRect(rect, bar / 2, bar / 2, paint)
        }
    }
}
