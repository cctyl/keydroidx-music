package io.github.cctyl.keydroidx.music.ui

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.widget.ProgressBar
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import io.github.cctyl.keydroidx.music.R
import io.github.cctyl.keydroidx.music.network.model.SongItem
import io.github.cctyl.keydroidx.music.player.PlaybackMode
import io.github.cctyl.keydroidx.music.player.PlaybackService
import io.github.cctyl.keydroidx.music.player.PlaybackStateManager
import io.github.cctyl.nokia.keycore.model.NokiaKeyAction
import io.github.cctyl.nokia.keycore.ui.NokiaBaseActivity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale

class MusicPlayerActivity : NokiaBaseActivity() {

    private var tvTitleView: TextView? = null
    private var tvArtistView: TextView? = null
    private var tvStatusView: TextView? = null
    private var progressBar: ProgressBar? = null
    private var tvCurrentTime: TextView? = null
    private var tvTotalTime: TextView? = null
    private var audioManager: AudioManager? = null

    override fun getContentLayoutRes(): Int = R.layout.activity_music_player

    override fun onInitViews() {
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        tvTitleView = findViewById(R.id.tv_player_title)
        tvArtistView = findViewById(R.id.tv_player_artist)
        tvStatusView = findViewById(R.id.tv_player_status)
        progressBar = findViewById(R.id.progress_music)
        tvCurrentTime = findViewById(R.id.tv_current_time)
        tvTotalTime = findViewById(R.id.tv_total_time)

        setPageTitle(getString(R.string.title_now_playing))
        setSoftKeys("模式", getString(R.string.softkey_pause), getString(R.string.softkey_back))

        observePlaybackState()
    }

    private fun observePlaybackState() {
        lifecycleScope.launch {
            PlaybackStateManager.currentSong.collectLatest { song: SongItem? ->
                if (song != null) {
                    tvTitleView?.text = song.name
                    tvArtistView?.text = song.artists?.joinToString("/") { it.name } ?: "未知艺术家"
                } else {
                    tvTitleView?.text = "暂无曲目"
                    tvArtistView?.text = "未知艺术家"
                }
            }
        }

        lifecycleScope.launch {
            PlaybackStateManager.isPlaying.collectLatest { playing ->
                setSoftCenter(if (playing) getString(R.string.softkey_pause) else getString(R.string.softkey_play))
                updateModeStatus(PlaybackStateManager.playMode.value, playing)
            }
        }

        lifecycleScope.launch {
            PlaybackStateManager.currentPositionMs.collectLatest { pos ->
                val dur = PlaybackStateManager.durationMs.value
                tvCurrentTime?.text = formatTime(pos)
                tvTotalTime?.text = formatTime(dur)

                if (dur > 0) {
                    val progress = ((pos * 100) / dur).toInt()
                    progressBar?.progress = progress.coerceIn(0, 100)
                } else {
                    progressBar?.progress = 0
                }
            }
        }
    }

    private fun updateModeStatus(mode: PlaybackMode, isPlaying: Boolean) {
        val playStateStr = if (isPlaying) "播放中" else "暂停中"
        val modeStr = when (mode) {
            PlaybackMode.LIST_LOOP -> "列表循环"
            PlaybackMode.SINGLE_LOOP -> "单曲循环"
            PlaybackMode.RANDOM -> "随机播放"
        }
        tvStatusView?.text = "[ $playStateStr · $modeStr ]"
    }

    private fun formatTime(ms: Long): String {
        val totalSec = (ms / 1000).coerceAtLeast(0)
        val min = totalSec / 60
        val sec = totalSec % 60
        return String.format(Locale.getDefault(), "%02d:%02d", min, sec)
    }

    override fun onAction(action: Int): Boolean {
        return when (action) {
            NokiaKeyAction.ACTION_SELECT -> {
                sendServiceAction(PlaybackService.ACTION_PLAY_PAUSE)
                true
            }
            NokiaKeyAction.ACTION_LEFT -> {
                sendServiceAction(PlaybackService.ACTION_PREV)
                true
            }
            NokiaKeyAction.ACTION_RIGHT -> {
                sendServiceAction(PlaybackService.ACTION_NEXT)
                true
            }
            NokiaKeyAction.ACTION_UP -> {
                audioManager?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                true
            }
            NokiaKeyAction.ACTION_DOWN -> {
                audioManager?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                true
            }
            NokiaKeyAction.ACTION_SOFT_LEFT -> {
                val nextMode = PlaybackStateManager.togglePlayMode()
                updateModeStatus(nextMode, PlaybackStateManager.isPlaying.value)
                true
            }
            NokiaKeyAction.ACTION_SOFT_RIGHT -> {
                finish()
                true
            }
            else -> super.onAction(action)
        }
    }

    private fun sendServiceAction(action: String) {
        val intent = Intent(this, PlaybackService::class.java).apply {
            this.action = action
        }
        startService(intent)
    }
}
