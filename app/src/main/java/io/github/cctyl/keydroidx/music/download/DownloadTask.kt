package io.github.cctyl.keydroidx.music.download

import java.io.Serializable

enum class DownloadStatus {
    PENDING,     // 等待中
    DOWNLOADING, // 正在下载
    PAUSED,      // 已暂停
    COMPLETED,   // 已完成
    FAILED       // 下载失败
}

/**
 * 下载任务实体类
 */
data class DownloadTask(
    val songId: Long,
    val title: String,
    val artist: String,
    val album: String = "",
    val durationMs: Long = 0,
    val coverUrl: String? = null,
    val fee: Int = 0,
    var status: DownloadStatus = DownloadStatus.PENDING,
    var progress: Int = 0,               // 0..100
    var downloadedBytes: Long = 0,
    var totalBytes: Long = 0,
    var audioPath: String? = null,       // 本地音频绝对路径
    var lyricPath: String? = null,       // 本地歌词绝对路径
    var errorMessage: String? = null,
    val createTime: Long = System.currentTimeMillis(),
    var finishTime: Long? = null
) : Serializable {
    fun toSongItem(): io.github.cctyl.keydroidx.music.network.model.SongItem {
        return io.github.cctyl.keydroidx.music.network.model.SongItem(
            id = songId,
            name = title,
            artists = listOf(io.github.cctyl.keydroidx.music.network.model.ArtistItem(name = artist)),
            album = io.github.cctyl.keydroidx.music.network.model.AlbumItem(name = album, picUrl = coverUrl),
            duration = durationMs,
            fee = fee,
            localPath = audioPath
        )
    }
}
