package io.github.cctyl.keydroidx.music.network.model

import com.google.gson.annotations.SerializedName

data class ArtistItem(
    @SerializedName("id") val id: Long? = null,
    @SerializedName("name") val name: String
)

data class AlbumItem(
    @SerializedName("id") val id: Long? = null,
    @SerializedName("name") val name: String?,
    @SerializedName("picUrl") val picUrl: String? = null
)

data class SongItem(
    @SerializedName("id") val id: Long,
    @SerializedName("name") val name: String,
    @SerializedName("ar") val artists: List<ArtistItem>?,
    @SerializedName("al") val album: AlbumItem?,
    @SerializedName("dt") val duration: Long? = null,
    /** 网易云 fee 字段：1=VIP 歌曲，0/null=免费 */
    @SerializedName("fee") val fee: Int? = null,
    /** 网易云版权/状态：st < 0（如 -200）表示无版权/已下架 */
    @SerializedName("noCopyright") var noCopyright: Boolean = false
) {
    /** 是否可播放（非下架/无版权歌曲） */
    val isPlayable: Boolean get() = !noCopyright

    val artistName: String get() = artists?.joinToString("/") { it.name } ?: "未知艺术家"
}

data class SongDetailResponse(
    @SerializedName("songs") val songs: List<SongDetail>
)

data class SongDetail(
    @SerializedName("id") val id: Long,
    @SerializedName("name") val name: String,
    @SerializedName("ar") val artists: List<ArtistItem>,
    @SerializedName("al") val album: AlbumItem,
    @SerializedName("dt") val duration: Long,
    @SerializedName("no") val trackNumber: Int? = null,
    @SerializedName("mv") val mvId: Long? = null
)

data class LyricResponse(
    @SerializedName("lrc") val lrc: LyricContent?,
    @SerializedName("tlyric") val tlyric: LyricContent?
)

data class LyricContent(
    @SerializedName("lyric") val lyric: String?
)
