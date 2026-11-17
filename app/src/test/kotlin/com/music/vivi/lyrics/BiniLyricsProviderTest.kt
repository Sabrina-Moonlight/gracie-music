package com.music.vivi.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BiniLyricsProviderTest {
    @Test
    fun backgroundVoiceGoesToItsOwnLine() {
        val ttml = """<tt xmlns="http://www.w3.org/ns/ttml"><body><div><p ttm:agent="v1" begin="0:05.000" end="0:08.000"><span ttm:role="x-bg" begin="0:05.000" end="0:06.000">(hello)</span></p></div></body></tt>"""
        val lrc = BiniLyricsProvider.ttmlToLrc(ttml)
        assertTrue(lrc.startsWith("[00:05.000]{bg}"))
        assertTrue(lrc.contains("hello"))
        assertFalse(lrc.contains("("))
        assertFalse(lrc.contains(")"))
    }

    @Test
    fun plainTextParagraph() {
        val ttml = """<tt xmlns="http://www.w3.org/ns/ttml"><body><div><p begin="0:01.000" end="0:03.000">plain line</p></div></body></tt>"""
        assertEquals("[00:01.000]plain line", BiniLyricsProvider.ttmlToLrc(ttml))
    }

    @Test
    fun emptyAndMalformedReturnBlank() {
        assertEquals("", BiniLyricsProvider.ttmlToLrc(""))
        assertEquals("", BiniLyricsProvider.ttmlToLrc("not xml"))
        assertEquals("", BiniLyricsProvider.ttmlToLrc("""<tt><body><div></div></body></tt>"""))
    }

    @Test
    fun wordLevelTimestamps() {
        val ttml = """<tt xmlns="http://www.w3.org/ns/ttml"><body><div><p begin="0:05.500" end="0:08.000"><span begin="0:05.500" end="0:06.500">hello</span> <span begin="0:06.500" end="0:08.000">world</span></p></div></body></tt>"""
        val lrc = BiniLyricsProvider.ttmlToLrc(ttml)
        assertEquals("[00:05.500]<00:05.500>hello <00:06.500>world<00:08.000>", lrc)
    }

    @Test
    fun syllablesOfOneWordAreNotSplitWithSpaces() {
        val ttml = """<tt xmlns="http://www.w3.org/ns/ttml"><body><div><p begin="0:01.000" end="0:03.000"><span begin="0:01.000" end="0:01.500">Me</span><span begin="0:01.500" end="0:02.000">ta</span><span begin="0:02.000" end="0:03.000">phor</span></p></div></body></tt>"""
        val lrc = BiniLyricsProvider.ttmlToLrc(ttml)
        assertEquals("[00:01.000]<00:01.000>Me<00:01.500>ta<00:02.000>phor<00:03.000>", lrc)
    }
}
