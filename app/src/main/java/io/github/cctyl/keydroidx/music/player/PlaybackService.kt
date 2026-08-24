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
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import io.github.cctyl.keydroidx.music.R
import io.github.cctyl.keydroidx.music.library.LibraryManager
import io.github.cctyl.keydroidx.music.network.model.SongItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class PlaybackService : MediaSessionService() {

    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private val handler = Handler(Looper.getMainLooper())

    private val progressRunnable = object : Runnable {
        override fun run() {
            player?.let { p ->
                val pos = p.currentPosition
                val dur = if (p.duration > 0) p.duration else 0L
                PlaybackStateManager.updateProgress(pos, dur)
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
                    androidx.media3.datasource.DefaultHttpDataSource.Factory()
                        .setAllowCrossProtocolRedirects(true)   // 网易云外链会 302 跳转
                        .setConnectTimeoutMs(10_000)
                        .setReadTimeoutMs(10_000)
                        .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                )
            )
            .build()
            .apply {
                addListener(PlayerListener())
            }

        mediaSession = player?.let {
            MediaSession.Builder(this, it).build()
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
        loadAndPlaySong(song)
    }

    private fun loadAndPlaySong(song: SongItem) {
        // 无版权/已下架歌曲直接跳过
        if (song.noCopyright) {
            Log.w(TAG, "No copyright song skipped: ${song.name}")
            skipToNextPlayable()
            return
        }
        // 会员歌直接跳过（fee=1）
        if ((song.fee ?: 0) == 1) {
            Log.w(TAG, "VIP song skipped: ${song.name}")
            skipToNextPlayable()
            return
        }
        // 本地歌曲：直接读文件，不走网易云取链与 VIP/版权检查
        song.localPath?.let { path ->
            Log.d(TAG, "Playing local song: ${song.name}, path: $path")
            val mediaItem = MediaItem.fromUri(android.net.Uri.parse(path))
            player?.let { p ->
                p.setMediaItem(mediaItem)
                p.prepare()
                p.play()
            }
            return
        }
        serviceScope.launch {
            try {
                Log.d(TAG, "Fetching song url for id: ${song.id}")
                val result = SongUrlFetcher.fetch(song.id, PlaybackPrefs.qualityLevel(this@PlaybackService))
                val url = result.url
                if (url.isNullOrEmpty()) {
                    Log.e(TAG, "Failed to get song url for: ${song.name}")
                    skipToNextPlayable()
                    return@launch
                }
                Log.d(TAG, "Playing song: ${song.name}, url: $url")
                val mediaItem = MediaItem.fromUri(android.net.Uri.parse(url))
                player?.let { p ->
                    p.setMediaItem(mediaItem)
                    p.prepare()
                    p.play()
                }
                // 成功准备播放，记录到最近播放历史
                LibraryManager.addRecentSong(song)
            } catch (e: Exception) {
                Log.e(TAG, "Error playing song: ${e.message}", e)
                skipToNextPlayable()
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
                it.play()
            }
        }
    }

    private fun playNext() {
        val playlist = PlaybackStateManager.playlist.value
        if (playlist.isEmpty()) return
        val current = PlaybackStateManager.currentIndex.value
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
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            Log.e(TAG, "onPlayerError: ${error.message}", error)
            skipToNextPlayable()
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
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "KeydroidX Music Playback",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
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
        const val ACTION_PLAY_INDEX = "io.github.cctyl.keydroidx.music.ACTION_PLAY_INDEX"
        const val ACTION_PLAY_PAUSE = "io.github.cctyl.keydroidx.music.ACTION_PLAY_PAUSE"
        const val ACTION_NEXT = "io.github.cctyl.keydroidx.music.ACTION_NEXT"
        const val ACTION_PREV = "io.github.cctyl.keydroidx.music.ACTION_PREV"
        const val ACTION_TOGGLE_MODE = "io.github.cctyl.keydroidx.music.ACTION_TOGGLE_MODE"
        const val ACTION_SEEK = "io.github.cctyl.keydroidx.music.ACTION_SEEK"
        const val EXTRA_INDEX = "extra_index"
        const val EXTRA_SEEK_POSITION = "extra_seek_position"
    }
}
