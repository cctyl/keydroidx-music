package io.github.cctyl.keydroidx.music.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import io.github.cctyl.keydroidx.music.R
import io.github.cctyl.keydroidx.music.lyric.LrcLine
import io.github.cctyl.keydroidx.music.lyric.LyricLoader
import io.github.cctyl.keydroidx.music.network.RetrofitClient
import io.github.cctyl.keydroidx.music.network.model.SongItem
import io.github.cctyl.keydroidx.music.player.LockScreenLyricTrigger
import io.github.cctyl.keydroidx.music.player.PlaybackService
import io.github.cctyl.keydroidx.music.player.PlaybackStateManager
import io.github.cctyl.keydroidx.music.util.CoverLoader
import io.github.cctyl.keydroidx.music.util.NLog as Log
import io.github.cctyl.nokia.common.model.KeydroidxKeyAction
import io.github.cctyl.nokia.common.ui.KeydroidxFontManager
import io.github.cctyl.nokia.keycore.ui.KeydroidxBaseActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * 锁屏歌词页（沉浸式全屏歌词锁屏）。
 *
 * 定位：生态中 Launcher 的「锁屏」只是 `DevicePolicyManager.lockNow()` 调起的系统 Keyguard，
 * 无法自绘界面，因此歌词锁屏由音乐 App 自己提供一个全屏页，叠加在系统锁屏之上：
 *   · API 27+ → [setShowWhenLocked] + [setTurnScreenOn]
 *   · API < 27 → Window 标志 FLAG_SHOW_WHEN_LOCKED / FLAG_TURN_SCREEN_ON
 * 并常亮屏幕（FLAG_KEEP_SCREEN_ON），否则锁屏 30 秒后自动熄屏，歌词就看不到了。
 *
 * 界面：**整屏铺满当前歌曲封面**（压一层自上而下加深的黑色遮罩保证可读性）+
 * 大时钟/日期 + 5 行滚动歌词窗口（当前行居中高亮）+ 歌曲信息 + 进度。
 * 顶部不使用基类骨架的「标题 + 信号电量」栏，也不用三段式软键栏，
 * 改为系统原生状态栏（透明）——封面铺到状态栏后方，内容层用 paddingTop 避开。
 *
 * 按键（全部走生态语义动作 KeydroidxKeyAction，不写死 KeyCode）：
 *   ← / →       上一曲 / 下一曲
 *   确定         播放 / 暂停（本页唯一的播放控制键）
 *   右软键       退出歌词锁屏（回到正在播放详情页，后台持续播放）
 *   锁屏键       同右软键（进入后按同一键即退出）
 *   *            快捷退出歌词锁屏（备用出口，防止软键在个别机型上映射异常）
 *   ↑ / ↓       明确不响应（不滚动歌词）
 */
class LyricLockScreenActivity : KeydroidxBaseActivity() {

    // ── 视图 ─────────────────────────────────────────────────
    private var ivCover: ImageView? = null
    private var lyricViews: List<TextView> = emptyList()
    private var tvClock: TextView? = null
    private var tvDate: TextView? = null
    private var tvSongTitle: TextView? = null
    private var tvSongArtist: TextView? = null
    private var progressTrack: View? = null
    private var progressFill: View? = null
    private var tvCurrentTime: TextView? = null
    private var tvTotalTime: TextView? = null
    private var tvStatus: TextView? = null
    private var tvHint: TextView? = null

    // ── 状态 ─────────────────────────────────────────────────
    private var lrcLines: List<LrcLine> = emptyList()
    /** 歌词窗口当前渲染的中心行，避免每 500ms 重复重绘 */
    private var shownLyricIndex = Int.MIN_VALUE
    private var loadedSongId = -1L
    /** 已加载的封面 URL，用于切歌去重 */
    private var loadedCoverUrl: String? = null
    private var lyricJob: Job? = null
    private var coverJob: Job? = null

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dateFormat = SimpleDateFormat("M月d日 EEEE", Locale.getDefault())

    override fun getContentLayoutRes(): Int = R.layout.activity_lyric_lock_screen

    // ─────────────────────────────────────────────────────────
    //  锁屏窗口标志
    // ─────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyLockScreenWindow()
        // 必须放在最后：基类在 onCreate 里做过全屏设置，这里压过它
        applyImmersiveStatusBar()
    }

    /**
     * 让本页可显示在系统锁屏之上并自动点亮屏幕。
     *
     * `setShowWhenLocked` / `setTurnScreenOn` 是 API 27 才有的方法，
     * minSdk=19 必须做版本分支，低版本退回等价的 Window 标志。
     */
    private fun applyLockScreenWindow() {
        val win = window
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            win.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        // 常亮：锁屏歌词的用途就是"放着看"，不常亮则 30 秒后熄屏，功能失效
        win.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /**
     * 顶部改为「系统原生状态栏 + 透明沉浸」。
     *
     * 基类默认隐藏系统状态栏做全屏，本页要把它放回来：状态栏透明后封面能铺到它后方，
     * 内容层再靠 paddingTop 避开（见 [applyStatusBarInset]）。
     *
     * 关键点：生态主题 `Theme.Keydroidx` 带 `android:windowFullscreen=true`，窗口因此长期带着
     * [WindowManager.LayoutParams.FLAG_FULLSCREEN]。这个**窗口级**标志优先级高于
     * `systemUiVisibility`，只要它还在，无论怎么设置 vsysui 状态栏都不会显示
     * （实测 320×480 机型：fl 里 FULLSCREEN 一清掉状态栏立刻出现）。
     * 所以这里必须**无条件**清掉它，不能只在低版本分支里清。
     */
    @Suppress("DEPRECATION", "NewApi")
    private fun applyImmersiveStatusBar() {
        val win = window

        // 清窗口级全屏标志（来源：主题 windowFullscreen，或基类在 API<30 上的 setFlags）
        win.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            win.statusBarColor = Color.TRANSPARENT
        } else {
            // API 19~20 没有 statusBarColor，用半透明标志达到同样效果
            win.addFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            win.setDecorFitsSystemWindows(false)
            win.insetsController?.apply {
                // 基类在这里调过 hide(statusBars())，必须显式 show 回来
                show(WindowInsets.Type.statusBars())
                hide(WindowInsets.Type.navigationBars())
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }

        // 清掉基类留下的 legacy 全屏标志（FULLSCREEN / IMMERSIVE_STICKY），
        // 只保留 LAYOUT_* —— 内容铺到状态栏后方，但状态栏本身保持可见
        win.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
    }

    /**
     * 给内容层补上状态栏高度的顶部内边距。
     *
     * 沉浸式下内容会与系统状态栏重叠，大时钟若顶到最上面会被系统的时间/电量压住。
     * 高度优先取窗口 insets 的真实值：本 App 在 attachBaseContext 里把 density 吸附到了
     * 160，而系统状态栏是按真实密度算的（实测 31px ≠ 资源值），用资源值会差几个像素；
     * insets 取不到时再退回 `status_bar_height`。
     */
    private fun applyStatusBarInset() {
        val root = findViewById<View>(R.id.layout_lock_root) ?: return
        val density = resources.displayMetrics.density
        val hPad = (H_PADDING_DP * density).toInt()
        val vPad = (V_PADDING_DP * density).toInt()

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val insetTop = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            val top = if (insetTop > 0) insetTop else statusBarHeightPx()
            view.setPadding(hPad, vPad + top, hPad, vPad)
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun statusBarHeightPx(): Int {
        val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) resources.getDimensionPixelSize(resId) else 0
    }

    // ─────────────────────────────────────────────────────────
    //  初始化
    // ─────────────────────────────────────────────────────────

    override fun onInitViews() {
        // 自定义顶栏（标题 + 信号 + 电量）与三段式软键栏一律不要：
        // 顶部交给系统原生状态栏，底部则完全留给封面
        titleBar?.visibility = View.GONE
        statusBar?.visibility = View.GONE
        bottomBar?.visibility = View.GONE
        // 状态栏的显隐统一由 onCreate 末尾 / onResume / onWindowFocusChanged 处理，
        // 这里只负责按状态栏高度把内容压下来
        applyStatusBarInset()

        // XML 静态经典蓝配色 → 当前生态主题色
        findViewById<View?>(android.R.id.content)?.let { MusicTheme.applyToViewTree(it) }

        // 本页可能由「全屏意图通知」在锁屏上拉起：进入后立刻清掉该通知，
        // 否则会作为高重要度通知残留在通知栏
        LockScreenLyricTrigger.cancelNotification(this)

        ivCover = findViewById(R.id.iv_lock_cover)
        tvClock = findViewById(R.id.tv_lock_clock)
        tvDate = findViewById(R.id.tv_lock_date)
        tvSongTitle = findViewById(R.id.tv_lock_song_title)
        tvSongArtist = findViewById(R.id.tv_lock_song_artist)
        progressTrack = findViewById(R.id.lock_progress_track)
        progressFill = findViewById(R.id.lock_progress_fill)
        tvCurrentTime = findViewById(R.id.tv_lock_current_time)
        tvTotalTime = findViewById(R.id.tv_lock_total_time)
        tvStatus = findViewById(R.id.tv_lock_status)
        tvHint = findViewById(R.id.tv_lock_hint)

        lyricViews = listOf(
            findViewById(R.id.lock_lyric_0),
            findViewById(R.id.lock_lyric_1),
            findViewById(R.id.lock_lyric_2),
            findViewById(R.id.lock_lyric_3),
            findViewById(R.id.lock_lyric_4)
        )

        // 大时钟/日期是锁屏的主视觉，按设计字号放大（基类随后会乘当前字体缩放系数重新套用）
        tvClock?.let {
            KeydroidxFontManager.setTextSize(it, TypedValue.COMPLEX_UNIT_SP, CLOCK_SIZE_SP)
        }
        tvDate?.let {
            KeydroidxFontManager.setTextSize(it, TypedValue.COMPLEX_UNIT_SP, DATE_SIZE_SP)
        }

        tvHint?.text = getString(R.string.lock_hint)
        tvCurrentTime?.text = getString(R.string.unknown_time)
        tvTotalTime?.text = getString(R.string.unknown_time)

        // 让根视图持焦，杜绝 touch mode 吞掉首个物理按键
        val root = findViewById<View>(R.id.layout_lock_root)
        root.requestFocus()
        root.post { root.requestFocus() }

        updateClock()
        renderLyricPlaceholder()
        observePlaybackState()
    }

    override fun onResume() {
        super.onResume()
        // 个别 ROM 回到前台会自行恢复全屏标志，这里再压一次，保证状态栏与沉浸观感稳定
        applyImmersiveStatusBar()
        findViewById<View>(R.id.layout_lock_root)?.let {
            it.requestFocus()
            it.post { it.requestFocus() }
        }
        updateClock()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // 锁屏场景下窗口焦点会反复得失（Keyguard 收放、亮熄屏），每次拿回焦点都重压一遍，
        // 否则系统会按主题的 windowFullscreen 把状态栏重新吃掉
        if (hasFocus) applyImmersiveStatusBar()
    }

    override fun onDestroy() {
        super.onDestroy()
        lyricJob?.cancel()
        coverJob?.cancel()
    }

    // ─────────────────────────────────────────────────────────
    //  播放状态观察
    // ─────────────────────────────────────────────────────────

    private fun observePlaybackState() {
        lifecycleScope.launch {
            PlaybackStateManager.currentSong.collectLatest { song ->
                tvSongTitle?.text = song?.name ?: getString(R.string.lock_no_song)
                tvSongArtist?.text = song?.artistName.orEmpty()
                loadCover(song)
                if (song == null) {
                    loadedSongId = -1L
                    lyricJob?.cancel()
                    lrcLines = emptyList()
                    shownLyricIndex = Int.MIN_VALUE
                    renderLyricPlaceholder()
                } else if (song.id != loadedSongId) {
                    loadLyrics(song.id)
                }
            }
        }

        lifecycleScope.launch {
            PlaybackStateManager.isPlaying.collectLatest { playing ->
                tvStatus?.text = if (playing)
                    getString(R.string.lock_status_playing)
                else
                    getString(R.string.lock_status_paused)
            }
        }

        lifecycleScope.launch {
            PlaybackStateManager.currentPositionMs.collectLatest { pos ->
                val dur = PlaybackStateManager.durationMs.value
                tvCurrentTime?.text = formatTime(pos)
                tvTotalTime?.text = formatTime(dur)
                updateProgressFill(pos, dur)
                updateClock()
                updateLyricFollow(pos)
            }
        }
    }

    // ─────────────────────────────────────────────────────────
    //  封面背景
    // ─────────────────────────────────────────────────────────

    /**
     * 取当前歌曲的封面地址。
     *
     * 播放队列里的 `album.picUrl` 经常是空的——例如歌单详情页构造队列时写死了
     * `AlbumItem(name = null, picUrl = null)`，从云端歌单/榜单一路播下来封面全丢。
     * 所以这里补一层兜底：地址缺失且是云端歌曲（id > 0）时，按 id 拉一次歌曲详情取 `al.picUrl`。
     *
     * 本地歌曲（id < 0）没有云端封面，直接返回 null，由调用方回落主题深色底。
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
     * 按当前歌曲换封面背景。
     *
     * 无封面 / 加载失败时保持透明，露出根布局的主题深色底（配合遮罩即纯色锁屏），
     * 不会出现"上一首的封面残留"。
     */
    private fun loadCover(song: SongItem?) {
        coverJob?.cancel()
        coverJob = lifecycleScope.launch {
            val url = resolveCoverUrl(song)
            if (url == loadedCoverUrl) return@launch
            loadedCoverUrl = url
            ivCover?.setImageDrawable(null)
            if (url == null) return@launch

            val bitmap = CoverLoader.load(url) ?: return@launch
            // 切歌竞态：只接受仍属于当前 URL 的结果
            if (isDestroyed || isFinishing) return@launch
            if (loadedCoverUrl != url) return@launch
            ivCover?.setImageBitmap(bitmap)
        }
    }

    // ─────────────────────────────────────────────────────────
    //  歌词加载与渲染
    // ─────────────────────────────────────────────────────────

    private fun loadLyrics(songId: Long) {
        lyricJob?.cancel()
        loadedSongId = songId
        lrcLines = emptyList()
        shownLyricIndex = Int.MIN_VALUE
        renderLyricPlaceholder()

        lyricJob = lifecycleScope.launch {
            val lines = LyricLoader.load(songId)
            // 切歌竞态：只接受仍属于当前歌曲的结果
            if (isDestroyed || isFinishing) return@launch
            if (PlaybackStateManager.currentSong.value?.id != songId) return@launch
            lrcLines = lines
            Log.d(TAG, "loaded ${lines.size} lyric lines for song $songId")
            if (lines.isEmpty()) {
                renderLyricPlaceholder()
            } else {
                updateLyricFollow(PlaybackStateManager.currentPositionMs.value)
            }
        }
    }

    /** 按播放进度推进歌词行，并在必要时重绘窗口。 */
    private fun updateLyricFollow(posMs: Long) {
        if (lrcLines.isEmpty()) return

        var idx = -1
        for (i in lrcLines.indices) {
            if (lrcLines[i].timeMs <= posMs) idx = i else break
        }

        if (idx != shownLyricIndex) {
            shownLyricIndex = idx
            renderLyricWindow(idx)
        }
    }

    /**
     * 渲染 5 行滚动窗口：slot 0..4 对应「中心行 -2 .. +2」，中心行永远停在正中间。
     * 越界槽位写入不换行空格占位，保证切行时行高稳定、不跳动。
     */
    private fun renderLyricWindow(center: Int) {
        val subtext = MusicTheme.current(applicationContext).subtext
        val highlight = createLineHighlightDrawable()

        lyricViews.forEachIndexed { slot, tv ->
            val idx = center - 2 + slot
            if (idx !in lrcLines.indices) {
                tv.text = NON_BREAKING_SPACE
                tv.background = null
                tv.setTextColor(subtext)
                tv.alpha = 0.12f
                applyLyricSize(tv, LYRIC_SIZE_SP - 2f)
                return@forEachIndexed
            }
            tv.alpha = 1f
            tv.text = lrcLines[idx].text
            when {
                // 中心行：主题色高亮底 + 白字 + 加大字号（不用 bold，避免点阵字像素粘连）
                idx == center -> {
                    tv.background = highlight
                    tv.setTextColor(Color.WHITE)
                    applyLyricSize(tv, LYRIC_SIZE_SP + 2f)
                }
                // 邻近行
                abs(idx - center) == 1 -> {
                    tv.background = null
                    tv.setTextColor(subtext)
                    applyLyricSize(tv, LYRIC_SIZE_SP)
                }
                // 远端行
                else -> {
                    tv.background = null
                    tv.setTextColor(subtext)
                    tv.alpha = 0.55f
                    applyLyricSize(tv, LYRIC_SIZE_SP - 1f)
                }
            }
        }
    }

    /** 无歌词 / 无曲目时的居中占位。 */
    private fun renderLyricPlaceholder() {
        val subtext = MusicTheme.current(applicationContext).subtext
        lyricViews.forEachIndexed { slot, tv ->
            tv.background = null
            tv.setTextColor(subtext)
            tv.alpha = if (slot == 2) 0.8f else 0.12f
            tv.text = if (slot == 2) getString(R.string.lock_no_lyric) else NON_BREAKING_SPACE
            applyLyricSize(tv, LYRIC_SIZE_SP)
        }
    }

    private fun applyLyricSize(tv: TextView, sp: Float) {
        KeydroidxFontManager.setTextSize(tv, TypedValue.COMPLEX_UNIT_SP, sp.coerceAtLeast(6f))
    }

    /** 当前行高亮底：主题强调色 25% 填充 + 50% 描边 + 4dp 圆角。 */
    private fun createLineHighlightDrawable(): GradientDrawable {
        val accent = MusicTheme.current(applicationContext).accent
        val density = resources.displayMetrics.density
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            // 0x40 = 25% 透明度填充；0x80000000（Int 最小值）= 50% 透明度描边
            setColor((accent and 0x00FFFFFF) or 0x40000000)
            setStroke((density + 0.5f).toInt(), (accent and 0x00FFFFFF) or -0x80000000)
            cornerRadius = 4f * density
        }
    }

    // ─────────────────────────────────────────────────────────
    //  进度 / 时钟
    // ─────────────────────────────────────────────────────────

    private fun updateProgressFill(pos: Long, dur: Long) {
        val track = progressTrack ?: return
        val fill = progressFill ?: return
        val trackWidth = track.width
        if (trackWidth <= 0 || dur <= 0L) {
            setFillWidth(fill, 0)
            return
        }
        val ratio = (pos.toFloat() / dur.toFloat()).coerceIn(0f, 1f)
        setFillWidth(fill, (trackWidth * ratio).toInt())
    }

    private fun setFillWidth(fill: View, widthPx: Int) {
        val lp = fill.layoutParams ?: return
        if (lp.width != widthPx) {
            lp.width = widthPx
            fill.layoutParams = lp
        }
    }

    private fun updateClock() {
        val time = timeFormat.format(Date())
        if (tvClock?.text?.toString() != time) tvClock?.text = time
        val date = dateFormat.format(Date())
        if (tvDate?.text?.toString() != date) tvDate?.text = date
    }

    private fun formatTime(ms: Long): String {
        if (ms <= 0L) return getString(R.string.unknown_time)
        val totalSec = ms / 1000
        return String.format(Locale.getDefault(), "%02d:%02d", totalSec / 60, totalSec % 60)
    }

    // ─────────────────────────────────────────────────────────
    //  按键交互
    // ─────────────────────────────────────────────────────────

    override fun onAction(action: Int): Boolean {
        return when (action) {
            KeydroidxKeyAction.LEFT -> {
                sendServiceAction(PlaybackService.ACTION_PREV)
                true
            }
            KeydroidxKeyAction.RIGHT -> {
                sendServiceAction(PlaybackService.ACTION_NEXT)
                true
            }
            // 确认键：本页唯一一个播放/暂停入口
            KeydroidxKeyAction.SELECT -> {
                sendServiceAction(PlaybackService.ACTION_PLAY_PAUSE)
                true
            }
            // ↑ / ↓ 明确吞掉：不滚动歌词，也不让它们落到系统的焦点导航上
            KeydroidxKeyAction.UP, KeydroidxKeyAction.DOWN -> true
            // 右软键 / 锁屏键：退出歌词锁屏（回播放页，后台继续播放）
            KeydroidxKeyAction.SOFT_RIGHT, KeydroidxKeyAction.LOCK_SCREEN -> {
                finish()
                true
            }
            else -> super.onAction(action)
        }
    }

    /**
     * `*` 不属于 KeydroidxKeyAction 语义集合，
     * 基类 `dispatchKeyEvent` 对未映射键码会放行到这里（与播放页做法一致）。
     * 保留它作为退出锁屏的备用出口：个别机型右软键会被 ROM 抢占。
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (event != null && event.repeatCount > 0) return true
        return when (keyCode) {
            KeyEvent.KEYCODE_STAR -> {
                finish()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    // ─────────────────────────────────────────────────────────
    //  播放控制
    // ─────────────────────────────────────────────────────────

    private fun sendServiceAction(action: String, positionMs: Long = -1L) {
        val intent = Intent(this, PlaybackService::class.java).apply {
            this.action = action
            if (positionMs >= 0) putExtra(PlaybackService.EXTRA_SEEK_POSITION, positionMs)
        }
        startService(intent)
    }

    companion object {
        private const val TAG = "LyricLockScreen"

        /** 大时钟设计字号：锁屏主视觉，明显大于生态常规字号阶梯 */
        private const val CLOCK_SIZE_SP = 30f

        /** 日期设计字号 */
        private const val DATE_SIZE_SP = 12f

        /** 歌词基准设计字号（当前行 +2、远端行 -1，在渲染时按档位微调） */
        private const val LYRIC_SIZE_SP = 11f

        /** 内容层基准内边距（dp），左右稍宽、上下稍窄 */
        private const val H_PADDING_DP = 10f
        private const val V_PADDING_DP = 6f

        /** 不换行空格：用于越界槽位占位，保证行高恒定 */
        private const val NON_BREAKING_SPACE = "\u00A0"

        /**
         * 统一入口：从播放页 / 任何前台页面进入歌词锁屏。
         * 单实例（singleTop），重复进入复用同一页。
         */
        fun start(context: Context) {
            val intent = Intent(context, LyricLockScreenActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
}
