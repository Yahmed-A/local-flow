package com.jaryah.localflow

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.Log
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/** One key. [text] is what it types (the label by default); [popup] holds the long-press choices. */
class Key(
    val label: String,
    val kind: Kind = Kind.CHAR,
    val weight: Float = 1f,
    val text: String = label,
    val popup: List<String> = emptyList(),
) {
    enum class Kind { CHAR, SHIFT, BACK, ENTER, SPACE, SYMBOLS, MORE, LETTERS, LANG }
    val cell = RectF()  // the key's slot; the drawn key sits inside it
    val hit = RectF()   // touch area: edge keys reach the screen edge
}

/** A page of keys. Rows narrower than [units] are centred; rows that fill it are stretched. */
class Page(val id: String, val lang: String, val units: Float, val rows: List<List<Key>>) {
    val letters get() = id == lang
}

object Layouts {
    private fun row(chars: String, w: Float = 1f, pop: Map<String, String> = emptyMap()) =
        chars.split(" ").map { Key(it, weight = w, popup = pop[it]?.split(" ") ?: emptyList()) }

    private fun bottom(ar: Boolean, toggle: Key) = listOf(
        toggle,
        Key(if (ar) "،" else ","),
        Key(if (ar) "EN" else "ع", Key.Kind.LANG),
        Key("space", Key.Kind.SPACE, 4f, " "),
        Key(".", popup = (if (ar) "؟ ! ، ؛ : \" - …" else "? ! , ' \" : ; - …").split(" ")),
        Key("⏎", Key.Kind.ENTER, 1.5f),
    )

    private fun back(w: Float) = Key("⌫", Key.Kind.BACK, w)

    private val EN_POP = mapOf(
        "q" to "1", "w" to "2", "e" to "3 è é ê ë ē", "r" to "4", "t" to "5", "y" to "6 ý ÿ",
        "u" to "7 ù ú û ü ū", "i" to "8 ì í î ï", "o" to "9 ò ó ô ö õ ø œ", "p" to "0",
        "a" to "à á â ä ã å æ", "s" to "ś š", "d" to "ð", "g" to "ğ", "l" to "ł",
        "z" to "ž ź ż", "c" to "ç ć č", "n" to "ñ ń",
    )

    private val AR_POP = mapOf(
        "ض" to "١ 1", "ص" to "٢ 2", "ث" to "٣ 3", "ق" to "٤ 4", "ف" to "٥ 5", "غ" to "٦ 6",
        "ع" to "٧ 7", "ه" to "٨ 8 ة", "خ" to "٩ 9", "ح" to "٠ 0",
        "ا" to "أ إ آ ٱ", "ء" to "أ إ ئ ؤ", "ي" to "ئ ى", "ى" to "ئ", "و" to "ؤ", "ل" to "لا",
    )

    val en = Page("en", "en", 10f, listOf(
        row("q w e r t y u i o p", pop = EN_POP),
        row("a s d f g h j k l", pop = EN_POP),
        listOf(Key("shift", Key.Kind.SHIFT, 1.5f)) + row("z x c v b n m", pop = EN_POP) + back(1.5f),
        bottom(false, Key("?123", Key.Kind.SYMBOLS, 1.5f)),
    ))

    val ar = Page("ar", "ar", 11f, listOf(
        row("ض ص ث ق ف غ ع ه خ ح ج", pop = AR_POP),
        row("ش س ي ب ل ا ت ن م ك ط", pop = AR_POP),
        row("ذ ئ ء ؤ ر ى ة و ز ظ د", 0.9f, AR_POP) + back(1.1f),
        bottom(true, Key("؟١٢٣", Key.Kind.SYMBOLS, 1.5f)),
    ))

    // fatha, fathatan, damma, dammatan, kasra, kasratan, sukun, shadda, dagger alif; shown on a tatweel
    private val TASHKEEL = "َ ً ُ ٌ ِ ٍ ْ ّ ٰ".split(" ")

    private fun symbols(ar: Boolean) = Page(if (ar) "sym-ar" else "sym-en", if (ar) "ar" else "en", 10f, listOf(
        if (ar) "١ ٢ ٣ ٤ ٥ ٦ ٧ ٨ ٩ ٠".split(" ").mapIndexed { i, d -> Key(d, popup = listOf("${(i + 1) % 10}")) }
        else row("1 2 3 4 5 6 7 8 9 0", pop = mapOf("1" to "¹ ½ ¼", "2" to "² ⅔", "3" to "³ ¾")),
        if (ar) TASHKEEL.map { Key("ـ$it", text = it) } + Key("ـ")
        else row("@ # £ _ & - + ( ) /", pop = mapOf("£" to "$ € ¥ ¢", "-" to "– — ·", "(" to "[ {", ")" to "] }")),
        listOf(Key("=\\<", Key.Kind.MORE, 1.5f)) +
            row(if (ar) "( ) : ؛ ؟ ! -" else "* \" ' : ; ! ?") + back(1.5f),
        bottom(ar, Key(if (ar) "أبت" else "ABC", Key.Kind.LETTERS, 1.5f)),
    ))

    private fun more(ar: Boolean) = Page(if (ar) "more-ar" else "more-en", if (ar) "ar" else "en", 10f, listOf(
        row("~ ` | • √ ÷ × ^ ° %"),
        row("@ # $ € £ & _ + = /"),
        listOf(Key(if (ar) "؟١٢٣" else "?123", Key.Kind.SYMBOLS, 1.5f)) + row("\\ { } [ ] < >") + back(1.5f),
        bottom(ar, Key(if (ar) "أبت" else "ABC", Key.Kind.LETTERS, 1.5f)),
    ))

    private val symEn = symbols(false)
    private val symAr = symbols(true)
    private val moreEn = more(false)
    private val moreAr = more(true)

    fun letters(lang: String) = if (lang == "ar") ar else en
    fun symbols(lang: String) = if (lang == "ar") symAr else symEn
    fun more(lang: String) = if (lang == "ar") moreAr else moreEn
}

/**
 * The typing keyboard, drawn on one canvas: a slim strip with the mic on top, then four rows.
 * Keys type on release; hold a key for its accents / digits, slide on space to move the cursor.
 */
class KeyboardView(context: Context, private val listener: Listener) : View(context) {

    interface Listener {
        fun onKey(key: Key)
        fun onPopupText(text: String)
        fun onLongPress(key: Key)
        fun onCursor(steps: Int)
        fun onMic()
    }

    enum class Shift { OFF, ONE, LOCK }

    var page: Page = Layouts.en
        set(v) { field = v; layoutKeys(); invalidate() }
    var shift = Shift.OFF
        set(v) { if (field != v) { field = v; invalidate() } }
    var enterLabel = "⏎"
        set(v) { field = v; invalidate() }
    var status = ""
        set(v) { if (field != v) { field = v; invalidate() } }
    var micVisible = true
        set(v) { field = v; invalidate() }
    var previews = true

    private val shifted get() = shift != Shift.OFF

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private val landscape get() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    private val rowH get() = dp(if (landscape) 42f else 54f)
    private val stripH get() = dp(if (landscape) 32f else 40f)
    private val padTop = dp(2f)
    private val padBottom = dp(4f)
    private val padSide = dp(3f)
    private val gapX = dp(3f)
    private val gapY = dp(5f)

    fun preferredHeight() = (padTop + stripH + 4 * rowH + padBottom).toInt()

    override fun onMeasure(widthSpec: Int, heightSpec: Int) =
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), preferredHeight())

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = layoutKeys()

    private val micRect = RectF()

    private fun layoutKeys() {
        if (width == 0) return
        val w = width - 2 * padSide
        page.rows.forEachIndexed { r, row ->
            val sum = row.sumOf { it.weight.toDouble() }.toFloat()
            val fill = sum >= page.units - 0.01f || row.any { it.kind == Key.Kind.SPACE }
            val unit = if (fill) w / sum else w / page.units
            var x = padSide + if (fill) 0f else (w - sum * unit) / 2
            val top = padTop + stripH + r * rowH
            row.forEachIndexed { i, k ->
                k.cell.set(x, top, x + k.weight * unit, top + rowH)
                k.hit.set(k.cell)
                if (i == 0) k.hit.left = 0f
                if (i == row.lastIndex) k.hit.right = width.toFloat()
                if (r == page.rows.lastIndex) k.hit.bottom = height.toFloat()
                x += k.weight * unit
            }
        }
        micRect.set(width - dp(60f), 0f, width.toFloat(), padTop + stripH)
        post { logKeys() }
    }

    /** `adb shell setprop log.tag.LFKeys DEBUG` prints every key's screen position (for tests). */
    private fun logKeys() {
        if (!Log.isLoggable(TAG, Log.DEBUG)) return
        val loc = IntArray(2).also { getLocationOnScreen(it) }
        val keys = page.rows.flatten().joinToString(" ") {
            "${it.label}@${(loc[0] + it.cell.centerX()).toInt()},${(loc[1] + it.cell.centerY()).toInt()}"
        }
        Log.d(TAG, "${page.id} mic@${(loc[0] + micRect.centerX()).toInt()},${(loc[1] + micRect.centerY()).toInt()} $keys")
    }

    private fun keyAt(x: Float, y: Float) = page.rows.firstNotNullOfOrNull { row -> row.firstOrNull { it.hit.contains(x, y) } }

    // ---------- drawing ----------

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
    private val micIcon = context.getDrawable(R.drawable.ic_mic)!!.mutate().apply { setTint(LocalFlowIME.INK) }
    private val r = RectF()
    private val arrow = Path()

    private fun label(k: Key) = when (k.kind) {
        Key.Kind.CHAR -> if (shifted && page.letters) k.label.uppercase() else k.label
        Key.Kind.ENTER -> enterLabel
        Key.Kind.SPACE -> if (page.letters) (if (page.lang == "ar") "العربية" else "English") else ""
        else -> k.label
    }

    private fun text(c: Canvas, s: String, cx: Float, cy: Float, size: Float, color: Int) {
        ink.textSize = size; ink.color = color
        c.drawText(s, cx, cy - (ink.descent() + ink.ascent()) / 2, ink)
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(LocalFlowIME.BG)
        val stripMid = padTop + stripH / 2
        if (status.isNotEmpty()) {
            ink.textAlign = Paint.Align.LEFT
            text(c, status, dp(14f), stripMid, sp(13f), LocalFlowIME.MUTED)
            ink.textAlign = Paint.Align.CENTER
        }
        if (micVisible) {
            val s = dp(12f).toInt()
            micIcon.setBounds(micRect.centerX().toInt() - s, stripMid.toInt() - s, micRect.centerX().toInt() + s, stripMid.toInt() + s)
            micIcon.draw(c)
        }

        val pressed = down?.takeIf { popup == null && !dragging }
        for (row in page.rows) for (k in row) {
            r.set(k.cell.left + gapX, k.cell.top + gapY, k.cell.right - gapX, k.cell.bottom - gapY)
            fill.color = when {
                k === pressed || (k === down && dragging) -> LocalFlowIME.PRESSED
                k.kind == Key.Kind.CHAR || k.kind == Key.Kind.SPACE -> LocalFlowIME.KEY
                else -> LocalFlowIME.MOD
            }
            c.drawRoundRect(r, dp(7f), dp(7f), fill)
            if (k.kind == Key.Kind.SHIFT) { drawShift(c, r.centerX(), r.centerY()); continue }
            val s = label(k)
            val size = when {
                k.kind == Key.Kind.SPACE -> sp(12f)
                k.kind == Key.Kind.CHAR || s == "⌫" || s == "⏎" -> sp(21f)
                else -> sp(14f)
            }
            text(c, s, r.centerX(), r.centerY(), size, if (k.kind == Key.Kind.SPACE) LocalFlowIME.MUTED else LocalFlowIME.INK)
            val hint = k.popup.firstOrNull()
            if (page.letters && hint != null && hint.first().isDigit()) {
                text(c, hint, r.right - dp(7f), r.top + dp(8f), sp(10f), LocalFlowIME.MUTED)
            }
        }

        if (pressed != null && previews && pressed.kind == Key.Kind.CHAR) {
            val pw = maxOf(pressed.cell.width(), dp(42f))
            val ph = rowH * 1.1f
            val left = (pressed.cell.centerX() - pw / 2).coerceIn(0f, width - pw)
            val top = maxOf(0f, pressed.cell.top + gapY - ph)
            r.set(left, top, left + pw, top + ph)
            fill.color = LocalFlowIME.PRESSED
            c.drawRoundRect(r, dp(9f), dp(9f), fill)
            text(c, label(pressed), r.centerX(), r.centerY(), sp(28f), LocalFlowIME.INK)
        }

        popup?.let { choices ->
            fill.color = LocalFlowIME.PRESSED
            c.drawRoundRect(popupRect, dp(9f), dp(9f), fill)
            choices.forEachIndexed { i, s ->
                r.set(popupRect.left + i * cellW, popupRect.top, popupRect.left + (i + 1) * cellW, popupRect.bottom)
                val selected = i == popupSel
                if (selected) {
                    fill.color = LocalFlowIME.INK
                    r.inset(dp(3f), dp(3f))
                    c.drawRoundRect(r, dp(7f), dp(7f), fill)
                }
                text(c, s, r.centerX(), r.centerY(), sp(22f), if (selected) LocalFlowIME.BG else LocalFlowIME.INK)
            }
        }
    }

    /** An outlined arrow; filled when shift is on, with a bar under it for caps lock. */
    private fun drawShift(c: Canvas, cx: Float, cy: Float) {
        val s = dp(8f)
        arrow.reset()
        arrow.moveTo(cx, cy - s)
        arrow.lineTo(cx + s, cy)
        arrow.lineTo(cx + s * 0.45f, cy)
        arrow.lineTo(cx + s * 0.45f, cy + s * 0.7f)
        arrow.lineTo(cx - s * 0.45f, cy + s * 0.7f)
        arrow.lineTo(cx - s * 0.45f, cy)
        arrow.lineTo(cx - s, cy)
        arrow.close()
        line.color = LocalFlowIME.INK
        line.strokeWidth = dp(1.6f)
        line.style = if (shifted) Paint.Style.FILL_AND_STROKE else Paint.Style.STROKE
        c.drawPath(arrow, line)
        if (shift == Shift.LOCK) {
            line.style = Paint.Style.FILL
            c.drawRect(cx - s * 0.6f, cy + s * 1.05f, cx + s * 0.6f, cy + s * 1.3f, line)
        }
    }

    // ---------- touch ----------

    private var down: Key? = null
    private var downId = -1
    private var downX = 0f
    private var lastX = 0f
    private var dragging = false
    private var popup: List<String>? = null
    private val popupRect = RectF()
    private var popupSel = 0
    private var cellW = 0f

    private val longPress = Runnable { onLong() }
    private val repeat = object : Runnable {
        override fun run() { down?.let { listener.onKey(it) }; postDelayed(this, 50) }
    }

    private fun haptic() = performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (down != null) release(true)  // fast typing: the next finger lands before the last lifts
                val i = e.actionIndex
                press(e.getPointerId(i), e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_MOVE -> {
                val i = e.findPointerIndex(downId)
                if (i >= 0) move(e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP ->
                if (e.getPointerId(e.actionIndex) == downId) release(true)
            MotionEvent.ACTION_CANCEL -> release(false)
        }
        return true
    }

    private fun press(id: Int, x: Float, y: Float) {
        if (y < padTop + stripH) {
            if (micVisible && micRect.contains(x, y)) { haptic(); listener.onMic() }
            return
        }
        val k = keyAt(x, y) ?: return
        down = k; downId = id; downX = x; lastX = x; dragging = false
        haptic()
        if (k.kind == Key.Kind.BACK) { listener.onKey(k); postDelayed(repeat, 400) }
        if (k.popup.isNotEmpty() || k.kind == Key.Kind.LANG) postDelayed(longPress, LONG_MS)
        invalidate()
    }

    private fun move(x: Float, y: Float) {
        val k = down ?: return
        val choices = popup
        when {
            choices != null -> {
                val sel = ((x - popupRect.left) / cellW).toInt().coerceIn(0, choices.lastIndex)
                if (sel != popupSel) { popupSel = sel; invalidate() }
            }
            k.kind == Key.Kind.SPACE -> {
                if (!dragging && abs(x - downX) > dp(16f)) { dragging = true; lastX = x; invalidate() }
                if (dragging) {
                    val step = dp(12f)
                    val n = ((x - lastX) / step).toInt()
                    if (n != 0) { listener.onCursor(n); lastX += n * step }
                }
            }
            k.kind == Key.Kind.CHAR -> {  // slid onto a neighbouring letter
                val other = keyAt(x, y)
                if (other != null && other !== k && other.kind == Key.Kind.CHAR) {
                    removeCallbacks(longPress)
                    down = other
                    if (other.popup.isNotEmpty()) postDelayed(longPress, LONG_MS)
                    invalidate()
                }
            }
        }
    }

    private fun release(commit: Boolean) {
        removeCallbacks(longPress); removeCallbacks(repeat)
        val k = down ?: return
        val choice = popup?.getOrNull(popupSel)
        val wasDragging = dragging
        down = null; downId = -1; popup = null; dragging = false
        invalidate()
        if (!commit) return
        when {
            choice != null -> listener.onPopupText(choice)
            wasDragging || k.kind == Key.Kind.BACK -> {}
            else -> listener.onKey(k)
        }
    }

    private fun onLong() {
        val k = down ?: return
        if (k.kind == Key.Kind.LANG) {
            down = null; invalidate()
            listener.onLongPress(k)
            return
        }
        val choices = if (shifted && page.letters) k.popup.map { it.uppercase() } else k.popup
        cellW = maxOf(k.cell.width() - 2 * gapX, dp(34f))
        val pw = cellW * choices.size
        val ph = rowH - gapY
        val left = (k.cell.left + gapX).coerceIn(dp(2f), maxOf(dp(2f), width - pw - dp(2f)))
        val top = maxOf(0f, k.cell.top + gapY - ph - dp(2f))
        popupRect.set(left, top, left + pw, top + ph)
        popupSel = ((downX - left) / cellW).toInt().coerceIn(0, choices.lastIndex)
        popup = choices
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        invalidate()
    }

    companion object {
        const val TAG = "LFKeys"
        const val LONG_MS = 350L
    }
}
