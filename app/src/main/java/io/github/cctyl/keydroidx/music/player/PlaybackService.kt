package io.github.cctyl.keydroidx.music.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import io.github.cctyl.keydroidx.music.util.NLog as Log
import androidx.core.app.NotificationCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import io.github.cctyl.keydroidx.music.R
import io.github.cctyl.keydroidx.music.auth.UserProfileCache
import io.github.cctyl.keydroidx.music.ui.MusicPlayerActivity
import io.github.cctyl.keydroidx.music.download.DownloadManager
import io.github.cctyl.keydroidx.music.library.LibraryManager
import io.github.cctyl.keydroidx.music.lyric.LrcLine
import io.github.cctyl.keydroidx.music.lyric.LrcParser
import io.github.cctyl.keydroidx.music.network.PlaylistApi
import io.github.cctyl.keydroidx.music.network.RetrofitClient
import io.github.cctyl.keydroidx.music.network.model.SongItem
import io.github.cctyl.keydroidx.music.util.NetworkUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PlaybackService : MediaSessionService() {

    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private val handler = Handler(Looper.getMainLooper())

    /** 当前歌曲已加载的歌词行（后台歌词跟踪用） */
    private var loadedLyrics: List<LrcLine> = emptyList()
    private var lyricsSongId: Long = -1L

    private val progressRunnable = object : Runnable {
        override fun run() {
            player?.let { p ->
                val pos = p.currentPosition
                val dur = if (p.duration > 0) p.duration else 0L
                PlaybackStateManager.updateProgress(pos, dur)
                // 后台歌词跟踪：计算当前歌词行并推送
                updateCurrentLyricLine(pos)
            }
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "PlaybackService onCreate")
        createNotificationChannel()

        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setMediaSourceFactory(
                androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
                    AudioCacheManager.createCacheDataSourceFactory(this)
                )
            )
            .build()
            .apply {
                addListener(PlayerListener())
            }

        mediaSession = player?.let {
            MediaSession.Builder(this, it)
                // 点媒体通知回到播放详情页
                .setSessionActivity(PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MusicPlayerActivity::class.java).apply {
                        // 复用已有播放页并清空其上方页面，避免反复点击通知叠层
                        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                ))
                .build().also { session ->
                    addSession(session)
                }
        }

        handler.post(progressRunnable)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.d(TAG, "onStartCommand action: $action")
        when (action) {
            ACTION_PLAY_INDEX -> {
                val index = intent.getIntExtra(EXTRA_INDEX, 0)
                playAtIndex(index)
            }
            ACTION_PLAY_PAUSE -> {
                togglePlayPause()
            }
            ACTION_NEXT -> {
                playNext()
            }
            ACTION_PREV -> {
                playPrev()
            }
            ACTION_TOGGLE_MODE -> {
                PlaybackStateManager.togglePlayMode()
            }
            ACTION_SEEK -> {
                val pos = intent.getLongExtra(EXTRA_SEEK_POSITION, 0L)
                player?.seekTo(pos)
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun playAtIndex(index: Int) {
        val playlist = PlaybackStateManager.playlist.value
        if (index !in playlist.indices) return

        PlaybackStateManager.updatePlaylist(playlist, index)
        val song = playlist[index]
        // 后台歌词跟踪：切歌时预加载歌词
        loadLyrics(song.id)
        loadAndPlaySong(song)
    }

    private fun loadAndPlaySong(song: SongItem) {
        // 本地歌曲：直接读文件，不走网易云取链与 VIP/版权检查
        song.localPath?.let { path ->
            Log.d(TAG, "Playing local song: ${song.name}, path: $path")
            val mediaItem = buildMediaItem(song, path)
            player?.let { p ->
                p.setMediaItem(mediaItem)
                p.prepare()
                p.play()
            }
            updateNotification()
            return
        }

        // 已下载歌曲优先直连本地文件（断网/飞行模式 100% 离线秒播）
        val downloadedTask = DownloadManager.getDownloadedSong(song.id)
        if (downloadedTask != null && !downloadedTask.audioPath.isNullOrBlank()) {
            val audioPath = downloadedTask.audioPath!!
            Log.d(TAG, "Playing downloaded song: ${song.name}, path: $audioPath")
            val mediaItem = buildMediaItem(song, audioPath)
            player?.let { p ->
                p.setMediaItem(mediaItem)
                p.prepare()
                p.play()
            }
            updateNotification()
            LibraryManager.addRecentSong(song)
            return
        }

        // 无版权/已下架歌曲直接跳过
        if (song.noCopyright) {
            Log.w(TAG, "No copyright song skipped: ${song.name}")
            skipToNextPlayable()
            return
        }

        // 网络歌曲播放
        val isCached = AudioCacheManager.isSongCached(this, song.id)
        val hasNetwork = NetworkUtils.isNetworkAvailable(this)
        val isUserVip = UserProfileCache.isVip(this)

        Log.i(TAG, "[VIP-CHECK] [PLAY-FLOW] Start playing -> id: ${song.id}, name: ${song.name}, fee: ${song.fee}, isCached: $isCached, hasNetwork: $hasNetwork, isUserVip: $isUserVip")

        if (!hasNetwork && !isCached) {
            Log.w(TAG, "[VIP-CHECK] [PLAY-FLOW] No network and song not cached: ${song.name}")
            showNoNetworkToast()
            player?.pause()
            PlaybackStateManager.updatePlayingState(false)
            return
        }

        if (isCached) {
            // 本地已有缓存，优先使用缓存播放（即便断网也能离线播）
            Log.d(TAG, "[VIP-CHECK] [PLAY-FLOW] Playing cached song: ${song.name}, id: ${song.id}")
            val dummyUri = "https://music.163.com/song/media/${song.id}.mp3"
            val mediaItem = buildMediaItem(song, dummyUri)
            player?.let { p ->
                p.setMediaItem(mediaItem)
                p.prepare()
                p.play()
            }
            updateNotification()
            LibraryManager.addRecentSong(song)

            // 如果是非 VIP 用户，检查是否是 VIP 歌曲
            val isVipSong = (song.fee ?: 0) == 1
            Log.i(TAG, "[VIP-CHECK] [CACHE-BRANCH] isUserVip: $isUserVip, isVipSong: $isVipSong, fee: ${song.fee}")
            if (!isUserVip && isVipSong) {
                Log.i(TAG, "[VIP-CHECK] Showing toast for cached VIP trial song: ${song.name}")
                handler.post {
                    Toast.makeText(
                        applicationContext,
                        "VIP 歌曲，正在播放试听片段",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } else if (!isUserVip && song.fee == 0) {
                // 如果历史记录里 fee 是 0，但在联网情况下异步核对是否为 VIP 歌曲，以便后续修正
                if (hasNetwork) {
                    serviceScope.launch {
                        try {
                            val checkResult = SongUrlFetcher.fetch(song.id, PlaybackPrefs.qualityLevel(this@PlaybackService))
                            Log.i(TAG, "[VIP-CHECK] [ASYNC-CHECK] result -> isTrial: ${checkResult.isTrial}, trialEnd: ${checkResult.trialEnd}")
                            if (checkResult.isTrial) {
                                val updatedSong = song.copy(fee = 1)
                                LibraryManager.addRecentSong(updatedSong)
                                val durationTip = if (checkResult.trialEnd > 0) "${checkResult.trialEnd}秒" else ""
                                handler.post {
                                    Toast.makeText(
                                        applicationContext,
                                        "VIP 歌曲，正在播放${durationTip}试听片段",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "[VIP-CHECK] [ASYNC-CHECK] Failed: ${e.message}")
                        }
                    }
                }
            }
            return
        }

        // 无缓存但在网络可用时：联网取播放链接
        serviceScope.launch {
            try {
                Log.d(TAG, "[VIP-CHECK] [NET-BRANCH] Fetching song url for id: ${song.id}")
                val result = SongUrlFetcher.fetch(song.id, PlaybackPrefs.qualityLevel(this@PlaybackService))
                val url = result.url
                if (url.isNullOrEmpty()) {
                    Log.e(TAG, "[VIP-CHECK] [NET-BRANCH] Failed to get song url for: ${song.name}")
                    if (!NetworkUtils.isNetworkAvailable(this@PlaybackService)) {
                        showNoNetworkToast()
                        player?.pause()
                        PlaybackStateManager.updatePlayingState(false)
                    } else {
                        skipToNextPlayable()
                    }
                    return@launch
                }
                Log.d(TAG, "[VIP-CHECK] [NET-BRANCH] Got url: $url, isTrial: ${result.isTrial}, trialStart: ${result.trialStart}, trialEnd: ${result.trialEnd}")

                val isVipOrTrial = result.isTrial || (song.fee ?: 0) == 1
                val updatedSong = if (isVipOrTrial && (song.fee ?: 0) != 1) {
                    song.copy(fee = 1)
                } else {
                    song
                }

                Log.i(TAG, "[VIP-CHECK] [NET-BRANCH] Decision -> isUserVip: $isUserVip, isVipOrTrial: $isVipOrTrial, showToast: ${!isUserVip && isVipOrTrial}")

                if (!isUserVip && isVipOrTrial) {
                    val durationTip = if (result.trialEnd > 0) "${result.trialEnd}秒" else ""
                    handler.post {
                        Toast.makeText(
                            applicationContext,
                            "VIP 歌曲，正在播放${durationTip}试听片段",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }

                val mediaItem = buildMediaItem(updatedSong, url)
                player?.let { p ->
                    p.setMediaItem(mediaItem)
                    p.prepare()
                    p.play()
                }
                updateNotification()
                // 成功准备播放，记录到最近播放历史（保存更新后的 fee 属性）
                LibraryManager.addRecentSong(updatedSong)
            } catch (e: Exception) {
                Log.e(TAG, "[VIP-CHECK] [NET-BRANCH] Error playing song: ${e.message}", e)
                if (!NetworkUtils.isNetworkAvailable(this@PlaybackService)) {
                    showNoNetworkToast()
                    player?.pause()
                    PlaybackStateManager.updatePlayingState(false)
                } else {
                    skipToNextPlayable()
                }
            }
        }
    }

    private var lastToastTime = 0L
    private fun showNoNetworkToast() {
        val now = System.currentTimeMillis()
        if (now - lastToastTime > 2500L) {
            lastToastTime = now
            handler.post {
                Toast.makeText(applicationContext, "无网络连接，请开启Wi-Fi或移动数据", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 连续不可播计数（会员歌/取链失败/播放错误），超过队列长度则停止 */
    private var consecutiveFailures = 0

    /** 当前歌曲不可播：跳下一首；整轮都失败则停止并提示 */
    private fun skipToNextPlayable() {
        val size = PlaybackStateManager.playlist.value.size
        consecutiveFailures++
        if (size <= 0 || consecutiveFailures >= size) {
            Log.e(TAG, "no playable song in queue, stopping")
            Toast.makeText(applicationContext, "没有可播放的歌曲", Toast.LENGTH_SHORT).show()
            player?.pause()
            PlaybackStateManager.updatePlayingState(false)
            consecutiveFailures = 0
            return
        }
        playNext()
    }

    private fun togglePlayPause() {
        player?.let {
            if (it.isPlaying) {
                it.pause()
            } else {
                // 如果当前由于没有网络处于暂停状态，且仍然没有网络，且当前歌曲既不是本地歌曲也没有本地缓存
                val currentSong = PlaybackStateManager.currentSong.value
                val isLocal = !currentSong?.localPath.isNullOrBlank()
                val isDownloaded = currentSong != null && DownloadManager.isDownloaded(currentSong.id)
                val isCached = currentSong != null && AudioCacheManager.isSongCached(this@PlaybackService, currentSong.id)
                val isNetworkConnected = NetworkUtils.isNetworkAvailable(this@PlaybackService)

                if (!isLocal && !isDownloaded && !isCached && !isNetworkConnected) {
                    showNoNetworkToast()
                    return
                }
                it.play()
            }
        }
    }

    private fun playNext() {
        val playlist = PlaybackStateManager.playlist.value
        if (playlist.isEmpty()) return
        val current = PlaybackStateManager.currentIndex.value
        // FM 模式手动切歌到队尾时也可能需要续批
        if (current >= playlist.size - 1) maybeFetchMoreFm()
        val mode = PlaybackStateManager.playMode.value

        val nextIndex = when (mode) {
            PlaybackMode.RANDOM -> (playlist.indices).random()
            PlaybackMode.SINGLE_LOOP, PlaybackMode.LIST_LOOP -> {
                if (current + 1 < playlist.size) current + 1 else 0
            }
        }
        playAtIndex(nextIndex)
    }

    private fun playPrev() {
        val playlist = PlaybackStateManager.playlist.value
        if (playlist.isEmpty()) return
        val current = PlaybackStateManager.currentIndex.value
        val mode = PlaybackStateManager.playMode.value

        val prevIndex = when (mode) {
            PlaybackMode.RANDOM -> (playlist.indices).random()
            PlaybackMode.SINGLE_LOOP, PlaybackMode.LIST_LOOP -> {
                if (current - 1 >= 0) current - 1 else playlist.size - 1
            }
        }
        playAtIndex(prevIndex)
    }

    private inner class PlayerListener : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            PlaybackStateManager.updatePlayingState(isPlaying)
            Log.d(TAG, "onIsPlayingChanged: $isPlaying")
            updateNotification()
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            Log.e(TAG, "onPlayerError: ${error.message}", error)
            if (!NetworkUtils.isNetworkAvailable(this@PlaybackService)) {
                showNoNetworkToast()
                player?.pause()
                PlaybackStateManager.updatePlayingState(false)
            } else {
                skipToNextPlayable()
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                consecutiveFailures = 0   // 成功开播，重置连续失败计数
            }
            if (playbackState == Player.STATE_ENDED) {
                Log.d(TAG, "onPlaybackStateChanged: STATE_ENDED")
                when (PlaybackStateManager.playMode.value) {
                    PlaybackMode.SINGLE_LOOP -> {
                        player?.seekTo(0)
                        player?.play()
                    }
                    else -> playNext()
                }
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            super.onMediaItemTransition(mediaItem, reason)
            maybeFetchMoreFm()
        }
    }

    /**
     * 私人 FM 续批：当前是最后一首且剩余不足 2 首时，
     * 异步拉取一批新歌追加到队列（去重后追加）。
     */
    private var fetchingFm = false
    private fun maybeFetchMoreFm() {
        if (!PlaybackStateManager.isPersonalFm.value || fetchingFm) return
        val playlist = PlaybackStateManager.playlist.value
        val index = PlaybackStateManager.currentIndex.value
        if (playlist.isEmpty() || index < playlist.size - 2) return

        fetchingFm = true
        Log.d(TAG, "FM stock low (index=$index/${playlist.size}), fetching more")
        serviceScope.launch {
            try {
                val batch = PlaylistApi.getPersonalFm()
                if (batch.isNotEmpty()) {
                    // 按歌曲 id 去重后追加
                    val existingIds = PlaybackStateManager.playlist.value.mapTo(mutableSetOf()) { it.id }
                    val fresh = batch.filter { existingIds.add(it.id) }
                    if (fresh.isNotEmpty()) {
                        PlaybackStateManager.appendPlaylist(fresh)
                        Log.d(TAG, "FM appended ${fresh.size} songs, total ${PlaybackStateManager.playlist.value.size}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "FM fetch more failed", e)
            } finally {
                fetchingFm = false
            }
        }
    }

    /**
     * 后台歌词跟踪：加载指定歌曲的 LRC 歌词（优先本地下载，否则联网）。
     * 供组件/Widget 在后台播放时展示当前歌词行。
     */
    private fun loadLyrics(songId: Long) {
        if (songId == lyricsSongId && loadedLyrics.isNotEmpty()) return
        lyricsSongId = songId
        loadedLyrics = emptyList()
        PlaybackStateManager.updateCurrentLyricLine(null)

        serviceScope.launch {
            try {
                // 1. 优先读取已下载的本地歌词
                val downloaded = DownloadManager.getDownloadedSong(songId)
                if (downloaded != null && !downloaded.lyricPath.isNullOrBlank()) {
                    val lrcFile = java.io.File(downloaded.lyricPath!!)
                    if (lrcFile.exists()) {
                        val raw = withContext(Dispatchers.IO) { lrcFile.readText(Charsets.UTF_8) }
                        if (!raw.isNullOrBlank()) {
                            loadedLyrics = LrcParser.parse(raw)
                            Log.d(TAG, "Loaded ${loadedLyrics.size} downloaded lyric lines for song $songId")
                            return@launch
                        }
                    }
                }

                // 2. 本地无歌词时联网拉取
                val resp = withContext(Dispatchers.IO) { RetrofitClient.api.getLyric(id = songId) }
                val raw = resp.lrc?.lyric
                if (!raw.isNullOrEmpty()) {
                    loadedLyrics = LrcParser.parse(raw)
                    Log.d(TAG, "Loaded ${loadedLyrics.size} lyric lines for song $songId")
                } else {
                    Log.d(TAG, "No lyric for song $songId")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load lyrics for song $songId: ${e.message}", e)
            }
        }
    }

    /** 根据播放进度计算当前歌词行并推送到 PlaybackStateManager（供 Provider/Widget 读取）。 */
    private fun updateCurrentLyricLine(posMs: Long) {
        if (loadedLyrics.isEmpty()) return
        var idx = -1
        for (i in loadedLyrics.indices) {
            if (loadedLyrics[i].timeMs <= posMs) idx = i else break
        }
        if (idx !in loadedLyrics.indices) return
        val line = loadedLyrics[idx].text
        // 仅当歌词行变化时推送（避免高频广播）
        if (line != PlaybackStateManager.getCurrentLyricLineSync()) {
            PlaybackStateManager.updateCurrentLyricLine(line)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "音乐播放控制",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "正在播放音乐控制通知"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildMediaItem(song: SongItem, uriString: String): MediaItem {
        val metadata = androidx.media3.common.MediaMetadata.Builder()
            .setTitle(song.name)
            .setArtist(song.artistName)
            .setAlbumTitle(song.album?.name)
            .build()

        val customCacheKey = AudioCacheManager.buildCacheKey(song.id)

        return MediaItem.Builder()
            .setMediaId(song.id.toString())
            .setUri(uriString)
            .setCustomCacheKey(customCacheKey)
            .setMediaMetadata(metadata)
            .build()
    }

    private fun updateNotification() {
        val currentSong = PlaybackStateManager.currentSong.value
        val isPlaying = player?.isPlaying ?: false
        val title = currentSong?.name ?: "KeydroidX Music"
        val artist = currentSong?.artistName ?: ""

        val playPauseAction = if (isPlaying) {
            NotificationCompat.Action(
                android.R.drawable.ic_media_pause,
                "暂停",
                createActionPendingIntent(ACTION_PLAY_PAUSE, 101)
            )
        } else {
            NotificationCompat.Action(
                android.R.drawable.ic_media_play,
                "播放",
                createActionPendingIntent(ACTION_PLAY_PAUSE, 101)
            )
        }

        val prevAction = NotificationCompat.Action(
            android.R.drawable.ic_media_previous,
            "上一曲",
            createActionPendingIntent(ACTION_PREV, 102)
        )

        val nextAction = NotificationCompat.Action(
            android.R.drawable.ic_media_next,
            "下一曲",
            createActionPendingIntent(ACTION_NEXT, 103)
        )

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MusicPlayerActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_music_launcher)
            .setContentTitle(title)
            .setContentText(artist)
            .setContentIntent(contentIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(isPlaying)
            .addAction(prevAction)
            .addAction(playPauseAction)
            .addAction(nextAction)
            .setStyle(
                androidx.media3.session.MediaStyleNotificationHelper.MediaStyle(mediaSession!!)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        if (!isPlaying) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_DETACH)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(false)
            }
        }
    }

    private fun createActionPendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, PlaybackService::class.java).apply {
            this.action = action
        }
        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    override fun onDestroy() {
        handler.removeCallbacks(progressRunnable)
        player?.release()
        player = null
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "PlaybackService"
        const val CHANNEL_ID = "keydroidx_music_playback_channel"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_PLAY_INDEX = "io.github.cctyl.keydroidx.music.ACTION_PLAY_INDEX"
        const val ACTION_PLAY_PAUSE = "io.github.cctyl.keydroidx.music.ACTION_PLAY_PAUSE"
        const val ACTION_NEXT = "io.github.cctyl.keydroidx.music.ACTION_NEXT"
        const val ACTION_PREV = "io.github.cctyl.keydroidx.music.ACTION_PREV"
        const val ACTION_TOGGLE_MODE = "io.github.cctyl.keydroidx.music.ACTION_TOGGLE_MODE"
        const val ACTION_SEEK = "io.github.cctyl.keydroidx.music.ACTION_SEEK"
        const val EXTRA_INDEX = "extra_index"
        const val EXTRA_SEEK_POSITION = "extra_seek_position"

        fun startPlay(context: Context, songs: List<SongItem>, index: Int = 0) {
            PlaybackStateManager.updatePlaylist(songs, index)
            val intent = Intent(context, PlaybackService::class.java).apply {
                action = ACTION_PLAY_INDEX
                putExtra(EXTRA_INDEX, index)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
