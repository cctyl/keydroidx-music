package io.github.cctyl.keydroidx.music.library

import io.github.cctyl.keydroidx.music.network.model.AlbumItem
import io.github.cctyl.keydroidx.music.network.model.ArtistItem
import io.github.cctyl.keydroidx.music.network.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ModelAndDataTest {

    @Test
    fun testSongItemModelFields() {
        val song = SongItem(
            id = 123456L,
            name = "七里香",
            artists = listOf(ArtistItem(id = 6452L, name = "周杰伦")),
            album = AlbumItem(id = 123L, name = "七里香专辑", picUrl = "http://example.com/cover.jpg"),
            duration = 300000L
        )

        assertEquals(123456L, song.id)
        assertEquals("七里香", song.name)
        assertEquals("周杰伦", song.artists?.firstOrNull()?.name)
        assertEquals("七里香专辑", song.album?.name)
        assertEquals(300000L, song.duration)
    }
}
