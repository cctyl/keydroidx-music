package io.github.cctyl.keydroidx.music.library

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.github.cctyl.keydroidx.music.network.model.ArtistItem
import io.github.cctyl.keydroidx.music.network.model.SongItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 歌曲收藏与本地音乐库管理
 *
 * 注：收藏（红心）已整体下沉到 [FavoriteStore]（O(1) 查询 + 轻量持久化 + 云端回填）。
 * 本类中所有收藏相关 API 均改为委托到 FavoriteStore，仅保留签名以兼容既有调用点。
 */
object LibraryManager {
    private const val PREFS_NAME = "keydroidx_music_library"
    private const val KEY_RECENT_SONGS = "recent_songs"
    private const val MAX_RECENT_COUNT = 100

    private val gson = Gson()
    private var prefs: SharedPreferences? = null

    /**
     * 收藏数据已由 [FavoriteStore] 统一托管（O(1) 查询 + 轻量持久化 + 云端回填）。
     * 这里仅为旧调用点保留一份**只读镜像**：持续订阅 FavoriteStore 并转成 SongItem。
     * 见 #收藏兼容镜像
     */
    private val _favoriteSongs = MutableStateFlow<List<SongItem>>(emptyList())
    @Deprecated("改用 FavoriteStore.favoriteSongs（轻量 Entry，O(1) 查询）")
    val favoriteSongs: StateFlow<List<SongItem>> = _favoriteSongs.asStateFlow()

    private val _recentSongs = MutableStateFlow<List<SongItem>>(emptyList())
    val recentSongs: StateFlow<List<SongItem>> = _recentSongs.asStateFlow()

    /** 驱动收藏镜像的独立作用域；Main.immediate 保证 StateFlow 当前值同步送达，启动即可见 */
    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            loadFromPrefs()
            startFavoriteMirror()
        }
    }

    private fun startFavoriteMirror() {
        syncScope.launch {
            FavoriteStore.favoriteSongs.collect { entries ->
                _favoriteSongs.value = entries.map {
                    SongItem(
                        id = it.id,
                        name = it.name,
                        artists = listOfNotNull(it.artist.takeIf { a -> a.isNotBlank() }?.let { a -> ArtistItem(name = a) }),
                        album = null,
                        duration = null
                    )
                }
            }
        }
    }

    private fun loadFromPrefs() {
        val sp = prefs ?: return
        // 收藏数据不再从这里读取（FavoriteStore.init 会迁移旧 fav_songs 并通过镜像回流），
        // 避免同时存在两个事实源导致状态打架。
        val recJson = sp.getString(KEY_RECENT_SONGS, null)
        if (!recJson.isNullOrBlank()) {
            val type = object : TypeToken<List<SongItem>>() {}.type
            _recentSongs.value = runCatching { gson.fromJson<List<SongItem>>(recJson, type) }.getOrDefault(emptyList())
        }
    }

    // ── 收藏管理 ───────────────────────────────────────────────────

    @Deprecated("改用 FavoriteStore.isFavorite（O(1) 哈希查询）")
    fun isFavorite(songId: Long): Boolean {
        return FavoriteStore.isFavorite(songId)
    }

    @Deprecated("改用 FavoriteStore.toggle（单一事实源）")
    suspend fun toggleFavorite(context: Context, song: SongItem): Boolean {
        return FavoriteStore.toggle(context, FavoriteStore.Entry(song.id, song.name, song.artistName))
    }

    @Deprecated("改用 FavoriteStore.seedEntries（云端为权威来源）")
    fun setFavoriteSongs(songs: List<SongItem>) {
        FavoriteStore.seedEntries(
            songs.map { FavoriteStore.Entry(it.id, it.name, it.artistName) },
            replace = true
        )
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

    fun removeRecentSong(songId: Long) {
        val currentList = _recentSongs.value.toMutableList()
        if (currentList.removeAll { it.id == songId }) {
            _recentSongs.value = currentList
            saveRecent()
        }
    }

    private fun saveRecent() {
        prefs?.edit()?.putString(KEY_RECENT_SONGS, gson.toJson(_recentSongs.value))?.apply()
    }

    fun clearRecent() {
        _recentSongs.value = emptyList()
        prefs?.edit()?.remove(KEY_RECENT_SONGS)?.apply()
    }
}
