package io.github.cctyl.keydroidx.music.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import io.github.cctyl.keydroidx.music.util.NLog as Log
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import androidx.lifecycle.lifecycleScope
import io.github.cctyl.keydroidx.music.R
import io.github.cctyl.keydroidx.music.cache.CommentCache
import io.github.cctyl.keydroidx.music.library.FavoriteStore
import io.github.cctyl.keydroidx.music.download.DownloadManager
import io.github.cctyl.keydroidx.music.download.DownloadStatus
import io.github.cctyl.keydroidx.music.lyric.LrcLine
import io.github.cctyl.keydroidx.music.lyric.LrcParser
import io.github.cctyl.keydroidx.music.network.CommentApi
import io.github.cctyl.keydroidx.music.network.RetrofitClient
import io.github.cctyl.keydroidx.music.network.model.SongItem
import io.github.cctyl.keydroidx.music.player.PlaybackMode
import io.github.cctyl.keydroidx.music.player.PlaybackPrefs
import io.github.cctyl.keydroidx.music.player.PlaybackService
import io.github.cctyl.keydroidx.music.player.PlaybackStateManager
import io.github.cctyl.keydroidx.music.util.CoverLoader
import io.github.cctyl.nokia.common.model.KeydroidxKeyAction
import io.github.cctyl.nokia.keycore.ui.KeydroidxBaseActivity
import io.github.cctyl.nokia.common.ui.KeydroidxFontManager
import io.github.cctyl.nokia.common.ui.KeydroidxIcons
import io.github.cctyl.nokia.common.ui.dialog.KeydroidxOptionsDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 正在播放详情页（黑胶唱机风格）
 *
 * UI 结构（自下而上，参考网易云播放页）：
 * 1. 背景层：当前歌曲封面铺满整屏，压一层自上而下加深的遮罩
 *    （封面先降采样再交给 ImageView 放大 = 低成本「模糊封面底」）
 * 2. 歌曲标题 + 歌手（顶部，白色带投影）
 * 3. 黑胶圆盘：居中且尽量大（尺寸按可用空间自适应），**盘心是圆形专辑封面**，
 *    播放时匀速旋转 8s/圈；无封面回落红色中心盖 + ♪ 图标
 * 4. 进度条 + 时间（current_time / [OK 播放/暂停] / total_time）
 * 5. 5 列按键指南（* 歌词 | ← 上曲 | OK 播放 | → 下曲 | # 模式）
 * 6. 歌曲操作栏（红心[1] / 下载[2] / 评论+数量[3]，单行三等分）
 * 7. 标题栏 + 底部软键栏（KeydroidxBaseActivity 注入，选项 / 暂停 / 返回）
 *
 * 本页**不再显示常驻歌词列表**：歌词只出现在 `*` 键唤起的全屏歌词层；
 * 但「当前歌词行」仍会随进度计算并推给 PlaybackStateManager，
 * 锁屏歌词页 / 桌面组件 / 通知栏都依赖这个值。
 */
class MusicPlayerActivity : KeydroidxBaseActivity() {

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
    private var vinylCenter: View? = null
    /** 铺满整屏的封面背景（降采样后交给 ImageView 放大，等效模糊底） */
    private var ivPlayerBg: ImageView? = null
    /** 全屏歌词层的虚化封面背景：与 [ivPlayerBg] 共用同一个 Bitmap，不额外占内存 */
    private var ivLyricBg: ImageView? = null
    /** 圆盘中心的圆形专辑封面 */
    private var ivVinylCover: ImageView? = null

    // ── 歌曲操作栏（红心 / 下载 / 评论+数量）──────────
    private var iconActionFavorite: TextView? = null
    private var iconActionDownload: TextView? = null
    private var iconActionComment: TextView? = null
    private var tvCommentCount: TextView? = null
    /** 当前歌曲的评论总数（null = 未知/拉取失败/本地歌曲），进入评论页时透传 */
    private var currentCommentTotal: Int? = null
    /** 评论数拉取协程：切歌时取消上一个，防止慢响应覆盖新歌的数字 */
    private var commentCountJob: Job? = null

    // ── 封面（背景 + 盘心）──────────────────────────────────
    /** 已加载的封面 URL：切歌去重，同一首反复回调不重复下载/解码 */
    private var loadedCoverUrl: String? = null
    private var coverJob: Job? = null
    /** 圆盘尺寸是否已按可用空间自适应过：改尺寸会再次触发 layout 回调，需要刹车 */
    private var vinylSizeApplied = false

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

    // ── 工具 ─────────────────────────────────────────────────
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun getContentLayoutRes(): Int = R.layout.activity_player

    override fun onInitViews() {
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        // XML 静态经典蓝配色 → 当前主题色
        findViewById<View?>(android.R.id.content)?.let { MusicTheme.applyToViewTree(it) }

        // ── 查找 View ───────────────────────────────────────
        vinylDisk = findViewById(R.id.vinyl_disk)
        vinylCenter = findViewById(R.id.vinyl_center)
        ivPlayPause = findViewById(R.id.iv_play_pause)
        ivVinylCover = findViewById(R.id.iv_vinyl_cover)
        ivPlayerBg = findViewById(R.id.iv_player_bg)
        ivLyricBg = findViewById(R.id.iv_lyric_bg)
        tvTitle = findViewById(R.id.tv_song_title)
        tvArtist = findViewById(R.id.tv_song_artist)
        tvCurrentTime = findViewById(R.id.tv_current_time)
        tvTotalTime = findViewById(R.id.tv_total_time)
        tvPlayStatus = findViewById(R.id.tv_play_status)
        progressTrack = findViewById(R.id.progress_track)
        progressFill = findViewById(R.id.progress_fill)
        layoutUpper = findViewById(R.id.layout_upper)
        // 圆盘尺寸按可用空间自适应（240×320 与 320×480 都要吃饱又不溢出）
        setupVinylSize()

        // 全屏歌词视图
        layoutLyricFullscreen = findViewById(R.id.layout_lyric_fullscreen)
        scrollLyricFull = findViewById(R.id.scroll_lyric_full)
        lyricFullContainer = findViewById(R.id.layout_lyric_full_list)

        // ── 设置图标（使用 KeydroidxIcons 矢量字体）──────────────
        // 盘心兜底图标 ♪ music_note：只在无封面（或加载失败）时可见，
        // 有封面时被圆形封面盖住并置 GONE（见 loadCover）
        KeydroidxIcons.setIcon(ivPlayPause, KeydroidxIcons.ICON_MUSIC_NOTE)
        KeydroidxIcons.setIcon(findViewById(R.id.icon_guide_prev), KeydroidxIcons.ICON_SKIP_PREVIOUS)
        KeydroidxIcons.setIcon(findViewById(R.id.icon_guide_next), KeydroidxIcons.ICON_SKIP_NEXT)
        KeydroidxIcons.setIcon(findViewById(R.id.icon_guide_lyrics), KeydroidxIcons.ICON_SUBTITLES)
        KeydroidxIcons.setIcon(findViewById(R.id.icon_guide_mode), KeydroidxIcons.ICON_REPEAT)
        KeydroidxIcons.setIcon(findViewById(R.id.icon_guide_playpause), KeydroidxIcons.ICON_PLAY)

        // ── 操作栏：红心 / 下载 / 评论 ─────────────────
        iconActionFavorite = findViewById(R.id.icon_action_favorite)
        iconActionDownload = findViewById(R.id.icon_action_download)
        iconActionComment = findViewById(R.id.icon_action_comment)
        tvCommentCount = findViewById(R.id.tv_comment_count)
        KeydroidxIcons.setIcon(iconActionComment, MusicIcons.COMMENT)
        // 红心 / 下载图标按当前状态渲染（收藏态、下载态）
        updateFavoriteIcon()
        updateDownloadIcon()

        // 触屏点击：与数字键 1/2/3 共用同一套 action 函数，保证两条路径行为一致。
        // 注意：这几个单元格不设 focusable（会抢走根视图焦点导致首键被吞），
        // 因此只依赖普通 click 回调，不参与按键焦点导航。
        findViewById<View>(R.id.action_favorite).setOnClickListener { actionFavorite() }
        findViewById<View>(R.id.action_download).setOnClickListener { actionDownload() }
        findViewById<View>(R.id.action_comment).setOnClickListener { actionComment() }

        // ── 文本兜底 ───────────────────────────────────────
        tvCurrentTime?.text = getString(R.string.unknown_time)
        tvTotalTime?.text = getString(R.string.unknown_time)
        tvPlayStatus?.text = getString(R.string.play_status_pause)

        // ── 标题栏 & 软键栏 ─────────────────────────────────
        setPageTitle(getString(R.string.title_now_playing))
        setTitleIcon(KeydroidxIcons.ICON_PLAY_CIRCLE_FILLED)
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

    override fun onResume() {
        super.onResume()
        // 返回桌面后再回到本页，窗口可能重新进入 touch mode（桌面上的触屏操作
        // 会把本窗口重置为 touch mode），而 onInitViews 只在 onCreate 跑一次、
        // 不会在 onResume 重跑，根视图会失焦 → 首个方向键被触摸模式吞掉。
        // 这里重新让根视图持焦（XML 已声明 focusable + focusableInTouchMode），
        // 并 post 一次应对窗口焦点稍后才就绪的情况。
        // 详见 NOKIA_DEVELOPMENT_RULES.md「进入界面后首个方向键被吞规范」。
        val playerRoot = findViewById<View>(R.id.layout_player_root)
        playerRoot.requestFocus()
        playerRoot.post { playerRoot.requestFocus() }
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
        populateFullscreenLyrics()
        updateCurrentLyricLine(PlaybackStateManager.currentPositionMs.value)
        Log.d(TAG, "[DEMO] loaded ${lrcLines.size} lyric lines")
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
                    // 封面：背景铺满 + 盘心圆形，随切歌同步换
                    loadCover(song)
                    // 歌词仍要加载：主界面不显示，但 * 键的全屏歌词层要用
                    if (DEMO_MODE) loadDemoLyrics() else loadLyrics(song.id)
                } else {
                    tvTitle?.text = "暂无曲目"
                    tvArtist?.text = "未知艺术家"
                    loadCover(null)
                    lrcLines = emptyList()
                    currentLyricIndex = -1
                    PlaybackStateManager.updateCurrentLyricLine(null)
                }
                // 操作栏随切歌刷新：评论数重新拉取，红心/下载态按新歌 id 重算
                loadCommentCount(song)
                updateFavoriteIcon()
                updateDownloadIcon()
            }
        }

        // 收藏态：FavoriteStore 是全局唯一事实源（轻量 id 快照），任何页面改动红心都会推到这里
        lifecycleScope.launch {
            FavoriteStore.favoriteIds.collectLatest { updateFavoriteIcon() }
        }

        // 下载态：下载进度/完成/失败都会推送，驱动下载图标三态切换
        lifecycleScope.launch {
            DownloadManager.tasks.collectLatest { updateDownloadIcon() }
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
                KeydroidxIcons.setIcon(
                    findViewById(R.id.icon_guide_playpause),
                    if (playing) KeydroidxIcons.ICON_PAUSE else KeydroidxIcons.ICON_PLAY
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
                // 主界面已无歌词列表，但仍要推进「当前歌词行」并外推给 PlaybackStateManager
                //（锁屏歌词页 / 桌面组件 / 通知栏都读这个值）
                updateCurrentLyricLine(pos)
                // 全屏歌词层（* 键唤起）的高亮
                updateFullscreenLyricHighlight(pos)
            }
        }
    }

    private fun updateModeIcon(mode: PlaybackMode) {
        val iconView = findViewById<TextView>(R.id.icon_guide_mode)
        when (mode) {
            PlaybackMode.LIST_LOOP -> KeydroidxIcons.setIcon(iconView, KeydroidxIcons.ICON_REPEAT)
            PlaybackMode.SINGLE_LOOP -> KeydroidxIcons.setIcon(iconView, KeydroidxIcons.ICON_REPEAT_ONE)
            PlaybackMode.RANDOM -> KeydroidxIcons.setIcon(iconView, KeydroidxIcons.ICON_SHUFFLE)
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
                KeydroidxKeyAction.UP -> {
                    moveFullscreenFocus(-1)
                    true
                }
                KeydroidxKeyAction.DOWN -> {
                    moveFullscreenFocus(1)
                    true
                }
                KeydroidxKeyAction.LEFT, KeydroidxKeyAction.RIGHT -> true   // 忽略左右
                KeydroidxKeyAction.SELECT -> {
                    seekToFocusedLyric()
                    true
                }
                KeydroidxKeyAction.SOFT_LEFT -> {
                    // 全屏歌词模式下的左软键 = 收藏，与数字键 1 行为一致
                    actionFavorite()
                    true
                }
                KeydroidxKeyAction.SOFT_RIGHT -> {
                    exitFullscreenLyric()
                    true
                }
                else -> super.onAction(action)
            }
        }

        return when (action) {
            // ⚠ 这里**绝对不能**处理 KeydroidxKeyAction.LOCK_SCREEN。
            //
            // 基类 dispatchKeyEvent 的分发顺序是：
            //   按键码 → resolveAction() 得到语义动作 → onAction(action)
            //   → 返回 true：事件就此终止，**不再下传给 onKeyDown**
            //   → 返回 false：继续 super.dispatchKeyEvent()，最终走到 onKeyDown
            //
            // 而部分机型（本机型实测如此）的 `*` 键会被解析层命成 LOCK_SCREEN 动作。
            // 一旦在这里消费它并 return true，原本能走到 onKeyDown(KEYCODE_STAR)
            // → 全屏歌词 的事件就被截走了，表现为「按 `*` 弹出锁屏歌词页而不是全屏歌词」。
            // 因此锁屏歌词改由 onKeyDown 精确匹配真实挂机键码（见 KEYCODE_ENDCALL 分支）。
            KeydroidxKeyAction.SELECT -> {
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
            KeydroidxKeyAction.LEFT -> {
                sendServiceAction(PlaybackService.ACTION_PREV)
                true
            }
            KeydroidxKeyAction.RIGHT -> {
                sendServiceAction(PlaybackService.ACTION_NEXT)
                true
            }
            KeydroidxKeyAction.UP -> {
                audioManager?.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_RAISE,
                    AudioManager.FLAG_SHOW_UI
                )
                true
            }
            KeydroidxKeyAction.DOWN -> {
                audioManager?.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_LOWER,
                    AudioManager.FLAG_SHOW_UI
                )
                true
            }
            KeydroidxKeyAction.SOFT_LEFT -> {
                showPlaybackOptions()
                true
            }
            KeydroidxKeyAction.SOFT_RIGHT -> {
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

    /**
     * 数字键 1 / 2 / 3 —— 操作栏快捷键。
     *
     * `KeydroidxKeyAction` 语义集合只覆盖方向、确定、左右软键、锁屏、拨号，
     * 不含数字键，因此这里沿用本项目已有的 `*` / `#` 做法，在 `onKeyDown` 里直接拦截。
     * 基类 `KeydroidxBaseActivity.dispatchKeyEvent` 对未映射的 keyCode 会放行到本方法。
     *
     * 长按会连续触发 repeat，这里只认第一次按下，避免长按把收藏反复开关。
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (event != null && event.repeatCount > 0) return true

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
            KeyEvent.KEYCODE_1 -> {
                // 收藏：全屏歌词模式下同样可用
                actionFavorite()
                true
            }
            KeyEvent.KEYCODE_2 -> {
                // 下载：全屏歌词模式下不响应，避免浏览歌词时误触
                if (!isLyricFull) actionDownload()
                true
            }
            KeyEvent.KEYCODE_3 -> {
                // 评论：全屏歌词模式下不响应
                if (!isLyricFull) actionComment()
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
        val isFav = currentSong != null && FavoriteStore.isFavorite(currentSong.id)

        val dialog = KeydroidxOptionsDialog(this, getString(R.string.softkey_options))
            .addItem(
                1,
                getString(R.string.option_play_queue),
                KeydroidxIcons.createDrawable(this, KeydroidxIcons.ICON_QUEUE_MUSIC, iconSize, iconColor)
            )
            .addItem(
                2,
                if (isFav) "取消收藏" else getString(R.string.softkey_favorite),
                KeydroidxIcons.createDrawable(this, if (isFav) KeydroidxIcons.ICON_FAVORITE_BORDER else KeydroidxIcons.ICON_FAVORITE, iconSize, iconColor)
            )
            .addItem(
                3,
                getString(R.string.option_quality),
                KeydroidxIcons.createDrawable(this, KeydroidxIcons.ICON_SETTINGS, iconSize, iconColor)
            )
            .addItem(
                4,
                getString(R.string.softkey_back),
                KeydroidxIcons.createDrawable(this, KeydroidxIcons.ICON_ARROW_BACK, iconSize, iconColor)
            )
            .setOnOptionSelectedListener { index, _ ->
                when (index) {
                    0 -> openCurrentQueue()     // 1. 播放列表
                    1 -> toggleFavorite()       // 2. 收藏 / 取消收藏
                    2 -> showQualityPicker()    // 3. 音质设置
                    3 -> finish()               // 4. 返回
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
                isFav = FavoriteStore.isFavorite(song.id),
                isVip = song.fee == 1,
                noCopyright = song.noCopyright
            )
        })
        PlaylistDetailActivity.start(
            this,
            getString(R.string.title_current_queue),
            KeydroidxIcons.ICON_QUEUE_MUSIC,
            displaySongs
        )
    }

    /**
     * 收藏 / 取消收藏当前正在播放的歌曲
     */
    private fun toggleFavorite() {
        val current = PlaybackStateManager.currentSong.value ?: return
        lifecycleScope.launch {
            val isFavNow = FavoriteStore.toggle(
                this@MusicPlayerActivity,
                FavoriteStore.Entry(current.id, current.name, current.artistName)
            )
            val msg = if (isFavNow) getString(R.string.toast_favorited) else getString(R.string.toast_unfavorited)
            Toast.makeText(this@MusicPlayerActivity, msg, Toast.LENGTH_SHORT).show()
        }
    }

    // ─────────────────────────────────────────────────────────
    //  操作栏：红心（1）/ 下载（2）/ 评论（3）
    // ─────────────────────────────────────────────────────────

    /**
     * 数字键 1 / 触屏红心：收藏或取消收藏当前歌曲。
     *
     * 全屏歌词模式下同样生效（看到好歌词顺手收藏）。
     */
    private fun actionFavorite() {
        val current = PlaybackStateManager.currentSong.value
        if (current == null) {
            Toast.makeText(this, getString(R.string.toast_no_song_playing), Toast.LENGTH_SHORT).show()
            return
        }
        toggleFavorite()
    }

    /** 数字键 2 / 触屏下载：把当前歌曲丢进下载队列（复用 DownloadManager）。 */
    private fun actionDownload() {
        val current = PlaybackStateManager.currentSong.value
        if (current == null) {
            Toast.makeText(this, getString(R.string.toast_no_song_playing), Toast.LENGTH_SHORT).show()
            return
        }
        if (!current.localPath.isNullOrBlank()) {
            Toast.makeText(this, getString(R.string.toast_download_local_song), Toast.LENGTH_SHORT).show()
            return
        }
        val task = DownloadManager.getTask(current.id)
        when {
            DownloadManager.isDownloaded(current.id) -> {
                Toast.makeText(this, getString(R.string.toast_download_already), Toast.LENGTH_SHORT).show()
            }
            task?.status == DownloadStatus.DOWNLOADING || task?.status == DownloadStatus.PENDING -> {
                Toast.makeText(this, getString(R.string.toast_download_ongoing), Toast.LENGTH_SHORT).show()
            }
            else -> {
                DownloadManager.enqueueDownload(current)
                Toast.makeText(this, getString(R.string.toast_download_started), Toast.LENGTH_SHORT).show()
            }
        }
        updateDownloadIcon()
    }

    /** 数字键 3 / 触屏评论：进入歌曲评论区。 */
    private fun actionComment() {
        val current = PlaybackStateManager.currentSong.value
        if (current == null) {
            Toast.makeText(this, getString(R.string.toast_no_song_playing), Toast.LENGTH_SHORT).show()
            return
        }
        // 本地歌曲（有本地路径，或本地扫描生成的负数 id）没有云端评论区
        if (!current.localPath.isNullOrBlank() || current.id <= 0L) {
            Toast.makeText(this, getString(R.string.toast_comment_local_song), Toast.LENGTH_SHORT).show()
            return
        }
        CommentActivity.start(this, current.id, current.name, currentCommentTotal)
    }

    /** 红心图标：已收藏=实心红心，未收藏=空心白心。 */
    private fun updateFavoriteIcon() {
        val iv = iconActionFavorite ?: return
        val song = PlaybackStateManager.currentSong.value
        val isFav = song != null && FavoriteStore.isFavorite(song.id)
        KeydroidxIcons.setIcon(iv, if (isFav) KeydroidxIcons.ICON_FAVORITE else KeydroidxIcons.ICON_FAVORITE_BORDER)
        iv.setTextColor(if (isFav) COLOR_FAV_RED else MusicTheme.current(this).text)
    }

    /** 下载图标三态：未下载=下载箭头 / 下载中=沙漏 / 已完成=对勾。 */
    private fun updateDownloadIcon() {
        val iv = iconActionDownload ?: return
        val song = PlaybackStateManager.currentSong.value
        val status = song?.let { DownloadManager.getTask(it.id)?.status }
        when {
            song != null && DownloadManager.isDownloaded(song.id) -> {
                KeydroidxIcons.setIcon(iv, KeydroidxIcons.ICON_CHECK)
                iv.setTextColor(MusicTheme.BRAND_ACCENT)
            }
            status == DownloadStatus.DOWNLOADING || status == DownloadStatus.PENDING -> {
                KeydroidxIcons.setIcon(iv, KeydroidxIcons.ICON_HOURGLASS)
                iv.setTextColor(MusicTheme.BRAND_SOFTKEY_CENTER)
            }
            else -> {
                KeydroidxIcons.setIcon(iv, KeydroidxIcons.ICON_DOWNLOAD)
                iv.setTextColor(MusicTheme.current(this).text)
            }
        }
    }

    /**
     * 拉取当前歌曲的评论总数并显示在评论图标右侧。
     *
     * 只取 `total` 这一个标量（limit=1 最小化报文），结果进 [CommentCache]，
     * 来回切歌不会重复打网络。拉取中/失败/本地歌曲一律隐藏数字，
     * 绝不把上一首的数字留在屏幕上，也绝不影响播放主流程。
     */
    private fun loadCommentCount(song: SongItem?) {
        commentCountJob?.cancel()
        val tv = tvCommentCount ?: return

        // 无曲目 / 本地歌曲：没有云端评论，直接隐藏
        if (song == null || !song.localPath.isNullOrBlank() || song.id <= 0L) {
            currentCommentTotal = null
            tv.visibility = View.GONE
            return
        }

        // 演示模式：不走网络，注入一个固定数量便于验收 999+ 的排版
        if (DEMO_MODE) {
            currentCommentTotal = DEMO_COMMENT_COUNT
            tv.text = CommentApi.formatCount(DEMO_COMMENT_COUNT)
            tv.visibility = View.VISIBLE
            return
        }

        val cached = CommentCache.getCount(song.id)
        if (cached != null) {
            currentCommentTotal = cached
            tv.text = CommentApi.formatCount(cached)
            tv.visibility = View.VISIBLE
            return
        }

        // 拉取中先隐藏，避免误显上一首的数字
        currentCommentTotal = null
        tv.visibility = View.GONE

        val requestedId = song.id
        commentCountJob = lifecycleScope.launch {
            try {
                val page = CommentApi.getSongComments(requestedId, offset = 0, limit = 1)
                if (isDestroyed || isFinishing) return@launch
                // 切歌竞态：只接受仍属于当前歌曲的结果
                if (PlaybackStateManager.currentSong.value?.id != requestedId) return@launch
                currentCommentTotal = page.total
                CommentCache.putCount(requestedId, page.total)
                tv.text = CommentApi.formatCount(page.total)
                tv.visibility = View.VISIBLE
                Log.d(TAG, "comment count for song $requestedId = ${page.total}")
            } catch (e: Exception) {
                // 评论是锦上添花的能力，失败静默降级：不提示、不打断播放
                Log.w(TAG, "load comment count failed songId=$requestedId: ${e.message}")
                if (PlaybackStateManager.currentSong.value?.id == requestedId) {
                    currentCommentTotal = null
                    tv.visibility = View.GONE
                }
            }
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

        val dialog = KeydroidxOptionsDialog(this, getString(R.string.title_quality))
        // 当前档位置顶
        dialog.addItem(
            0,
            "● ${labels[current]}",
            KeydroidxIcons.createDrawable(this, KeydroidxIcons.ICON_CHECK, iconSize, iconColor)
        )
        var seq = 1
        for (level in PlaybackPrefs.QUALITY_LEVELS) {
            if (level == current) continue
            dialog.addItem(
                seq++,
                labels[level],
                KeydroidxIcons.createDrawable(this, KeydroidxIcons.ICON_MUSIC_NOTE, iconSize, iconColor)
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
        setTitleIcon(KeydroidxIcons.ICON_LYRICS)
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
        setTitleIcon(KeydroidxIcons.ICON_PLAY_CIRCLE_FILLED)
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
        KeydroidxFontManager.applyToViewTree(container)
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
                topMargin = dp(4)
                bottomMargin = dp(4)
            }
            gravity = Gravity.CENTER
            KeydroidxFontManager.setTextSize(this, android.util.TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(Color.parseColor("#E0FFFFFF"))
            setLineSpacing(dp(2).toFloat(), 1f)
            includeFontPadding = false
            setSingleLine(false)
            maxLines = 3
            setPadding(dp(10), dp(5), dp(10), dp(5))
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
        val focusBg = MusicTheme.createFocusDrawable(this, 4f)
        val white = Color.parseColor("#FFFFFF")
        val cyan = MusicTheme.BRAND_ACCENT
        val normal = MusicTheme.current(applicationContext).subtext

        val cursor = if (focusLyricIndex in lyricFullTextViews.indices) focusLyricIndex else playing

        lyricFullTextViews.forEachIndexed { i, tv ->
            val isCursor = (i == cursor)
            val isPlaying = (i == playing)

            when {
                // 1. 光标正好停在当前播放行（或默认跟随模式下的播放行）
                isCursor && isPlaying -> {
                    tv.background = cyanBg
                    tv.setTextColor(white)
                    tv.setTypeface(null, android.graphics.Typeface.NORMAL)
                    KeydroidxFontManager.setTextSize(tv, android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
                }
                // 2. 用户方向键选中的光标行（但不是当前播放行）
                isCursor && !isPlaying -> {
                    tv.background = focusBg
                    tv.setTextColor(white)
                    tv.setTypeface(null, android.graphics.Typeface.NORMAL)
                    KeydroidxFontManager.setTextSize(tv, android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
                }
                // 3. 当前播放行（但用户光标移到了其他行）
                !isCursor && isPlaying -> {
                    tv.background = cyanBg
                    tv.setTextColor(cyan)
                    tv.setTypeface(null, android.graphics.Typeface.NORMAL)
                    KeydroidxFontManager.setTextSize(tv, android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
                }
                // 4. 普通歌词行
                else -> {
                    tv.background = null
                    tv.setTextColor(normal)
                    tv.setTypeface(null, android.graphics.Typeface.NORMAL)
                    KeydroidxFontManager.setTextSize(tv, android.util.TypedValue.COMPLEX_UNIT_SP, 11f)
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
     * 异步拉取 LRC 文本并解析为 LrcLine 列表。
     * 优先读取本地已下载的歌词文件；若无本地歌词则联网请求。
     *
     * 解析结果只服务于 `*` 键的全屏歌词层（主界面已不显示常驻歌词），
     * 顺带把「当前行」推给 PlaybackStateManager 供锁屏歌词页等读取。
     */
    private fun loadLyrics(songId: Long) {
        lifecycleScope.launch {
            try {
                // 1. 优先读取已下载的本地歌词
                val downloaded = DownloadManager.getDownloadedSong(songId)
                if (downloaded != null && !downloaded.lyricPath.isNullOrBlank()) {
                    val lrcFile = java.io.File(downloaded.lyricPath!!)
                    if (lrcFile.exists()) {
                        val raw = withContext(Dispatchers.IO) {
                            lrcFile.readText(Charsets.UTF_8)
                        }
                        if (!raw.isNullOrBlank()) {
                            lrcLines = LrcParser.parse(raw)
                            currentLyricIndex = -1
                            populateFullscreenLyrics()
                            updateCurrentLyricLine(PlaybackStateManager.currentPositionMs.value)
                            Log.d(TAG, "Loaded ${lrcLines.size} downloaded lyric lines for song $songId")
                            return@launch
                        }
                    }
                }

                // 2. 本地无歌词时联网拉取
                val resp = withContext(Dispatchers.IO) {
                    RetrofitClient.api.getLyric(id = songId)
                }
                val raw = resp.lrc?.lyric
                if (raw.isNullOrEmpty()) {
                    lrcLines = emptyList()
                    currentLyricIndex = -1
                    populateFullscreenLyrics()
                    updateCurrentLyricLine(PlaybackStateManager.currentPositionMs.value)
                    return@launch
                }
                lrcLines = LrcParser.parse(raw)
                currentLyricIndex = -1
                populateFullscreenLyrics()
                updateCurrentLyricLine(PlaybackStateManager.currentPositionMs.value)
                Log.d(TAG, "Loaded ${lrcLines.size} lyric lines for song $songId")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load lyrics for song $songId: ${e.message}", e)
                lrcLines = emptyList()
                currentLyricIndex = -1
                populateFullscreenLyrics()
                updateCurrentLyricLine(PlaybackStateManager.currentPositionMs.value)
            }
        }
    }

    /**
     * 推进「当前歌词行」并外推给 PlaybackStateManager，**不渲染**。
     *
     * 主界面已不再有歌词列表（视觉主体是背景封面 + 居中大圆盘），但当前行仍是公开数据：
     * 锁屏歌词页 [LyricLockScreenActivity]、桌面组件、通知栏都从
     * [PlaybackStateManager.updateCurrentLyricLine] 取它。因此保留纯计算部分，
     * 渲染与滚动部分随歌词列表一起删除。
     */
    private fun updateCurrentLyricLine(posMs: Long) {
        if (lrcLines.isEmpty()) {
            if (currentLyricIndex != -1) {
                currentLyricIndex = -1
                PlaybackStateManager.updateCurrentLyricLine(null)
            }
            return
        }
        // 二分查找当前行：最后一行 timeMs <= posMs
        var idx = -1
        for (i in lrcLines.indices) {
            if (lrcLines[i].timeMs <= posMs) idx = i else break
        }
        if (idx == currentLyricIndex) return   // 未变化则不重发
        currentLyricIndex = idx
        PlaybackStateManager.updateCurrentLyricLine(lrcLines.getOrNull(idx)?.text)
    }

    // ─────────────────────────────────────────────────────────
    //  封面（整屏模糊底 + 盘心圆形封面）
    // ─────────────────────────────────────────────────────────

    /**
     * 取当前歌曲的封面地址。
     *
     * 播放队列里的 `album.picUrl` 经常是空的——歌单详情页构造队列时写死了
     * `AlbumItem(picUrl = null)`，从云端歌单/榜单一路播下来封面全丢。所以补一层兜底：
     * 地址缺失且是云端歌曲（id > 0）时按 id 拉一次歌曲详情取 `al.picUrl`。
     * 本地歌曲（id < 0）没有云端封面，直接返回 null，由调用方回落主题深色底。
     *
     * 与锁屏歌词页里的同名逻辑一致：各自维护是为了不让播放页反向依赖锁屏页，
     * 若出现第三处调用再抽到 util。
     */
    private suspend fun resolveCoverUrl(song: SongItem?): String? {
        song ?: return null
        song.album?.picUrl?.takeIf { it.isNotBlank() }?.let { return it }
        if (song.id <= 0) return null
        return try {
            RetrofitClient.api.getSongDetail("""[{"id":${song.id}}]""")
                .songs.firstOrNull()?.album?.picUrl?.takeIf { it.isNotBlank() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "resolve cover url failed songId=${song.id}: ${e.message}")
            null
        }
    }

    /**
     * 按当前歌曲换封面：整屏背景 + 盘心圆形封面。
     *
     * 无封面 / 加载失败时把两张图都清掉：背景露出主题深色底（叠上遮罩即纯色），
     * 盘心回到红色中心盖 + ♪ 图标，绝不残留上一首的封面。
     */
    private fun loadCover(song: SongItem?) {
        coverJob?.cancel()
        coverJob = lifecycleScope.launch {
            val url = resolveCoverUrl(song)
            // 切歌去重：同一首反复回调不重复下载/解码
            if (url == loadedCoverUrl) return@launch
            loadedCoverUrl = url
            ivPlayerBg?.setImageDrawable(null)
            ivLyricBg?.setImageDrawable(null)
            clearVinylCover()
            if (url == null) return@launch

            val bitmap = CoverLoader.load(url) ?: return@launch
            if (isDestroyed || isFinishing) return@launch
            // 切歌竞态：只接受仍属于当前 URL 的结果
            if (loadedCoverUrl != url) return@launch

            val blurred = toBlurredBackground(bitmap)
            ivPlayerBg?.setImageBitmap(blurred)
            // 全屏歌词层沿用同一张虚化封面，保证两页视觉连贯（共享 Bitmap，无额外内存）
            ivLyricBg?.setImageBitmap(blurred)
            ivVinylCover?.setImageDrawable(toCircularCover(bitmap))
            ivPlayPause?.visibility = View.GONE
        }
    }

    /** 盘心恢复「红色中心盖 + ♪ 兜底图标」。 */
    private fun clearVinylCover() {
        ivVinylCover?.setImageDrawable(null)
        ivPlayPause?.visibility = View.VISIBLE
    }

    /**
     * 背景「模糊」：先把封面降到极低分辨率，再交给 ImageView 双线性放大。
     *
     * 为什么不用 RenderScript / StackBlur：minSdk=19 而 RenderScript 在 API 31 已废弃、
     * 部分 ROM 不带运行时；纯算法模糊又要额外几十毫秒 CPU 与一份大 Bitmap。
     * 降采样后由 ImageView 双线性插值放大本质上就是一次低成本低通滤波，
     * 配上遮罩后的观感与网易云的模糊封面底一致。
     */
    private fun toBlurredBackground(bitmap: Bitmap): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return bitmap
        val targetW: Int
        val targetH: Int
        if (w >= h) {
            targetW = BG_BLUR_SIZE
            targetH = (h * BG_BLUR_SIZE / w).coerceAtLeast(1)
        } else {
            targetH = BG_BLUR_SIZE
            targetW = (w * BG_BLUR_SIZE / h).coerceAtLeast(1)
        }
        return try {
            Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
        } catch (e: Exception) {
            Log.w(TAG, "blur background failed: ${e.message}")
            bitmap
        }
    }

    /**
     * 盘心圆形封面。
     *
     * 必须先裁出位图中心正方形再套圆形：[RoundedBitmapDrawableFactory] 自己不做
     * centerCrop，直接喂非正方形位图会被拉成椭圆。
     */
    private fun toCircularCover(bitmap: Bitmap): android.graphics.drawable.Drawable {
        val side = minOf(bitmap.width, bitmap.height)
        val left = (bitmap.width - side) / 2
        val top = (bitmap.height - side) / 2
        val square = Bitmap.createBitmap(bitmap, left, top, side, side)
        return RoundedBitmapDrawableFactory.create(resources, square).apply {
            isCircular = true
        }
    }

    // ─────────────────────────────────────────────────────────
    //  圆盘尺寸自适应
    // ─────────────────────────────────────────────────────────

    /**
     * 圆盘尺寸自适应。
     *
     * 生态机型跨度大（240×320 与 320×480 都在跑），写死尺寸要么小屏溢出、要么大屏浪费。
     * 圆盘所在的中间区是 weight=1，测量完成后按「可用宽高较小值 × 比例」定尺寸，
     * 保证两种屏上圆盘都尽量大且完整可见；盘心封面同步按比例缩放。
     *
     * [vinylSizeApplied] 是刹车：改子视图尺寸会再次触发本回调，不刹车会无限循环。
     */
    private fun setupVinylSize() {
        val container = layoutUpper ?: return
        container.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            if (vinylSizeApplied) return@addOnLayoutChangeListener
            val w = right - left
            val h = bottom - top
            if (w <= 0 || h <= 0) return@addOnLayoutChangeListener
            val density = resources.displayMetrics.density
            val size = (minOf(w, h) * VINYL_RATIO).toInt()
                .coerceIn((MIN_VINYL_DP * density).toInt(), (MAX_VINYL_DP * density).toInt())
            applySquareSize(vinylDisk, size)
            applySquareSize(vinylCenter, (size * VINYL_CENTER_RATIO).toInt())
            vinylSizeApplied = true
            Log.d(TAG, "vinyl size = $size px (available ${w}x$h)")
        }
    }

    private fun applySquareSize(view: View?, size: Int) {
        view ?: return
        val lp = view.layoutParams ?: return
        if (lp.width == size && lp.height == size) return
        lp.width = size
        lp.height = size
        view.layoutParams = lp
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
        mainHandler.removeCallbacks(demoRunnable)
        vinylRotateAnim?.cancel()
        commentCountJob?.cancel()
        coverJob?.cancel()
    }

    /**
     * 播放页实例被复用时回调（singleTask / CLEAR_TOP / SINGLE_TOP）。
     *
     * 典型场景：在播放页按挂机键回桌面（本页只是被压到后台，没有 finish），
     * 再从桌面「正在播放」组件进入 —— 不会新建实例，而是复用当前实例并走到这里。
     * 播放态全部来自 PlaybackStateManager 的 StateFlow，视图无需重建，
     * 只需挂上新的 intent（onResume 会兜底 requestFocus，首个方向键不会被吞）。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        Log.d(TAG, "onNewIntent：复用已有播放页实例，任务栈不再叠层")
    }

    companion object {
        private const val TAG = "MusicPlayerActivity"

        /** 收藏态红心色（与 PlaylistDetailActivity 的红心保持一致） */
        private const val COLOR_FAV_RED = 0xFFEF4444.toInt()

        /** 圆盘占「可用宽高较小值」的比例 */
        private const val VINYL_RATIO = 0.9f

        /** 圆盘尺寸下限：240×320 小屏的下限，再小撑不起画面主体 */
        private const val MIN_VINYL_DP = 96f

        /** 圆盘尺寸上限：再大在 320×480 上会把进度条与操作栏挤出屏幕 */
        private const val MAX_VINYL_DP = 220f

        /** 盘心封面（红盖）占圆盘直径的比例：对齐网易云「封面大、黑胶环窄」的观感 */
        private const val VINYL_CENTER_RATIO = 0.52f

        /** 背景封面降采样后的最长边（px）：越小越"糊"，64 已足够平滑且几乎不占内存 */
        private const val BG_BLUR_SIZE = 64

        /**
         * 统一入口：复用任务栈中已有的播放页，并清空其上方压着的页面。
         * 配合 manifest 的 singleTask，保证从任何入口（桌面组件 / 通知栏 /
         * 歌单页 / 本地音乐 / 下载页 / 私人 FM）反复进入都只有一个播放页实例。
         */
        fun start(context: Context) {
            val intent = Intent(context, MusicPlayerActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                // 非 Activity 上下文（Service / Application）必须补 NEW_TASK
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }

        // === 演示模式：硬编码真实 LRC + 模拟进度，方便 UI 验收 ===
        private const val DEMO_MODE = false
        private const val DEMO_DURATION_MS = 255_000L  // 4:15
        private const val DEMO_TICK_MS = 1000L        // 每秒推进
        /** 演示模式下的评论数（与参考截图的 174 对齐，便于验收 999+ 排版） */
        private const val DEMO_COMMENT_COUNT = 174
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
