package io.github.cctyl.keydroidx.music.lyric

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcParserTest {

    @Test
    fun testParseSimpleLrc() {
        val lrc = """
            [00:01.00]第一句歌词
            [00:03.500]第二句歌词
            [01:10.25]第三句歌词
        """.trimIndent()

        val parsed = LrcParser.parse(lrc)
        assertEquals(3, parsed.size)
        assertEquals(1000L, parsed[0].timeMs)
        assertEquals("第一句歌词", parsed[0].text)

        assertEquals(3500L, parsed[1].timeMs)
        assertEquals("第二句歌词", parsed[1].text)

        assertEquals(70250L, parsed[2].timeMs)
        assertEquals("第三句歌词", parsed[2].text)
    }

    @Test
    fun testParseEmptyAndInvalidLrc() {
        val lrc = """
            [ar:周杰伦]
            [ti:晴天]
            [00:00.00]
            [invalid_tag]
        """.trimIndent()

        val parsed = LrcParser.parse(lrc)
        assertTrue(parsed.isEmpty())
    }
}
