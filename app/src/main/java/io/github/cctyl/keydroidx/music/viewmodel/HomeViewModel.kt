package io.github.cctyl.keydroidx.music.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.cctyl.keydroidx.music.cache.ContentCache
import io.github.cctyl.keydroidx.music.network.PlaylistApi
import io.github.cctyl.keydroidx.music.network.RetrofitClient
import io.github.cctyl.keydroidx.music.network.model.AlbumItem
import io.github.cctyl.keydroidx.music.network.model.SongItem
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class HomeUiState {
    object Loading : HomeUiState()
    data class Success(
        val dailySongs: List<SongItem>,
        val recommendPlaylists: List<PlaylistApi.PlaylistCard>,
        val newAlbums: List<AlbumItem>
    ) : HomeUiState()
    data class Error(val message: String) : HomeUiState()
}

class HomeViewModel : ViewModel() {

    private val _uiState = MutableStateFlow<HomeUiState>(HomeUiState.Loading)
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        loadHomeData()
    }

    fun loadHomeData(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            if (!forceRefresh) {
                val cachedDaily = ContentCache.homeDailySongs
                val cachedPlaylists = ContentCache.homeRecommendPlaylists
                if (cachedDaily != null && cachedPlaylists != null) {
                    _uiState.value = HomeUiState.Success(
                        dailySongs = cachedDaily,
                        recommendPlaylists = cachedPlaylists,
                        newAlbums = emptyList()
                    )
                }
            }

            try {
                coroutineScope {
                    val dailyDeferred = async { PlaylistApi.getDailyRecommendSongs() }
                    val playlistDeferred = async { PlaylistApi.getRecommendPlaylists() }
                    val newAlbumsDeferred = async { runCatching { RetrofitClient.api.getNewAlbums().albums ?: emptyList() }.getOrDefault(emptyList()) }

                    val daily = dailyDeferred.await()
                    val playlists = playlistDeferred.await()
                    val newAlbums = newAlbumsDeferred.await()

                    ContentCache.homeDailySongs = daily
                    _uiState.value = HomeUiState.Success(
                        dailySongs = daily,
                        recommendPlaylists = playlists,
                        newAlbums = newAlbums
                    )
                }
            } catch (e: Exception) {
                if (_uiState.value !is HomeUiState.Success) {
                    _uiState.value = HomeUiState.Error(e.message ?: "加载失败")
                }
            }
        }
    }
}
