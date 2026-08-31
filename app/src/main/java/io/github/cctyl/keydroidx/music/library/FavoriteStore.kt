package io.github.cctyl.keydroidx.music.library

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.github.cctyl.keydroidx.music.auth.CookieManager
import io.github.cctyl.keydroidx.music.auth.UserProfileCache
import io.github.cctyl.keydroidx.music.network.PlaylistApi
import io.github.cctyl.keydroidx.music.network.model.SongItem
import io.github.cctyl.keydroidx.music.util.NLog as Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * 全局收藏唯一事实源。
 *
 * 设计要点：
 * 1. **O(1) 查询**：内部用 `LinkedHashMap<Long, Entry>` 保序索引，对外派生不可变 `Set<Long>` 快照。
 *    旧实现 `LibraryManager.isFavorite()` 是 `List.any { }`，渲染 n 行即 O(n²)；这里查表恒定 O(1)，
 *    且查询全程只读内存快照，绝不触发磁盘 I/O 或网络。
 * 2. **双 Flow 分层**：`favoriteIds`（轻量快照，高频订阅 → 驱动红心局部重绘）与
 *    `favoriteSongs`（完整条目，低频订阅 → 驱动计数与离线列表），避免切一次收藏就推整份列表。
 * 3. **轻量持久化**：只存 `[{i:id,n:name,a:artist}]`，替代旧版整份 `List<SongItem>` 的 Gson JSON
 *    （含 artists/album/duration 等，几百首时单次 apply 写盘量很大）。
 * 4. **云端回填**：进入「我喜欢的音乐」时 seed 全量 id，保证该歌单内永远全红心；
 *    冷启动再由 `syncFromCloud()` 静默预拉，使「最近播放」一进来红心就正确。
 */
object FavoriteStore {

    private const val TAG = "FavoriteStore"

    /** 与 LibraryManager 共用同一份 SP，便于启动时迁移旧数据 */
    private const val PREFS_NAME = "keydroidx_music_library"
    private const val KEY_INDEX = "fav_index_v2"
    /** 旧版键（LibraryManager 写入的整份 SongItem JSON），仅作迁移源，永不使用 */
    private const val KEY_LEGACY_SONGS = "fav_songs"
    private const val KEY_MIGRATED = "fav_migrated"
    private const val KEY_PLAYLIST_ID = "fav_playlist_id"
    private const val KEY_SYNC_MS = "fav_sync_ms"
    private const val KEY_CLOUD_SEEDED = "fav_cloud_seeded"

    /** 云端同步节流窗口：10 分钟内不重复预拉 */
    private const val SYNC_THROTTLE_MS = 10 * 60 * 1000L

    /** 轻量收藏条目：只保留列表展示与计数所需的最小字段 */
    data class Entry(val id: Long, val name: String, val artist: String)

    /**
     * 索引是否已完成过一次云端全量回填。
     *
     * 置位后索引即视为**权威且实时**的数据源：它是云端快照 + 本地 toggle 增量的合集，
     * 因此各处计数应直接取 [size()]，而不是再去用 `getUserPlaylists` 那刻的 trackCount 快照
     * （否则收藏后计数会停留在旧值）。未置位（离线/同步尚未成功）时才需要退回云端快照。
     */
    @Volatile
    private var cloudSeeded = false
    fun isCloudSeeded(): Boolean = cloudSeeded

    // ── 内存索引（LinkedHashMap 保序，供「我喜欢的音乐」离线列表与计数使用）──
    private val lock = Any()
    private val index = LinkedHashMap<Long, Entry>()

    private var prefs: SharedPreferences? = null

    /** 单线程串行写盘：保证连续多次 toggle 的落盘顺序与提交顺序一致，不会出现旧值覆盖新值 */
    private val writeDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val writeScope = CoroutineScope(SupervisorJob() + writeDispatcher)

    private val _favoriteIds = MutableStateFlow<Set<Long>>(emptySet())
    /** 高频订阅：红心重绘。每次变更产出新的不可变快照 */
    val favoriteIds: StateFlow<Set<Long>> = _favoriteIds.asStateFlow()

    private val _favoriteSongs = MutableStateFlow<List<Entry>>(emptyList())
    /** 低频订阅：我的 Tab 计数 / 离线「我喜欢的音乐」列表 */
    val favoriteSongs: StateFlow<List<Entry>> = _favoriteSongs.asStateFlow()

    // ══════════════════════════════════════════════════════════
    //  初始化与持久化
    // ══════════════════════════════════════════════════════════

    fun init(context: Context) {
        if (prefs != null) return
        val sp = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = sp

        val loaded = if (sp.contains(KEY_INDEX)) {
            readIndex(sp.getString(KEY_INDEX, null))
        } else {
            migrateLegacy(sp)
        }
        synchronized(lock) {
            index.clear()
            loaded.forEach { index[it.id] = it }
        }
        // 兼容升级：KEY_CLOUD_SEEDED 是后加的标记，老用户从未写过。
        // 若历史上成功同步过一次（fav_sync_ms > 0），索引同样源自云端全量，视作已对齐，
        // 避免升级后计数因等不到下一次同步（受 10 分钟节流）而继续滞留旧值。
        cloudSeeded = sp.getBoolean(KEY_CLOUD_SEEDED, false) || sp.getLong(KEY_SYNC_MS, 0L) > 0L
        publish()
        Log.d(TAG, "init: ${index.size} favorite songs loaded, cloudSeeded=$cloudSeeded")
    }

    /**
     * 迁移旧版 `fav_songs`（整份 SongItem JSON）到轻量索引。
     * 旧键**保留不删**：LibraryManager 与其它调用点仍可能持有旧内存态，删除会造成收藏「消失」。
     * 用 `fav_migrated` 标记防重复迁移；即使旧数据为空也要落标记，避免每次启动都做一次 Gson 解析。
     */
    private fun migrateLegacy(sp: SharedPreferences): List<Entry> {
        val legacyJson = sp.getString(KEY_LEGACY_SONGS, null)
        val entries = if (!legacyJson.isNullOrBlank()) {
            val type = object : TypeToken<List<SongItem>>() {}.type
            val songs: List<SongItem> = try {
                Gson().fromJson<List<SongItem>>(legacyJson, type) ?: emptyList()
            } catch (e: Exception) {
                Log.w(TAG, "parse legacy fav_songs failed: ${e.message}")
                emptyList()
            }
            songs.map { Entry(id = it.id, name = it.name, artist = it.artistName) }
        } else {
            emptyList()
        }
        sp.edit()
            .putString(KEY_INDEX, writeIndex(entries))
            .putBoolean(KEY_MIGRATED, true)
            .apply()
        Log.d(TAG, "migrate legacy fav_songs -> fav_index_v2: ${entries.size} songs")
        return entries
    }

    private fun readIndex(json: String?): List<Entry> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val id = o.optLong("i", 0L)
                if (id == 0L) null
                else Entry(id = id, name = o.optString("n", ""), artist = o.optString("a", ""))
            }
        }.getOrElse {
            Log.w(TAG, "readIndex failed: ${it.message}")
            emptyList()
        }
    }

    private fun writeIndex(entries: List<Entry>): String {
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(JSONObject().apply {
                put("i", e.id)
                put("n", e.name)
                put("a", e.artist)
            })
        }
        return arr.toString()
    }

    /** 立即刷出两条 Flow 的不可变快照（在锁外赋值，避免订阅者回调里再入锁） */
    private fun publish() {
        val (snapshot, ids) = synchronized(lock) {
            index.values.toList() to index.keys.toSet()
        }
        _favoriteSongs.value = snapshot
        _favoriteIds.value = ids
    }

    private fun saveAsync() {
        writeScope.launch {
            val snapshot = synchronized(lock) { index.values.toList() }
            val json = writeIndex(snapshot)
            prefs?.edit()?.putString(KEY_INDEX, json)?.apply()
        }
    }

    // ══════════════════════════════════════════════════════════
    //  查询（O(1)，纯内存）
    // ══════════════════════════════════════════════════════════

    /** 收藏态查询：O(1) 哈希查找，与收藏总数无关；不触发磁盘 I/O 与网络 */
    fun isFavorite(songId: Long): Boolean = _favoriteIds.value.contains(songId)

    fun size(): Int = _favoriteIds.value.size

    // ══════════════════════════════════════════════════════════
    //  收藏 / 取消收藏
    // ══════════════════════════════════════════════════════════

    /**
     * 收藏 / 取消收藏：先同步云端，再更新本地索引并落盘。
     *
     * 采用**乐观本地更新**：云端 `likeSong` 失败时仍改本地并只记 `Log.w`。
     * 原因：按键机场景弱网/离线常见，若严格要求云端 200 才改本地，用户将完全无法收藏。
     *
     * @return 操作后的收藏态（true=已收藏）
     */
    suspend fun toggle(context: Context, song: Entry): Boolean {
        val target = !isFavorite(song.id)
        val cookie = CookieManager.getCookie(context)
        if (!cookie.isNullOrBlank()) {
            val ok = runCatching { PlaylistApi.likeSong(song.id, target) }.getOrElse {
                Log.w(TAG, "likeSong failed id=${song.id} -> $target: ${it.message}")
                false
            }
            if (!ok) Log.w(TAG, "cloud rejected like id=${song.id} -> $target, keep local optimistic")
        }
        applyLocal(target, song)
        return target
    }

    private fun applyLocal(fav: Boolean, song: Entry) {
        synchronized(lock) {
            if (fav) {
                val old = index[song.id]
                if (old == null) {
                    // 新收藏插到头部（与旧实现一致，最近收藏排在前面）
                    val merged = LinkedHashMap<Long, Entry>()
                    merged[song.id] = song
                    merged.putAll(index)
                    index.clear()
                    index.putAll(merged)
                } else if (old != song) {
                    // 已有则只补元数据，不动顺序
                    index[song.id] = song.copy(
                        name = song.name.ifBlank { old.name },
                        artist = song.artist.ifBlank { old.artist }
                    )
                } else {
                    return
                }
            } else {
                if (index.remove(song.id) == null) return
            }
        }
        publish()
        saveAsync()
    }

    // ══════════════════════════════════════════════════════════
    //  云端回填
    // ══════════════════════════════════════════════════════════

    /**
     * 回填 id 索引（无元数据版本，用于冷启动预拉）。
     * 已在索引中的 id 会保留原有 name/artist；新增 id 用空占位，后续进入歌单时由
     * [seedEntries] 补齐标题。
     *
     * @param replace true=以云端为准全量替换；false=增量并集（分页追加，避免覆盖用户在别处的取消收藏）
     */
    fun seed(ids: List<Long>, replace: Boolean) {
        if (replace && ids.isEmpty()) {
            // 防误清空：网络异常返回空列表时绝不替换，否则用户本地收藏会被整体抹掉
            Log.w(TAG, "seed(replace=true) ignored: empty id list")
            return
        }
        if (replace) markCloudSeeded()
        val changed = synchronized(lock) {
            if (replace) {
                val next = LinkedHashMap<Long, Entry>()
                ids.forEach { id -> next[id] = index[id] ?: Entry(id, "", "") }
                if (next.keys == index.keys && next.values == index.values) {
                    false
                } else {
                    index.clear()
                    index.putAll(next)
                    true
                }
            } else {
                var changed = false
                ids.forEach { id ->
                    if (!index.containsKey(id)) {
                        index[id] = Entry(id, "", "")
                        changed = true
                    }
                }
                changed
            }
        }
        if (changed) {
            publish()
            saveAsync()
            Log.d(TAG, "seed ids=${ids.size} replace=$replace -> index=${index.size}")
        }
    }

    /**
     * 回填带元数据的条目（进入「我喜欢的音乐」拿到完整曲目后调用，一次性把标题/艺术家补齐）。
     */
    fun seedEntries(entries: List<Entry>, replace: Boolean) {
        if (replace && entries.isEmpty()) {
            Log.w(TAG, "seedEntries(replace=true) ignored: empty list")
            return
        }
        if (replace) markCloudSeeded()
        val changed = synchronized(lock) {
            if (replace) {
                val next = LinkedHashMap<Long, Entry>()
                entries.forEach { e ->
                    val old = index[e.id]
                    next[e.id] = if (old != null) e.copy(
                        name = e.name.ifBlank { old.name },
                        artist = e.artist.ifBlank { old.artist }
                    ) else e
                }
                if (next.keys == index.keys && next.values == index.values) {
                    false
                } else {
                    index.clear()
                    index.putAll(next)
                    true
                }
            } else {
                var changed = false
                entries.forEach { e ->
                    if (!index.containsKey(e.id)) {
                        index[e.id] = e
                        changed = true
                    }
                }
                changed
            }
        }
        if (changed) {
            publish()
            saveAsync()
            Log.d(TAG, "seedEntries n=${entries.size} replace=$replace -> index=${index.size}")
        }
    }

    /** 标记索引已与云端对齐（幂等，仅首次落盘） */
    private fun markCloudSeeded() {
        if (cloudSeeded) return
        cloudSeeded = true
        prefs?.edit()?.putBoolean(KEY_CLOUD_SEEDED, true)?.apply()
    }

    /** 记住「我喜欢的音乐」的歌单 id，供冷启动预拉复用，省掉一次 getUserPlaylists */
    fun rememberFavPlaylistId(playlistId: Long) {
        if (playlistId <= 0L) return
        val sp = prefs ?: return
        if (sp.getLong(KEY_PLAYLIST_ID, 0L) != playlistId) {
            sp.edit().putLong(KEY_PLAYLIST_ID, playlistId).apply()
        }
    }

    /**
     * 冷启动 / 登录后静默预拉云端收藏。
     * 前置条件：已登录（有 cookie）且距上次成功同步超过 10 分钟。
     * 任何失败都静默降级，只保留本地索引，不影响播放与浏览。
     */
    suspend fun syncFromCloud(context: Context, force: Boolean = false) = withContext(Dispatchers.IO) {
        if (!CookieManager.hasCookie(context)) {
            Log.d(TAG, "syncFromCloud skipped: not logged in")
            return@withContext
        }
        val sp = prefs ?: context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        if (!force) {
            val last = sp.getLong(KEY_SYNC_MS, 0L)
            if (System.currentTimeMillis() - last < SYNC_THROTTLE_MS) {
                Log.d(TAG, "syncFromCloud skipped: throttled (last sync ${System.currentTimeMillis() - last}ms ago)")
                return@withContext
            }
        }

        // ① 优先用已记住的歌单 id；② 退到歌单列表缓存（零网络开销）；③ 最后才补拉
        var playlistId = sp.getLong(KEY_PLAYLIST_ID, 0L)
        if (playlistId == 0L) {
            playlistId = UserProfileCache.loadPlaylists(context)
                .firstOrNull { it.specialType == 5 }?.id ?: 0L
        }
        if (playlistId == 0L) {
            val uid = UserProfileCache.load(context)?.userId ?: 0L
            if (uid == 0L) {
                Log.d(TAG, "syncFromCloud skipped: no fav playlist id and no uid")
                return@withContext
            }
            playlistId = runCatching {
                PlaylistApi.getUserPlaylists(uid).playlists.firstOrNull { it.specialType == 5 }?.id ?: 0L
            }.getOrElse {
                Log.w(TAG, "getUserPlaylists failed: ${it.message}")
                0L
            }
        }
        if (playlistId == 0L) {
            Log.w(TAG, "syncFromCloud skipped: fav playlist not found")
            return@withContext
        }

        val ids = runCatching { PlaylistApi.getPlaylistTrackIds(playlistId) }.getOrElse {
            Log.w(TAG, "getPlaylistTrackIds failed: ${it.message}")
            emptyList()
        }
        if (ids.isEmpty()) {
            // 空结果一律不 replace，防止异常响应把本地收藏清空
            Log.w(TAG, "syncFromCloud aborted: empty track ids, keep local index")
            return@withContext
        }

        sp.edit()
            .putLong(KEY_PLAYLIST_ID, playlistId)
            .putLong(KEY_SYNC_MS, System.currentTimeMillis())
            .apply()
        seed(ids, replace = true)
        Log.d(TAG, "syncFromCloud done: playlist=$playlistId ids=${ids.size}")
    }
}
