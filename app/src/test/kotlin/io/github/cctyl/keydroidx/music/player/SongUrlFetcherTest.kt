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
        // 取链失败时返回 null（不再伪造 outer/url 兜底地址），此用例需要联网环境
        assertNotNull("未取到播放链接，请确认测试机可正常访问网易云", result)
        val url = result!!.url
        assertTrue(url.isNotEmpty())
        assertFalse(url.contains("song/media/outer/url"))
    }
}
