package com.ikeyboard

import android.content.Context
import android.content.SharedPreferences

/**
 * Thin wrapper over SharedPreferences holding every user-facing setting.
 *
 * The settings screen runs in its own process (so its "Try it" field can't deadlock against the IME's
 * synchronous InputConnection calls), so the file is opened in multi-process mode and re-read on each
 * new [Prefs] instance.
 */
class Prefs(context: Context) {
    @Suppress("DEPRECATION")
    val sp: SharedPreferences = context.getSharedPreferences("ikeyboard", Context.MODE_MULTI_PROCESS)

    var autoCapitalize by bool("auto_cap", true)
    var autoCorrect by bool("auto_correct", true)
    var predictive by bool("predictive", true)
    var charPreview by bool("char_preview", true)
    var periodShortcut by bool("period_shortcut", true)
    var capsLock by bool("caps_lock", true)
    var sound by bool("sound", true)
    var haptics by bool("haptics", false)

    /** 0 = follow system, 1 = light, 2 = dark */
    var theme: Int
        get() = sp.getInt("theme", 0)
        set(v) = sp.edit().putInt("theme", v).apply()

    var userWords: Set<String>
        get() = sp.getStringSet("user_words", emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet("user_words", v).apply()

    var recentEmoji: List<String>
        get() = sp.getString("recent_emoji", "")!!.split('\u0001').filter { it.isNotEmpty() }
        set(v) = sp.edit().putString("recent_emoji", v.joinToString("\u0001")).apply()

    private fun bool(key: String, def: Boolean) = object : kotlin.properties.ReadWriteProperty<Any?, Boolean> {
        override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>) = sp.getBoolean(key, def)
        override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: Boolean) {
            sp.edit().putBoolean(key, value).apply()
        }
    }
}
