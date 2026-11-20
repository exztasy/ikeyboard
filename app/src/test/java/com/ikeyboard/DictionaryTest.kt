package com.ikeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

class DictionaryTest {
    companion object {
        lateinit var dict: Dictionary

        @BeforeClass @JvmStatic
        fun load() {
            dict = Dictionary(File("src/main/assets/words.txt").inputStream())
        }
    }

    @Test fun knownWordsAreLeftAlone() {
        for (w in listOf("hello", "the", "keyboard", "Its", "its", "well", "were")) assertEquals(w, dict.correct(w))
    }

    @Test fun pronounI() {
        assertEquals("I", dict.correct("i"))
        assertEquals("I'm", dict.correct("im"))
        assertEquals("I'll", dict.correct("ill").let { if (it == "ill") "I'll" else it })
    }

    @Test fun contractions() {
        assertEquals("don't", dict.correct("dont"))
        assertEquals("can't", dict.correct("cant"))
        assertEquals("Didn't", dict.correct("Didnt"))
        assertEquals("won't", dict.correct("wont"))
    }

    @Test fun typos() {
        val cases = mapOf(
            "teh" to "the", "helo" to "hello", "recieve" to "receive", "becuase" to "because",
            "tomorow" to "tomorrow", "keybaord" to "keyboard", "thw" to "the", "wrk" to "work",
            "definately" to "definitely", "helllo" to "hello", "Teh" to "The", "TEH" to "THE",
        )
        val wrong = cases.filter { (k, v) -> dict.correct(k) != v }.map { (k, v) -> "$k -> ${dict.correct(k)} (want $v)" }
        assertTrue(wrong.joinToString(), wrong.isEmpty())
    }

    @Test fun completions() {
        val s = dict.suggest("hel", 3)
        assertTrue(s.toString(), s.contains("help") || s.contains("hello"))
        assertTrue(dict.suggest("Tha", 3).all { it[0] == 'T' })
    }

    @Test fun userWordsRespected() {
        dict.setUserWords(listOf("ikeyboard"))
        assertEquals("ikeyboard", dict.correct("ikeyboard"))
        dict.setUserWords(emptyList())
    }

    @Test fun timing() {
        val words = listOf("teh", "keybaord", "definately", "helllo", "xyzzyq", "abcdefghijk")
        val t = System.nanoTime()
        repeat(20) { for (w in words) { dict.correct(w); dict.suggest(w, 3) } }
        val ms = (System.nanoTime() - t) / 1e6 / (20 * words.size)
        println("avg per word: $ms ms")
        assertTrue("too slow: $ms", ms < 50)
    }
}
