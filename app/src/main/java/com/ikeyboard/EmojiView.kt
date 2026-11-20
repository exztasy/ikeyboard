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
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/** Horizontally scrolling emoji picker with sticky section titles and a category bar, iOS style. */
@SuppressLint("ViewConstructor")
class EmojiView(context: Context, private val listener: Listener) : View(context) {

    interface Listener {
        fun onEmoji(emoji: String)
        fun onEmojiBackspace()
        fun onEmojiClose()
        fun onFeedback(kind: KeyboardView.FeedbackKind)
    }

    private class Section(val title: String, val icon: String, val emoji: List<String>) {
        var startX = 0f
        var width = 0f
    }

    var theme: KeyboardTheme = KeyboardTheme.LIGHT
        set(v) { field = v; invalidate() }

    /** Height is dictated by the letter keyboard so switching never makes the window jump. */
    var fixedHeight = 0

    private val dp = resources.displayMetrics.density
    private val sp = resources.displayMetrics.scaledDensity
    private val titleH = 30 * dp
    private val barH = 42 * dp
    private val sidePad = 8 * dp
    private var rows = 5
    private var cell = 0f
    private var sections = listOf<Section>()
    private var contentW = 0f
    private var scrollPos = 0f

    private val emojiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.LEFT }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val path = Path()
    private val rect = RectF()

    private val scroller = OverScroller(context)
    private var velocity: VelocityTracker? = null
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var dragging = false
    private var pressedEmoji: Pair<Int, Int>? = null
    private var pressedBar = -1 // -2 = ABC, -3 = delete, >= 0 = category
    private val handler = Handler(Looper.getMainLooper())
    private val repeatDelete = object : Runnable {
        override fun run() {
            listener.onEmojiBackspace()
            handler.postDelayed(this, 100)
        }
    }

    private val categories: List<EmojiData.Category> by lazy {
        // Drop anything the device's emoji font can't render (older Android versions).
        EmojiData.CATEGORIES.map { c ->
            EmojiData.Category(c.title, c.icon, c.emoji.distinct().filter { emojiPaint.hasGlyph(it) })
        }
    }

    fun reload(recents: List<String>) {
        val list = mutableListOf<Section>()
        val rec = recents.filter { emojiPaint.hasGlyph(it) }
        // "Frequently Used" only appears once there's history, so a first run opens on smileys.
        if (rec.isNotEmpty()) list.add(Section("FREQUENTLY USED", "🕘", rec))
        categories.forEach { list.add(Section(it.title, it.icon, it.emoji)) }
        sections = list
        scrollPos = 0f
        scroller.forceFinished(true)
        relayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), fixedHeight)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        relayout()
    }

    private fun relayout() {
        if (height == 0) return
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        rows = if (landscape) 3 else 5
        cell = (height - titleH - barH) / rows
        var x = sidePad
        for (s in sections) {
            s.startX = x
            val cols = max(1, ceil(s.emoji.size / rows.toFloat()).toInt())
            s.width = if (s.emoji.isEmpty()) width * 0.6f else cols * cell
            x += s.width + 14 * dp
        }
        contentW = x + sidePad
        scrollPos = scrollPos.coerceIn(0f, maxScroll())
    }

    private fun maxScroll() = max(0f, contentW - width)

    private fun currentSection(): Int {
        val probe = scrollPos + width * 0.25f
        var idx = 0
        for ((i, s) in sections.withIndex()) if (s.startX <= probe) idx = i
        if (scrollPos >= maxScroll() - 1) idx = sections.size - 1
        return idx
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollPos = scroller.currX.toFloat()
            postInvalidateOnAnimation()
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(theme.background)
        val gridTop = titleH
        emojiPaint.textSize = cell * 0.62f
        val fm = emojiPaint.fontMetrics
        val emojiDy = -(fm.ascent + fm.descent) / 2

        for ((si, s) in sections.withIndex()) {
            val sx = s.startX - scrollPos
            if (sx > width || sx + s.width < 0) continue
            // Sticky title: pinned to the left edge while its section is on screen.
            text.textSize = 12.5f * sp
            text.typeface = Typeface.DEFAULT_BOLD
            text.color = theme.secondaryText
            val tw = text.measureText(s.title)
            val tx = (if (sx < sidePad) sidePad else sx).coerceAtMost(sx + s.width - tw).coerceAtLeast(sx)
            canvas.drawText(s.title, tx, titleH - 10 * dp, text)

            if (s.emoji.isEmpty()) {
                continue
            }
            for ((i, e) in s.emoji.withIndex()) {
                val col = i / rows
                val row = i % rows
                val cx = sx + col * cell + cell / 2
                if (cx < -cell || cx > width + cell) continue
                val cy = gridTop + row * cell + cell / 2
                if (pressedEmoji?.first == si && pressedEmoji?.second == i) {
                    fill.color = theme.specialKey
                    rect.set(cx - cell / 2 + 2 * dp, cy - cell / 2 + 2 * dp, cx + cell / 2 - 2 * dp, cy + cell / 2 - 2 * dp)
                    canvas.drawRoundRect(rect, 8 * dp, 8 * dp, fill)
                }
                canvas.drawText(e, cx, cy + emojiDy, emojiPaint)
            }
        }
        drawBar(canvas)
    }

    private fun barSlots(): Int = sections.size + 2

    private fun drawBar(canvas: Canvas) {
        val top = height - barH
        val slotW = width / barSlots().toFloat()
        val cy = top + barH / 2
        val current = currentSection()

        // ABC
        text.typeface = Typeface.DEFAULT
        text.textSize = 15f * sp
        text.color = theme.text
        text.textAlign = Paint.Align.CENTER
        if (pressedBar == -2) {
            fill.color = theme.specialKey
            rect.set(4 * dp, top + 5 * dp, slotW - 4 * dp, height - 5 * dp)
            canvas.drawRoundRect(rect, 6 * dp, 6 * dp, fill)
        }
        canvas.drawText("ABC", slotW / 2, cy - (text.descent() + text.ascent()) / 2, text)
        text.textAlign = Paint.Align.LEFT

        emojiPaint.textSize = 17f * sp
        val fm = emojiPaint.fontMetrics
        for ((i, s) in sections.withIndex()) {
            val cx = slotW * (i + 1) + slotW / 2
            if (i == current) {
                fill.color = theme.specialKey
                canvas.drawCircle(cx, cy, 14 * dp, fill)
            }
            emojiPaint.alpha = if (i == current) 255 else 150
            canvas.drawText(s.icon, cx, cy - (fm.ascent + fm.descent) / 2, emojiPaint)
        }
        emojiPaint.alpha = 255

        // Delete
        val dx = slotW * (barSlots() - 1) + slotW / 2
        val w = 22 * dp
        val h = 16 * dp
        val l = dx - w / 2
        val t = cy - h / 2
        path.reset()
        path.moveTo(l, t + h / 2)
        path.lineTo(l + w * 0.3f, t)
        path.lineTo(l + w, t)
        path.lineTo(l + w, t + h)
        path.lineTo(l + w * 0.3f, t + h)
        path.close()
        stroke.strokeWidth = 1.5f * dp
        stroke.color = theme.text
        if (pressedBar == -3) {
            fill.color = theme.text
            canvas.drawPath(path, fill)
            stroke.color = theme.background
        } else {
            canvas.drawPath(path, stroke)
        }
        canvas.drawLine(l + w * 0.47f, t + h * 0.31f, l + w * 0.79f, t + h * 0.69f, stroke)
        canvas.drawLine(l + w * 0.79f, t + h * 0.31f, l + w * 0.47f, t + h * 0.69f, stroke)
    }

    private fun emojiAt(x: Float, y: Float): Pair<Int, Int>? {
        if (y < titleH || y > height - barH) return null
        val cx = x + scrollPos
        val row = ((y - titleH) / cell).toInt().coerceIn(0, rows - 1)
        for ((si, s) in sections.withIndex()) {
            if (cx >= s.startX && cx < s.startX + s.width) {
                val col = ((cx - s.startX) / cell).toInt()
                val idx = col * rows + row
                return if (idx < s.emoji.size) si to idx else null
            }
        }
        return null
    }

    private fun barSlotAt(x: Float): Int {
        val slot = (x / (width / barSlots().toFloat())).toInt().coerceIn(0, barSlots() - 1)
        return when (slot) {
            0 -> -2
            barSlots() - 1 -> -3
            else -> slot - 1
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                downX = e.x; downY = e.y; lastX = e.x
                dragging = false
                velocity?.recycle()
                velocity = VelocityTracker.obtain().also { it.addMovement(e) }
                if (e.y >= height - barH) {
                    pressedBar = barSlotAt(e.x)
                    listener.onFeedback(
                        if (pressedBar == -3) KeyboardView.FeedbackKind.DELETE else KeyboardView.FeedbackKind.MODIFIER,
                    )
                    if (pressedBar == -3) {
                        listener.onEmojiBackspace()
                        handler.postDelayed(repeatDelete, 450)
                    }
                } else {
                    pressedEmoji = emojiAt(e.x, e.y)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(e)
                if (pressedBar == -1) {
                    if (!dragging && abs(e.x - downX) > slop) {
                        dragging = true
                        pressedEmoji = null
                    }
                    if (dragging) {
                        scrollPos = (scrollPos - (e.x - lastX)).coerceIn(0f, maxScroll())
                    }
                }
                lastX = e.x
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(repeatDelete)
                if (pressedBar != -1) {
                    when (val b = pressedBar) {
                        -2 -> listener.onEmojiClose()
                        -3 -> {}
                        else -> if (barSlotAt(e.x) == b) jumpTo(b)
                    }
                } else if (dragging) {
                    velocity?.let {
                        it.computeCurrentVelocity(1000)
                        scroller.fling(
                            scrollPos.toInt(), 0, -it.xVelocity.toInt(), 0,
                            0, maxScroll().toInt(), 0, 0,
                        )
                        postInvalidateOnAnimation()
                    }
                } else {
                    pressedEmoji?.let { (si, i) ->
                        listener.onFeedback(KeyboardView.FeedbackKind.KEY)
                        listener.onEmoji(sections[si].emoji[i])
                    }
                }
                pressedEmoji = null
                pressedBar = -1
                velocity?.recycle()
                velocity = null
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(repeatDelete)
                pressedEmoji = null
                pressedBar = -1
            }
        }
        invalidate()
        return true
    }

    private fun jumpTo(section: Int) {
        scroller.forceFinished(true)
        val target = (sections[section].startX - sidePad).coerceIn(0f, maxScroll())
        scroller.startScroll(scrollPos.toInt(), 0, (target - scrollPos).toInt(), 0, 300)
        postInvalidateOnAnimation()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        handler.removeCallbacks(repeatDelete)
    }
}
