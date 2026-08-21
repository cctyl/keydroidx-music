package io.github.cctyl.keydroidx.music.network.model

import com.google.gson.annotations.SerializedName

data class SearchResponse(
    @SerializedName("result") val result: SearchResult?
)

data class SearchResult(
    @SerializedName("songs") val songs: List<SongItem>?,
    @SerializedName("albums") val albums: List<AlbumSearchItem>?,
    @SerializedName("artists") val artists: List<ArtistSearchItem>?
)

data class AlbumSearchItem(
    @SerializedName("id") val id: Long,
    @SerializedName("name") val name: String,
    @SerializedName("picUrl") val picUrl: String?,
    @SerializedName("artist") val artist: ArtistItem?,
    @SerializedName("publishTime") val publishTime: Long?,
    @SerializedName("size") val size: Int?,
    @SerializedName("company") val company: String?
)

data class ArtistSearchItem(
    @SerializedName("id") val id: Long,
    @SerializedName("name") val name: String,
    @SerializedName("picUrl") val picUrl: String?,
    @SerializedName("picId") val picId: Long?,
    @SerializedName("albumSize") val albumSize: Int?,
    @SerializedName("musicSize") val musicSize: Int?,
    @SerializedName("alias") val alias: List<String>?,
    @SerializedName("trans") val trans: String?
)
