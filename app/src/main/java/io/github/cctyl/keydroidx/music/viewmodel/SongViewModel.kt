package io.github.cctyl.keydroidx.music.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import io.github.cctyl.keydroidx.music.network.RetrofitClient
import io.github.cctyl.keydroidx.music.network.model.SongDetail
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 单曲详情与歌词加载 ViewModel
 */
class SongViewModel : ViewModel() {
    private val _songDetail = MutableStateFlow<SongDetail?>(null)
    val songDetail: StateFlow<SongDetail?> = _songDetail.asStateFlow()

    private val _lyric = MutableStateFlow<String?>(null)
    val lyric: StateFlow<String?> = _lyric.asStateFlow()

    private val _translatedLyric = MutableStateFlow<String?>(null)
    val translatedLyric: StateFlow<String?> = _translatedLyric.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    fun loadSongDetail(songId: Long) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val c = Gson().toJson(listOf(mapOf("id" to songId, "v" to 0)))
                val detailResponse = RetrofitClient.api.getSongDetail(c)
                _songDetail.value = detailResponse.songs.firstOrNull()

                val lyricResponse = RetrofitClient.api.getLyric(id = songId)
                _lyric.value = lyricResponse.lrc?.lyric
                _translatedLyric.value = lyricResponse.tlyric?.lyric
            } catch (e: Exception) {
                // 静默处理或记录日志
            } finally {
                _isLoading.value = false
            }
        }
    }
}
