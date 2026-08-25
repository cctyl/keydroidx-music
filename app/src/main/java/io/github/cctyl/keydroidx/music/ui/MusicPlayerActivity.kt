package io.github.cctyl.keydroidx.music.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import io.github.cctyl.keydroidx.music.R
import io.github.cctyl.keydroidx.music.library.LibraryManager
import io.github.cctyl.keydroidx.music.lyric.LrcLine
import io.github.cctyl.keydroidx.music.lyric.LrcParser
import io.github.cctyl.keydroidx.music.network.RetrofitClient
import io.github.cctyl.keydroidx.music.network.model.SongItem
import io.github.cctyl.keydroidx.music.player.PlaybackMode
import io.github.cctyl.keydroidx.music.player.PlaybackPrefs
import io.github.cctyl.keydroidx.music.player.PlaybackService
import io.github.cctyl.keydroidx.music.player.PlaybackStateManager
import io.github.cctyl.nokia.keycore.model.NokiaKeyAction
import io.github.cctyl.nokia.keycore.ui.NokiaBaseActivity
import io.github.cctyl.nokia.keycore.ui.NokiaFontManager
import io.github.cctyl.nokia.keycore.ui.NokiaIcons
import io.github.cctyl.nokia.keycore.ui.dialog.NokiaOptionsDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 正在播放详情页（黑胶唱机风格）
 *
 * UI 结构（自上而下）：
 * 1. 音量条浮层（默认隐藏，UP/DOWN 触发，淡入 200ms，2 秒无操作淡出）
 * 2. 标题栏（NokiaBaseActivity 注入）
 * 3. 黑胶唱片（带右上角唱针，播放时匀速旋转 8s/圈）
 * 4. 歌曲标题 + 歌手 / 专辑
 * 5. 歌词预览框（高亮青色背景 + 当前单行歌词 + [按 * 全屏] 提示）
 * 6. 进度条 + 时间（current_time / [OK 播放/暂停] / total_time）
 * 7. 5 列按键指南（← 上曲 | → 下曲 | * 歌词 | # 模式 | 左软:选项）
 * 8. 底部软键栏（NokiaBaseActivity 注入，选项 / 暂停 / 返回）
 */
class MusicPlayerActivity : NokiaBaseActivity() {

    // ── 视图引用 ─────────────────────────────────────────────
    private var vinylDisk: View? = null
    private var ivPlayPause: TextView? = null
    private var tvTitle: TextView? = null
    private var tvArtist: TextView? = null
    private var tvCurrentTime: TextView? = null
    private var tvTotalTime: TextView? = null
    private var tvPlayStatus: TextView? = null
    private var progressTrack: View? = null
    private var progressFill: View? = null
    private var layoutUpper: View? = null
    private var scrollLyric: ScrollView? = null
    private var lyricListContainer: LinearLayout? = null
    private var layoutVolumePanel: View? = null
    private var tvVolumeLabel: TextView? = null
    private var iconVolume: TextView? = null
    private var volumeFill: View? = null

    // ── 全屏歌词 ─────────────────────────────────────────────
    private var layoutLyricFullscreen: View? = null
    private var scrollLyricFull: ScrollView? = null
    private var lyricFullContainer: LinearLayout? = null
    private val lyricFullTextViews = mutableListOf<TextView>()
    private var focusLyricIndex = -1   // 全屏下用户浏览光标（-1=跟随当前播放行）

    // ── 系统服务 ─────────────────────────────────────────────
    private var audioManager: AudioManager? = null

    // ── 状态 ─────────────────────────────────────────────────
    private var isPlaying = false
    private var isLyricFull = false
    private var currentMode = PlaybackMode.LIST_LOOP
    private var vinylRotateAnim: ValueAnimator? = null

    // ── 歌词数据 ─────────────────────────────────────────────
    private var lrcLines: List<LrcLine> = emptyList()
    private var currentLyricIndex = -1
    private val lyricTextViews = mutableListOf<TextView>()

    // ── 工具 ─────────────────────────────────────────────────
    private val mainHandler = Handler(Looper.getMainLooper())
    private val hideVolumeRunnable = Runnable { hideVolumePanel() }

    override fun getContentLayoutRes(): Int = R.layout.activity_player

    override fun onInitViews() {
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // 首次进入若媒体音量为 0，自动提升到 50% 避免无声播放
        ensureAudibleVolume()

        // ── 查找 View ───────────────────────────────────────
        vinylDisk = findViewById(R.id.vinyl_disk)
        ivPlayPause = findViewById(R.id.iv_play_pause)
        tvTitle = findViewById(R.id.tv_song_title)
        tvArtist = findViewById(R.id.tv_song_artist)
        tvCurrentTime = findViewById(R.id.tv_current_time)
        tvTotalTime = findViewById(R.id.tv_total_time)
        tvPlayStatus = findViewById(R.id.tv_play_status)
        progressTrack = findViewById(R.id.progress_track)
        progressFill = findViewById(R.id.progress_fill)
        layoutUpper = findViewById(R.id.layout_upper)
        scrollLyric = findViewById(R.id.scroll_lyric)
        lyricListContainer = findViewById(R.id.layout_lyric_list)
        layoutVolumePanel = findViewById(R.id.layout_volume_panel)
        tvVolumeLabel = findViewById(R.id.tv_volume_label)
        iconVolume = findViewById(R.id.icon_volume)
        volumeFill = findViewById(R.id.volume_fill)

        // 全屏歌词视图
        layoutLyricFullscreen = findViewById(R.id.layout_lyric_fullscreen)
        scrollLyricFull = findViewById(R.id.scroll_lyric_full)
        lyricFullContainer = findViewById(R.id.layout_lyric_full_list)

        // ── 设置图标（使用 NokiaIcons 矢量字体）──────────────
        // 唱片中心图标：始终显示 ♪ music_note
        NokiaIcons.setIcon(ivPlayPause, NokiaIcons.ICON_MUSIC_NOTE)
        NokiaIcons.setIcon(iconVolume, NokiaIcons.ICON_VOLUME_UP)
        NokiaIcons.setIcon(findViewById(R.id.icon_guide_prev), NokiaIcons.ICON_SKIP_PREVIOUS)
        NokiaIcons.setIcon(findViewById(R.id.icon_guide_next), NokiaIcons.ICON_SKIP_NEXT)
        NokiaIcons.setIcon(findViewById(R.id.icon_guide_lyrics), NokiaIcons.ICON_SUBTITLES)
        NokiaIcons.setIcon(findViewById(R.id.icon_guide_mode), NokiaIcons.ICON_REPEAT)
        NokiaIcons.setIcon(findViewById(R.id.icon_guide_playpause), NokiaIcons.ICON_PLAY)

        // ── 文本兜底 ───────────────────────────────────────
        tvCurrentTime?.text = getString(R.string.unknown_time)
        tvTotalTime?.text = getString(R.string.unknown_time)
        tvPlayStatus?.text = getString(R.string.play_status_pause)
        tvVolumeLabel?.text = getString(R.string.volume_label, currentVolumePercent())

        // ── 歌词区占位 ───────────────────────────────────────
        showLyricPlaceholder()

        // ── 标题栏 & 软键栏 ─────────────────────────────────
        setPageTitle(getString(R.string.title_now_playing))
        setTitleIcon(NokiaIcons.ICON_PLAY_CIRCLE_FILLED)
        setSoftKeys(
            getString(R.string.softkey_options),
            getString(R.string.softkey_play),
            getString(R.string.softkey_back)
        )

        setStatusBarVisible(true)
        registerBatteryReceiver()

        // 让根视图持有焦点：本页无列表项可获焦，若窗口处于「无焦点视图」
        // 状态（触屏设备新窗口默认 touch mode），首个方向键会被 Android
        // 焦点框架用于退出触摸模式而被吞掉，到不了 onAction。
        // 窗口持有焦点视图后，第一个方向键即可正常派发。XML 已声明
        // focusable + focusableInTouchMode，这里主动 requestFocus 兜底，
        // 并 post 一次应对窗口焦点稍后才就绪的情况。
        val playerRoot = findViewById<View>(R.id.layout_player_root)
        playerRoot.requestFocus()
        playerRoot.post { playerRoot.requestFocus() }

        // 监听播放状态
        observePlaybackState()

        // 演示模式：注入模拟歌曲 + 启动进度计时器，方便 UI 验收
        if (DEMO_MODE) startDemoPlayback()
    }

    // ─────────────────────────────────────────────────────────
    //  演示播放（硬编码歌词 + 模拟进度推进）
    // ─────────────────────────────────────────────────────────

    private var demoPos = 0L
    private val demoRunnable = object : Runnable {
        override fun run() {
            demoPos += DEMO_TICK_MS
            if (demoPos >= DEMO_DURATION_MS) demoPos = 0L   // 循环播放
            PlaybackStateManager.updateProgress(demoPos, DEMO_DURATION_MS)
            mainHandler.postDelayed(this, DEMO_TICK_MS)
        }
    }

    private fun startDemoPlayback() {
        // 构造演示歌曲
        val demoSong = SongItem(
            id = 1L,
            name = "顺风顺水",
            artists = listOf(
                io.github.cctyl.keydroidx.music.network.model.ArtistItem(name = "邹念慈"),
                io.github.cctyl.keydroidx.music.network.model.ArtistItem(name = "繁星合唱团")
            ),
            album = io.github.cctyl.keydroidx.music.network.model.AlbumItem(name = "顺风顺水", picUrl = null),
            duration = DEMO_DURATION_MS
        )
        PlaybackStateManager.updateCurrentSong(demoSong)
        PlaybackStateManager.updatePlayingState(true)
        demoPos = 0L
        PlaybackStateManager.updateProgress(0L, DEMO_DURATION_MS)
        // 启动进度计时器
        mainHandler.postDelayed(demoRunnable, DEMO_TICK_MS)
    }

    /**
     * 演示模式：直接解析本地 LRC，不走网络。
     */
    private fun loadDemoLyrics() {
        lrcLines = LrcParser.parse(DEMO_LRC)
        currentLyricIndex = -1
        populateLyricLines()
        populateFullscreenLyrics()
        Log.d(TAG, "[DEMO] loaded ${lrcLines.size} lyric lines")
    }

    // ─────────────────────────────────────────────────────────
    //  音量工具
    // ─────────────────────────────────────────────────────────

    private fun currentVolumePercent(): Int {
        val am = audioManager ?: return 0
        val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        return if (max <= 0) 0 else (cur * 100 / max)
    }

    private fun ensureAudibleVolume() {
        val am = audioManager ?: return
        val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (cur == 0 && max > 0) {
            // 提升到 50%，避免用户首听无声
            am.setStreamVolume(AudioManager.STREAM_MUSIC, max / 2, 0)
        }
    }

    private fun showVolumePanel() {
        val am = audioManager ?: return
        val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val percent = if (max <= 0) 0 else (cur * 100 / max)

        // 根据音量切换图标
        val icon = if (cur == 0) NokiaIcons.ICON_VOLUME_OFF else NokiaIcons.ICON_VOLUME_UP
        NokiaIcons.setIcon(iconVolume, icon)

        tvVolumeLabel?.text = getString(R.string.volume_label, percent)

        // 更新填充宽度
        volumeFill?.let { fill ->
            val parentWidth = (fill.parent as? View)?.width ?: 0
            val lp = fill.layoutParams
            lp.width = (parentWidth * percent / 100).coerceAtLeast(if (percent > 0) 1 else 0)
            fill.layoutParams = lp
        }

        // 显示并自动隐藏
        layoutVolumePanel?.let { panel ->
            if (panel.visibility != View.VISIBLE) {
                panel.alpha = 0f
                panel.visibility = View.VISIBLE
                panel.animate().alpha(1f).setDuration(180).start()
            }
        }
        mainHandler.removeCallbacks(hideVolumeRunnable)
        mainHandler.postDelayed(hideVolumeRunnable, VOLUME_HIDE_DELAY_MS)
    }

    private fun hideVolumePanel() {
        layoutVolumePanel?.animate()
            ?.alpha(0f)
            ?.setDuration(220)
            ?.withEndAction { layoutVolumePanel?.visibility = View.GONE }
            ?.start()
    }

    // ─────────────────────────────────────────────────────────
    //  黑胶唱片旋转动画
    // ─────────────────────────────────────────────────────────

    private fun startVinylRotation() {
        val disk = vinylDisk ?: return
        // 取消已有的动画，避免叠加
        vinylRotateAnim?.cancel()
        disk.clearAnimation()

        // 启用硬件层：整张唱片（含音符 TextView）只栅格化一次，
        // 之后每帧由 GPU 矩阵变换旋转，消除文本逐帧取整带来的抖动
        disk.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        // 圆心必须在布局完成后按实际尺寸设置：若在 measure 之前启动动画，
        // width=0 会把 pivot 设成 (0,0)，转盘绕左上角转而“消失一部分”
        if (disk.width > 0) {
            disk.pivotX = disk.width / 2f
            disk.pivotY = disk.height / 2f
        } else {
            disk.addOnLayoutChangeListener(vinylPivotFixListener)
        }

        val anim = ObjectAnimator.ofFloat(disk, View.ROTATION, 0f, 360f).apply {
            duration = 8000L           // 8 秒一圈，匀速
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
        }
        vinylRotateAnim = anim
        anim.start()
    }

    /** 布局完成后校正转盘圆心并移除自身监听 */
    private val vinylPivotFixListener: View.OnLayoutChangeListener = object : View.OnLayoutChangeListener {
        override fun onLayoutChange(
            v: View, left: Int, top: Int, right: Int, bottom: Int,
            oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int
        ) {
            v.pivotX = v.width / 2f
            v.pivotY = v.height / 2f
            v.removeOnLayoutChangeListener(this)
            Log.d(TAG, "vinyl pivot fixed: ${v.width}x${v.height}")
        }
    }

    private fun stopVinylRotation() {
        val disk = vinylDisk ?: return
        vinylRotateAnim?.cancel()
        // 属性动画取消后 rotation 保持在当前角度自然静止；释放硬件层
        disk.setLayerType(View.LAYER_TYPE_NONE, null)
    }

    // ─────────────────────────────────────────────────────────
    //  进度条更新
    // ─────────────────────────────────────────────────────────

    private fun updateProgressFill(pos: Long, dur: Long) {
        val track = progressTrack ?: return
        val fill = progressFill ?: return

        val trackWidth = track.width
        if (trackWidth <= 0 || dur <= 0L) {
            // 宽度未测量或时长未知，填充置 0
            applyFillWidth(fill, 0)
            return
        }
        val ratio = (pos.toFloat() / dur.toFloat()).coerceIn(0f, 1f)
        applyFillWidth(fill, (trackWidth * ratio).toInt())
    }

    private fun applyFillWidth(fill: View, widthPx: Int) {
        val lp = fill.layoutParams
        if (lp == null) return
        if (lp.width != widthPx) {
            lp.width = widthPx
            fill.layoutParams = lp
        }
    }

    // ─────────────────────────────────────────────────────────
    //  播放状态观察
    // ─────────────────────────────────────────────────────────

    private fun observePlaybackState() {
        lifecycleScope.launch {
            PlaybackStateManager.currentSong.collectLatest { song: SongItem? ->
                if (song != null) {
                    tvTitle?.text = song.name
                    tvArtist?.text = song.artists?.joinToString("/") { it.name } ?: "未知艺术家"
                    // 切歌时加载歌词：演示模式用本地 LRC，真实模式走网络
                    if (DEMO_MODE) loadDemoLyrics() else loadLyrics(song.id)
                } else {
                    tvTitle?.text = "暂无曲目"
                    tvArtist?.text = "未知艺术家"
                    lrcLines = emptyList()
                    currentLyricIndex = -1
                    showLyricPlaceholder()
                }
            }
        }

        lifecycleScope.launch {
            PlaybackStateManager.isPlaying.collectLatest { playing ->
                isPlaying = playing
                // 中心图标逻辑（对齐 HTML 原型）：
                //   playing=true   → ♪ music_note （音乐流动）
                //   playing=false  → ∥ pause      （暂停状态）
                // 中心图标始终保持 ♪ music_note，不随状态切换
                // （播放状态由黑胶旋转 + 底部软键文字体现）
                // 全屏歌词模式下中间软键是「回正进度」，不能被播放状态覆盖
                if (!isLyricFull) {
                    setSoftCenter(
                        if (playing) getString(R.string.softkey_pause)
                        else getString(R.string.softkey_play)
                    )
                }
                tvPlayStatus?.text = if (playing)
                    getString(R.string.play_status_playing)
                else
                    getString(R.string.play_status_pause)

                // 联动黑胶唱片旋转 + 中间指南条播放/暂停图标
                if (playing) startVinylRotation() else stopVinylRotation()
                NokiaIcons.setIcon(
                    findViewById(R.id.icon_guide_playpause),
                    if (playing) NokiaIcons.ICON_PAUSE else NokiaIcons.ICON_PLAY
                )
            }
        }

        lifecycleScope.launch {
            PlaybackStateManager.playMode.collectLatest { mode ->
                currentMode = mode
                updateModeIcon(mode)
            }
        }

        lifecycleScope.launch {
            PlaybackStateManager.currentPositionMs.collectLatest { pos ->
                val dur = PlaybackStateManager.durationMs.value
                tvCurrentTime?.text = formatTime(pos)
                tvTotalTime?.text = formatTime(dur)
                updateProgressFill(pos, dur)
                // 同步歌词高亮（普通 + 全屏）
                updateLyricHighlight(pos)
                updateFullscreenLyricHighlight(pos)
            }
        }
    }

    private fun updateModeIcon(mode: PlaybackMode) {
        val iconView = findViewById<TextView>(R.id.icon_guide_mode)
        when (mode) {
            PlaybackMode.LIST_LOOP -> NokiaIcons.setIcon(iconView, NokiaIcons.ICON_REPEAT)
            PlaybackMode.SINGLE_LOOP -> NokiaIcons.setIcon(iconView, NokiaIcons.ICON_REPEAT_ONE)
            PlaybackMode.RANDOM -> NokiaIcons.setIcon(iconView, NokiaIcons.ICON_SHUFFLE)
        }
    }

    private fun formatTime(ms: Long): String {
        if (ms <= 0L) return getString(R.string.unknown_time)
        val totalSec = ms / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        return String.format(Locale.getDefault(), "%02d:%02d", min, sec)
    }

    // ─────────────────────────────────────────────────────────
    //  按键交互
    // ─────────────────────────────────────────────────────────

    override fun onAction(action: Int): Boolean {
        // ── 全屏歌词模式：独立按键语义 ──
        if (isLyricFull) {
            return when (action) {
                NokiaKeyAction.UP -> {
                    moveFullscreenFocus(-1)
                    true
                }
                NokiaKeyAction.DOWN -> {
                    moveFullscreenFocus(1)
                    true
                }
                NokiaKeyAction.LEFT, NokiaKeyAction.RIGHT -> true   // 忽略左右
                NokiaKeyAction.SELECT -> {
                    seekToFocusedLyric()
                    true
                }
                NokiaKeyAction.SOFT_LEFT -> {
                    // 收藏歌曲（占位）
                    Toast.makeText(this, "已收藏：${tvTitle?.text ?: ""}", Toast.LENGTH_SHORT).show()
                    true
                }
                NokiaKeyAction.SOFT_RIGHT -> {
                    exitFullscreenLyric()
                    true
                }
                else -> super.onAction(action)
            }
        }

        return when (action) {
            NokiaKeyAction.SELECT -> {
                if (DEMO_MODE) {
                    isPlaying = !isPlaying
                    PlaybackStateManager.updatePlayingState(isPlaying)
                    if (isPlaying) mainHandler.postDelayed(demoRunnable, DEMO_TICK_MS)
                    else mainHandler.removeCallbacks(demoRunnable)
                } else {
                    sendServiceAction(PlaybackService.ACTION_PLAY_PAUSE)
                }
                true
            }
            NokiaKeyAction.LEFT -> {
                sendServiceAction(PlaybackService.ACTION_PREV)
                true
            }
            NokiaKeyAction.RIGHT -> {
                sendServiceAction(PlaybackService.ACTION_NEXT)
                true
            }
            NokiaKeyAction.UP -> {
                audioManager?.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_RAISE,
                    0
                )
                showVolumePanel()
                true
            }
            NokiaKeyAction.DOWN -> {
                audioManager?.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_LOWER,
                    0
                )
                showVolumePanel()
                true
            }
            NokiaKeyAction.SOFT_LEFT -> {
                showPlaybackOptions()
                true
            }
            NokiaKeyAction.SOFT_RIGHT -> {
                finish()
                true
            }
            else -> super.onAction(action)
        }
    }

    /**
     * 全屏歌词：上下移动光标。
     */
    private fun moveFullscreenFocus(delta: Int) {
        if (lrcLines.isEmpty()) return
        if (focusLyricIndex < 0) focusLyricIndex = currentLyricIndex.coerceAtLeast(0)
        focusLyricIndex = (focusLyricIndex + delta).coerceIn(0, lrcLines.lastIndex)
        updateFullscreenLyricHighlight(PlaybackStateManager.currentPositionMs.value)
    }

    /**
     * 全屏歌词：OK 键「回正进度」——跳转到光标行的 timestamp。
     */
    private fun seekToFocusedLyric() {
        // 跟随模式（-1）下按确认：默认跳转到当前播放行
        val idx = if (focusLyricIndex in lrcLines.indices) focusLyricIndex else currentLyricIndex
        if (idx !in lrcLines.indices) return
        val targetMs = lrcLines[idx].timeMs
        if (DEMO_MODE) {
            demoPos = targetMs
            PlaybackStateManager.updateProgress(targetMs, DEMO_DURATION_MS)
        } else {
            // 真实模式：发 seek 给 Service
            sendServiceAction(PlaybackService.ACTION_SEEK, targetMs)
        }
        // 回正后光标重新跟随当前播放行
        focusLyricIndex = -1
        updateFullscreenLyricHighlight(targetMs)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_STAR -> {
                toggleLyricFull()
                true
            }
            KeyEvent.KEYCODE_POUND -> {
                val nextMode = PlaybackStateManager.togglePlayMode()
                updateModeIcon(nextMode)
                showModeToast(nextMode)
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    // ─────────────────────────────────────────────────────────
    //  模式切换 Toast
    // ─────────────────────────────────────────────────────────

    private fun showModeToast(mode: PlaybackMode) {
        val nameRes = when (mode) {
            PlaybackMode.LIST_LOOP -> R.string.mode_list_loop
            PlaybackMode.SINGLE_LOOP -> R.string.mode_single_loop
            PlaybackMode.RANDOM -> R.string.mode_shuffle
        }
        Toast.makeText(this, getString(R.string.mode_toast, getString(nameRes)), Toast.LENGTH_SHORT).show()
    }

    // ─────────────────────────────────────────────────────────
    //  播放选项菜单（左软键）
    // ─────────────────────────────────────────────────────────

    private fun showPlaybackOptions() {
        // 统一图标规格 18dp 白色（对齐 keydroidx-core 规范）
        val iconColor = android.graphics.Color.WHITE
        val iconSize = (18 * resources.displayMetrics.density).toInt()

        // 选项菜单：播放列表、收藏/取消收藏、音质设置、返回
        val currentSong = PlaybackStateManager.currentSong.value
        val isFav = currentSong != null && LibraryManager.isFavorite(currentSong.id)

        val dialog = NokiaOptionsDialog(this, getString(R.string.softkey_options))
            .addItem(
                1,
                getString(R.string.option_play_queue),
                NokiaIcons.createDrawable(this, NokiaIcons.ICON_QUEUE_MUSIC, iconSize, iconColor)
            )
            .addItem(
                2,
                if (isFav) "取消收藏" else getString(R.string.softkey_favorite),
                NokiaIcons.createDrawable(this, if (isFav) NokiaIcons.ICON_FAVORITE_BORDER else NokiaIcons.ICON_FAVORITE, iconSize, iconColor)
            )
            .addItem(
                3,
                getString(R.string.option_quality),
                NokiaIcons.createDrawable(this, NokiaIcons.ICON_SETTINGS, iconSize, iconColor)
            )
            .addItem(
                4,
                getString(R.string.softkey_back),
                NokiaIcons.createDrawable(this, NokiaIcons.ICON_ARROW_BACK, iconSize, iconColor)
            )
            .setOnOptionSelectedListener { index, _ ->
                when (index) {
                    0 -> openCurrentQueue()                      // 1. 播放列表
                    1 -> toggleFavorite()                        // 2. 收藏 / 取消收藏
                    2 -> showQualityPicker()                     // 3. 音质设置
                    3 -> finish()                                // 4. 返回
                }
            }
        dialog.show()
    }

    /**
     * 打开当前播放队列列表
     */
    private fun openCurrentQueue() {
        val playlist = PlaybackStateManager.playlist.value
        if (playlist.isEmpty()) {
            Toast.makeText(this, getString(R.string.toast_queue_empty), Toast.LENGTH_SHORT).show()
            return
        }
        val displaySongs = ArrayList(playlist.map { song ->
            SongDisplayItem(
                id = song.id,
                title = song.name,
                artist = song.artistName,
                isFav = LibraryManager.isFavorite(song.id),
                isVip = song.fee == 1,
                noCopyright = song.noCopyright
            )
        })
        PlaylistDetailActivity.start(
            this,
            getString(R.string.title_current_queue),
            NokiaIcons.ICON_QUEUE_MUSIC,
            displaySongs
        )
    }

    /**
     * 收藏 / 取消收藏当前正在播放的歌曲
     */
    private fun toggleFavorite() {
        val current = PlaybackStateManager.currentSong.value ?: return
        lifecycleScope.launch {
            val isFavNow = LibraryManager.toggleFavorite(this@MusicPlayerActivity, current)
            val msg = if (isFavNow) "已收藏到「我喜欢的音乐」" else "已取消收藏"
            Toast.makeText(this@MusicPlayerActivity, msg, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 音质选择二级弹窗：标准/较高/极高/无损/Hi-Res。
     * 当前档位置顶显示，切换后持久化，下一首播放生效（当前曲目不打断）。
     */
    private fun showQualityPicker() {
        val iconColor = android.graphics.Color.WHITE
        val iconSize = (18 * resources.displayMetrics.density).toInt()
        val current = PlaybackPrefs.qualityLevel(this)
        val labels = mapOf(
            "standard" to getString(R.string.quality_standard),
            "higher" to getString(R.string.quality_higher),
            "exhigh" to getString(R.string.quality_exhigh),
            "lossless" to getString(R.string.quality_lossless),
            "hires" to getString(R.string.quality_hires)
        )

        val dialog = NokiaOptionsDialog(this, getString(R.string.title_quality))
        // 当前档位置顶
        dialog.addItem(
            0,
            "● ${labels[current]}",
            NokiaIcons.createDrawable(this, NokiaIcons.ICON_CHECK, iconSize, iconColor)
        )
        var seq = 1
        for (level in PlaybackPrefs.QUALITY_LEVELS) {
            if (level == current) continue
            dialog.addItem(
                seq++,
                labels[level],
                NokiaIcons.createDrawable(this, NokiaIcons.ICON_MUSIC_NOTE, iconSize, iconColor)
            )
        }
        dialog.setOnOptionSelectedListener { index, _ ->
            if (index > 0) {
                // 跳过置顶的当前档位后，映射回实际 level
                val others = PlaybackPrefs.QUALITY_LEVELS.filter { it != current }
                val chosen = others.getOrNull(index - 1) ?: return@setOnOptionSelectedListener
                PlaybackPrefs.setQualityLevel(this, chosen)
                Toast.makeText(
                    this,
                    getString(R.string.quality_applied, labels[chosen]),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        dialog.show()
    }

    /**
     * * 键：进入/退出全屏歌词浏览。
     * 进入时：覆盖层盖住内容区，标题改「歌词浏览」，软键改「收藏歌曲/回正进度/返回播放」。
     * 退出时：恢复播放详情。
     */
    private fun toggleLyricFull() {
        if (isLyricFull) exitFullscreenLyric() else enterFullscreenLyric()
    }

    private fun enterFullscreenLyric() {
        isLyricFull = true
        // 约定：-1 = 光标跟随当前播放行；手动上下浏览时才设为具体行号
        focusLyricIndex = -1
        Log.d(TAG, "[lyric-debug] enter fullscreen: lines=${lrcLines.size} views=${lyricFullTextViews.size}")
        layoutLyricFullscreen?.visibility = View.VISIBLE
        setPageTitle(getString(R.string.title_lyric_browse))
        setTitleIcon(NokiaIcons.ICON_LYRICS)
        setSoftKeys(
            getString(R.string.softkey_favorite),
            getString(R.string.softkey_seek),
            getString(R.string.softkey_return_play)
        )
        // 立即刷新一次高亮并滚到当前行
        updateFullscreenLyricHighlight(PlaybackStateManager.currentPositionMs.value)
    }

    private fun exitFullscreenLyric() {
        isLyricFull = false
        focusLyricIndex = -1
        layoutLyricFullscreen?.visibility = View.GONE
        setPageTitle(getString(R.string.title_now_playing))
        setTitleIcon(NokiaIcons.ICON_PLAY_CIRCLE_FILLED)
        setSoftCenter(if (isPlaying) getString(R.string.softkey_pause) else getString(R.string.softkey_play))
        setSoftLeft(getString(R.string.softkey_options))
        setSoftRight(getString(R.string.softkey_back))
    }

    /**
     * 填充全屏歌词：元数据 + 歌词行。
     */
    private fun populateFullscreenLyrics() {
        val container = lyricFullContainer ?: return
        container.removeAllViews()
        lyricFullTextViews.clear()

        if (lrcLines.isEmpty()) {
            val tv = buildFullscreenLyricTextView().apply {
                text = getString(R.string.no_lyric)
                setTextColor(Color.parseColor("#64748B"))
            }
            container.addView(tv)
            return
        }
        for (line in lrcLines) {
            val tv = buildFullscreenLyricTextView().apply {
                text = line.text
                setTextColor(Color.parseColor("#E0FFFFFF"))
            }
            container.addView(tv)
            lyricFullTextViews.add(tv)
        }
        adjustFullscreenLyricPadding()
        // 动态创建的行补一次点阵字体+缩放（同 PlaylistDetailActivity）
        NokiaFontManager.applyToViewTree(container)
    }

    /**
     * 全屏歌词单行 TextView（居中、可多行、点阵风）。
     */
    private fun buildFullscreenLyricTextView(): TextView {
        return TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(5)
                bottomMargin = dp(5)
            }
            gravity = Gravity.CENTER
            textSize = 13f
            setTextColor(Color.parseColor("#E0FFFFFF"))
            setLineSpacing(dp(2).toFloat(), 1f)
            includeFontPadding = false
            setSingleLine(false)
            maxLines = 3
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }
    }

    /**
     * 动态调整全屏歌词容器的上下 padding，保证首行和末行滚动时都能完美停在视口正中央，
     * 绝不贴边或被标题栏/软键栏遮挡。
     */
    private fun adjustFullscreenLyricPadding() {
        val sv = scrollLyricFull ?: return
        val wrapper = findViewById<LinearLayout>(R.id.layout_lyric_full_wrapper) ?: return
        val h = sv.height
        if (h <= 0) return
        val targetPadding = ((h / 2) - dp(18)).coerceAtLeast(dp(60))
        if (wrapper.paddingTop != targetPadding || wrapper.paddingBottom != targetPadding) {
            wrapper.setPadding(wrapper.paddingLeft, targetPadding, wrapper.paddingRight, targetPadding)
        }
    }

    /**
     * 全屏歌词高亮：
     * - 当前播放行且光标选中：青色圆角高亮框 + 白字加粗
     * - 用户方向键选中的光标行（非播放行）：深蓝焦点框 + 白字加粗
     * - 正在播放行（光标在别处）：青色圆角播放框 + 青字加粗（清晰提示正在唱这句）
     * - 普通歌词行：透明背景 + 灰字正常
     */
    private fun updateFullscreenLyricHighlight(posMs: Long) {
        Log.d(TAG, "[lyric-tick] full=$isLyricFull lines=${lrcLines.size} views=${lyricFullTextViews.size} pos=$posMs")
        if (!isLyricFull || lrcLines.isEmpty() || lyricFullTextViews.isEmpty()) return

        // 当前播放行
        var playing = -1
        for (i in lrcLines.indices) {
            if (lrcLines[i].timeMs <= posMs) playing = i else break
        }
        // 光标跟随：focusLyricIndex < 0 表示「跟随当前播放行」。
        // 注意：此处只读不改，绝不能把 playing 写进 focusLyricIndex，
        // 否则它会被冻结在第一次的 playing 值上，导致滚动目标永远停在某行（表现为歌词界面一直停在顶部）。

        val cyanBg = resources.getDrawable(R.drawable.bg_lyric_current)
        val focusBg = resources.getDrawable(R.drawable.bg_focused_item)
        val white = Color.parseColor("#FFFFFF")
        val cyan = Color.parseColor("#38BDF8")
        val normal = Color.parseColor("#B0B0B0")

        val cursor = if (focusLyricIndex in lyricFullTextViews.indices) focusLyricIndex else playing

        lyricFullTextViews.forEachIndexed { i, tv ->
            val isCursor = (i == cursor)
            val isPlaying = (i == playing)

            when {
                // 1. 光标正好停在当前播放行（或默认跟随模式下的播放行）
                isCursor && isPlaying -> {
                    tv.background = cyanBg
                    tv.setTextColor(white)
                    tv.setTypeface(null, android.graphics.Typeface.BOLD)
                    tv.textSize = 14f
                }
                // 2. 用户方向键选中的光标行（但不是当前播放行）
                isCursor && !isPlaying -> {
                    tv.background = focusBg
                    tv.setTextColor(white)
                    tv.setTypeface(null, android.graphics.Typeface.BOLD)
                    tv.textSize = 14f
                }
                // 3. 当前播放行（但用户光标移到了其他行）
                !isCursor && isPlaying -> {
                    tv.background = cyanBg
                    tv.setTextColor(cyan)
                    tv.setTypeface(null, android.graphics.Typeface.BOLD)
                    tv.textSize = 14f
                }
                // 4. 普通歌词行
                else -> {
                    tv.background = null
                    tv.setTextColor(normal)
                    tv.setTypeface(null, android.graphics.Typeface.NORMAL)
                    tv.textSize = 13f
                }
            }
        }

        // 滚动使光标行居中（光标 = 播放行时跟随，否则跟随光标）
        val target = if (focusLyricIndex in lyricFullTextViews.indices) focusLyricIndex else playing
        Log.d(TAG, "[lyric-scroll] check: focus=$focusLyricIndex playing=$playing target=$target")
        if (target in lyricFullTextViews.indices) {
            scrollLyricFull?.post {
                val sv = scrollLyricFull ?: return@post
                adjustFullscreenLyricPadding()
                val tv = lyricFullTextViews[target]
                val wrapper = findViewById<LinearLayout>(R.id.layout_lyric_full_wrapper)
                val wrapperPadTop = wrapper?.paddingTop ?: 0
                val tvCenterInScrollView = wrapperPadTop + tv.top + tv.height / 2
                val dest = (tvCenterInScrollView - sv.height / 2).coerceAtLeast(0)
                sv.smoothScrollTo(0, dest)
            }
        }
    }

    private fun accumulateFullscreenTop(index: Int): Int {
        // 累加前 index 行高度（元数据头已移除，内容顶即容器顶）
        var top = 0
        val container = lyricFullContainer ?: return top
        for (i in 0 until index) {
            if (i < container.childCount) top += container.getChildAt(i).height
        }
        return top
    }

    // ─────────────────────────────────────────────────────────
    //  歌词加载 / 渲染 / 高亮
    // ─────────────────────────────────────────────────────────

    /**
     * 异步拉取 LRC 文本并解析为 LrcLine 列表，然后填充到歌词区。
     */
    private fun loadLyrics(songId: Long) {
        lifecycleScope.launch {
            try {
                val resp = withContext(Dispatchers.IO) {
                    RetrofitClient.api.getLyric(id = songId)
                }
                val raw = resp.lrc?.lyric
                if (raw.isNullOrEmpty()) {
                    lrcLines = emptyList()
                    currentLyricIndex = -1
                    showLyricPlaceholder()
                    return@launch
                }
                lrcLines = LrcParser.parse(raw)
                currentLyricIndex = -1
                populateLyricLines()
                populateFullscreenLyrics()
                Log.d(TAG, "Loaded ${lrcLines.size} lyric lines for song $songId")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load lyrics for song $songId: ${e.message}", e)
                lrcLines = emptyList()
                currentLyricIndex = -1
                showLyricPlaceholder()
            }
        }
    }

    /**
     * 无歌词时的占位行。
     */
    private fun showLyricPlaceholder() {
        val container = lyricListContainer ?: return
        container.removeAllViews()
        lyricTextViews.clear()
        val tv = buildLyricTextView().apply {
            text = getString(R.string.no_lyric)
            setTextColor(Color.parseColor("#64748B"))
        }
        container.addView(tv)
    }

    /**
     * 将 lrcLines 全部渲染为 TextView 加入容器。
     */
    private fun populateLyricLines() {
        val container = lyricListContainer ?: return
        container.removeAllViews()
        lyricTextViews.clear()
        if (lrcLines.isEmpty()) {
            showLyricPlaceholder()
            return
        }
        for (line in lrcLines) {
            val tv = buildLyricTextView().apply {
                text = line.text
                setTextColor(Color.parseColor("#B0B0B0"))
            }
            container.addView(tv)
            lyricTextViews.add(tv)
        }
        // 动态创建的行补一次点阵字体+缩放（同 PlaylistDetailActivity）
        NokiaFontManager.applyToViewTree(container)
    }

    /**
     * 构造单个歌词 TextView（居中、单行、点阵风）。
     */
    private fun buildLyricTextView(): TextView {
        return TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(3)
                bottomMargin = dp(3)
            }
            gravity = Gravity.CENTER
            textSize = 11f          // sp
            setTextColor(Color.parseColor("#B0B0B0"))
            setLineSpacing(dp(2).toFloat(), 1f)
            includeFontPadding = false
            setSingleLine(false)
            maxLines = 2
        }
    }

    /**
     * 根据当前播放进度高亮对应歌词行，并滚动使其居中。
     */
    private fun updateLyricHighlight(posMs: Long) {
        if (lrcLines.isEmpty() || lyricTextViews.isEmpty()) return

        // 二分查找当前行：最后一行 timeMs <= posMs
        var idx = -1
        for (i in lrcLines.indices) {
            if (lrcLines[i].timeMs <= posMs) idx = i else break
        }
        if (idx == currentLyricIndex) return   // 未变化则不重绘
        currentLyricIndex = idx

        val accent = Color.parseColor("#38BDF8")
        val normal = Color.parseColor("#B0B0B0")
        val fontScale = NokiaFontManager.getFontScale()
        val customTf = NokiaFontManager.getTypeface(this)

        lyricTextViews.forEachIndexed { i, tv ->
            if (i == idx) {
                tv.setTextColor(accent)
                tv.setTypeface(customTf, android.graphics.Typeface.BOLD)
                tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f * fontScale)
            } else {
                tv.setTextColor(normal)
                tv.setTypeface(customTf, android.graphics.Typeface.NORMAL)
                tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11f * fontScale)
            }
        }

        // 滚动使当前行尽量居中
        if (idx in lyricTextViews.indices) {
            val sv = scrollLyric ?: return
            val target = lyricTextViews[idx]
            sv.post {
                val targetTop = accumulateTop(idx)
                val targetCenter = targetTop + target.height / 2
                val scrollTarget = (targetCenter - sv.height / 2).coerceAtLeast(0)
                Log.d(TAG, "[lyric-scroll-plain] idx=$idx targetTop=$targetCenter dest=$scrollTarget before=${sv.scrollY} max=${sv.height - (lyricListContainer?.height ?: 0)}")
                sv.smoothScrollTo(0, scrollTarget)
                sv.postDelayed({
                    Log.d(TAG, "[lyric-scroll-plain] after idx=$idx scrollY=${scrollLyric?.scrollY}")
                }, 600)
            }
        }
    }

    /**
     * 累加第 [index] 行之前所有兄弟项的高度，计算相对于 ScrollView 内容顶部的 top。
     */
    private fun accumulateTop(index: Int): Int {
        val container = lyricListContainer ?: return 0
        var top = 0
        for (i in 0 until index) {
            if (i < container.childCount) {
                top += container.getChildAt(i).height
            }
        }
        return top
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density + 0.5f).toInt()
    }

    private fun sendServiceAction(action: String, positionMs: Long = -1L) {
        val intent = Intent(this, PlaybackService::class.java).apply {
            this.action = action
            if (positionMs >= 0) putExtra(PlaybackService.EXTRA_SEEK_POSITION, positionMs)
        }
        startService(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacks(hideVolumeRunnable)
        mainHandler.removeCallbacks(demoRunnable)
        vinylRotateAnim?.cancel()
    }

    companion object {
        private const val TAG = "MusicPlayerActivity"
        private const val VOLUME_HIDE_DELAY_MS = 1800L

        // === 演示模式：硬编码真实 LRC + 模拟进度，方便 UI 验收 ===
        private const val DEMO_MODE = false
        private const val DEMO_DURATION_MS = 255_000L  // 4:15
        private const val DEMO_TICK_MS = 1000L        // 每秒推进
        private const val DEMO_LRC = """[00:00.00]顺风顺水 - 邹念慈
[00:08.00]风起的时候 谁在等候
[00:16.00]月先洒在 远方的山头
[00:24.00]你说要走 我没有挽留
[00:32.00]只把心事 藏进眼眸
[00:42.00]顺风顺水 一路漂流
[00:50.00]带着回忆 去向天尽头
[00:58.00]那些温柔 那些忧愁
[01:08.00]都随流水 慢慢走
[01:16.00]云开的清晨 又是新的渡口
[01:26.00]我在岸边 等一叶轻舟
[01:34.00]顺风顺水 别回头
[01:42.00]前方的路 还要走
[01:52.00]把思念 折成纸鹤
[02:00.00]放飞在 这一片星河
[02:10.00]愿你顺风 顺水 顺心意
[02:18.00]愿你此生 不再添泊
[02:28.00]山高水长 总有归处
[02:36.00]风轻云淡 便是归途
[02:46.00]顺风顺水 各自安好
[02:54.00]相逢一笑 已是最好
[03:04.00]岁月不语 流水无声
[03:14.00]愿你顺风 顺水 一生
[03:24.00]（间奏）
[03:50.00]风起的时候 谁在等候
[04:00.00]月先依旧 山河依旧
[04:10.00]顺风顺水 别回头"""
    }
}
