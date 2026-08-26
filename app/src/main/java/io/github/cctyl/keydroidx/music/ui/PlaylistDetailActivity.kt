package io.github.cctyl.keydroidx.music.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import io.github.cctyl.keydroidx.music.R
import io.github.cctyl.keydroidx.music.cache.PlaylistSongCache
import io.github.cctyl.keydroidx.music.download.DownloadManager
import io.github.cctyl.keydroidx.music.download.DownloadStatus
import io.github.cctyl.keydroidx.music.library.LibraryManager
import io.github.cctyl.keydroidx.music.network.PlaylistApi
import io.github.cctyl.keydroidx.music.network.RetrofitClient
import io.github.cctyl.keydroidx.music.network.model.AlbumItem
import io.github.cctyl.keydroidx.music.network.model.ArtistItem
import io.github.cctyl.keydroidx.music.network.model.SongItem
import io.github.cctyl.keydroidx.music.player.PlaybackService
import io.github.cctyl.keydroidx.music.player.PlaybackStateManager
import io.github.cctyl.nokia.keycore.model.NokiaKeyAction
import io.github.cctyl.nokia.keycore.ui.NokiaBaseActivity
import io.github.cctyl.nokia.keycore.ui.NokiaFontManager
import io.github.cctyl.nokia.keycore.ui.NokiaIcons
import io.github.cctyl.nokia.keycore.ui.dialog.NokiaConfirmDialog
import io.github.cctyl.nokia.keycore.ui.dialog.NokiaOptionsDialog
import io.github.cctyl.nokia.keycore.ui.page.NokiaListFocusHelper
import java.io.Serializable
import kotlinx.coroutines.launch

/**
 * 歌单详情页（歌曲列表）
 *
 * 从 MainActivity 或其他入口进入，显示一个歌单的全部歌曲。
 * 支持物理按键导航：UP/DOWN 移动焦点，SELECT 播放，
 * SOFT_LEFT 呼出选项菜单，SOFT_RIGHT 返回列表。
 */
class PlaylistDetailActivity : NokiaBaseActivity() {

    companion object {
        private const val TAG_DETAIL = "PlaylistDetail"
        /** 懒加载页大小：每次只创建这么多行视图 */
        private const val PAGE_SIZE = 20
        private const val EXTRA_PLAYLIST_NAME = "playlist_name"
        private const val EXTRA_PLAYLIST_ID = "playlist_id"
        private const val EXTRA_PLAYLIST_ICON = "playlist_icon"
        private const val EXTRA_ALL_FAV = "all_fav"
        private const val EXTRA_IS_HISTORY = "is_history"
        private const val EXTRA_SONGS = "songs"
        private const val EXTRA_SEARCH_KEYWORD = "search_keyword"
        /** 搜索结果每页条数（与接口 limit 一致） */
        private const val SEARCH_PAGE_SIZE = 30
        private const val NO_ID = -1L

        /**
         * 启动歌单详情页（本地歌曲列表，mock 数据用或历史记录）
         */
        fun start(
            context: Context,
            playlistName: String,
            playlistIcon: String,
            songs: ArrayList<SongDisplayItem>,
            isHistory: Boolean = false
        ) {
            val intent = Intent(context, PlaylistDetailActivity::class.java).apply {
                putExtra(EXTRA_PLAYLIST_NAME, playlistName)
                putExtra(EXTRA_PLAYLIST_ICON, playlistIcon)
                putExtra(EXTRA_SONGS, songs)
                putExtra(EXTRA_IS_HISTORY, isHistory)
            }
            context.startActivity(intent)
        }

        /**
         * 启动歌单详情页（真实网易云歌单，按 ID 异步拉取歌曲）
         * @param allFav true=歌单内所有歌曲显示红心（如「我喜欢的音乐」）
         */
        fun start(
            context: Context,
            playlistId: Long,
            playlistName: String,
            playlistIcon: String,
            allFav: Boolean = false
        ) {
            val intent = Intent(context, PlaylistDetailActivity::class.java).apply {
                putExtra(EXTRA_PLAYLIST_NAME, playlistName)
                putExtra(EXTRA_PLAYLIST_ICON, playlistIcon)
                putExtra(EXTRA_PLAYLIST_ID, playlistId)
                putExtra(EXTRA_ALL_FAV, allFav)
            }
            context.startActivity(intent)
        }

        /**
         * 启动搜索结果页（按关键词异步搜索，滚动到底自动加载下一页）
         */
        fun start(context: Context, keyword: String) {
            val intent = Intent(context, PlaylistDetailActivity::class.java).apply {
                putExtra(EXTRA_PLAYLIST_NAME, "搜索：$keyword")
                putExtra(EXTRA_PLAYLIST_ICON, NokiaIcons.ICON_SEARCH)
                putExtra(EXTRA_SEARCH_KEYWORD, keyword)
            }
            context.startActivity(intent)
        }
    }

    // ── 数据 ──
    private var playlistName: String = ""
    private var playlistIcon: String = NokiaIcons.ICON_QUEUE_MUSIC
    private var playlistId: Long = NO_ID
    /** 「我喜欢的音乐」等全收藏歌单：所有歌曲红心 */
    private var allFav = false
    private var isHistory = false
    private var songs: List<SongDisplayItem> = emptyList()

    // ── 搜索模式（网络分页）──
    /** 非空表示本页为搜索结果页，按关键词分页拉取 */
    private var searchKeyword: String? = null
    private var searchLoading = false
    /** 服务端是否还有更多结果（上一页返回满 SEARCH_PAGE_SIZE 条时认为可能有） */
    private var searchHasMore = true

    // ── UI 控件 ──
    private lateinit var llPlayAll: LinearLayout
    private lateinit var tvPlayAllSub: TextView
    private lateinit var badgePlayMode: TextView
    private lateinit var llSongContainer: LinearLayout

    // ── 焦点状态 ──
    private val songItemViews = mutableListOf<LinearLayout>()
    private lateinit var focusHelper: NokiaListFocusHelper
    val focusIdx: Int get() = if (::focusHelper.isInitialized) focusHelper.focusIndex else 0

    // ── 懒加载 ──
    /** 已渲染的曲目条数（songs 全量在内存，视图分页创建） */
    private var renderedCount = 0

    // ── 颜色（跟随当前主题，取值见 MusicTheme / HTML 原型）──
    private val colorWhite get() = Color.WHITE
    private val colorSubtext get() = MusicTheme.current(this).subtext
    private val colorSubtextFocused get() = MusicTheme.SUBTEXT_FOCUSED
    private val colorAccent get() = MusicTheme.BRAND_ACCENT
    private val colorFavRed get() = Color.parseColor("#EF4444")
    private val colorFavGray get() = Color.parseColor("#64748B")
    private val colorDivider get() = MusicTheme.current(this).dashed

    // ══════════════════════════════════════════════════════════
    //  NokiaBaseActivity 回调
    // ══════════════════════════════════════════════════════════
    override fun getContentLayoutRes(): Int = R.layout.activity_playlist_detail

    override fun onInitViews() {
        // XML 静态经典蓝配色 → 当前主题色
        findViewById<View?>(android.R.id.content)?.let { MusicTheme.applyToViewTree(it) }
        // 读取 Intent 数据
        playlistName = intent.getStringExtra(EXTRA_PLAYLIST_NAME) ?: "歌单"
        playlistIcon = intent.getStringExtra(EXTRA_PLAYLIST_ICON) ?: NokiaIcons.ICON_QUEUE_MUSIC
        playlistId = intent.getLongExtra(EXTRA_PLAYLIST_ID, NO_ID)
        allFav = intent.getBooleanExtra(EXTRA_ALL_FAV, false)
        isHistory = intent.getBooleanExtra(EXTRA_IS_HISTORY, false)
        @Suppress("UNCHECKED_CAST")
        songs = (intent.getSerializableExtra(EXTRA_SONGS) as? ArrayList<SongDisplayItem>) ?: emptyList()
        searchKeyword = intent.getStringExtra(EXTRA_SEARCH_KEYWORD)

        // 设置标题栏
        setPageTitle(playlistName)
        setTitleIcon(playlistIcon)
        setStatusBarVisible(true)
        setSignalIcon(NokiaIcons.ICON_SIGNAL_CELLULAR_4_BAR)
        setBatteryPercent("70%")
        setSoftKeys(
            getString(R.string.softkey_options),
            getString(R.string.softkey_play_selected),
            getString(R.string.softkey_back_to_list)
        )

        // 绑定控件
        llPlayAll = findViewById(R.id.ll_play_all)
        tvPlayAllSub = findViewById(R.id.tv_play_all_sub)
        badgePlayMode = findViewById(R.id.badge_play_mode)
        llSongContainer = findViewById(R.id.ll_song_container)

        // 初始化焦点辅助器
        val scroll = findViewById<android.widget.ScrollView>(R.id.scroll_playlist_detail)
        focusHelper = NokiaListFocusHelper(this, scroll)
        focusHelper.setOnFocusChangedListener { oldIdx, newIdx, newView ->
            if (newView is LinearLayout) {
                setChildTextColors(newView, true)
            }
            val oldView = focusHelper.items.getOrNull(oldIdx) as? LinearLayout
            if (oldView != null) {
                if (oldView == llPlayAll) {
                    oldView.setBackgroundColor(Color.parseColor("#40000000"))
                }
                setChildTextColors(oldView, false)
            }
        }

        // 设置播放全部行
        NokiaIcons.setIcon(findViewById(R.id.icon_play_all), NokiaIcons.ICON_PLAY_CIRCLE)
        tvPlayAllSub.text = when {
            searchKeyword != null && songs.isEmpty() -> "搜索中…"
            playlistId != NO_ID && songs.isEmpty() -> "加载中…"
            else -> "共 ${songs.size} 首歌曲" + if (searchKeyword != null && searchHasMore) "（下滑加载更多）" else ""
        }

        // 真实歌单：异步拉取歌曲列表；搜索：分页拉取；本地歌单：直接用传入数据
        if (playlistId != NO_ID && songs.isEmpty()) {
            fetchRealSongs()
        } else if (searchKeyword != null && songs.isEmpty()) {
            loadMoreSearch(initial = true)
        } else {
            finishSetup()
        }
    }

    /**
     * 搜索下一页结果并追加到列表。
     * @param initial 首页：完成后 finishSetup 渲染整页；否则只追加新条目视图
     */
    private fun loadMoreSearch(initial: Boolean) {
        val kw = searchKeyword ?: return
        if (searchLoading || !searchHasMore) return
        searchLoading = true
        lifecycleScope.launch {
            try {
                val offset = songs.size
                Log.d(TAG_DETAIL, "search '$kw' page offset=$offset")
                val response = RetrofitClient.api.search(keyword = kw, offset = offset)
                val pageSongs = response.result?.songs ?: emptyList()
                if (isDestroyed || isFinishing) return@launch

                // 按 ID 去重后追加
                val existIds = songs.map { it.id }.toSet()
                val mapped = pageSongs
                    .filter { it.id !in existIds }
                    .map { s ->
                        SongDisplayItem(
                            id = s.id,
                            title = s.name,
                            artist = s.artists?.joinToString("/") { it.name } ?: "未知艺术家",
                            isVip = (s.fee ?: 0) == 1,
                            noCopyright = s.noCopyright
                        )
                    }
                // 返回不足一页 → 已到底；去重后为空也视为到底
                searchHasMore = pageSongs.size >= SEARCH_PAGE_SIZE && mapped.isNotEmpty()

                if (initial) {
                    songs = mapped
                    if (songs.isEmpty()) {
                        Toast.makeText(this@PlaylistDetailActivity, "未找到与「$kw」相关的歌曲", Toast.LENGTH_SHORT).show()
                    }
                    finishSetup()
                } else {
                    if (mapped.isEmpty()) {
                        Toast.makeText(this@PlaylistDetailActivity, "没有更多搜索结果了", Toast.LENGTH_SHORT).show()
                    } else {
                        songs = songs + mapped
                        appendSongs(PAGE_SIZE)
                        focusHelper.setItems(getFocusableViews())
                    }
                }
                tvPlayAllSub.text = "共 ${songs.size} 首歌曲" +
                        if (searchHasMore) "（下滑加载更多）" else ""
                Log.d(TAG_DETAIL, "search '$kw' loaded ${mapped.size}, total=${songs.size}, hasMore=$searchHasMore")
            } catch (e: Exception) {
                Log.e(TAG_DETAIL, "loadMoreSearch failed", e)
                if (!isDestroyed && !isFinishing) {
                    Toast.makeText(this@PlaylistDetailActivity, "加载失败：${e.message}", Toast.LENGTH_SHORT).show()
                }
            } finally {
                searchLoading = false
            }
        }
    }

    /**
     * 拉取歌单歌曲（缓存优先）。
     * ① 有缓存 → 立即渲染，离线可看
     * ② 后台拉最新数据 → 覆盖缓存并重新渲染
     */
    private fun fetchRealSongs() {
        // ① 先用缓存立即渲染
        val cached = PlaylistSongCache.load(this, playlistId)
        if (cached.isNotEmpty()) {
            Log.d(TAG_DETAIL, "playlist $playlistId show cached: ${cached.size} songs")
            songs = cached.map {
                SongDisplayItem(
                    it.id,
                    it.title,
                    it.artist,
                    isFav = allFav,
                    isVip = it.isVip,
                    noCopyright = it.noCopyright
                )
            }
            finishSetup()
        }

        // ② 后台刷新最新数据（完成后重建列表，但保留光标位置）
        lifecycleScope.launch {
            try {
                val result = PlaylistApi.getPlaylistDetail(playlistId)
                Log.d(TAG_DETAIL, "playlist $playlistId loaded ${result.size} songs")
                songs = result.map { s ->
                    SongDisplayItem(
                        s.id,
                        s.name,
                        s.artists?.joinToString("/") { it.name } ?: "未知艺术家",
                        isFav = allFav,
                        isVip = (s.fee ?: 0) == 1,
                        noCopyright = s.noCopyright
                    )
                }
                PlaylistSongCache.save(
                    this@PlaylistDetailActivity, playlistId,
                    songs.map { PlaylistSongCache.Entry(it.id, it.title, it.artist, it.isVip, it.noCopyright) }
                )
            } catch (e: Exception) {
                if (cached.isEmpty()) {
                    // 无缓存且拉取失败才报错；有缓存则静默保留旧数据
                    Log.e(TAG_DETAIL, "fetchRealSongs failed", e)
                    Toast.makeText(this@PlaylistDetailActivity, "加载失败：${e.message}", Toast.LENGTH_SHORT).show()
                } else {
                    Log.w(TAG_DETAIL, "refresh failed, keep cache: ${e.message}")
                }
            }
            if (!isDestroyed && !isFinishing && songs.isNotEmpty()) {
                finishSetup(preserveFocus = true)
            }
        }
    }

    private fun finishSetup(preserveFocus: Boolean = false) {
        // 设置播放全部行副文字
        tvPlayAllSub.text = "共 ${songs.size} 首歌曲"

        // 填充歌曲列表
        populateSongs()

        // 初始焦点；后台刷新重建时保留光标位置，避免用户正在浏览被重置回顶部
        val views = getFocusableViews()
        focusHelper.setItems(views)
        if (preserveFocus) {
            val targetIdx = focusHelper.focusIndex.coerceIn(0, (views.size - 1).coerceAtLeast(0))
            focusHelper.setFocusIndex(targetIdx, true)
        } else {
            focusHelper.setFocusIndex(0, true)
        }
    }

    // ══════════════════════════════════════════════════════════
    //  填充歌曲列表（懒加载：每次只渲染一页，焦点到底部时追加）
    // ══════════════════════════════════════════════════════════
    private fun populateSongs() {
        llSongContainer.removeAllViews()
        songItemViews.clear()
        renderedCount = 0
        appendSongs(PAGE_SIZE)
    }

    /** 追加渲染下一页曲目视图。 */
    private fun appendSongs(count: Int) {
        val from = renderedCount
        val to = minOf(from + count, songs.size)

        for (idx in from until to) {
            val song = songs[idx]
            // 分隔线在条目之前（首条除外）→ 跨页也能正确衔接
            if (idx > 0) {
                llSongContainer.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 1
                    ).also { lp ->
                        lp.leftMargin = dp(8)
                        lp.rightMargin = dp(8)
                    }
                    setBackgroundColor(colorDivider)
                })
            }

            val itemView = LayoutInflater.from(this)
                .inflate(R.layout.item_song, llSongContainer, false) as LinearLayout

            val tvIndex = itemView.findViewById<TextView>(R.id.tv_song_index)
            val tvTitle = itemView.findViewById<TextView>(R.id.tv_song_title)
            val tvArtist = itemView.findViewById<TextView>(R.id.tv_song_artist)
            val iconPlaying = itemView.findViewById<TextView>(R.id.icon_playing)
            val iconFav = itemView.findViewById<TextView>(R.id.icon_fav)

            tvIndex.text = (idx + 1).toString()
            tvTitle.text = song.title

            // 播放指示器（当前歌曲预留，后续由播放状态管理）
            iconPlaying.visibility = View.GONE
            NokiaIcons.setIcon(iconPlaying, NokiaIcons.ICON_VOLUME_UP)

            tvArtist.text = song.artist

            // 会员歌曲 / 无权限歌曲：灰显 + 提示标记
            if (song.noCopyright) {
                val gray = Color.parseColor("#9CA3AF")
                tvIndex.setTextColor(gray)
                tvTitle.setTextColor(gray)
                tvArtist.text = "${song.artist} · 无权限"
                tvArtist.setTextColor(gray)
            } else if (song.isVip) {
                val gray = Color.parseColor("#9CA3AF")
                tvIndex.setTextColor(gray)
                tvTitle.setTextColor(gray)
                tvArtist.text = "${song.artist} · 需要会员"
                tvArtist.setTextColor(gray)
            }

            if (song.isFav) {
                NokiaIcons.setIcon(iconFav, NokiaIcons.ICON_FAVORITE)
                iconFav.setTextColor(colorFavRed)
            } else {
                NokiaIcons.setIcon(iconFav, NokiaIcons.ICON_FAVORITE_BORDER)
                iconFav.setTextColor(colorFavGray)
            }

            llSongContainer.addView(itemView)
            songItemViews.add(itemView)
        }
        renderedCount = to
        // 动态创建的行错过了基类的字体初始化，主动补一次点阵字体+缩放，
        // 否则先显示系统默认大字，等异步 onFontChanged 才突然变小
        NokiaFontManager.applyToViewTree(llSongContainer)
    }

    // ══════════════════════════════════════════════════════════
    //  焦点列表构建
    // ══════════════════════════════════════════════════════════
    private fun buildFocusList() {
        // 焦点列表：第0项 = "播放全部"，第1..N项 = 歌曲
        // 使用一个集中的列表管理
        // 注意：llPlayAll 和 songItemViews 就是焦点列表
    }

    private fun getFocusableViews(): List<View> {
        return listOf(llPlayAll) + songItemViews
    }

    private fun applyFocus() {
        val views = getFocusableViews()
        views.forEachIndexed { i, view ->
            if (view is LinearLayout) {
                if (i == focusIdx) {
                    view.background = MusicTheme.createFocusDrawable(this, 4f)
                    setChildTextColors(view, true)
                    // 规范要求（NOKIA_DEVELOPMENT_RULES.md）：焦点移动必须 requestFocus()，
                    // 否则 ScrollView 不知道要把这一行滚进可视区，光标会滚出屏幕外
                    view.requestFocus()
                } else {
                    view.setBackgroundColor(Color.TRANSPARENT)
                    // 播放全部行有特殊背景，恢复它
                    if (view == llPlayAll) {
                        view.setBackgroundColor(Color.parseColor("#40000000"))
                    }
                    setChildTextColors(view, false)
                }
            }
        }
    }

    /**
     * 设置 LinearLayout 内子 TextView 的焦点/非焦点颜色
     */
    private fun setChildTextColors(parent: LinearLayout, focused: Boolean) {
        val mainColor = colorWhite
        val subColor = if (focused) colorSubtextFocused else colorSubtext
        val iconColor = if (focused) colorWhite else colorAccent

        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            when (child) {
                is TextView -> {
                    // 判断是否是图标 TextView
                    val iconTf = NokiaIcons.getTypeface(this)
                    if (child.typeface == iconTf) {
                        // 特殊处理收藏心图标（保持红色）
                        val text = child.text.toString()
                        if (text == NokiaIcons.ICON_FAVORITE || text == NokiaIcons.ICON_FAVORITE_BORDER) {
                            // 保持原色
                        } else {
                            child.setTextColor(iconColor)
                        }
                    } else {
                        child.setTextColor(mainColor)
                    }
                }
                is LinearLayout -> {
                    for (j in 0 until child.childCount) {
                        val grandChild = child.getChildAt(j)
                        if (grandChild is TextView) {
                            val iconTf = NokiaIcons.getTypeface(this)
                            if (grandChild.typeface == iconTf) {
                                grandChild.setTextColor(iconColor)
                            } else {
                                grandChild.setTextColor(if (j == 0) mainColor else subColor)
                            }
                        }
                    }
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════
    //  按键处理
    // ══════════════════════════════════════════════════════════
    override fun onAction(action: Int): Boolean {
        return when (action) {
            NokiaKeyAction.UP -> {
                focusHelper.onDirection(action)
                true
            }
            NokiaKeyAction.DOWN -> {
                // 懒加载：焦点接近已渲染末尾时追加下一页（提前 2 条预加载）
                if (focusHelper.focusIndex >= renderedCount - 2) {
                    if (renderedCount < songs.size) {
                        appendSongs(PAGE_SIZE)
                        focusHelper.setItems(getFocusableViews())
                        Log.d(TAG_DETAIL, "lazy load: rendered ${renderedCount}/${songs.size}")
                    } else if (searchKeyword != null) {
                        // 视图已全部渲染且还有更多 → 拉取搜索下一页
                        loadMoreSearch(initial = false)
                    }
                }
                focusHelper.onDirection(action)
                true
            }
            NokiaKeyAction.SELECT -> {
                // 选中播放
                val currentIdx = focusHelper.focusIndex
                if (currentIdx == 0) {
                    // 播放全部
                    if (songs.isNotEmpty()) {
                        playSong(songs[0])
                    }
                } else {
                    val songIdx = currentIdx - 1
                    if (songIdx in songs.indices) {
                        playSong(songs[songIdx])
                    }
                }
                true
            }
            NokiaKeyAction.SOFT_LEFT -> {
                showOptionsMenu()
                true
            }
            NokiaKeyAction.SOFT_RIGHT -> {
                finish()
                true
            }
            else -> super.onAction(action)
        }
    }

    private fun playSong(song: SongDisplayItem) {
        var startIndex = if (focusIdx == 0) 0 else focusIdx - 1

        // 无权限/下架歌不可播：从选中处向后找第一个可播的（环形）
        if (song.noCopyright) {
            val alt = (1..songs.size).firstOrNull { off ->
                val cand = songs[(startIndex + off) % songs.size]
                !cand.noCopyright && !cand.isVip
            }?.let { (startIndex + it) % songs.size }
            if (alt == null) {
                Toast.makeText(this, "歌单内全部歌曲都无权限，无法播放", Toast.LENGTH_SHORT).show()
                return
            }
            Toast.makeText(this, "《${song.title}》无权限播放，已跳过", Toast.LENGTH_SHORT).show()
            startIndex = alt
        } else if (song.isVip) {
            // 会员歌不可播：从选中处向后找第一个可播的（环形）
            val alt = (1..songs.size).firstOrNull { off ->
                val cand = songs[(startIndex + off) % songs.size]
                !cand.noCopyright && !cand.isVip
            }?.let { (startIndex + it) % songs.size }
            if (alt == null) {
                Toast.makeText(this, "歌单内全部歌曲都需要会员，无法播放", Toast.LENGTH_SHORT).show()
                return
            }
            Toast.makeText(this, "《${song.title}》需要会员，已跳过", Toast.LENGTH_SHORT).show()
            startIndex = alt
        }

        // 1. SongDisplayItem → SongItem 并构造播放队列
        val queue: List<SongItem> = songs.map { display ->
            SongItem(
                id = display.id,
                name = display.title,
                artists = listOfNotNull(
                    display.artist.takeIf { it.isNotBlank() }?.let { ArtistItem(name = it) }
                ),
                album = AlbumItem(name = null, picUrl = null),
                duration = null,
                fee = if (display.isVip) 1 else 0,
                noCopyright = display.noCopyright
            )
        }
        val safeIndex = startIndex.coerceIn(0, queue.lastIndex.coerceAtLeast(0))

        // 2. 推入全局播放状态管理器（普通歌单播放，退出 FM 模式）
        PlaybackStateManager.setPersonalFm(false)
        PlaybackStateManager.updatePlaylist(queue, safeIndex)

        // 3. 启动后台播放服务，按索引加载并播放
        val playIntent = Intent(this, PlaybackService::class.java).apply {
            action = PlaybackService.ACTION_PLAY_INDEX
            putExtra(PlaybackService.EXTRA_INDEX, safeIndex)
        }
        startService(playIntent)

        // 4. 跳转播放详情页
        val playerIntent = Intent(this, MusicPlayerActivity::class.java)
        startActivity(playerIntent)
    }

    private fun showOptionsMenu() {
        val dialog = NokiaOptionsDialog(this, getString(R.string.softkey_options))
        val actions = mutableListOf<() -> Unit>()
        val iconColor = android.graphics.Color.WHITE
        val iconSize = (18 * resources.displayMetrics.density).toInt()

        if (isHistory) {
            dialog.addItem(1, getString(R.string.menu_clear_all_history), NokiaIcons.createDrawable(this, NokiaIcons.ICON_DELETE, iconSize, iconColor))
            actions.add { showClearHistoryConfirmDialog() }

            if (focusIdx > 0) {
                val songIdx = focusIdx - 1
                if (songIdx in songs.indices) {
                    val targetSong = songs[songIdx]
                    dialog.addItem(2, getString(R.string.menu_remove_from_history), NokiaIcons.createDrawable(this, NokiaIcons.ICON_CLOSE, iconSize, iconColor))
                    actions.add {
                        LibraryManager.removeRecentSong(targetSong.id)
                        val updated = songs.toMutableList()
                        updated.removeAt(songIdx)
                        songs = updated
                        tvPlayAllSub.text = "共 ${songs.size} 首歌曲"
                        focusHelper.setFocusIndex(focusIdx.coerceAtMost(songs.size), true)
                        finishSetup(preserveFocus = true)
                        Toast.makeText(this, getString(R.string.toast_history_removed), Toast.LENGTH_SHORT).show()
                    }

                    // 下载 / 删除下载
                    val dlTask = DownloadManager.getTask(targetSong.id)
                    if (dlTask == null || dlTask.status == DownloadStatus.FAILED) {
                        dialog.addItem(actions.size + 1, getString(R.string.menu_download_song), NokiaIcons.createDrawable(this, NokiaIcons.ICON_DOWNLOAD, iconSize, iconColor))
                        actions.add {
                            val songItem = SongItem(
                                id = targetSong.id,
                                name = targetSong.title,
                                artists = listOfNotNull(targetSong.artist.takeIf { it.isNotBlank() }?.let { ArtistItem(name = it) }),
                                album = AlbumItem(name = null, picUrl = null),
                                duration = null,
                                fee = if (targetSong.isVip) 1 else 0,
                                noCopyright = targetSong.noCopyright
                            )
                            DownloadManager.enqueueDownload(songItem)
                            Toast.makeText(this, "已加入下载队列", Toast.LENGTH_SHORT).show()
                        }
                    } else if (dlTask.status == DownloadStatus.COMPLETED) {
                        dialog.addItem(actions.size + 1, getString(R.string.menu_delete_download), NokiaIcons.createDrawable(this, NokiaIcons.ICON_DELETE, iconSize, iconColor))
                        actions.add {
                            val confirm = NokiaConfirmDialog(
                                this,
                                getString(R.string.dialog_delete_download_title),
                                getString(R.string.dialog_delete_download_msg)
                            )
                            confirm.setPositiveButton("删除") {
                                DownloadManager.deleteDownload(targetSong.id)
                                Toast.makeText(this, "已删除下载", Toast.LENGTH_SHORT).show()
                            }
                            confirm.show()
                        }
                    } else {
                        dialog.addItem(actions.size + 1, getString(R.string.menu_pause_download), NokiaIcons.createDrawable(this, NokiaIcons.ICON_PAUSE, iconSize, iconColor))
                        actions.add {
                            DownloadManager.pauseDownload(targetSong.id)
                            Toast.makeText(this, "已暂停", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        } else {
            if (focusIdx > 0) {
                val songIdx = focusIdx - 1
                if (songIdx in songs.indices) {
                    val targetSong = songs[songIdx]
                    dialog.addItem(1, getString(R.string.menu_play_song), NokiaIcons.createDrawable(this, NokiaIcons.ICON_PLAY, iconSize, iconColor))
                    actions.add { playSong(targetSong) }

                    // 下载 / 删除下载
                    val dlTask = DownloadManager.getTask(targetSong.id)
                    if (dlTask == null || dlTask.status == DownloadStatus.FAILED) {
                        dialog.addItem(actions.size + 1, getString(R.string.menu_download_song), NokiaIcons.createDrawable(this, NokiaIcons.ICON_DOWNLOAD, iconSize, iconColor))
                        actions.add {
                            val songItem = SongItem(
                                id = targetSong.id,
                                name = targetSong.title,
                                artists = listOfNotNull(targetSong.artist.takeIf { it.isNotBlank() }?.let { ArtistItem(name = it) }),
                                album = AlbumItem(name = null, picUrl = null),
                                duration = null,
                                fee = if (targetSong.isVip) 1 else 0,
                                noCopyright = targetSong.noCopyright
                            )
                            DownloadManager.enqueueDownload(songItem)
                            Toast.makeText(this, "已加入下载队列", Toast.LENGTH_SHORT).show()
                        }
                    } else if (dlTask.status == DownloadStatus.COMPLETED) {
                        dialog.addItem(actions.size + 1, getString(R.string.menu_delete_download), NokiaIcons.createDrawable(this, NokiaIcons.ICON_DELETE, iconSize, iconColor))
                        actions.add {
                            val confirm = NokiaConfirmDialog(
                                this,
                                getString(R.string.dialog_delete_download_title),
                                getString(R.string.dialog_delete_download_msg)
                            )
                            confirm.setPositiveButton("删除") {
                                DownloadManager.deleteDownload(targetSong.id)
                                Toast.makeText(this, "已删除下载", Toast.LENGTH_SHORT).show()
                            }
                            confirm.show()
                        }
                    } else {
                        dialog.addItem(actions.size + 1, getString(R.string.menu_pause_download), NokiaIcons.createDrawable(this, NokiaIcons.ICON_PAUSE, iconSize, iconColor))
                        actions.add {
                            DownloadManager.pauseDownload(targetSong.id)
                            Toast.makeText(this, "已暂停", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }

        dialog.addItem(actions.size + 1, getString(R.string.opt_refresh), NokiaIcons.createDrawable(this, NokiaIcons.ICON_REFRESH, iconSize, iconColor))
        actions.add {
            if (isHistory) {
                songs = LibraryManager.recentSongs.value.map { song ->
                    SongDisplayItem(
                        id = song.id,
                        title = song.name,
                        artist = song.artistName,
                        isFav = LibraryManager.isFavorite(song.id),
                        isVip = song.fee == 1,
                        noCopyright = song.noCopyright
                    )
                }
                tvPlayAllSub.text = "共 ${songs.size} 首歌曲"
                focusHelper.setFocusIndex(0, true)
                finishSetup(preserveFocus = false)
                Toast.makeText(this, "已刷新", Toast.LENGTH_SHORT).show()
            } else if (playlistId != NO_ID) {
                fetchRealSongs()
            }
        }

        dialog.setOnOptionSelectedListener { index, _ ->
            if (index in actions.indices) {
                actions[index].invoke()
            }
        }
        dialog.show()
    }

    private fun showClearHistoryConfirmDialog() {
        NokiaConfirmDialog(this, getString(R.string.dialog_clear_history_title), getString(R.string.dialog_clear_history_msg))
            .setPositiveButton(getString(R.string.dialog_confirm)) {
                LibraryManager.clearRecent()
                songs = emptyList()
                tvPlayAllSub.text = "共 0 首歌曲"
                focusHelper.setFocusIndex(0, true)
                finishSetup(preserveFocus = false)
                Toast.makeText(this, getString(R.string.toast_history_cleared), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(getString(R.string.dialog_cancel), null)
            .show()
    }

    // ══════════════════════════════════════════════════════════
    //  工具
    // ══════════════════════════════════════════════════════════
    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density + 0.5f).toInt()
    }
}

/**
 * 歌曲展示数据（用于跨 Activity 传递）
 */
data class SongDisplayItem(
    val id: Long,
    val title: String,
    val artist: String,
    val isFav: Boolean = false,
    val isVip: Boolean = false,
    val noCopyright: Boolean = false
) : Serializable