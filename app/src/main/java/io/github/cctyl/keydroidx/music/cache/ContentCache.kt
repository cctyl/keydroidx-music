package io.github.cctyl.keydroidx.music.cache

import androidx.collection.LruCache
import io.github.cctyl.keydroidx.music.network.PlaylistApi
import io.github.cctyl.keydroidx.music.network.model.AlbumDetailResponse
import io.github.cctyl.keydroidx.music.network.model.ArtistDetailData
import io.github.cctyl.keydroidx.music.network.model.SongItem

/**
 * 内存与快速缓存层，负责消除网络加载白屏与状态跳变
 */
object ContentCache {
    // ── 首页 ─────────────────────────────────────────────────────────
    @Volatile var homeDailySongs: List<SongItem>? = null
    @Volatile var homeRecommendPlaylists: List<PlaylistApi.PlaylistCard>? = null
    @Volatile var homeNewSongs: List<SongItem>? = null

    // ── 详情页（按 ID 缓存，LRU 32 项封顶） ──────────────────────────
    private val albumCache = LruCache<Long, AlbumDetailResponse>(32)
    private val playlistCache = LruCache<Long, List<SongItem>>(32)
    private val artistCache = LruCache<Long, ArtistDetailData>(32)

    fun getAlbum(id: Long): AlbumDetailResponse? = albumCache[id]
    fun putAlbum(id: Long, data: AlbumDetailResponse) { albumCache.put(id, data) }

    fun getPlaylistSongs(id: Long): List<SongItem>? = playlistCache[id]
    fun putPlaylistSongs(id: Long, data: List<SongItem>) { playlistCache.put(id, data) }

    fun getArtist(id: Long): ArtistDetailData? = artistCache[id]
    fun putArtist(id: Long, data: ArtistDetailData) { artistCache.put(id, data) }

    // ── 用户 ─────────────────────────────────────────────────────────
    @Volatile var userProfile: PlaylistApi.UserProfile? = null

    // 清空所有缓存
    fun clearAll() {
        homeDailySongs = null
        homeRecommendPlaylists = null
        homeNewSongs = null
        albumCache.evictAll()
        playlistCache.evictAll()
        artistCache.evictAll()
        userProfile = null
    }
}
