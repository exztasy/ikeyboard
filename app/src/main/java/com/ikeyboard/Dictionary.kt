package com.ikeyboard

import android.content.Context
import java.io.InputStream
import kotlin.math.abs
import kotlin.math.min

/**
 * Word list + lightweight spelling model powering QuickType-style suggestions and auto-correction.
 *
 * Frequencies are Zipf values x100 (so "the" ≈ 773, a rare word ≈ 150).
 */
class Dictionary(words: InputStream) {
    constructor(context: Context) : this(context.assets.open("words.txt"))

    private val freq = HashMap<String, Int>(32000)
    private val sorted: Array<String>
    private val byLength: Array<MutableList<String>> = Array(MAX_LEN + 3) { mutableListOf() }
    /** "dont" -> "don't", "im" -> "i'm" ... */
    private val contractions = HashMap<String, String>()
    private val userWords = HashSet<String>()

    init {
        words.bufferedReader().useLines { lines ->
            for (line in lines) {
                val tab = line.indexOf('\t')
                if (tab <= 0) continue
                freq[line.substring(0, tab)] = line.substring(tab + 1).toInt()
            }
        }
        for ((w, f) in freq) {
            if (w.length <= MAX_LEN + 2) byLength[w.length].add(w)
            val apos = w.indexOf('\'')
            if (apos > 0) {
                val stripped = w.replace("'", "")
                val sf = freq[stripped]
                if (sf == null || sf < f - 60) {
                    val prev = contractions[stripped]
                    if (prev == null || freq[prev]!! < f) contractions[stripped] = w
                }
            }
        }
        sorted = freq.keys.toTypedArray().also { it.sort() }
    }

    fun setUserWords(words: Collection<String>) {
        userWords.clear()
        userWords.addAll(words.map { it.lowercase() })
    }

    fun isKnown(word: String): Boolean {
        val w = word.lowercase()
        return freq.containsKey(w) || userWords.contains(w)
    }

    private fun frequency(w: String): Int = when {
        userWords.contains(w) -> maxOf(freq[w] ?: 0, USER_WORD_FREQ)
        else -> freq[w] ?: 0
    }

    /**
     * Returns the word auto-correct would replace [typed] with, or [typed] itself when no change is warranted.
     */
    fun correct(typed: String): String {
        if (typed.isEmpty() || typed.any { !it.isLetter() && it != '\'' }) return typed
        val lower = typed.lowercase()
        // Words the user taught us are always respected.
        if (userWords.contains(lower)) return typed
        if (lower == "i") return "I"
        contractions[lower]?.let { return matchCase(typed, it) }
        if (lower.startsWith("i'") && freq.containsKey(lower)) return "I" + typed.substring(1)
        if (freq.containsKey(lower)) return typed
        if (lower.length < 2) return typed

        val best = fuzzy(lower, 1).firstOrNull() ?: return typed
        return matchCase(typed, best.first)
    }

    /**
     * Up to [limit] candidates for the word being typed: completions first, then spelling corrections.
     */
    fun suggest(typed: String, limit: Int): List<String> {
        if (typed.isEmpty() || typed.any { !it.isLetter() && it != '\'' }) return emptyList()
        val lower = typed.lowercase()
        val out = LinkedHashSet<String>()

        contractions[lower]?.let { out.add(it) }
        if (lower == "i") out.add("I")

        // Completions by frequency.
        val completions = ArrayList<Pair<String, Int>>()
        var i = lowerBound(lower)
        while (i < sorted.size && sorted[i].startsWith(lower)) {
            val w = sorted[i]
            if (w != lower) completions.add(w to frequency(w))
            i++
        }
        for (u in userWords) if (u.startsWith(lower) && u != lower) completions.add(u to frequency(u))
        completions.sortByDescending { it.second }

        // Typo fixes compete with long-tail completions.
        val fixes = if (freq.containsKey(lower)) emptyList() else fuzzy(lower, 3)
        fixes.firstOrNull()?.let { out.add(it.first) }
        for ((w, _) in completions) {
            if (out.size >= limit) break
            out.add(w)
        }
        for ((w, _) in fixes) {
            if (out.size >= limit) break
            out.add(w)
        }
        return out.take(limit).map { matchCase(typed, it) }
    }

    private var cacheKey = ""
    private var cacheValue: List<Pair<String, Int>> = emptyList()

    /** Ranked spelling corrections for an unknown word (memoised for the word being typed). */
    private fun fuzzy(lower: String, limit: Int): List<Pair<String, Int>> {
        if (lower != cacheKey) {
            cacheValue = computeFuzzy(lower)
            cacheKey = lower
        }
        return cacheValue.take(limit)
    }

    private fun firstLetterPlausible(typed: String, w: String): Boolean {
        val a = typed[0]
        val b = w[0]
        if (a == b) return true
        if (typed.length > 1 && w.length > 1 && typed[1] == b && w[1] == a) return true
        return subCost(a, b) < 1f
    }

    private fun computeFuzzy(lower: String): List<Pair<String, Int>> {
        val n = lower.length
        if (n > MAX_LEN) return emptyList()
        val maxDist = when {
            n <= 3 -> 1.0f
            n <= 6 -> 1.5f
            else -> 2.0f
        }
        val results = ArrayList<Pair<String, Int>>()
        val lo = maxOf(1, n - 2)
        val hi = min(MAX_LEN + 2, n + 2)
        for (len in lo..hi) {
            if (abs(len - n) > maxDist) continue
            for (w in byLength[len]) {
                if (!firstLetterPlausible(lower, w)) continue
                val f = frequency(w)
                if (f < MIN_CORRECTION_FREQ) continue
                val d = distance(lower, w, maxDist)
                if (d > maxDist) continue
                // Each unit of edit distance costs ~3 Zipf points.
                var score = f - (d * EDIT_WEIGHT).toInt()
                if (w[0] == lower[0]) score += 40
                results.add(w to score)
            }
        }
        for (u in userWords) {
            if (abs(u.length - n) <= 2) {
                val d = distance(lower, u, maxDist)
                if (d <= maxDist) results.add(u to (USER_WORD_FREQ - (d * EDIT_WEIGHT).toInt() + 40))
            }
        }
        results.sortByDescending { it.second }
        return results.take(MAX_RESULTS)
    }

    private fun lowerBound(key: String): Int {
        var lo = 0
        var hi = sorted.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (sorted[mid] < key) lo = mid + 1 else hi = mid
        }
        return lo
    }

    companion object {
        private const val MAX_LEN = 20
        private const val MAX_RESULTS = 5
        private const val EDIT_WEIGHT = 300
        private const val MIN_CORRECTION_FREQ = 280
        private const val USER_WORD_FREQ = 520

        private val ROWS = arrayOf("qwertyuiop", "asdfghjkl", "zxcvbnm")

        /** Physical key positions, used to make fat-finger substitutions cheap. */
        private val POS: Map<Char, Pair<Float, Float>> = buildMap {
            ROWS.forEachIndexed { r, row ->
                row.forEachIndexed { c, ch -> put(ch, (c + r * 0.5f) to r.toFloat()) }
            }
        }

        private fun subCost(a: Char, b: Char): Float {
            if (a == b) return 0f
            val pa = POS[a]
            val pb = POS[b]
            if (pa != null && pb != null) {
                val dx = pa.first - pb.first
                val dy = pa.second - pb.second
                if (dx * dx + dy * dy <= 1.3f) return 0.7f
            }
            return 1f
        }

        /** Damerau–Levenshtein distance with keyboard-aware substitution costs and early exit. */
        fun distance(a: String, b: String, cutoff: Float): Float {
            val n = a.length
            val m = b.length
            var prev2 = FloatArray(m + 1)
            var prev = FloatArray(m + 1) { it.toFloat() }
            var cur = FloatArray(m + 1)
            for (i in 1..n) {
                cur[0] = i.toFloat()
                var rowMin = cur[0]
                for (j in 1..m) {
                    val ca = a[i - 1]
                    val cb = b[j - 1]
                    // Doubled letters ("helllo", "tomorow") and apostrophes are cheap.
                    val delCost = if (ca == '\'' || (i > 1 && a[i - 2] == ca)) 0.3f else 1f
                    val insCost = if (cb == '\'' || (j > 1 && b[j - 2] == cb)) 0.3f else 1f
                    var v = minOf(prev[j] + delCost, cur[j - 1] + insCost, prev[j - 1] + subCost(ca, cb))
                    if (i > 1 && j > 1 && ca == b[j - 2] && a[i - 2] == cb) v = min(v, prev2[j - 2] + 0.8f)
                    cur[j] = v
                    if (v < rowMin) rowMin = v
                }
                if (rowMin > cutoff) return Float.MAX_VALUE
                val t = prev2; prev2 = prev; prev = cur; cur = t
            }
            return prev[m]
        }

        /** Applies the capitalisation pattern of [typed] to [word]. */
        fun matchCase(typed: String, word: String): String = when {
            word == "I" || word.startsWith("i'") && typed.lowercase().startsWith("i") ->
                if (typed.length > 1 && typed.all { !it.isLetter() || it.isUpperCase() }) word.uppercase()
                else "I" + word.substring(1)
            typed.length > 1 && typed.all { !it.isLetter() || it.isUpperCase() } -> word.uppercase()
            typed.isNotEmpty() && typed[0].isUpperCase() -> word.replaceFirstChar { it.uppercase() }
            else -> word
        }
    }
}
