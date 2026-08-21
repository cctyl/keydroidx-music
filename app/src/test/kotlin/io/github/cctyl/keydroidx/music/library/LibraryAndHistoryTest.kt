package io.github.cctyl.keydroidx.music.library

import io.github.cctyl.keydroidx.music.network.model.ArtistItem
import io.github.cctyl.keydroidx.music.network.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryAndHistoryTest {

    @Test
    fun testSongItemEqualsAndOperations() {
        val song1 = SongItem(id = 123L, name = "晴天", artists = listOf(ArtistItem(1L, "周杰伦")), album = null)
        val song2 = SongItem(id = 123L, name = "晴天", artists = listOf(ArtistItem(1L, "周杰伦")), album = null)
        val song3 = SongItem(id = 456L, name = "七里香", artists = listOf(ArtistItem(1L, "周杰伦")), album = null)

        assertEquals(song1, song2)
        assertEquals(song1.id, song2.id)

        val list = mutableListOf(song1)
        assertTrue(list.any { it.id == 123L })
        assertFalse(list.any { it.id == 456L })

        list.add(0, song3)
        assertEquals(456L, list[0].id)
    }

    @Test
    fun testSearchHistoryLogic() {
        val historyList = mutableListOf<String>()

        fun add(kw: String) {
            val trimmed = kw.trim()
            if (trimmed.isBlank()) return
            historyList.removeAll { it.equals(trimmed, ignoreCase = true) }
            historyList.add(0, trimmed)
        }

        add("周杰伦")
        add("林俊杰")
        add("周杰伦")

        assertEquals(2, historyList.size)
        assertEquals("周杰伦", historyList[0])
        assertEquals("林俊杰", historyList[1])
    }
}
