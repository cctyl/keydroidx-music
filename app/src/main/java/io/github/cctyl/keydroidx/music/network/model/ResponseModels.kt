package io.github.cctyl.keydroidx.music.network.model

import com.google.gson.annotations.SerializedName

// ==================== 专辑详情 ====================
data class AlbumDetailResponse(
    @SerializedName("album") val album: AlbumDetail?,
    @SerializedName("songs") val songs: List<AlbumSongItem>?,
    @SerializedName("code") val code: Int = 200
)

data class AlbumDetail(
    @SerializedName("id") val id: Long,
    @SerializedName("name") val name: String,
    @SerializedName("picUrl") val picUrl: String?,
    @SerializedName("artist") val artist: ArtistItem?,
    @SerializedName("publishTime") val publishTime: Long?,
    @SerializedName("company") val company: String?,
    @SerializedName("description") val description: String?,
    @SerializedName("size") val size: Int? = null
)

data class AlbumSongItem(
    @SerializedName("id") val id: Long,
    @SerializedName("name") val name: String,
    @SerializedName("ar") val artists: List<ArtistItem>?,
    @SerializedName("al") val album: AlbumItem?,
    @SerializedName("dt") val dt: Long = 0,
    @SerializedName("no") val trackNumber: Int? = null
) {
    fun getDurationMs(): Long = dt
}

// ==================== 艺人专辑列表 ====================
data class ArtistAlbumsResponse(
    @SerializedName("code") val code: Int = 200,
    @SerializedName("artist") val artist: ArtistDetail?,
    @SerializedName("hotAlbums") val hotAlbums: List<ArtistAlbumItem>?,
    @SerializedName("more") val more: Boolean? = null
)

data class ArtistAlbumItem(
    @SerializedName("id") val id: Long,
    @SerializedName("name") val name: String,
    @SerializedName("picUrl") val picUrl: String?,
    @SerializedName("publishTime") val publishTime: Long?,
    @SerializedName("size") val size: Int?,
    @SerializedName("artist") val artist: ArtistItem? = null
)

// ==================== 艺人详情 ====================
data class ArtistDetailResponse(
    @SerializedName("code") val code: Int = 200,
    @SerializedName("data") val data: ArtistDetailData?,
    @SerializedName("artist") val artist: ArtistDetail?,
    @SerializedName("hotSongs") val hotSongs: List<ArtistSongItem>?
)

data class ArtistDetailData(
    @SerializedName("artist") val artist: ArtistDetail?,
    @SerializedName("hotSongs") val hotSongs: List<ArtistSongItem>?
)

data class ArtistDetail(
    @SerializedName("id") val id: Long,
    @SerializedName("name") val name: String,
    @SerializedName("picUrl") val picUrl: String?,
    @SerializedName("cover") val cover: String? = null,
    @SerializedName("albumSize") val albumSize: Int?,
    @SerializedName("musicSize") val musicSize: Int?
)

data class ArtistSongItem(
    @SerializedName("id") val id: Long,
    @SerializedName("name") val name: String,
    @SerializedName("ar") val artists: List<ArtistItem>?,
    @SerializedName("al") val album: AlbumItem?,
    @SerializedName("dt") val dt: Long = 0,
    @SerializedName("duration") val duration: Long = 0
) {
    fun getDurationMs(): Long = if (dt > 0) dt else duration
}

// ==================== 发现 / 推荐 ====================
data class PersonalizedPlaylist(
    @SerializedName("id") val id: Long,
    @SerializedName("name") val name: String,
    @SerializedName("picUrl") val picUrl: String?,
    @SerializedName("playCount") val playCount: Long?
)

data class PersonalizedResponse(
    @SerializedName("result") val result: List<PersonalizedPlaylist>?
)

data class NewAlbumsResponse(
    @SerializedName("albums") val albums: List<AlbumItem>?
)

data class UserProfile(
    @SerializedName("userId") val userId: Long,
    @SerializedName("nickname") val nickname: String,
    @SerializedName("avatarUrl") val avatarUrl: String?
)

data class UserDetailResponse(
    @SerializedName("profile") val profile: UserProfile?
)
