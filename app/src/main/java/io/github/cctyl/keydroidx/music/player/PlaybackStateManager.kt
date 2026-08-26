package io.github.cctyl.keydroidx.music.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import io.github.cctyl.keydroidx.music.network.model.SongItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class PlaybackMode {
    LIST_LOOP,   // 列表循环
    SINGLE_LOOP, // 单曲循环
    RANDOM       // 随机播放
}

object PlaybackStateManager {
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentSong = MutableStateFlow<SongItem?>(null)
    val currentSong: StateFlow<SongItem?> = _currentSong.asStateFlow()

    private val _currentPositionMs = MutableStateFlow(0L)
    val currentPositionMs: StateFlow<Long> = _currentPositionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _playMode = MutableStateFlow(PlaybackMode.LIST_LOOP)
    val playMode: StateFlow<PlaybackMode> = _playMode.asStateFlow()

    private val _playlist = MutableStateFlow<List<SongItem>>(emptyList())
    val playlist: StateFlow<List<SongItem>> = _playlist.asStateFlow()

    private val _currentIndex = MutableStateFlow(-1)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    /** 私人 FM 模式：队列播到末尾时自动向服务端拉取下一批歌曲续上 */
    private val _isPersonalFm = MutableStateFlow(false)
    val isPersonalFm: StateFlow<Boolean> = _isPersonalFm.asStateFlow()

    /** 当前歌词行文本（供 Provider、Widget 读取） */
    private val _currentLyricLine = MutableStateFlow<String?>(null)
    val currentLyricLine: StateFlow<String?> = _currentLyricLine.asStateFlow()

    /** 用于发送广播的 Context（由 Application 或 Service 初始化） */
    private var broadcastContext: Context? = null
    private val mainHandler = android.os.Handler(Looper.getMainLooper())

    /** 初始化广播 Context（在 Application.onCreate 调用） */
    fun initBroadcastContext(context: Context) {
        broadcastContext = context.applicationContext
    }

    fun updatePlayingState(playing: Boolean) {
        _isPlaying.value = playing
        sendPlaybackChangedBroadcast()
    }

    fun updateCurrentSong(song: SongItem?) {
        _currentSong.value = song
        sendPlaybackChangedBroadcast()
    }

    fun updateProgress(pos: Long, dur: Long) {
        _currentPositionMs.value = pos
        _durationMs.value = dur
        // 进度变化高频，不每次都发广播；由 Provider 直接读取 value
    }

    fun updatePlayMode(mode: PlaybackMode) {
        _playMode.value = mode
        sendPlaybackChangedBroadcast()
    }

    fun updatePlaylist(list: List<SongItem>, index: Int) {
        _playlist.value = list
        _currentIndex.value = index
        if (index in list.indices) {
            _currentSong.value = list[index]
        }
        sendPlaybackChangedBroadcast()
    }

    fun setPersonalFm(enabled: Boolean) {
        _isPersonalFm.value = enabled
    }

    /** FM 续批：追加歌曲到队列末尾，返回追加后的队列大小 */
    fun appendPlaylist(list: List<SongItem>): Int {
        _playlist.value = _playlist.value + list
        return _playlist.value.size
    }

    fun togglePlayMode(): PlaybackMode {
        val next = when (_playMode.value) {
            PlaybackMode.LIST_LOOP -> PlaybackMode.SINGLE_LOOP
            PlaybackMode.SINGLE_LOOP -> PlaybackMode.RANDOM
            PlaybackMode.RANDOM -> PlaybackMode.LIST_LOOP
        }
        _playMode.value = next
        sendPlaybackChangedBroadcast()
        return next
    }

    /** 更新当前歌词行（由播放页/Service 在歌词解析后调用） */
    fun updateCurrentLyricLine(lyric: String?) {
        _currentLyricLine.value = lyric
        // 歌词行变化频率低，发广播通知 Widget 刷新
        sendPlaybackChangedBroadcast()
    }

    /** 获取当前歌词行（同步，供 Provider query 直接调用） */
    fun getCurrentLyricLineSync(): String? = _currentLyricLine.value

    /** 发送播放状态变化广播（Widget、Provider 监听刷新） */
    private fun sendPlaybackChangedBroadcast() {
        val ctx = broadcastContext ?: return
        mainHandler.post {
            val song = _currentSong.value
            val intent = Intent(ACTION_PLAYBACK_CHANGED).apply {
                putExtra(EXTRA_SONG_ID, song?.id?.toString() ?: "")
                putExtra(EXTRA_TITLE, song?.name ?: "")
                putExtra(EXTRA_ARTIST, song?.artistName ?: "")
                putExtra(EXTRA_ALBUM_ART, song?.album?.picUrl ?: "")
                putExtra(EXTRA_PLAYING, _isPlaying.value)
                putExtra(EXTRA_POSITION, _currentPositionMs.value)
                putExtra(EXTRA_DURATION, _durationMs.value)
                putExtra(EXTRA_LYRIC_LINE, _currentLyricLine.value)
                putExtra(EXTRA_PLAY_MODE, _playMode.value.ordinal)
                putExtra(EXTRA_PLAYLIST_SIZE, _playlist.value.size)
                putExtra(EXTRA_CURRENT_INDEX, _currentIndex.value)
            }
            ctx.sendBroadcast(intent)
            // 同时通知 ContentProvider 数据变更
            ctx.contentResolver.notifyChange(PLAYBACK_URI, null)
        }
    }

    const val ACTION_PLAYBACK_CHANGED = "io.github.cctyl.keydroidx.music.PLAYBACK_CHANGED"
    const val EXTRA_SONG_ID = "song_id"
    const val EXTRA_TITLE = "title"
    const val EXTRA_ARTIST = "artist"
    const val EXTRA_ALBUM_ART = "album_art"
    const val EXTRA_PLAYING = "playing"
    const val EXTRA_POSITION = "position"
    const val EXTRA_DURATION = "duration"
    const val EXTRA_LYRIC_LINE = "lyric_line"
    const val EXTRA_PLAY_MODE = "play_mode"
    const val EXTRA_PLAYLIST_SIZE = "playlist_size"
    const val EXTRA_CURRENT_INDEX = "current_index"
    val PLAYBACK_URI = Uri.parse("content://io.github.cctyl.keydroidx.music.playback/state")
}