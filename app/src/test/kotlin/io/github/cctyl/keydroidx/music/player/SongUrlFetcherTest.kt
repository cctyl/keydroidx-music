package io.github.cctyl.keydroidx.music.player

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SongUrlFetcherTest {
    @Test
    fun testFetchUrlForOnlineSong() = runBlocking {
        // 《上山岗》
        val songId = 3392513818L
        val result = SongUrlFetcher.fetch(songId, level = "standard")
        assertNotNull(result)
        assertTrue(result.url.isNotEmpty())
        assertFalse(result.url.contains("song/media/outer/url"))
    }
}
