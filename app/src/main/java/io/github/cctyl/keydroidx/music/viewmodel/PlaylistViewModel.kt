package io.github.cctyl.keydroidx.music.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.cctyl.keydroidx.music.cache.ContentCache
import io.github.cctyl.keydroidx.music.network.PlaylistApi
import io.github.cctyl.keydroidx.music.network.RetrofitClient
import io.github.cctyl.keydroidx.music.network.model.AlbumDetailResponse
import io.github.cctyl.keydroidx.music.network.model.ArtistDetailData
import io.github.cctyl.keydroidx.music.network.model.SongItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class PlaylistUiState {
    object Loading : PlaylistUiState()
    data class SongsLoaded(val title: String, val songs: List<SongItem>) : PlaylistUiState()
    data class AlbumLoaded(val album: AlbumDetailResponse) : PlaylistUiState()
    data class ArtistLoaded(val artist: ArtistDetailData) : PlaylistUiState()
    data class Error(val message: String) : PlaylistUiState()
}

class PlaylistViewModel : ViewModel() {

    private val _uiState = MutableStateFlow<PlaylistUiState>(PlaylistUiState.Loading)
    val uiState: StateFlow<PlaylistUiState> = _uiState.asStateFlow()

    fun loadPlaylist(playlistId: Long, name: String = "歌单") {
        viewModelScope.launch {
            _uiState.value = PlaylistUiState.Loading
            val cached = ContentCache.getPlaylistSongs(playlistId)
            if (cached != null) {
                _uiState.value = PlaylistUiState.SongsLoaded(name, cached)
            }

            try {
                val songs = PlaylistApi.getPlaylistDetail(playlistId)
                ContentCache.putPlaylistSongs(playlistId, songs)
                _uiState.value = PlaylistUiState.SongsLoaded(name, songs)
            } catch (e: Exception) {
                if (_uiState.value !is PlaylistUiState.SongsLoaded) {
                    _uiState.value = PlaylistUiState.Error(e.message ?: "歌单加载失败")
                }
            }
        }
    }

    fun loadAlbum(albumId: Long) {
        viewModelScope.launch {
            _uiState.value = PlaylistUiState.Loading
            val cached = ContentCache.getAlbum(albumId)
            if (cached != null) {
                _uiState.value = PlaylistUiState.AlbumLoaded(cached)
            }

            try {
                val album = RetrofitClient.api.getAlbumDetail(albumId)
                ContentCache.putAlbum(albumId, album)
                _uiState.value = PlaylistUiState.AlbumLoaded(album)
            } catch (e: Exception) {
                if (_uiState.value !is PlaylistUiState.AlbumLoaded) {
                    _uiState.value = PlaylistUiState.Error(e.message ?: "专辑加载失败")
                }
            }
        }
    }

    fun loadArtist(artistId: Long) {
        viewModelScope.launch {
            _uiState.value = PlaylistUiState.Loading
            val cached = ContentCache.getArtist(artistId)
            if (cached != null) {
                _uiState.value = PlaylistUiState.ArtistLoaded(cached)
            }

            try {
                val artist = RetrofitClient.api.getArtistDetail(artistId)
                val detailData = artist.data ?: ArtistDetailData(artist.artist, artist.hotSongs)
                ContentCache.putArtist(artistId, detailData)
                _uiState.value = PlaylistUiState.ArtistLoaded(detailData)
            } catch (e: Exception) {
                if (_uiState.value !is PlaylistUiState.ArtistLoaded) {
                    _uiState.value = PlaylistUiState.Error(e.message ?: "歌手信息加载失败")
                }
            }
        }
    }
}
