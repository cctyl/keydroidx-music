package io.github.cctyl.keydroidx.music.player

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

    fun updatePlayingState(playing: Boolean) {
        _isPlaying.value = playing
    }

    fun updateCurrentSong(song: SongItem?) {
        _currentSong.value = song
    }

    fun updateProgress(pos: Long, dur: Long) {
        _currentPositionMs.value = pos
        _durationMs.value = dur
    }

    fun updatePlayMode(mode: PlaybackMode) {
        _playMode.value = mode
    }

    fun updatePlaylist(list: List<SongItem>, index: Int) {
        _playlist.value = list
        _currentIndex.value = index
        if (index in list.indices) {
            _currentSong.value = list[index]
        }
    }

    fun togglePlayMode(): PlaybackMode {
        val next = when (_playMode.value) {
            PlaybackMode.LIST_LOOP -> PlaybackMode.SINGLE_LOOP
            PlaybackMode.SINGLE_LOOP -> PlaybackMode.RANDOM
            PlaybackMode.RANDOM -> PlaybackMode.LIST_LOOP
        }
        _playMode.value = next
        return next
    }
}
