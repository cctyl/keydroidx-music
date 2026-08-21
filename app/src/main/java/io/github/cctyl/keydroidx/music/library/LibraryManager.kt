package io.github.cctyl.keydroidx.music.library

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.github.cctyl.keydroidx.music.auth.CookieManager
import io.github.cctyl.keydroidx.music.network.PlaylistApi
import io.github.cctyl.keydroidx.music.network.model.SongItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 歌曲收藏与本地音乐库管理
 */
object LibraryManager {
    private const val PREFS_NAME = "keydroidx_music_library"
    private const val KEY_FAVORITE_SONGS = "fav_songs"
    private const val KEY_RECENT_SONGS = "recent_songs"
    private const val MAX_RECENT_COUNT = 100

    private val gson = Gson()
    private var prefs: SharedPreferences? = null

    private val _favoriteSongs = MutableStateFlow<List<SongItem>>(emptyList())
    val favoriteSongs: StateFlow<List<SongItem>> = _favoriteSongs.asStateFlow()

    private val _recentSongs = MutableStateFlow<List<SongItem>>(emptyList())
    val recentSongs: StateFlow<List<SongItem>> = _recentSongs.asStateFlow()

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            loadFromPrefs()
        }
    }

    private fun loadFromPrefs() {
        val sp = prefs ?: return
        val favJson = sp.getString(KEY_FAVORITE_SONGS, null)
        if (!favJson.isNullOrBlank()) {
            val type = object : TypeToken<List<SongItem>>() {}.type
            _favoriteSongs.value = runCatching { gson.fromJson<List<SongItem>>(favJson, type) }.getOrDefault(emptyList())
        }

        val recJson = sp.getString(KEY_RECENT_SONGS, null)
        if (!recJson.isNullOrBlank()) {
            val type = object : TypeToken<List<SongItem>>() {}.type
            _recentSongs.value = runCatching { gson.fromJson<List<SongItem>>(recJson, type) }.getOrDefault(emptyList())
        }
    }

    // ── 收藏管理 ───────────────────────────────────────────────────

    fun isFavorite(songId: Long): Boolean {
        return _favoriteSongs.value.any { it.id == songId }
    }

    suspend fun toggleFavorite(context: Context, song: SongItem): Boolean {
        val currentlyFav = isFavorite(song.id)
        val targetFav = !currentlyFav

        // 若已登录，优先同步云端
        val cookie = CookieManager.getCookie(context)
        if (!cookie.isNullOrBlank()) {
            runCatching {
                PlaylistApi.likeSong(song.id, targetFav)
            }
        }

        // 更新本地
        val currentList = _favoriteSongs.value.toMutableList()
        if (targetFav) {
            if (!currentList.any { it.id == song.id }) {
                currentList.add(0, song)
            }
        } else {
            currentList.removeAll { it.id == song.id }
        }

        _favoriteSongs.value = currentList
        saveFavorites()
        return targetFav
    }

    fun setFavoriteSongs(songs: List<SongItem>) {
        _favoriteSongs.value = songs
        saveFavorites()
    }

    private fun saveFavorites() {
        prefs?.edit()?.putString(KEY_FAVORITE_SONGS, gson.toJson(_favoriteSongs.value))?.apply()
    }

    // ── 最近播放 ───────────────────────────────────────────────────

    fun addRecentSong(song: SongItem) {
        val currentList = _recentSongs.value.toMutableList()
        currentList.removeAll { it.id == song.id }
        currentList.add(0, song)
        if (currentList.size > MAX_RECENT_COUNT) {
            currentList.removeAt(currentList.lastIndex)
        }
        _recentSongs.value = currentList
        saveRecent()
    }

    private fun saveRecent() {
        prefs?.edit()?.putString(KEY_RECENT_SONGS, gson.toJson(_recentSongs.value))?.apply()
    }

    fun clearRecent() {
        _recentSongs.value = emptyList()
        prefs?.edit()?.remove(KEY_RECENT_SONGS)?.apply()
    }
}
