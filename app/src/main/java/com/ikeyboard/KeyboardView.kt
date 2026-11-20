package com.ikeyboard

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import kotlin.math.max

/**
 * The keyboard surface: QuickType suggestion strip on top, keys below, plus key-preview balloons,
 * accent pop-ups and the space-bar trackpad. Everything is drawn on one canvas so balloons for the
 * top row can float over the suggestion strip like they do on iOS.
 */
@SuppressLint("ViewConstructor")
class KeyboardView(context: Context, private val listener: Listener) : View(context) {

    interface Listener {
        fun onText(text: String, fromMode: Mode)
        fun onBackspace(repeat: Int)
        fun onBackspaceReleased()
        fun onSpace()
        fun onReturn()
        fun onEmoji()
        fun onSwitchInputMethod()
        fun onCursorMove(dx: Int, dy: Int)
        fun onSuggestion(slot: Int)
        /** Asks the service to re-evaluate auto-capitalisation for the current cursor position. */
        fun onShiftChanged()
        fun onFeedback(kind: FeedbackKind)
    }

    enum class FeedbackKind { KEY, DELETE, SPACE, RETURN, MODIFIER }
    enum class Shift { OFF, ON, LOCKED }

    class Suggestion(val text: String, val highlighted: Boolean = false)

    // ---- public state -----------------------------------------------------------------------

    var theme: KeyboardTheme = KeyboardTheme.LIGHT
        set(v) { field = v; invalidate() }

    var mode: Mode = Mode.LETTERS
        private set
    var variant: FieldVariant = FieldVariant.TEXT
        private set
    var shift: Shift = Shift.OFF
        private set

    var showPreview = true
    var capsLockEnabled = true
    var returnLabel = "return"
        set(v) { field = v; invalidate() }
    var returnHighlighted = false
        set(v) { field = v; invalidate() }

    /** Three QuickType slots (left, centre, right); null = empty. */
    var suggestions: List<Suggestion?> = listOf(null, null, null)
        set(v) { field = v; invalidate() }

    // ---- metrics ------------------------------------------------------------------------------

    private val dp = resources.displayMetrics.density
    private val sp = resources.displayMetrics.scaledDensity
    private var landscape = false
    private val stripHeight get() = (if (landscape) 38 else 46) * dp
    private val keyHeight get() = (if (landscape) 33 else 43) * dp
    private val rowGap get() = (if (landscape) 6 else 11) * dp
    private val topPad get() = (if (landscape) 4 else 8) * dp
    private val bottomPad get() = (if (landscape) 4 else 5) * dp
    private val hGap = 6 * dp
    private val sideMargin = 3 * dp
    private val radius = 5 * dp

    private var layout: KeyboardLayout = Layouts.build(Mode.LETTERS, FieldVariant.TEXT)

    // ---- paints -------------------------------------------------------------------------------

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val tmpRect = RectF()
    private val path = Path()

    // ---- touch state --------------------------------------------------------------------------

    private class Pointer(val id: Int, var x: Float, var y: Float) {
        var key: Key? = null
        var downKey: Key? = null
        var stripSlot = -1
        var downTime = 0L
        var movedOff = false
        var popup: Popup? = null
        var trackpad = false
        var tpX = 0f
        var tpY = 0f
        /** Layout to return to after a slide-from-"123" gesture. */
        var slideReturnMode: Mode? = null
        var typedWhileHeld = false
        var handled = false
    }

    private class Popup(val key: Key, val items: List<String>, val cellW: Float, val rect: RectF) {
        var selected = 0
    }

    private val pointers = LinkedHashMap<Int, Pointer>()
    private val handler = Handler(Looper.getMainLooper())
    private var lastShiftDown = 0L
    private var backspaceRepeat = 0
    private var trackpadActive = false

    private val longPressRunnable = Runnable { onLongPress() }
    private val backspaceRunnable = object : Runnable {
        override fun run() {
            backspaceRepeat++
            listener.onBackspace(backspaceRepeat)
            listener.onFeedback(FeedbackKind.DELETE)
            val delay = if (backspaceRepeat > 14) 160L else 90L
            handler.postDelayed(this, delay)
        }
    }

    init {
        isHapticFeedbackEnabled = false
        landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    }

    // ---- configuration ------------------------------------------------------------------------

    fun configure(mode: Mode, variant: FieldVariant) {
        this.variant = variant
        setMode(mode)
    }

    fun setMode(m: Mode) {
        mode = m
        layout = Layouts.build(m, variant)
        layoutKeys()
        invalidate()
    }

    fun setShift(s: Shift) {
        if (s == shift) return
        shift = s
        invalidate()
    }

    /** Called by the service after text changes; never overrides caps lock. */
    fun applyAutoShift(capsOn: Boolean) {
        if (shift == Shift.LOCKED || mode != Mode.LETTERS) return
        if (pointers.values.any { it.key?.type == KeyType.SHIFT }) return
        setShift(if (capsOn) Shift.ON else Shift.OFF)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        landscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, totalHeight().toInt())
    }

    fun totalHeight(): Float = stripHeight + topPad + 4 * keyHeight + 3 * rowGap + bottomPad

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        layoutKeys()
    }

    private fun layoutKeys() {
        if (width == 0) return
        val usable = width - 2 * sideMargin
        val unit = usable / layout.units
        var y = stripHeight + topPad
        val padGap = if (layout.isPad) 6 * dp else hGap
        val kh = keyHeight
        for (row in layout.rows) {
            var x = sideMargin
            for (k in row) {
                val w = unit * k.width
                k.rect.set(x + padGap / 2, y, x + w - padGap / 2, y + kh)
                x += w
            }
            y += kh + rowGap
        }
    }

    // ---- drawing ------------------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(theme.background)
        drawStrip(canvas)
        val popupPointer = pointers.values.firstOrNull { it.popup != null }
        for (row in layout.rows) for (k in row) {
            if (k.type == KeyType.SPACER) continue
            drawKey(canvas, k)
        }
        if (!trackpadActive && showPreview && !layout.isPad) {
            for (p in pointers.values) {
                val k = p.key ?: continue
                if (k.type == KeyType.CHAR && p.popup == null && !p.handled) drawPreview(canvas, k)
            }
        }
        popupPointer?.popup?.let { drawPopup(canvas, it) }
    }

    private fun isPressed(k: Key) = pointers.values.any { it.key === k && !it.trackpad }

    private fun drawStrip(canvas: Canvas) {
        val h = stripHeight
        val slotW = width / 3f
        val pressedSlot = pointers.values.firstOrNull { it.stripSlot >= 0 }?.stripSlot ?: -1
        text.textSize = 16.5f * sp
        text.typeface = Typeface.DEFAULT
        val showDividers = !trackpadActive
        for (i in 0 until 3) {
            val s = suggestions.getOrNull(i) ?: continue
            if (trackpadActive) continue
            val l = i * slotW
            if (s.highlighted || i == pressedSlot) {
                fill.color = if (i == pressedSlot) theme.specialKey else theme.highlight
                tmpRect.set(l + 4 * dp, 6 * dp, l + slotW - 4 * dp, h - 6 * dp)
                canvas.drawRoundRect(tmpRect, 6 * dp, 6 * dp, fill)
            }
            text.color = theme.text
            val label = ellipsize(s.text, slotW - 14 * dp)
            canvas.drawText(label, l + slotW / 2, h / 2 - (text.descent() + text.ascent()) / 2, text)
        }
        if (showDividers) {
            fill.color = theme.divider
            for (i in 1..2) {
                // Dividers hide next to a highlighted / pressed slot, as on iOS.
                val a = suggestions.getOrNull(i - 1)
                val b = suggestions.getOrNull(i)
                if (a == null && b == null) continue
                if (a?.highlighted == true || b?.highlighted == true || pressedSlot == i || pressedSlot == i - 1) continue
                val x = i * slotW
                canvas.drawRect(x - 0.5f * dp, h * 0.28f, x + 0.5f * dp, h * 0.72f, fill)
            }
        }
    }

    private fun ellipsize(s: String, maxW: Float): String {
        if (text.measureText(s) <= maxW) return s
        var end = s.length
        while (end > 1 && text.measureText(s, 0, end) + text.measureText("…") > maxW) end--
        return s.substring(0, end) + "…"
    }

    private fun keyColor(k: Key, pressed: Boolean): Int {
        if (layout.isPad) {
            val transparent = k.type == KeyType.BACKSPACE || k.caption.isEmpty()
            return when {
                transparent && pressed -> theme.key
                transparent -> 0
                pressed -> theme.specialKey
                else -> theme.key
            }
        }
        return when {
            k.type == KeyType.RETURN && returnHighlighted -> if (pressed) theme.key else theme.accent
            k.type == KeyType.SHIFT && shift != Shift.OFF -> theme.key
            k.isSpecial -> if (pressed) theme.key else theme.specialKey
            // Character keys don't change colour when the balloon is shown; without it they darken.
            pressed && (!showPreview || k.type == KeyType.SPACE) -> theme.specialKey
            else -> theme.key
        }
    }

    private fun drawKey(canvas: Canvas, k: Key) {
        val pressed = isPressed(k)
        val r = k.rect
        val color = if (trackpadActive) theme.key else keyColor(k, pressed)
        if (color != 0) {
            fill.color = theme.keyShadow
            tmpRect.set(r.left, r.top + 1 * dp, r.right, r.bottom + 1 * dp)
            canvas.drawRoundRect(tmpRect, radius, radius, fill)
            fill.color = color
            canvas.drawRoundRect(r, radius, radius, fill)
        }
        if (trackpadActive) return

        val fg = if (k.type == KeyType.RETURN && returnHighlighted && !pressed) theme.accentText else theme.text
        text.color = fg
        stroke.color = fg
        fill.color = fg
        val cx = r.centerX()
        val cy = r.centerY()
        when (k.type) {
            KeyType.CHAR -> {
                if (layout.isPad) {
                    drawPadKey(canvas, k)
                } else {
                    val label = if (mode == Mode.LETTERS && shift != Shift.OFF) k.label.uppercase() else k.label
                    text.typeface = Typeface.DEFAULT
                    text.textSize = when {
                        label.length > 1 -> 16f * sp
                        mode == Mode.LETTERS && shift == Shift.OFF -> 24f * sp
                        else -> 22.5f * sp
                    }
                    // Lowercase glyphs sit visually lower; nudge them up a touch like iOS does.
                    val nudge = if (mode == Mode.LETTERS && shift == Shift.OFF) -1.5f * dp else 0f
                    canvas.drawText(label, cx, cy - (text.descent() + text.ascent()) / 2 + nudge, text)
                }
            }
            KeyType.SPACE, KeyType.RETURN, KeyType.MODE_NUMBERS, KeyType.MODE_SYMBOLS, KeyType.MODE_LETTERS -> {
                val label = if (k.type == KeyType.RETURN) returnLabel else k.label
                text.typeface = Typeface.DEFAULT
                text.textSize = 16f * sp
                canvas.drawText(label, cx, cy - (text.descent() + text.ascent()) / 2, text)
            }
            KeyType.SHIFT -> drawShift(canvas, cx, cy)
            KeyType.BACKSPACE -> drawBackspace(canvas, cx, cy, pressed)
            KeyType.EMOJI -> drawEmojiIcon(canvas, cx, cy)
            KeyType.SPACER -> {}
        }
    }

    private fun drawPadKey(canvas: Canvas, k: Key) {
        val r = k.rect
        text.typeface = Typeface.DEFAULT
        if (k.caption.isEmpty()) {
            text.textSize = 20f * sp
            canvas.drawText(k.label, r.centerX(), r.centerY() - (text.descent() + text.ascent()) / 2, text)
            return
        }
        text.textSize = (if (landscape) 22f else 26f) * sp
        val digitY = r.centerY() - 2 * dp
        canvas.drawText(k.label, r.centerX(), digitY, text)
        text.textSize = 10f * sp
        text.typeface = Typeface.DEFAULT_BOLD
        text.letterSpacing = 0.15f
        canvas.drawText(k.caption, r.centerX(), digitY + 13 * dp, text)
        text.letterSpacing = 0f
    }

    private fun drawShift(canvas: Canvas, cx: Float, cy: Float) {
        val s = 19 * dp
        val l = cx - s / 2
        val t = cy - s / 2 - 1 * dp
        path.reset()
        path.moveTo(l + s * 0.5f, t + s * 0.04f)
        path.lineTo(l + s * 0.96f, t + s * 0.52f)
        path.lineTo(l + s * 0.71f, t + s * 0.52f)
        path.lineTo(l + s * 0.71f, t + s * 0.94f)
        path.lineTo(l + s * 0.29f, t + s * 0.94f)
        path.lineTo(l + s * 0.29f, t + s * 0.52f)
        path.lineTo(l + s * 0.04f, t + s * 0.52f)
        path.close()
        stroke.strokeWidth = 1.6f * dp
        if (shift == Shift.OFF) {
            canvas.drawPath(path, stroke)
        } else {
            fill.style = Paint.Style.FILL
            canvas.drawPath(path, fill)
            canvas.drawPath(path, stroke)
            if (shift == Shift.LOCKED) {
                tmpRect.set(l + s * 0.27f, t + s * 1.07f, l + s * 0.73f, t + s * 1.07f + 1.8f * dp)
                canvas.drawRoundRect(tmpRect, 0.9f * dp, 0.9f * dp, fill)
            }
        }
    }

    private fun drawBackspace(canvas: Canvas, cx: Float, cy: Float, pressed: Boolean) {
        val w = 23 * dp
        val h = 17 * dp
        val l = cx - w / 2
        val t = cy - h / 2
        path.reset()
        path.moveTo(l, t + h / 2)
        path.lineTo(l + w * 0.3f, t)
        path.lineTo(l + w, t)
        path.lineTo(l + w, t + h)
        path.lineTo(l + w * 0.3f, t + h)
        path.close()
        stroke.strokeWidth = 1.5f * dp
        val xl = l + w * 0.47f
        val xr = l + w * 0.79f
        val yt = t + h * 0.31f
        val yb = t + h * 0.69f
        if (pressed) {
            canvas.drawPath(path, fill)
            canvas.drawPath(path, stroke)
            stroke.color = theme.key
        } else {
            canvas.drawPath(path, stroke)
        }
        canvas.drawLine(xl, yt, xr, yb, stroke)
        canvas.drawLine(xr, yt, xl, yb, stroke)
        stroke.color = theme.text
    }

    private fun drawEmojiIcon(canvas: Canvas, cx: Float, cy: Float) {
        val r = 10.5f * dp
        stroke.strokeWidth = 1.5f * dp
        canvas.drawCircle(cx, cy, r, stroke)
        fill.style = Paint.Style.FILL
        canvas.drawCircle(cx - r * 0.36f, cy - r * 0.28f, 1.4f * dp, fill)
        canvas.drawCircle(cx + r * 0.36f, cy - r * 0.28f, 1.4f * dp, fill)
        tmpRect.set(cx - r * 0.55f, cy - r * 0.45f, cx + r * 0.55f, cy + r * 0.6f)
        canvas.drawArc(tmpRect, 20f, 140f, false, stroke)
    }

    /** iOS-style balloon: the key grows upward into a wider rounded bubble. */
    private fun balloonPath(k: Key, bubble: RectF): Path {
        val kr = k.rect
        val neckTop = bubble.bottom
        val neckBottom = kr.top + 2 * dp
        val mid = (neckTop + neckBottom) / 2
        val br = 8 * dp
        path.reset()
        path.moveTo(kr.left, kr.bottom - radius)
        path.lineTo(kr.left, neckBottom)
        path.cubicTo(kr.left, mid, bubble.left, mid, bubble.left, neckTop)
        path.lineTo(bubble.left, bubble.top + br)
        path.quadTo(bubble.left, bubble.top, bubble.left + br, bubble.top)
        path.lineTo(bubble.right - br, bubble.top)
        path.quadTo(bubble.right, bubble.top, bubble.right, bubble.top + br)
        path.lineTo(bubble.right, neckTop)
        path.cubicTo(bubble.right, mid, kr.right, mid, kr.right, neckBottom)
        path.lineTo(kr.right, kr.bottom - radius)
        path.quadTo(kr.right, kr.bottom, kr.right - radius, kr.bottom)
        path.lineTo(kr.left + radius, kr.bottom)
        path.quadTo(kr.left, kr.bottom, kr.left, kr.bottom - radius)
        path.close()
        return path
    }

    private fun drawBalloon(canvas: Canvas, k: Key, bubble: RectF) {
        val p = balloonPath(k, bubble)
        fill.color = 0x33000000
        canvas.save()
        canvas.translate(0f, 1 * dp)
        canvas.drawPath(p, fill)
        canvas.restore()
        fill.color = theme.popup
        canvas.drawPath(p, fill)
    }

    private fun drawPreview(canvas: Canvas, k: Key) {
        val kr = k.rect
        val ext = 11 * dp
        var left = kr.left - ext
        var right = kr.right + ext
        if (left < 0) { left = kr.left; right = kr.right + 2 * ext }
        if (right > width) { right = kr.right; left = kr.left - 2 * ext }
        val bottom = kr.top - 9 * dp
        val top = bottom - keyHeight * 1.05f
        tmpRect.set(left, top, right, bottom)
        val bubble = RectF(tmpRect)
        drawBalloon(canvas, k, bubble)
        val label = if (mode == Mode.LETTERS && shift != Shift.OFF) k.label.uppercase() else k.label
        text.color = theme.text
        text.typeface = Typeface.DEFAULT
        text.textSize = (if (label.length > 1) 22f else 34f) * sp
        canvas.drawText(label, bubble.centerX(), bubble.centerY() - (text.descent() + text.ascent()) / 2 + 2 * dp, text)
    }

    private fun drawPopup(canvas: Canvas, p: Popup) {
        drawBalloon(canvas, p.key, p.rect)
        text.typeface = Typeface.DEFAULT
        for ((i, item) in p.items.withIndex()) {
            val l = p.rect.left + 5 * dp + i * p.cellW
            val cell = RectF(l, p.rect.top + 5 * dp, l + p.cellW, p.rect.bottom - 5 * dp)
            if (i == p.selected) {
                fill.color = theme.accent
                canvas.drawRoundRect(cell, 5 * dp, 5 * dp, fill)
                text.color = theme.accentText
            } else {
                text.color = theme.text
            }
            text.textSize = (if (item.length > 2) 17f else 25f) * sp
            canvas.drawText(item, cell.centerX(), cell.centerY() - (text.descent() + text.ascent()) / 2, text)
        }
    }

    // ---- touch --------------------------------------------------------------------------------

    private fun keyAt(x: Float, y: Float): Key? {
        if (y < stripHeight) return null
        var best: Key? = null
        var bestD = Float.MAX_VALUE
        for (row in layout.rows) for (k in row) {
            if (k.type == KeyType.SPACER) continue
            val r = k.rect
            val dx = max(0f, max(r.left - x, x - r.right))
            val dy = max(0f, max(r.top - y, y - r.bottom))
            // Rows are further apart than keys, so weigh vertical misses a bit less.
            val d = dx * dx + dy * dy * 0.8f
            if (d < bestD) { bestD = d; best = k }
        }
        return best
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                pointerDown(e.getPointerId(i), e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) pointerMove(e.getPointerId(i), e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = e.actionIndex
                pointerUp(e.getPointerId(i), e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_CANCEL -> cancelAll()
        }
        invalidate()
        return true
    }

    private fun pointerDown(id: Int, x: Float, y: Float) {
        // A new finger commits whatever the previous one was hovering over (fast two-thumb typing).
        for (other in pointers.values.toList()) {
            val k = other.key ?: continue
            if (other.handled || other.popup != null || other.trackpad) continue
            if (k.type == KeyType.CHAR || k.type == KeyType.SPACE || k.type == KeyType.RETURN) {
                commitKey(other, k)
                other.handled = true
            }
        }
        handler.removeCallbacks(longPressRunnable)

        val p = Pointer(id, x, y)
        p.downTime = SystemClock.uptimeMillis()
        pointers[id] = p

        if (y < stripHeight) {
            if (suggestions.getOrNull((x / (width / 3f)).toInt().coerceIn(0, 2)) != null) {
                p.stripSlot = (x / (width / 3f)).toInt().coerceIn(0, 2)
            }
            return
        }
        val k = keyAt(x, y) ?: return
        p.key = k
        p.downKey = k

        when (k.type) {
            KeyType.SHIFT -> {
                val now = SystemClock.uptimeMillis()
                shift = when {
                    shift == Shift.LOCKED -> Shift.OFF
                    capsLockEnabled && now - lastShiftDown < 300 -> Shift.LOCKED
                    shift == Shift.ON -> Shift.OFF
                    else -> Shift.ON
                }
                lastShiftDown = now
                listener.onFeedback(FeedbackKind.MODIFIER)
            }
            KeyType.MODE_NUMBERS, KeyType.MODE_SYMBOLS, KeyType.MODE_LETTERS -> {
                val from = mode
                setMode(
                    when (k.type) {
                        KeyType.MODE_NUMBERS -> Mode.NUMBERS
                        KeyType.MODE_SYMBOLS -> Mode.SYMBOLS
                        else -> Mode.LETTERS
                    },
                )
                if (mode == Mode.LETTERS) listener.onShiftChanged()
                p.slideReturnMode = from
                p.key = keyAt(x, y)
                p.downKey = p.key
                listener.onFeedback(FeedbackKind.MODIFIER)
            }
            KeyType.BACKSPACE -> {
                backspaceRepeat = 0
                listener.onBackspace(0)
                listener.onFeedback(FeedbackKind.DELETE)
                handler.postDelayed(backspaceRunnable, 450)
            }
            KeyType.CHAR, KeyType.SPACE -> {
                listener.onFeedback(if (k.type == KeyType.SPACE) FeedbackKind.SPACE else FeedbackKind.KEY)
                handler.postDelayed(longPressRunnable, if (k.type == KeyType.SPACE) 380 else 400)
            }
            KeyType.EMOJI -> {
                listener.onFeedback(FeedbackKind.MODIFIER)
                handler.postDelayed(longPressRunnable, 450)
            }
            KeyType.RETURN -> listener.onFeedback(FeedbackKind.RETURN)
            KeyType.SPACER -> {}
        }
    }

    private fun pointerMove(id: Int, x: Float, y: Float) {
        val p = pointers[id] ?: return
        p.x = x
        p.y = y
        if (p.handled) return

        if (p.trackpad) {
            val stepX = 9 * dp
            val stepY = 22 * dp
            while (x - p.tpX > stepX) { listener.onCursorMove(1, 0); p.tpX += stepX }
            while (p.tpX - x > stepX) { listener.onCursorMove(-1, 0); p.tpX -= stepX }
            while (y - p.tpY > stepY) { listener.onCursorMove(0, 1); p.tpY += stepY }
            while (p.tpY - y > stepY) { listener.onCursorMove(0, -1); p.tpY -= stepY }
            return
        }
        p.popup?.let { pop ->
            val idx = ((x - pop.rect.left - 5 * dp) / pop.cellW).toInt().coerceIn(0, pop.items.size - 1)
            pop.selected = idx
            return
        }
        if (p.stripSlot >= 0) return
        val cur = p.key ?: return
        // Delete never slides; everything else lets the finger travel to another key.
        if (cur.type == KeyType.BACKSPACE) return
        val k = keyAt(x, y)
        if (k !== cur) {
            if (cur.type == KeyType.SPACE && y > cur.rect.top - rowGap) {
                return // let the thumb wander along the space bar
            }
            p.movedOff = true
            p.key = k
            handler.removeCallbacks(longPressRunnable)
            if (k != null && (k.type == KeyType.CHAR)) {
                p.downTime = SystemClock.uptimeMillis()
                handler.postDelayed(longPressRunnable, 400)
            }
        }
    }

    private fun pointerUp(id: Int, x: Float, y: Float) {
        val p = pointers.remove(id) ?: return
        handler.removeCallbacks(longPressRunnable)
        if (p.key?.type == KeyType.BACKSPACE || p.downKey?.type == KeyType.BACKSPACE) {
            handler.removeCallbacks(backspaceRunnable)
            listener.onBackspaceReleased()
        }
        if (p.trackpad) {
            trackpadActive = false
            return
        }
        if (p.stripSlot >= 0) {
            if (y < stripHeight + 10 * dp) listener.onSuggestion(p.stripSlot)
            return
        }
        p.popup?.let { pop ->
            listener.onText(pop.items[pop.selected], mode)
            afterCharCommitted(p)
            return
        }
        if (p.handled) {
            finishShiftHold(p)
            return
        }
        val k = p.key ?: return
        when (k.type) {
            KeyType.SHIFT -> finishShiftHold(p)
            KeyType.MODE_NUMBERS, KeyType.MODE_SYMBOLS, KeyType.MODE_LETTERS -> {}
            KeyType.EMOJI -> listener.onEmoji()
            KeyType.BACKSPACE -> {}
            else -> {
                // Slid off the mode key onto a symbol: type it and go back, like iOS.
                val back = p.slideReturnMode
                commitKey(p, k)
                if (back != null && p.movedOff && k.type == KeyType.CHAR) {
                    setMode(back)
                    if (back == Mode.LETTERS) listener.onShiftChanged()
                }
            }
        }
    }

    private fun finishShiftHold(p: Pointer) {
        if (p.downKey?.type == KeyType.SHIFT && p.typedWhileHeld && shift == Shift.ON) {
            setShift(Shift.OFF)
        }
    }

    private fun commitKey(p: Pointer, k: Key) {
        when (k.type) {
            KeyType.CHAR -> {
                val out = if (mode == Mode.LETTERS && shift != Shift.OFF) k.output.uppercase() else k.output
                listener.onText(out, mode)
                afterCharCommitted(p)
            }
            KeyType.SPACE -> {
                listener.onSpace()
                // iOS jumps back to letters after a space typed on the number/symbol pages.
                if (mode == Mode.NUMBERS || mode == Mode.SYMBOLS) {
                    setMode(Mode.LETTERS)
                    listener.onShiftChanged()
                }
            }
            KeyType.RETURN -> listener.onReturn()
            else -> {}
        }
    }

    private fun afterCharCommitted(p: Pointer) {
        // Any finger still holding shift learns that it was used as a modifier.
        pointers.values.forEach { if (it.key?.type == KeyType.SHIFT) it.typedWhileHeld = true }
        val shiftHeld = pointers.values.any { it.key?.type == KeyType.SHIFT }
        if (mode == Mode.LETTERS && shift == Shift.ON && !shiftHeld) setShift(Shift.OFF)
        if ((mode == Mode.NUMBERS || mode == Mode.SYMBOLS) && p.key?.output == "'") {
            setMode(Mode.LETTERS)
        }
        listener.onShiftChanged()
    }

    private fun onLongPress() {
        val p = pointers.values.lastOrNull { !it.handled } ?: return
        val k = p.key ?: return
        when (k.type) {
            KeyType.SPACE -> {
                p.trackpad = true
                p.tpX = p.x
                p.tpY = p.y
                trackpadActive = true
                listener.onFeedback(FeedbackKind.MODIFIER)
            }
            KeyType.EMOJI -> {
                p.handled = true
                listener.onSwitchInputMethod()
            }
            KeyType.CHAR -> {
                val upper = mode == Mode.LETTERS && shift != Shift.OFF
                val items = if (layout.isPad) k.alternates else {
                    val alts = Layouts.alternatesFor(k.label, upper)
                    if (alts.isEmpty()) return
                    if (mode == Mode.LETTERS) alts else listOf(if (upper) k.output.uppercase() else k.output) + alts
                }
                if (items.isEmpty()) return
                p.popup = makePopup(k, items)
                listener.onFeedback(FeedbackKind.MODIFIER)
            }
            else -> {}
        }
        invalidate()
    }

    private fun makePopup(k: Key, items: List<String>): Popup {
        val kr = k.rect
        val cellW = if (layout.isPad) kr.width() / 2 else max(kr.width(), (if (items.any { it.length > 2 }) 52 else 0) * dp)
        val w = cellW * items.size + 10 * dp
        val growLeft = kr.centerX() > width / 2f
        var left = if (growLeft) kr.right + 5 * dp - w else kr.left - 5 * dp
        left = left.coerceIn(2 * dp, max(2 * dp, width - 2 * dp - w))
        val bottom = kr.top - 9 * dp
        val rect = RectF(left, bottom - keyHeight - 10 * dp, left + w, bottom)
        if (rect.top < 0) rect.offset(0f, -rect.top)
        val pop = Popup(k, items, cellW, rect)
        // Start on the item nearest the finger.
        pop.selected = ((kr.centerX() - left - 5 * dp) / cellW).toInt().coerceIn(0, items.size - 1)
        return pop
    }

    private fun cancelAll() {
        handler.removeCallbacks(longPressRunnable)
        handler.removeCallbacks(backspaceRunnable)
        pointers.clear()
        trackpadActive = false
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cancelAll()
    }

    fun reset() = cancelAll()
}
