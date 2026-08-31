package io.github.cctyl.keydroidx.music.network

import io.github.cctyl.keydroidx.music.util.NLog as Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 网易云「歌曲评论」接口封装。
 *
 * 接口：`/api/v1/resource/comments/R_SO_4_{songId}`
 *
 * 与 [PlaylistApi] 完全同范式（eapi 加密 + `org.json` 手解析），并额外做一次
 * **普通 GET 兜底**：eapi 加密通道对评论接口存在返回非 200 的情况，
 * 降级思路与 `player/SongUrlFetcher` 的 interface3 / music.163 双主机回退一致。
 *
 * 两条通道都失败时抛异常，由调用方决定降级表现（播放页隐藏数字、评论页展示失败提示），
 * **绝不阻断播放主流程**。
 */
object CommentApi {
    private const val TAG = "CommentApi"

    /** eapi 加密通道路径前缀（EapiCrypto 内部会把 /eapi/ 还原成 /api/ 参与签名） */
    private const val EAPI_PATH_PREFIX = "/eapi/v1/resource/comments/"

    /** 普通 GET 兜底路径前缀（RetrofitClient.get 会自行拼上 BASE_URL，故不带前导斜杠） */
    private const val PLAIN_PATH_PREFIX = "api/v1/resource/comments/"

    /** 单条评论 */
    data class Comment(
        val id: Long,
        val content: String,
        val timeMs: Long,
        val likedCount: Int,
        val nickname: String,
        val avatarUrl: String
    )

    /** 一页评论数据 */
    data class CommentPage(
        /** 评论总数（用于 999+ 截断） */
        val total: Int,
        /** 热门评论，仅 offset == 0 时非空 */
        val hot: List<Comment>,
        /** 最新评论（本次请求返回的部分） */
        val comments: List<Comment>,
        /** 服务端是否还有更多 */
        val hasMore: Boolean
    )

    /**
     * 拉取歌曲评论。
     *
     * @param songId 网易云歌曲 ID
     * @param offset 分页偏移，0 表示首页（首页会一并返回热门评论）
     * @param limit  每页条数
     * @throws IllegalStateException 两条通道都失败时抛出
     */
    suspend fun getSongComments(
        songId: Long,
        offset: Int = 0,
        limit: Int = 20
    ): CommentPage = withContext(Dispatchers.IO) {
        val resourceId = "R_SO_4_$songId"
        val payload = mapOf(
            "rid" to resourceId,
            "threadId" to resourceId,
            "offset" to offset.toString(),
            "limit" to limit.toString(),
            "total" to "true",
            "csrf_token" to ""
        )

        var lastError: String? = null

        // ── 通道一：eapi 加密通道（与歌单 / 取链同源） ──
        try {
            val body = RetrofitClient
                .eapiPost("$EAPI_PATH_PREFIX$resourceId", payload)
                .body()?.string().orEmpty()
            Log.d(TAG, "comments via eapi: songId=$songId offset=$offset")
            return@withContext parseCommentPage(body, offset)
        } catch (e: Exception) {
            lastError = "eapi: ${e.message}"
            Log.w(TAG, "comments eapi channel failed songId=$songId: ${e.message}")
        }

        // ── 通道二：普通 GET 兜底 ──
        try {
            val path = "$PLAIN_PATH_PREFIX$resourceId?offset=$offset&limit=$limit&total=true"
            val body = RetrofitClient.get(path).body()?.string().orEmpty()
            Log.d(TAG, "comments via plain GET: songId=$songId offset=$offset")
            return@withContext parseCommentPage(body, offset)
        } catch (e: Exception) {
            Log.w(TAG, "comments plain channel failed songId=$songId: ${e.message}")
        }

        throw IllegalStateException(lastError ?: "comments unavailable")
    }

    /** 解析评论分页响应；code != 200 时抛异常驱动调用方降级。 */
    private fun parseCommentPage(body: String, offset: Int): CommentPage {
        if (body.isBlank()) throw IllegalStateException("empty response")
        val json = JSONObject(body)
        val code = json.optInt("code", -1)
        if (code != 200) {
            throw IllegalStateException("code=$code body=${body.take(120)}")
        }
        val total = json.optInt("total", 0)
        val hot = parseList(json.optJSONArray("hotComments"), offset == 0)
        val list = parseList(json.optJSONArray("comments"), true)
        val hasMore = json.optBoolean("more", false) ||
            (list.isNotEmpty() && offset + list.size < total)
        return CommentPage(total = total, hot = hot, comments = list, hasMore = hasMore)
    }

    private fun parseList(arr: JSONArray?, enabled: Boolean): List<Comment> {
        if (arr == null || !enabled) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val user = o.optJSONObject("user")
            Comment(
                id = o.optLong("commentId", 0L),
                content = o.optString("content").orEmpty(),
                timeMs = o.optLong("time", 0L),
                likedCount = o.optInt("likedCount", 0),
                nickname = user?.optString("nickname").orEmpty().ifBlank { "匿名用户" },
                avatarUrl = user?.optString("avatarUrl").orEmpty()
            )
        }
    }

    /**
     * 评论数展示格式化：超过 999 统一显示 `999+`，避免窄屏溢出。
     */
    fun formatCount(total: Int): String = if (total > 999) "999+" else total.toString()
}
