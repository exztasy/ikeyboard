package com.ikeyboard

import android.content.Context
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.InputType
import android.text.TextUtils
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import kotlin.concurrent.thread

class IKeyboardService : InputMethodService(), KeyboardView.Listener, EmojiView.Listener {

    private lateinit var prefs: Prefs
    @Volatile private var dictionary: Dictionary? = null

    private var root: FrameLayout? = null
    private lateinit var keyboard: KeyboardView
    private lateinit var emoji: EmojiView
    private var theme = KeyboardTheme.LIGHT

    // Per-field behaviour.
    private var suggestionsAllowed = true
    private var autoCorrectAllowed = true
    private var autoCapAllowed = true
    private var capModes = TextUtils.CAP_MODE_SENTENCES
    private var enterAction = EditorInfo.IME_ACTION_NONE

    // Typing session state.
    private class Correction(val original: String, val replacement: String, val separator: String)
    private var lastCorrection: Correction? = null
    private var lastSpaceTime = 0L
    private var justPickedSuggestion = false
    private var slotWords: List<Pair<String, Boolean>?> = listOf(null, null, null)

    private val audio by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= 31) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator?
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        thread(name = "dict-load") {
            val d = Dictionary(applicationContext)
            d.setUserWords(prefs.userWords)
            dictionary = d
        }
    }

    override fun onCreateInputView(): View {
        keyboard = KeyboardView(this, this)
        emoji = EmojiView(this, this).apply { visibility = View.GONE }
        val frame = FrameLayout(this)
        frame.addView(keyboard, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        frame.addView(emoji, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        frame.setOnApplyWindowInsetsListener { v, insets ->
            val bottom = if (Build.VERSION.SDK_INT >= 30) {
                insets.getInsets(WindowInsets.Type.navigationBars()).bottom
            } else {
                @Suppress("DEPRECATION") insets.systemWindowInsetBottom
            }
            v.setPadding(0, 0, 0, bottom)
            insets
        }
        root = frame
        applyTheme()
        return frame
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    private fun applyTheme() {
        theme = KeyboardTheme.resolve(this, prefs.theme)
        keyboard.theme = theme
        emoji.theme = theme
        root?.setBackgroundColor(theme.background)
        @Suppress("DEPRECATION")
        window.window?.navigationBarColor = theme.background
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        applyTheme()
        dictionary?.setUserWords(prefs.userWords)
        keyboard.reset()
        hideEmoji()
        keyboard.showPreview = prefs.charPreview
        keyboard.capsLockEnabled = prefs.capsLock

        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        val flags = info.inputType and InputType.TYPE_MASK_FLAGS
        val isText = cls == InputType.TYPE_CLASS_TEXT
        val password = isText && variation in setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
        )
        val email = isText && (variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS)
        val url = isText && variation == InputType.TYPE_TEXT_VARIATION_URI
        val variant = when {
            email -> FieldVariant.EMAIL
            url -> FieldVariant.URL
            else -> FieldVariant.TEXT
        }
        val mode = when (cls) {
            InputType.TYPE_CLASS_NUMBER -> Mode.NUMBER_PAD
            InputType.TYPE_CLASS_PHONE -> Mode.PHONE
            InputType.TYPE_CLASS_DATETIME -> Mode.NUMBERS
            else -> Mode.LETTERS
        }
        val plainText = isText && !password && !email && !url
        val noSuggestionFlag = flags and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0
        suggestionsAllowed = plainText && !noSuggestionFlag && variation != InputType.TYPE_TEXT_VARIATION_FILTER
        autoCorrectAllowed = plainText && prefs.autoCorrect && !noSuggestionFlag &&
            variation != InputType.TYPE_TEXT_VARIATION_PERSON_NAME
        autoCapAllowed = plainText && prefs.autoCapitalize
        capModes = when {
            flags and InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS != 0 -> TextUtils.CAP_MODE_CHARACTERS
            flags and InputType.TYPE_TEXT_FLAG_CAP_WORDS != 0 ||
                variation == InputType.TYPE_TEXT_VARIATION_PERSON_NAME -> TextUtils.CAP_MODE_WORDS
            else -> TextUtils.CAP_MODE_SENTENCES
        }

        // Return key: label and blue tint follow the field's action, like iOS.
        val multiLine = isText && flags and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0
        val noEnterAction = info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0
        enterAction = if (multiLine || noEnterAction) EditorInfo.IME_ACTION_NONE
        else info.imeOptions and EditorInfo.IME_MASK_ACTION
        val (label, blue) = when (enterAction) {
            EditorInfo.IME_ACTION_GO -> "go" to true
            EditorInfo.IME_ACTION_SEARCH -> "search" to true
            EditorInfo.IME_ACTION_SEND -> "send" to true
            EditorInfo.IME_ACTION_DONE -> "done" to true
            EditorInfo.IME_ACTION_NEXT -> "next" to false
            EditorInfo.IME_ACTION_PREVIOUS -> "previous" to false
            else -> "return" to false
        }
        keyboard.returnLabel = info.actionLabel?.toString()?.lowercase()?.takeIf { enterAction != EditorInfo.IME_ACTION_NONE } ?: label
        keyboard.returnHighlighted = blue

        keyboard.configure(mode, variant)
        keyboard.setShift(KeyboardView.Shift.OFF)
        lastCorrection = null
        justPickedSuggestion = false
        lastSpaceTime = 0
        refresh()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        if (root != null) {
            keyboard.reset()
            hideEmoji()
        }
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        if (root != null) refresh()
    }

    // ---- helpers -----------------------------------------------------------------------------

    private fun isWordChar(c: Char) = c.isLetter() || c == '\''

    /** The partial word immediately left of the cursor, or "" when the cursor isn't at the end of a word. */
    private fun currentWord(): String {
        val ic = currentInputConnection ?: return ""
        val before = ic.getTextBeforeCursor(48, 0)?.toString() ?: return ""
        val after = ic.getTextAfterCursor(1, 0)?.toString() ?: ""
        if (after.isNotEmpty() && isWordChar(after[0])) return ""
        var i = before.length
        while (i > 0 && isWordChar(before[i - 1])) i--
        var word = before.substring(i)
        word = word.trimStart('\'')
        if (word.isEmpty()) return ""
        val prevIndex = before.length - word.length - 1
        if (prevIndex >= 0) {
            val p = before[prevIndex]
            if (p.isDigit() || p in "@#/_\\.-:") return ""
        }
        return word
    }

    private fun refresh() {
        updateShift()
        updateSuggestions()
    }

    private fun updateShift() {
        if (!::keyboard.isInitialized) return
        if (!autoCapAllowed) {
            keyboard.applyAutoShift(false)
            return
        }
        val ic = currentInputConnection ?: return
        keyboard.applyAutoShift(ic.getCursorCapsMode(capModes) != 0)
    }

    private fun updateSuggestions() {
        val dict = dictionary
        val word = if (suggestionsAllowed && prefs.predictive && dict != null) currentWord() else ""
        if (word.isEmpty() || dict == null) {
            slotWords = listOf(null, null, null)
        } else {
            val fix = if (autoCorrectAllowed) dict.correct(word) else word
            val cands = dict.suggest(word, 4).filter { !it.equals(word, true) && it != fix }
            slotWords = if (fix != word) {
                listOf(word to true, fix to false, cands.firstOrNull()?.let { it to false })
            } else {
                listOf(word to true, cands.getOrNull(0)?.let { it to false }, cands.getOrNull(1)?.let { it to false })
            }
        }
        keyboard.suggestions = slotWords.mapIndexed { i, s ->
            s?.let {
                if (it.second) KeyboardView.Suggestion("“${it.first}”")
                else KeyboardView.Suggestion(it.first, highlighted = i == 1 && autoCorrectAllowed &&
                    dict?.correct(word) == it.first && it.first != word)
            }
        }
    }

    private fun learn(word: String) {
        val w = word.lowercase()
        if (w.length < 2 || dictionary?.isKnown(w) == true) return
        val set = prefs.userWords.toMutableSet()
        set.add(w)
        prefs.userWords = set
        dictionary?.setUserWords(set)
    }

    /** Replaces the word before the cursor with its correction, remembering it so delete can undo it. */
    private fun autoCorrect(separator: String) {
        lastCorrection = null
        if (!autoCorrectAllowed) return
        val dict = dictionary ?: return
        val ic = currentInputConnection ?: return
        val word = currentWord()
        if (word.isEmpty()) return
        val fix = dict.correct(word)
        if (fix == word) return
        ic.deleteSurroundingText(word.length, 0)
        ic.commitText(fix, 1)
        lastCorrection = Correction(word, fix, separator)
    }

    // ---- KeyboardView.Listener ---------------------------------------------------------------

    override fun onText(text: String, fromMode: Mode) {
        val ic = currentInputConnection ?: return
        ic.beginBatchEdit()
        val isPunct = text.length == 1 && text[0] in ".,!?;:"
        if (isPunct && justPickedSuggestion) {
            // "word ." -> "word." after tapping a suggestion, like iOS.
            if (ic.getTextBeforeCursor(1, 0) == " ") ic.deleteSurroundingText(1, 0)
        }
        if (text.length == 1 && text[0] in ".,!?;:)\"") autoCorrect(text) else lastCorrection = null
        ic.commitText(text, 1)
        ic.endBatchEdit()
        justPickedSuggestion = false
        lastSpaceTime = 0
    }

    override fun onSpace() {
        val ic = currentInputConnection ?: return
        val now = SystemClock.uptimeMillis()
        ic.beginBatchEdit()
        val before = ic.getTextBeforeCursor(2, 0)?.toString() ?: ""
        if (prefs.periodShortcut && now - lastSpaceTime < 1000 && before.length == 2 && before[1] == ' ' &&
            (before[0].isLetterOrDigit() || before[0] in ")\"'”’") && suggestionsAllowedForShortcut()
        ) {
            ic.deleteSurroundingText(1, 0)
            ic.commitText(". ", 1)
            lastSpaceTime = 0
            lastCorrection = null
        } else {
            autoCorrect(" ")
            ic.commitText(" ", 1)
            lastSpaceTime = now
        }
        ic.endBatchEdit()
        justPickedSuggestion = false
    }

    private fun suggestionsAllowedForShortcut(): Boolean {
        val info = currentInputEditorInfo ?: return true
        return info.inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_TEXT
    }

    override fun onBackspace(repeat: Int) {
        val ic = currentInputConnection ?: return
        justPickedSuggestion = false
        lastSpaceTime = 0

        // Undo the auto-correction that just happened, and remember the word.
        val c = lastCorrection
        lastCorrection = null
        if (repeat == 0 && c != null) {
            val tail = c.replacement + c.separator
            if (ic.getTextBeforeCursor(tail.length, 0)?.toString() == tail) {
                ic.beginBatchEdit()
                ic.deleteSurroundingText(tail.length, 0)
                ic.commitText(c.original, 1)
                ic.endBatchEdit()
                learn(c.original)
                return
            }
        }

        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            ic.commitText("", 1)
            return
        }
        val before = ic.getTextBeforeCursor(64, 0)?.toString()
        if (before == null) {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
            return
        }
        if (before.isEmpty()) {
            // Some editors (e.g. terminals) expose no text; a raw key event still works there.
            if (currentInputEditorInfo?.inputType == InputType.TYPE_NULL) sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
            return
        }
        val count = if (repeat > 20) {
            // Held long enough: delete whole words, as iOS accelerates.
            var i = before.length
            while (i > 0 && before[i - 1].isWhitespace()) i--
            while (i > 0 && !before[i - 1].isWhitespace()) i--
            (before.length - i).coerceAtLeast(1)
        } else {
            val bi = android.icu.text.BreakIterator.getCharacterInstance()
            bi.setText(before)
            val start = bi.preceding(before.length)
            (before.length - if (start == android.icu.text.BreakIterator.DONE) before.length - 1 else start).coerceAtLeast(1)
        }
        ic.deleteSurroundingText(count, 0)
    }

    override fun onBackspaceReleased() {}

    override fun onReturn() {
        val ic = currentInputConnection ?: return
        justPickedSuggestion = false
        lastSpaceTime = 0
        if (enterAction != EditorInfo.IME_ACTION_NONE && enterAction != EditorInfo.IME_ACTION_UNSPECIFIED) {
            autoCorrect("")
            ic.performEditorAction(enterAction)
        } else if (currentInputEditorInfo?.inputType == InputType.TYPE_NULL) {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        } else {
            ic.beginBatchEdit()
            autoCorrect("\n")
            ic.commitText("\n", 1)
            ic.endBatchEdit()
        }
    }

    override fun onEmoji() {
        emoji.fixedHeight = keyboard.totalHeight().toInt()
        emoji.reload(prefs.recentEmoji)
        keyboard.visibility = View.GONE
        emoji.visibility = View.VISIBLE
    }

    private fun hideEmoji() {
        if (!::emoji.isInitialized) return
        emoji.visibility = View.GONE
        keyboard.visibility = View.VISIBLE
    }

    override fun onSwitchInputMethod() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showInputMethodPicker()
    }

    override fun onCursorMove(dx: Int, dy: Int) {
        when {
            dx < 0 -> sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_LEFT)
            dx > 0 -> sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_RIGHT)
            dy < 0 -> sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_UP)
            dy > 0 -> sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_DOWN)
        }
    }

    override fun onSuggestion(slot: Int) {
        val (word, literal) = slotWords.getOrNull(slot) ?: return
        val ic = currentInputConnection ?: return
        val current = currentWord()
        ic.beginBatchEdit()
        if (current.isNotEmpty()) ic.deleteSurroundingText(current.length, 0)
        ic.commitText("$word ", 1)
        ic.endBatchEdit()
        if (literal) learn(word)
        onFeedback(KeyboardView.FeedbackKind.KEY)
        lastCorrection = null
        justPickedSuggestion = true
        lastSpaceTime = SystemClock.uptimeMillis()
    }

    override fun onShiftChanged() = updateShift()

    override fun onFeedback(kind: KeyboardView.FeedbackKind) {
        if (prefs.sound && audio.ringerMode == AudioManager.RINGER_MODE_NORMAL) {
            val fx = when (kind) {
                KeyboardView.FeedbackKind.DELETE -> AudioManager.FX_KEYPRESS_DELETE
                KeyboardView.FeedbackKind.SPACE -> AudioManager.FX_KEYPRESS_SPACEBAR
                KeyboardView.FeedbackKind.RETURN -> AudioManager.FX_KEYPRESS_RETURN
                else -> AudioManager.FX_KEYPRESS_STANDARD
            }
            audio.playSoundEffect(fx, 0.35f)
        }
        if (prefs.haptics) {
            val v = vibrator ?: return
            if (Build.VERSION.SDK_INT >= 29) {
                v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
            } else {
                v.vibrate(VibrationEffect.createOneShot(12, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        }
    }

    // ---- EmojiView.Listener ------------------------------------------------------------------

    override fun onEmoji(emoji: String) {
        currentInputConnection?.commitText(emoji, 1)
        val recents = prefs.recentEmoji.toMutableList()
        recents.remove(emoji)
        recents.add(0, emoji)
        prefs.recentEmoji = recents.take(32)
        lastCorrection = null
        justPickedSuggestion = false
    }

    override fun onEmojiBackspace() = onBackspace(0)

    override fun onEmojiClose() {
        hideEmoji()
        refresh()
    }
}
