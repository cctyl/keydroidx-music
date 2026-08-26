package io.github.cctyl.keydroidx.music.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.util.Log
import io.github.cctyl.keydroidx.music.player.PlaybackStateManager

/**
 * 暴露当前播放状态给外部（Launcher 组件、系统 AppWidget 等）。
 *
 * Authority: io.github.cctyl.keydroidx.music.playback
 * URI: content://io.github.cctyl.keydroidx.music.playback/state
 *
 * 列定义：
 * - song_id       : Long   歌曲 ID
 * - title         : String 歌曲标题
 * - artist        : String 歌手名
 * - album_art_uri : String 专辑封面图片 URL
 * - is_playing    : Int    0/1 是否正在播放
 * - position_ms   : Long   当前播放位置 (ms)
 * - duration_ms   : Long   歌曲总时长 (ms)
 * - lyric_text    : String 当前歌词行文本（无歌词时为空）
 * - updated_at    : Long   数据更新时间戳 (ms)
 */
class PlaybackProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "io.github.cctyl.keydroidx.music.playback"
        const val PATH_STATE = "state"
        const val CONTENT_URI = "content://$AUTHORITY/$PATH_STATE"
        const val CONTENT_TYPE = "vnd.android.cursor.item/vnd.keydroidx.music.playback"

        private const val URI_STATE = 1
        private val uriMatcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(AUTHORITY, PATH_STATE, URI_STATE)
        }

        private const val TAG = "PlaybackProvider"

        private val DEFAULT_PROJECTION = arrayOf(
            "song_id", "title", "artist", "album_art_uri",
            "is_playing", "position_ms", "duration_ms",
            "lyric_text", "updated_at"
        )
    }

    private lateinit var context: Context

    override fun onCreate(): Boolean {
        context = requireContext()
        Log.d(TAG, "PlaybackProvider onCreate")
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        if (uriMatcher.match(uri) != URI_STATE) {
            return null
        }

        // 从 PlaybackStateManager 读取最新状态（主线程同步读取 StateFlow value，极快）
        val song = PlaybackStateManager.currentSong.value
        val isPlaying = PlaybackStateManager.isPlaying.value
        val positionMs = PlaybackStateManager.currentPositionMs.value
        val durationMs = PlaybackStateManager.durationMs.value

        // 当前歌词行（同步读取 StateFlow，无 I/O）
        val lyricLine = PlaybackStateManager.getCurrentLyricLineSync()

        val columns = projection ?: DEFAULT_PROJECTION

        val matrix = MatrixCursor(columns).apply {
            addRow(columns.map { column ->
                when (column) {
                    "song_id" -> song?.id?.toString() ?: ""
                    "title" -> song?.name ?: ""
                    "artist" -> song?.artistName ?: ""
                    "album_art_uri" -> song?.album?.picUrl ?: ""
                    "is_playing" -> if (isPlaying) "1" else "0"
                    "position_ms" -> positionMs.toString()
                    "duration_ms" -> durationMs.toString()
                    "lyric_text" -> lyricLine ?: ""
                    "updated_at" -> System.currentTimeMillis().toString()
                    else -> ""
                }
            }.toTypedArray())
        }

        matrix.setNotificationUri(context.contentResolver, uri)
        return matrix
    }

    override fun getType(uri: Uri): String? {
        return if (uriMatcher.match(uri) == URI_STATE) CONTENT_TYPE else null
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}