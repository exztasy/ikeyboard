package com.ikeyboard

import android.graphics.RectF

enum class KeyType { CHAR, SHIFT, BACKSPACE, MODE_NUMBERS, MODE_SYMBOLS, MODE_LETTERS, EMOJI, GLOBE, SPACE, RETURN, SPACER }

class Key(
    val type: KeyType,
    val label: String = "",
    val output: String = label,
    val width: Float = 1f,
    val alternates: List<String> = emptyList(),
    /** Small secondary caption, used by the number pad ("ABC", "DEF" ...). */
    val caption: String = "",
) {
    val rect = RectF()
    /** Drawn with the darker "function key" colour. */
    val isSpecial: Boolean
        get() = type != KeyType.CHAR && type != KeyType.SPACE
}

class KeyboardLayout(val rows: List<List<Key>>, val units: Float, val isPad: Boolean = false)

enum class Mode { LETTERS, NUMBERS, SYMBOLS, PHONE, NUMBER_PAD }
enum class FieldVariant { TEXT, EMAIL, URL }

object Layouts {
    /** Long-press accent menus, in the order iOS shows them. */
    private val ALTERNATES = mapOf(
        "a" to "à á â ä æ ã å ā", "c" to "ç ć č", "e" to "è é ê ë ē ė ę",
        "i" to "î ï í ī į ì", "l" to "ł", "n" to "ñ ń", "o" to "ô ö ò ó œ ø ō õ",
        "s" to "ß ś š", "u" to "û ü ù ú ū", "y" to "ÿ", "z" to "ž ź ż",
        "0" to "°", "-" to "– — •", "/" to "\\", "$" to "₽ ¥ € ¢ £ ₩",
        "&" to "§", "\"" to "” „ “ » «", "." to "…", "?" to "¿", "!" to "¡",
        "'" to "’ ‘ ‚ `", "%" to "‰", "=" to "≠ ≈", "@" to "", ".com" to ".net .org .edu .co .io",
    ).mapValues { (_, v) -> v.split(' ').filter { it.isNotEmpty() } }

    /** The Lithuanian letters, shown in the strip above the keys on the Lithuanian keyboard. */
    val LITHUANIAN_LETTERS = listOf("ą", "č", "ę", "ė", "į", "š", "ų", "ū", "ž")

    private val LITHUANIAN_ALTERNATES = mapOf(
        "a" to listOf("ą"), "c" to listOf("č"), "e" to listOf("ę", "ė"), "i" to listOf("į"),
        "s" to listOf("š"), "u" to listOf("ų", "ū"), "z" to listOf("ž"),
    )

    /** Long-press menu for [label]; on the Lithuanian keyboard its letters come first. */
    fun alternatesFor(label: String, upper: Boolean, lithuanian: Boolean = false): List<String> {
        val base = ALTERNATES[label.lowercase()] ?: emptyList()
        val lt = if (lithuanian) LITHUANIAN_ALTERNATES[label.lowercase()] ?: emptyList() else emptyList()
        val list = (lt + base).distinct()
        return if (upper) list.map { it.uppercase() } else list
    }

    private fun chars(s: String, width: Float = 1f) =
        s.split(' ').map { Key(KeyType.CHAR, it, it, width, ALTERNATES[it] ?: emptyList()) }

    private fun spacer(w: Float) = Key(KeyType.SPACER, width = w)

    /** Bottom row; with [globe] a language-switch key is added and the other keys give up room for it. */
    private fun bottomRow(modeKey: Key, variant: FieldVariant, globe: Boolean): List<Key> {
        val g = if (globe) listOf(Key(KeyType.GLOBE, width = if (variant == FieldVariant.TEXT) 1.25f else 1f)) else emptyList()
        val emoji = Key(KeyType.EMOJI, width = if (globe && variant != FieldVariant.TEXT) 1f else 1.25f)
        val ret = Key(KeyType.RETURN, "return", width = 2.5f)
        return when (variant) {
            FieldVariant.TEXT -> listOf(modeKey) + g + listOf(emoji, Key(KeyType.SPACE, "space", " ", if (globe) 3.75f else 5f), ret)
            FieldVariant.EMAIL -> listOf(modeKey) + g + listOf(
                emoji, Key(KeyType.SPACE, "space", " ", if (globe) 2.5f else 3f),
                Key(KeyType.CHAR, "@", "@", 1.25f),
                Key(KeyType.CHAR, ".", ".", if (globe) 1f else 1.25f, ALTERNATES["."]!!),
                Key(KeyType.RETURN, "return", width = 2f),
            )
            FieldVariant.URL -> listOf(modeKey) + g + listOf(
                emoji, Key(KeyType.CHAR, ".", ".", if (globe) 1.25f else 1.5f, ALTERNATES["."]!!),
                Key(KeyType.CHAR, "/", "/", if (globe) 1.25f else 1.5f),
                Key(KeyType.CHAR, ".com", ".com", if (globe) 1.75f else 2f, ALTERNATES[".com"]!!), ret,
            )
        }
    }

    fun build(mode: Mode, variant: FieldVariant, globe: Boolean = false): KeyboardLayout = when (mode) {
        Mode.LETTERS -> KeyboardLayout(
            listOf(
                chars("q w e r t y u i o p"),
                listOf(spacer(0.5f)) + chars("a s d f g h j k l") + spacer(0.5f),
                listOf(Key(KeyType.SHIFT, width = 1.3f), spacer(0.2f)) + chars("z x c v b n m") +
                    listOf(spacer(0.2f), Key(KeyType.BACKSPACE, width = 1.3f)),
                bottomRow(Key(KeyType.MODE_NUMBERS, "123", width = 1.25f), variant, globe),
            ), 10f,
        )
        Mode.NUMBERS -> KeyboardLayout(
            listOf(
                chars("1 2 3 4 5 6 7 8 9 0"),
                chars("- / : ; ( ) $ & @ \""),
                listOf(Key(KeyType.MODE_SYMBOLS, "#+=", width = 1.3f), spacer(0.2f)) + chars(". , ? ! '", 1.4f) +
                    listOf(spacer(0.2f), Key(KeyType.BACKSPACE, width = 1.3f)),
                bottomRow(Key(KeyType.MODE_LETTERS, "ABC", width = 1.25f), variant, globe),
            ), 10f,
        )
        Mode.SYMBOLS -> KeyboardLayout(
            listOf(
                chars("[ ] { } # % ^ * + ="),
                chars("_ \\ | ~ < > € £ ¥ •"),
                listOf(Key(KeyType.MODE_NUMBERS, "123", width = 1.3f), spacer(0.2f)) + chars(". , ? ! '", 1.4f) +
                    listOf(spacer(0.2f), Key(KeyType.BACKSPACE, width = 1.3f)),
                bottomRow(Key(KeyType.MODE_LETTERS, "ABC", width = 1.25f), variant, globe),
            ), 10f,
        )
        Mode.PHONE, Mode.NUMBER_PAD -> {
            val caps = listOf("", "ABC", "DEF", "GHI", "JKL", "MNO", "PQRS", "TUV", "WXYZ")
            val digits = (1..9).map { Key(KeyType.CHAR, "$it", "$it", 1f, caption = caps[it - 1].ifEmpty { " " }) }
            val bottomLeft = if (mode == Mode.PHONE)
                Key(KeyType.CHAR, "+*#", "+", 1f, listOf("+", "*", "#", ",", ";"))
            else Key(KeyType.CHAR, ".", ".", 1f, listOf(".", ",", "-"))
            KeyboardLayout(
                listOf(
                    digits.subList(0, 3), digits.subList(3, 6), digits.subList(6, 9),
                    listOf(bottomLeft, Key(KeyType.CHAR, "0", "0", 1f, caption = if (mode == Mode.PHONE) "+" else " "),
                        Key(KeyType.BACKSPACE)),
                ), 3f, isPad = true,
            )
        }
    }
}
