package com.ikeyboard

import android.content.Context
import android.content.res.Configuration

/** Colours sampled to match the stock iOS keyboard in light and dark appearance. */
data class KeyboardTheme(
    val dark: Boolean,
    val background: Int,
    val key: Int,
    val specialKey: Int,
    val keyShadow: Int,
    val text: Int,
    val accent: Int,
    val accentText: Int,
    val divider: Int,
    val secondaryText: Int,
    val popup: Int,
    val highlight: Int,
) {
    companion object {
        val LIGHT = KeyboardTheme(
            dark = false,
            background = 0xFFD1D3D9.toInt(),
            key = 0xFFFFFFFF.toInt(),
            specialKey = 0xFFABB0BA.toInt(),
            keyShadow = 0xFF898A8D.toInt(),
            text = 0xFF000000.toInt(),
            accent = 0xFF007AFF.toInt(),
            accentText = 0xFFFFFFFF.toInt(),
            divider = 0xFFA9ABB1.toInt(),
            secondaryText = 0xFF6E7076.toInt(),
            popup = 0xFFFFFFFF.toInt(),
            highlight = 0xFFFFFFFF.toInt(),
        )
        val DARK = KeyboardTheme(
            dark = true,
            background = 0xFF252527.toInt(),
            key = 0xFF6B6B6D.toInt(),
            specialKey = 0xFF444446.toInt(),
            keyShadow = 0xFF0C0C0D.toInt(),
            text = 0xFFFFFFFF.toInt(),
            accent = 0xFF0A84FF.toInt(),
            accentText = 0xFFFFFFFF.toInt(),
            divider = 0xFF4D4D50.toInt(),
            secondaryText = 0xFFA0A0A5.toInt(),
            popup = 0xFF6B6B6D.toInt(),
            highlight = 0xFF5A5A5D.toInt(),
        )

        fun resolve(context: Context, pref: Int): KeyboardTheme = when (pref) {
            1 -> LIGHT
            2 -> DARK
            else -> {
                val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                if (night == Configuration.UI_MODE_NIGHT_YES) DARK else LIGHT
            }
        }
    }
}
