package io.github.cctyl.keydroidx.music.network

import com.google.gson.Gson
import io.github.cctyl.keydroidx.music.network.model.LyricResponse
import io.github.cctyl.keydroidx.music.network.model.SearchResponse
import io.github.cctyl.keydroidx.music.network.model.SongUrlResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class NetworkModelsTest {

    private val gson = Gson()

    @Test
    fun testParseSearchResponse() {
        val json = """
            {
                "result": {
                    "songs": [
                        {
                            "id": 186016,
                            "name": "晴天",
                            "ar": [{"id": 6452, "name": "周杰伦"}],
                            "al": {"id": 18896, "name": "叶惠美", "picUrl": "http://example.com/pic.jpg"},
                            "dt": 269000
                        }
                    ]
                }
            }
        """.trimIndent()

        val response = gson.fromJson(json, SearchResponse::class.java)
        assertNotNull(response?.result?.songs)
        assertEquals(1, response.result?.songs?.size)
        val song = response.result!!.songs!![0]
        assertEquals(186016L, song.id)
        assertEquals("晴天", song.name)
        assertEquals("周杰伦", song.artists?.get(0)?.name)
        assertEquals("叶惠美", song.album?.name)
    }

    @Test
    fun testParseSongUrlResponse() {
        val json = """
            {
                "data": [
                    {
                        "id": 186016,
                        "url": "http://m701.music.126.net/xxx.mp3",
                        "br": 320000,
                        "size": 10485760,
                        "type": "mp3",
                        "level": "exhigh"
                    }
                ]
            }
        """.trimIndent()

        val response = gson.fromJson(json, SongUrlResponse::class.java)
        assertNotNull(response?.data)
        assertEquals(1, response.data.size)
        assertEquals(186016L, response.data[0].id)
        assertEquals("http://m701.music.126.net/xxx.mp3", response.data[0].url)
    }

    @Test
    fun testParseLyricResponse() {
        val json = """
            {
                "lrc": {
                    "lyric": "[00:00.00]晴天\n[00:01.00]周杰伦"
                }
            }
        """.trimIndent()

        val response = gson.fromJson(json, LyricResponse::class.java)
        assertNotNull(response?.lrc?.lyric)
        assertEquals("[00:00.00]晴天\n[00:01.00]周杰伦", response.lrc?.lyric)
    }
}
