package io.github.cctyl.keydroidx.music.network.model

import com.google.gson.annotations.SerializedName

// ==================== 云音乐排行榜（api/toplist） ====================
data class ToplistResponse(
    @SerializedName("code") val code: Int = 200,
    @SerializedName("list") val list: List<ToplistBoard> = emptyList()
)

/**
 * 单个榜单。id 即对应歌单 id，可直接用歌单详情接口拉取完整曲目。
 */
data class ToplistBoard(
    @SerializedName("id") val id: Long,
    @SerializedName("name") val name: String,
    @SerializedName("updateFrequency") val updateFrequency: String? = null,
    @SerializedName("trackCount") val trackCount: Int? = null,
    @SerializedName("tracks") val tracks: List<ToplistTrack>? = null
)

data class ToplistTrack(
    @SerializedName("first") val first: String? = null,
    @SerializedName("second") val second: String? = null
) {
    /** first=歌名，second=歌手 */
    fun previewText(): String {
        val song = first?.takeIf { it.isNotBlank() } ?: return "暂无曲目"
        val artist = second?.takeIf { it.isNotBlank() }
        return if (artist != null) "$song - $artist" else song
    }
}
