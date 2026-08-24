package io.github.cctyl.keydroidx.music.network

import android.util.Log
import io.github.cctyl.keydroidx.music.network.model.AlbumItem
import io.github.cctyl.keydroidx.music.network.model.ArtistItem
import io.github.cctyl.keydroidx.music.network.model.SongItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

object PlaylistApi {
    private const val TAG = "PlaylistApi"
    private const val USER_PLAYLIST_PATH = "/eapi/user/playlist"
    private const val PLAYLIST_DETAIL_PATH = "/eapi/v6/playlist/detail"
    private const val ACCOUNT_GET_PATH = "/eapi/w/nuser/account/get"

    /**
     * 获取当前登录用户的 UID
     */
    suspend fun getCurrentUserId(): Long = withContext(Dispatchers.IO) {
        val payload = emptyMap<String, String>()
        val response = RetrofitClient.eapiPost(ACCOUNT_GET_PATH, payload)
        val body = response.body?.string() ?: throw Exception("empty response")
        val json = JSONObject(body)
        val account = json.optJSONObject("account")
            ?: json.optJSONObject("profile")
            ?: throw Exception("no account data, body=$body")
        val userId = account.optLong("id", 0)
        if (userId == 0L) throw Exception("UID not found in: $body")
        userId
    }

    data class UserProfile(
        val userId: Long,
        val nickname: String,
        val avatarUrl: String,
        val signature: String = "",      // 个性签名
        val level: Int = 0,              // 等级
        val followeds: Long = 0,         // 粉丝数
        val follows: Long = 0,           // 关注数
        val playlistCount: Long = 0,     // 创建的歌单数
        val vipType: Int = 0             // 0=无 11=黑胶VIP
    )

    suspend fun getUserProfile(): UserProfile = withContext(Dispatchers.IO) {
        val payload = emptyMap<String, String>()
        val response = RetrofitClient.eapiPost(ACCOUNT_GET_PATH, payload)
        val body = response.body?.string() ?: throw Exception("empty response")
        val json = JSONObject(body)
        Log.d(TAG, "account/get response code=${json.optInt("code", -1)}")

        val account = json.optJSONObject("account")
        val profile = json.optJSONObject("profile")

        val base = UserProfile(
            userId = profile?.optLong("userId", account?.optLong("id", 0) ?: 0)
                ?: account?.optLong("id", 0) ?: 0,
            nickname = profile?.optString("nickname", "")?.ifEmpty { "用户" }
                ?: account?.optString("userName", "")?.ifEmpty { "用户" }
                ?: "用户",
            avatarUrl = profile?.optString("avatarUrl", "")
                ?: account?.optString("avatarUrl", "") ?: "",
            signature = profile?.optString("signature", "") ?: "",
            vipType = profile?.optInt("vipType", 0) ?: 0
        )

        // account/get 不含 level/粉丝/歌单数 —— 从 user/detail 补齐
        return@withContext mergeUserDetail(base)
    }

    /**
     * 拉 GET /api/v1/user/detail/{uid}（与 ncrust 同源接口），
     * 把 level / 粉丝 / 关注 / 歌单数合并进 UserProfile。
     * 失败不影响主流程（保持 account/get 的结果）。
     */
    private suspend fun mergeUserDetail(base: UserProfile): UserProfile = withContext(Dispatchers.IO) {
        try {
            val body = RetrofitClient.get("/api/v1/user/detail/${base.userId}").body?.string() ?: return@withContext base
            val json = JSONObject(body)
            if (json.optInt("code", -1) != 200) {
                Log.w(TAG, "user/detail code=${json.optInt("code", -1)}")
                return@withContext base
            }
            val p = json.optJSONObject("profile")
            base.copy(
                level = json.optInt("level", 0),
                followeds = p?.optLong("followeds", 0) ?: 0,
                follows = p?.optLong("follows", 0) ?: 0,
                playlistCount = p?.optLong("playlistCount", 0) ?: 0,
                signature = base.signature.ifBlank { p?.optString("signature", "") ?: "" }
            )
        } catch (e: Exception) {
            Log.w(TAG, "mergeUserDetail failed: ${e.message}")
            base
        }
    }

    suspend fun getUserPlaylists(uid: Long, limit: Int = 100, offset: Int = 0): UserPlaylistResult = withContext(Dispatchers.IO) {
        val payload = mapOf(
            "uid" to uid.toString(),
            "limit" to limit.toString(),
            "offset" to offset.toString(),
            "includeVideo" to "false"
        )
        val response = RetrofitClient.eapiPost(USER_PLAYLIST_PATH, payload)
        val body = response.body?.string() ?: throw Exception("empty response")
        val json = JSONObject(body)
        val code = json.optInt("code", -1)
        if (code != 200) throw Exception("API error: code=$code")

        val playlists = mutableListOf<PlaylistInfo>()
        val playlistArray = json.optJSONArray("playlist") ?: JSONArray()
        for (i in 0 until playlistArray.length()) {
            val item = playlistArray.getJSONObject(i)
            playlists.add(
                PlaylistInfo(
                    id = item.optLong("id"),
                    name = item.optString("name"),
                    coverImgUrl = item.optString("coverImgUrl"),
                    trackCount = item.optInt("trackCount"),
                    creatorUserId = item.optJSONObject("creator")?.optLong("userId") ?: 0,
                    specialType = item.optInt("specialType"),
                    privacy = item.optInt("privacy")
                )
            )
        }

        UserPlaylistResult(
            playlists = playlists,
            total = json.optInt("total", 0),
            more = json.optBoolean("more", false)
        )
    }

    suspend fun getPlaylistDetail(playlistId: Long): List<SongItem> = withContext(Dispatchers.IO) {
        val payload = mapOf(
            "id" to playlistId.toString(),
            "n" to "1000",
            "s" to "0"
        )
        val response = RetrofitClient.eapiPost(PLAYLIST_DETAIL_PATH, payload)
        val body = response.body?.string() ?: throw Exception("empty response")
        val json = JSONObject(body)
        val code = json.optInt("code", -1)
        if (code != 200) throw Exception("API error: code=$code")

        val playlistObj = json.optJSONObject("playlist") ?: return@withContext emptyList()

        // 解析权限表 (privileges)
        val privilegeMap = mutableMapOf<Long, Boolean>() // songId -> noCopyright
        val privilegesArray = playlistObj.optJSONArray("privileges") ?: json.optJSONArray("privileges")
        if (privilegesArray != null) {
            for (i in 0 until privilegesArray.length()) {
                val p = privilegesArray.getJSONObject(i)
                val id = p.optLong("id")
                val st = p.optInt("st", 0)
                // st < 0 表示无版权或已下架
                privilegeMap[id] = (st < 0)
            }
        }

        val tracksMap = mutableMapOf<Long, SongItem>()
        val trackArray = playlistObj.optJSONArray("tracks")
        if (trackArray != null) {
            for (i in 0 until trackArray.length()) {
                val song = parseSongTrack(trackArray.getJSONObject(i), privilegeMap)
                tracksMap[song.id] = song
            }
        }

        val allIds = mutableListOf<Long>()
        val trackIdsArray = playlistObj.optJSONArray("trackIds")
        if (trackIdsArray != null) {
            for (i in 0 until trackIdsArray.length()) {
                allIds.add(trackIdsArray.getJSONObject(i).optLong("id"))
            }
        }

        if (allIds.isEmpty()) return@withContext tracksMap.values.toList()

        val missingIds = allIds.filter { it !in tracksMap }
        val batchSize = 500
        for (start in missingIds.indices step batchSize) {
            val batch = missingIds.subList(start, minOf(start + batchSize, missingIds.size))
            val fetched = fetchSongDetails(batch)
            if (fetched.isEmpty() && batch.isNotEmpty()) {
                Log.w(TAG, "batch of ${batch.size} songs returned empty from server")
            }
            fetched.forEach { tracksMap[it.id] = it }
        }

        allIds.mapNotNull { tracksMap[it] }
    }

    private fun parseSongTrack(track: JSONObject, privilegeMap: Map<Long, Boolean>? = null): SongItem {
        val songId = track.optLong("id")
        val artistArray = track.optJSONArray("ar")
        val artists: List<ArtistItem>? = artistArray?.let {
            (0 until it.length()).map { j ->
                ArtistItem(name = it.getJSONObject(j).optString("name"))
            }
        }
        val albumJson = track.optJSONObject("al")
        val album: AlbumItem? = albumJson?.let {
            AlbumItem(id = it.optLong("id"), name = it.optString("name"), picUrl = it.optString("picUrl"))
        }
        val st = track.optInt("st", 0)
        val noCopyright = privilegeMap?.get(songId) ?: (st < 0)
        return SongItem(
            id = songId,
            name = track.optString("name"),
            artists = artists,
            album = album,
            duration = track.optLong("dt"),
            fee = track.optInt("fee", 0),
            noCopyright = noCopyright
        )
    }

    private suspend fun fetchSongDetails(ids: List<Long>): List<SongItem> = withContext(Dispatchers.IO) {
        val cArray = JSONArray()
        ids.forEach { id -> cArray.put(JSONObject().put("id", id)) }
        val payload = mapOf("c" to cArray.toString())
        var lastError: Exception? = null
        repeat(2) { attempt ->
            try {
                val response = RetrofitClient.eapiPost("/eapi/v3/song/detail", payload)
                val body = response.body?.string() ?: return@repeat
                val respJson = JSONObject(body)
                val songArray = respJson.optJSONArray("songs") ?: return@repeat
                val privilegesArray = respJson.optJSONArray("privileges")
                val privMap = mutableMapOf<Long, Boolean>()
                if (privilegesArray != null) {
                    for (i in 0 until privilegesArray.length()) {
                        val p = privilegesArray.getJSONObject(i)
                        val id = p.optLong("id")
                        val st = p.optInt("st", 0)
                        privMap[id] = (st < 0)
                    }
                }
                return@withContext (0 until songArray.length()).map { i ->
                    parseSongTrack(songArray.getJSONObject(i), privMap)
                }
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "fetchSongDetails attempt ${attempt + 1} failed: ${e.message}")
                if (attempt == 0) delay(500)
            }
        }
        lastError?.let { Log.e(TAG, "fetchSongDetails gave up after retry", it) }
        emptyList()
    }

    data class PlaylistInfo(
        val id: Long,
        val name: String,
        val coverImgUrl: String,
        val trackCount: Int,
        val creatorUserId: Long,
        val specialType: Int,
        val privacy: Int
    )

    data class UserPlaylistResult(
        val playlists: List<PlaylistInfo>,
        val total: Int,
        val more: Boolean
    )

    data class PlaylistCard(
        val id: Long,
        val name: String,
        val coverUrl: String,
        val playCount: Long = 0,
        val trackCount: Int = 0,
        val copywriter: String = ""
    )

    suspend fun getDailyRecommendSongs(): List<SongItem> = withContext(Dispatchers.IO) {
        val response = RetrofitClient.eapiPost("/eapi/v2/discovery/recommend/songs", emptyMap())
        val body = response.body?.string() ?: throw Exception("empty response")
        val json = JSONObject(body)
        val arr = json.optJSONArray("recommend") ?: json.optJSONArray("data")
            ?: return@withContext emptyList()
        (0 until arr.length()).map { i ->
            val s = arr.getJSONObject(i)
            SongItem(
                id = s.optLong("id"),
                name = s.optString("name"),
                artists = s.optJSONArray("artists")?.let { ar ->
                    (0 until ar.length()).map { j ->
                        ArtistItem(name = ar.getJSONObject(j).optString("name"))
                    }
                },
                album = s.optJSONObject("album")?.let {
                    AlbumItem(id = it.optLong("id"), name = it.optString("name"), picUrl = it.optString("picUrl"))
                },
                duration = s.optLong("duration").takeIf { it != 0L }
            )
        }
    }

    suspend fun getRecommendPlaylists(): List<PlaylistCard> = withContext(Dispatchers.IO) {
        val cookie = RetrofitClient.getCookie()
        if (!cookie.isNullOrBlank()) {
            try {
                val response = RetrofitClient.eapiPost("/eapi/v1/discovery/recommend/resource", emptyMap())
                val body = response.body?.string() ?: ""
                val arr = JSONObject(body).optJSONArray("recommend")
                if (arr != null && arr.length() > 0) {
                    return@withContext (0 until minOf(arr.length(), 10)).map { i ->
                        val item = arr.getJSONObject(i)
                        PlaylistCard(
                            id = item.optLong("id"),
                            name = item.optString("name"),
                            coverUrl = item.optString("picUrl"),
                            playCount = item.optLong("playCount"),
                            trackCount = if (item.optString("name") == "私人雷达") 35 else item.optInt("trackCount"),
                            copywriter = item.optString("copywriter")
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "getRecommendPlaylists eapi failed, fallback to personalized: ${e.message}")
            }
        }
        // 未登录或 eapi 失败时：平滑回退到公开推荐歌单接口
        try {
            val response = RetrofitClient.get("/api/personalized/playlist?limit=10")
            val body = response.body?.string() ?: ""
            val arr = JSONObject(body).optJSONArray("result") ?: return@withContext emptyList()
            (0 until minOf(arr.length(), 10)).map { i ->
                val item = arr.getJSONObject(i)
                PlaylistCard(
                    id = item.optLong("id"),
                    name = item.optString("name"),
                    coverUrl = item.optString("picUrl"),
                    playCount = item.optLong("playCount"),
                    trackCount = item.optInt("trackCount"),
                    copywriter = item.optString("copywriter")
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "getRecommendPlaylists personalized fallback error: ${e.message}", e)
            emptyList()
        }
    }

    suspend fun getPersonalFm(): List<SongItem> = withContext(Dispatchers.IO) {
        val response = RetrofitClient.eapiPost("/eapi/v1/radio/get", emptyMap())
        val body = response.body?.string() ?: throw Exception("empty response")
        val arr = JSONObject(body).optJSONArray("data") ?: return@withContext emptyList()
        (0 until arr.length()).map { i ->
            val s = arr.getJSONObject(i)
            SongItem(
                id = s.optLong("id"),
                name = s.optString("name"),
                artists = s.optJSONArray("ar")?.let { ar ->
                    (0 until ar.length()).map { j ->
                        ArtistItem(name = ar.getJSONObject(j).optString("name"))
                    }
                },
                album = s.optJSONObject("al")?.let {
                    AlbumItem(id = it.optLong("id"), name = it.optString("name"), picUrl = it.optString("picUrl"))
                },
                duration = s.optLong("dt").takeIf { it != 0L }
            )
        }
    }

    suspend fun likeSong(songId: Long, like: Boolean): Boolean = withContext(Dispatchers.IO) {
        val payload = mapOf(
            "trackId" to songId.toString(),
            "like" to like.toString(),
            "time" to "3"
        )
        val response = RetrofitClient.eapiPost("/eapi/song/like", payload)
        val body = response.body?.string() ?: return@withContext false
        JSONObject(body).optInt("code", -1) == 200
    }
}
