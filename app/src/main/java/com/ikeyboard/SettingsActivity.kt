package com.ikeyboard

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/** Setup + preferences screen styled after an iOS grouped settings table. */
class SettingsActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var content: LinearLayout
    private var enableStatus: TextView? = null
    private var selectStatus: TextView? = null

    private val dp get() = resources.displayMetrics.density
    private fun color(id: Int) = getColor(id)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val scroll = ScrollView(this).apply { isFillViewport = true }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), (24 * dp).toInt(), (16 * dp).toInt(), (32 * dp).toInt())
        }
        scroll.addView(content)
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            @Suppress("DEPRECATION")
            v.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
            insets
        }
        setContentView(scroll)
        build()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // The input-method picker is a dialog; refresh once it closes.
        if (hasFocus) updateStatus()
    }

    private fun build() {
        content.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            setTextColor(color(R.color.label))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding((4 * dp).toInt(), 0, 0, (8 * dp).toInt())
        })

        section("SETUP", "Turn on iKeyboard in system settings, then pick it as your current keyboard.") {
            enableStatus = actionRow(it, "Enable keyboard") {
                startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            }
            selectStatus = actionRow(it, "Switch to iKeyboard") {
                (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
            }
        }

        section(
            "KEYBOARDS",
            "With both on, tap the 🌐 key to switch. The Lithuanian keyboard shows ą č ę ė į š ų ū ž " +
                "above the keys in place of suggestions.",
        ) { group ->
            lateinit var en: Switch
            lateinit var lt: Switch
            fun keepOne(sw: Switch, other: Switch, checked: Boolean): Boolean {
                if (!checked && !other.isChecked) {
                    sw.isChecked = true
                    android.widget.Toast.makeText(this, "Keep at least one keyboard on", android.widget.Toast.LENGTH_SHORT).show()
                    return false
                }
                return true
            }
            en = toggle(group, "English", prefs.langEnglish) { v ->
                if (keepOne(en, lt, v)) prefs.langEnglish = v
            }
            lt = toggle(group, "Lietuvių (Lithuanian)", prefs.langLithuanian) { v ->
                if (keepOne(lt, en, v)) {
                    prefs.langLithuanian = v
                    // Turning Lithuanian on switches to it straight away.
                    if (v) prefs.currentLanguage = Prefs.LITHUANIAN
                }
            }
        }

        section("TRY IT", "Return adds a line in the notes box and submits the single-line field.") { group ->
            fun styled(e: EditText) = e.apply {
                background = null
                setTextColor(color(R.color.label))
                setHintTextColor(color(R.color.secondary_label))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                setPadding((16 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt(), (12 * dp).toInt())
            }
            group.addView(styled(EditText(this)).apply {
                hint = "Notes (multi-line)…"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE
                minHeight = (88 * dp).toInt()
                gravity = Gravity.TOP
            })
            group.addView(View(this).apply { setBackgroundColor(color(R.color.separator)) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxOf(1, (0.5f * dp).toInt())).apply {
                    marginStart = (16 * dp).toInt()
                })
            group.addView(styled(EditText(this)).apply {
                hint = "Single-line field"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                isSingleLine = true
                setOnEditorActionListener { v, _, _ ->
                    android.widget.Toast.makeText(this@SettingsActivity, "Submitted: ${v.text}", android.widget.Toast.LENGTH_SHORT).show()
                    v.text = null
                    true
                }
            })
        }

        section("ALL KEYBOARDS", null) {
            toggle(it, "Auto-Capitalization", prefs.autoCapitalize) { v -> prefs.autoCapitalize = v }
            toggle(it, "Auto-Correction", prefs.autoCorrect) { v -> prefs.autoCorrect = v }
            toggle(it, "Predictive", prefs.predictive) { v -> prefs.predictive = v }
            toggle(it, "Enable Caps Lock", prefs.capsLock) { v -> prefs.capsLock = v }
            toggle(it, "Character Preview", prefs.charPreview) { v -> prefs.charPreview = v }
            toggle(it, "“.” Shortcut", prefs.periodShortcut) { v -> prefs.periodShortcut = v }
        }
        footer("Double tapping the space bar will insert a period followed by a space.")

        section("KEYBOARD FEEDBACK", null) {
            toggle(it, "Sound", prefs.sound) { v -> prefs.sound = v }
            toggle(it, "Haptic", prefs.haptics) { v -> prefs.haptics = v }
        }

        section("APPEARANCE", null) { group ->
            val names = listOf("Automatic", "Light", "Dark")
            val rows = names.mapIndexed { i, name ->
                actionRow(group, name, chevron = false) { }.also { tv ->
                    (tv.parent as View).setOnClickListener {
                        prefs.theme = i
                        refreshThemeChecks(group, i)
                    }
                }
            }
            rows.forEach { it.setTextColor(color(R.color.tint)) }
            refreshThemeChecks(group, prefs.theme)
        }

        section("DICTIONARY", "Words you keep after an auto-correction are learned. Reset to forget them.") {
            actionRow(it, "Reset Keyboard Dictionary", chevron = false, destructive = true) {
                prefs.userWords = emptySet()
                android.widget.Toast.makeText(this, "Dictionary reset", android.widget.Toast.LENGTH_SHORT).show()
            }
        }

        content.addView(TextView(this).apply {
            text = "Tips\n• Double-tap shift for caps lock.\n• Touch and hold a letter for accents.\n" +
                "• Touch and hold the space bar to move the cursor.\n• Slide from 123 to a symbol and let go to type it.\n" +
                "• Touch and hold the emoji key to switch keyboards.\n\nWord list derived from wordfreq (CC BY-SA 4.0)."
            setTextColor(color(R.color.secondary_label))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding((16 * dp).toInt(), (20 * dp).toInt(), (16 * dp).toInt(), 0)
        })
    }

    private fun refreshThemeChecks(group: LinearLayout, selected: Int) {
        var idx = 0
        for (i in 0 until group.childCount) {
            val row = group.getChildAt(i) as? LinearLayout ?: continue
            val status = row.getChildAt(1) as? TextView ?: continue
            status.text = if (idx == selected) "✓" else ""
            status.setTextColor(color(R.color.tint))
            idx++
        }
    }

    private fun updateStatus() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val enabled = imm.enabledInputMethodList.any { it.packageName == packageName }
        val current = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: ""
        val selected = current.startsWith("$packageName/")
        enableStatus?.text = if (enabled) "On" else "Off"
        selectStatus?.text = if (selected) "In use" else ""
    }

    // ---- tiny iOS-style table builders --------------------------------------------------------

    private fun section(title: String, footerText: String?, fill: (LinearLayout) -> Unit) {
        content.addView(TextView(this).apply {
            text = title
            setTextColor(color(R.color.secondary_label))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding((16 * dp).toInt(), (22 * dp).toInt(), 0, (7 * dp).toInt())
        })
        val group = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 10 * dp
                setColor(color(R.color.cell_bg))
            }
            clipToOutline = true
        }
        content.addView(group)
        fill(group)
        // Remove the trailing separator.
        if (group.childCount > 0 && group.getChildAt(group.childCount - 1).tag == "sep") {
            group.removeViewAt(group.childCount - 1)
        }
        footerText?.let { footer(it) }
    }

    private fun footer(t: String) {
        content.addView(TextView(this).apply {
            text = t
            setTextColor(color(R.color.secondary_label))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding((16 * dp).toInt(), (7 * dp).toInt(), (16 * dp).toInt(), 0)
        })
    }

    private fun row(group: LinearLayout, label: String, destructive: Boolean = false): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = (46 * dp).toInt()
            setPadding((16 * dp).toInt(), 0, (14 * dp).toInt(), 0)
            val ta = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
            foreground = ta.getDrawable(0)
            ta.recycle()
        }
        row.addView(TextView(this).apply {
            text = label
            setTextColor(if (destructive) 0xFFFF3B30.toInt() else color(R.color.label))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        group.addView(row)
        group.addView(View(this).apply {
            tag = "sep"
            setBackgroundColor(color(R.color.separator))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxOf(1, (0.5f * dp).toInt())).apply {
            marginStart = (16 * dp).toInt()
        })
        return row
    }

    private fun actionRow(
        group: LinearLayout, label: String, chevron: Boolean = true, destructive: Boolean = false,
        onClick: () -> Unit,
    ): TextView {
        val r = row(group, label, destructive)
        val status = TextView(this).apply {
            setTextColor(color(R.color.secondary_label))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        }
        r.addView(status)
        if (chevron) {
            r.addView(TextView(this).apply {
                text = "  ›"
                setTextColor(color(R.color.separator))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            })
        }
        r.setOnClickListener { onClick() }
        return status
    }

    private fun toggle(group: LinearLayout, label: String, value: Boolean, onChange: (Boolean) -> Unit): Switch {
        val r = row(group, label)
        val sw = Switch(this).apply {
            isChecked = value
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            thumbTintList = ColorStateList(states, intArrayOf(0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt()))
            trackTintList = ColorStateList(states, intArrayOf(0xFF34C759.toInt(), 0xFF8E8E93.toInt()))
            setOnCheckedChangeListener { _, checked -> onChange(checked) }
        }
        r.addView(sw)
        r.setOnClickListener { sw.toggle() }
        return sw
    }
}
