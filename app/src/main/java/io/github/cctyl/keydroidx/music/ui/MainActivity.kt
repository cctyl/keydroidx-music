package io.github.cctyl.keydroidx.music.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import io.github.cctyl.keydroidx.music.R
import io.github.cctyl.keydroidx.music.library.LibraryManager
import io.github.cctyl.nokia.keycore.model.NokiaKeyAction
import io.github.cctyl.nokia.keycore.ui.NokiaBaseActivity
import io.github.cctyl.nokia.keycore.ui.NokiaFontManager
import io.github.cctyl.nokia.keycore.ui.NokiaIcons
import io.github.cctyl.nokia.keycore.ui.dialog.NokiaOptionsDialog
import io.github.cctyl.keydroidx.music.auth.CookieManager
import io.github.cctyl.keydroidx.music.auth.UserProfileCache
import io.github.cctyl.keydroidx.music.cache.PlaylistSongCache
import io.github.cctyl.keydroidx.music.network.PlaylistApi
import io.github.cctyl.keydroidx.music.network.RetrofitClient
import io.github.cctyl.keydroidx.music.player.PlaybackService
import io.github.cctyl.keydroidx.music.player.PlaybackStateManager
import io.github.cctyl.keydroidx.music.ui.PlaylistDetailActivity
import io.github.cctyl.keydroidx.music.ui.SongDisplayItem
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * 音乐库主界面，包含 4 个 Tab：我的 / 发现 / 榜单 / 搜索
 *
 * 布局结构（activity_music_main.xml）：
 *   NokiaBaseActivity 骨架提供 顶部标题栏 + 底部软键栏
 *   本布局只负责: Tab栏 + 4个内容页
 */
class MainActivity : NokiaBaseActivity() {

    // ── Tab 索引 ──
    private val TAB_MINE = 0
    private val TAB_DISCOVER = 1
    private val TAB_CHART = 2
    private val TAB_SEARCH = 3
    private var currentTab = TAB_MINE

    // ── Tab 栏控件 ──
    private lateinit var tabViews: List<View>
    private lateinit var tabIcons: List<TextView>
    private lateinit var tabLabels: List<TextView>
    private lateinit var tabIndicators: List<View>
    private lateinit var tabContents: List<View>

    // ── 我的 Tab ──
    private lateinit var mineUserHeader: LinearLayout
    private lateinit var mineFixedRoots: List<LinearLayout>
    private lateinit var minePlaylistRoots: MutableList<LinearLayout>
    private var realPlaylists: List<PlaylistApi.PlaylistInfo> = emptyList()
    /** specialType=5 的「我喜欢的音乐」歌单 ID（未拉到时为 null） */
    private var favPlaylistId: Long? = null

    // ── 发现 Tab ──
    private lateinit var discoverGridRoots: List<LinearLayout>
    private var discoverListRoots = mutableListOf<LinearLayout>()
    private var discoverPlaylists: List<PlaylistApi.PlaylistCard> = emptyList()

    private val tagPalettes = listOf(
        Pair("#E05A47", "精选"),
        Pair("#1E88E5", "推荐"),
        Pair("#43A047", "热播"),
        Pair("#8E24AA", "私享"),
        Pair("#FB8C00", "歌单"),
        Pair("#00ACC1", "雷达"),
        Pair("#D81B60", "流行"),
        Pair("#546E7A", "精选"),
        Pair("#3949AB", "推荐"),
        Pair("#00897B", "精选")
    )

    // ── 榜单 Tab ──
    private lateinit var chartItemRoots: MutableList<LinearLayout>

    // ── 搜索 Tab ──
    private lateinit var searchKeywordRoots: MutableList<LinearLayout>

    // ── 当前焦点状态 ──
    private var focusItems: List<LinearLayout> = emptyList()
    private var focusIdx = 0

    // ── 颜色缓存 ──
    private val colorAccent by lazy { Color.parseColor("#38BDF8") }
    private val colorSubtext by lazy { Color.parseColor("#B0B0B0") }
    private val colorWhite by lazy { Color.WHITE }
    private val colorSubtextFocused by lazy { Color.parseColor("#E0F2FE") }
    private val colorFocusBg by lazy { Color.parseColor("#0055AA") }
    private val activeTabGradient by lazy {
        GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.TRANSPARENT, Color.parseColor("#660055AA"))
        )
    }
    private val colorDivider by lazy { Color.parseColor("#2D426B") }
    private val colorSectionBg by lazy { Color.parseColor("#4D000000") }

    // ══════════════════════════════════════════════════════════
    //  NokiaBaseActivity 回调
    // ══════════════════════════════════════════════════════════
    override fun getContentLayoutRes(): Int = R.layout.activity_music_main

    override fun onResume() {
        super.onResume()
        // 刷新最近播放记录数量展示
        findViewById<View>(R.id.content_mine)?.let { mineRoot ->
            val recentCount = LibraryManager.recentSongs.value.size
            mineRoot.findViewById<TextView>(R.id.tv_history_sub)?.text = "${recentCount} 首播放记录"
            mineRoot.findViewById<TextView>(R.id.badge_history)?.text = "$recentCount"
        }
    }

    override fun onInitViews() {
        // 响应式 Tab 栏高度：HTML 原型 tab 栏 = 40px / 340px ≈ 11.76% 屏幕高度
        val tabBar = findViewById<View>(R.id.tab_bar)
        val screenHeight = resources.displayMetrics.heightPixels
        val tabBarHeightPx = (screenHeight * 0.1176f).toInt().coerceAtLeast(
            (40 * resources.displayMetrics.density).toInt()
        )
        tabBar.layoutParams.height = tabBarHeightPx

        setupTabBar()
        setupMineTab()
        setupDiscoverTab()
        setupChartTab()
        setupSearchTab()
        switchTab(TAB_MINE)

        // 加载用户信息（未登录则显示占位提示）
        loadUserProfile()

        // 状态栏：电量图标 + 百分比（与 HTML 原型一致）
        setStatusBarVisible(true)
        registerBatteryReceiver()

        requestNotificationPermissionIfNeeded()
    }

    /**
     * Android 13+ 媒体通知需要 POST_NOTIFICATIONS 运行时权限，
     * 不申请的话播放通知栏不显示（前台服务仍存活，但用户看不到状态）。
     */
    private fun requestNotificationPermissionIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            Log.d("MainActivity", "request POST_NOTIFICATIONS")
            ActivityCompat.requestPermissions(
                this,
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                2001
            )
        }
    }

    // ══════════════════════════════════════════════════════════
    //  Tab 栏
    // ══════════════════════════════════════════════════════════
    private fun setupTabBar() {
        tabViews = listOf(
            findViewById(R.id.tab_mine),
            findViewById(R.id.tab_discover),
            findViewById(R.id.tab_chart),
            findViewById(R.id.tab_search)
        )
        tabIcons = listOf(
            findViewById(R.id.tab_icon_mine),
            findViewById(R.id.tab_icon_discover),
            findViewById(R.id.tab_icon_chart),
            findViewById(R.id.tab_icon_search)
        )
        tabLabels = listOf(
            findViewById(R.id.tab_label_mine),
            findViewById(R.id.tab_label_discover),
            findViewById(R.id.tab_label_chart),
            findViewById(R.id.tab_label_search)
        )
        tabIndicators = listOf(
            findViewById(R.id.tab_indicator_mine),
            findViewById(R.id.tab_indicator_discover),
            findViewById(R.id.tab_indicator_chart),
            findViewById(R.id.tab_indicator_search)
        )
        tabContents = listOf(
            findViewById(R.id.content_mine),
            findViewById(R.id.content_discover),
            findViewById(R.id.content_chart),
            findViewById(R.id.content_search)
        )

        // 设置 Tab 图标
        val iconCodes = listOf(
            NokiaIcons.ICON_PERSON,
            NokiaIcons.ICON_EXPLORE,
            NokiaIcons.ICON_LEADERBOARD,
            NokiaIcons.ICON_SEARCH
        )
        tabIcons.forEachIndexed { i, tv -> NokiaIcons.setIcon(tv, iconCodes[i]) }

        // Tab 触屏点击
        tabViews.forEachIndexed { i, v -> v.setOnClickListener { switchTab(i) } }
    }

    // ══════════════════════════════════════════════════════════
    //  我的 Tab
    // ══════════════════════════════════════════════════════════
    private fun setupMineTab() {
        val mineRoot = findViewById<View>(R.id.content_mine)

        // 3 个固定入口
        mineUserHeader = mineRoot.findViewById(R.id.layout_user_header)
        mineFixedRoots = listOf(
            mineRoot.findViewById(R.id.item_favorites),
            mineRoot.findViewById(R.id.item_history),
            mineRoot.findViewById(R.id.item_local)
        )

        // 图标
        NokiaIcons.setIcon(mineRoot.findViewById(R.id.icon_favorites), NokiaIcons.ICON_FAVORITE)
        NokiaIcons.setIcon(mineRoot.findViewById(R.id.icon_history), NokiaIcons.ICON_HISTORY)
        NokiaIcons.setIcon(mineRoot.findViewById(R.id.icon_local), NokiaIcons.ICON_SD_CARD)

        // 副文字 / badge
        mineRoot.findViewById<TextView>(R.id.tv_favorites_sub).text = "0 首歌曲"
        mineRoot.findViewById<TextView>(R.id.badge_favorites).text = "0"
        val recentCount = LibraryManager.recentSongs.value.size
        mineRoot.findViewById<TextView>(R.id.tv_history_sub).text = "${recentCount} 首播放记录"
        mineRoot.findViewById<TextView>(R.id.badge_history).text = "$recentCount"

        // 在固定入口之间插入虚线分割线
        // mineRoot 是 ScrollView，其第一个子 View 是根 LinearLayout
        val mineListRoot = (mineRoot as? android.widget.ScrollView)?.getChildAt(0) as? LinearLayout
        if (mineListRoot != null) {
            val favIdx = mineListRoot.indexOfChild(mineFixedRoots[0])
            val histIdx = mineListRoot.indexOfChild(mineFixedRoots[1])
            if (favIdx >= 0 && histIdx > favIdx) {
                mineListRoot.addView(makeDivider(6, 6), favIdx + 1)
                // 插入第一个分割线后，history 的索引 +1
                val newHistIdx = mineListRoot.indexOfChild(mineFixedRoots[1])
                mineListRoot.addView(makeDivider(6, 6), newHistIdx + 1)
            }
        }

        // Section Header（数量由真实数据刷新）
        mineRoot.findViewById<TextView>(R.id.tv_section_playlist).text = "自建与收藏歌单"

        // 歌单列表：登录后由 loadUserPlaylists() 动态填充真实数据
        minePlaylistRoots = mutableListOf()
    }

    /**
     * 拉取当前用户的真实网易云歌单并填充到列表。
     * 未登录/失败时保留空列表。
     */
    private fun loadUserPlaylists(uid: Long) {
        lifecycleScope.launch {
            try {
                val result = PlaylistApi.getUserPlaylists(uid)
                Log.d("MainActivity", "getUserPlaylists: ${result.playlists.size}/${result.total} more=${result.more}")
                UserProfileCache.savePlaylists(this@MainActivity, result.playlists)
                renderUserPlaylists(result.playlists)
            } catch (e: Exception) {
                Log.e("MainActivity", "loadUserPlaylists failed", e)
            }
        }
    }

    /**
     * 渲染真实歌单列表（替换 mock 数据）。
     * specialType=5 的「我喜欢的音乐」不进下方列表，
     * 而是映射到上方固定入口「我喜欢的音乐」。
     */
    private fun renderUserPlaylists(playlists: List<PlaylistApi.PlaylistInfo>) {
        realPlaylists = playlists
        val container = findViewById<LinearLayout>(R.id.ll_playlist_container) ?: return
        container.removeAllViews()
        minePlaylistRoots.clear()

        // 拆出「我喜欢的音乐」→ 上方固定入口
        val fav = playlists.firstOrNull { it.specialType == 5 }
        favPlaylistId = fav?.id
        if (fav != null) {
            findViewById<TextView>(R.id.tv_favorites_sub)?.text = "${fav.trackCount} 首歌曲 · 云端已同步"
            findViewById<TextView>(R.id.badge_favorites)?.text = "${fav.trackCount}"
        }
        val listPlaylists = playlists.filter { it.specialType != 5 }

        findViewById<TextView>(R.id.tv_section_playlist)?.text = "自建与收藏歌单 (${listPlaylists.size})"

        listPlaylists.forEachIndexed { i, pl ->
            val itemView = LayoutInflater.from(this)
                .inflate(R.layout.item_playlist, container, false) as LinearLayout
            // 按歌单名哈希轮换图标，保持视觉区分
            val iconCode = playlistIcons[pl.name.hashCode().mod(playlistIcons.size)]
            NokiaIcons.setIcon(itemView.findViewById(R.id.icon_playlist), iconCode)
            NokiaIcons.setIcon(itemView.findViewById(R.id.icon_playlist_arrow), NokiaIcons.ICON_CHEVRON_RIGHT)
            itemView.findViewById<TextView>(R.id.tv_playlist_name).text = pl.name
            itemView.findViewById<TextView>(R.id.tv_playlist_sub).text = "${pl.trackCount} 首歌曲"
            // 记住真实歌单 ID，点击时用
            itemView.tag = pl.id
            container.addView(itemView)
            minePlaylistRoots.add(itemView)

            if (i < listPlaylists.size - 1) {
                container.addView(makeDivider(6, 6))
            }
        }

        // 当前在「我的」tab 时刷新焦点列表（用户头部为第 0 项，保证方向键可滚回顶部）
        if (currentTab == TAB_MINE) {
            focusItems = mineFocusItems()
            focusIdx = focusIdx.coerceAtMost((focusItems.size - 1).coerceAtLeast(0))
            applyFocus()
        }
        // 动态创建的行补一次点阵字体+缩放（同 PlaylistDetailActivity）
        NokiaFontManager.applyToViewTree(container)
    }

    // 真实歌单条目轮换用的图标池
    private val playlistIcons = listOf(
        NokiaIcons.ICON_QUEUE_MUSIC,
        NokiaIcons.ICON_ALBUM,
        NokiaIcons.ICON_MUSIC_NOTE,
        NokiaIcons.ICON_FAVORITE,
        NokiaIcons.ICON_LIBRARY_MUSIC
    )

    // ══════════════════════════════════════════════════════════
    //  发现 Tab
    // ══════════════════════════════════════════════════════════
    private fun setupDiscoverTab() {
        val discRoot = findViewById<View>(R.id.content_discover)

        // 宫格图标（仅保留 私人FM 与 每日推荐）
        NokiaIcons.setIcon(discRoot.findViewById(R.id.icon_grid_fm), NokiaIcons.ICON_RADIO)
        NokiaIcons.setIcon(discRoot.findViewById(R.id.icon_grid_daily), NokiaIcons.ICON_TODAY)

        discoverGridRoots = listOf(
            discRoot.findViewById(R.id.grid_fm),
            discRoot.findViewById(R.id.grid_daily)
        )
        discoverListRoots = mutableListOf()

        loadDiscoverPlaylists()
    }

    private fun loadDiscoverPlaylists() {
        lifecycleScope.launch {
            try {
                val playlists = PlaylistApi.getRecommendPlaylists()
                if (isDestroyed || isFinishing) return@launch
                if (playlists.isNotEmpty()) {
                    discoverPlaylists = playlists
                    renderDiscoverPlaylists(playlists)
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "loadDiscoverPlaylists error", e)
            }
        }
    }

    private fun renderDiscoverPlaylists(playlists: List<PlaylistApi.PlaylistCard>) {
        val discRoot = findViewById<View>(R.id.content_discover) ?: return
        val container = discRoot.findViewById<LinearLayout>(R.id.ll_discover_playlist) ?: return
        container.removeAllViews()
        discoverListRoots.clear()

        val density = resources.displayMetrics.density

        playlists.forEachIndexed { i, card ->
            val itemView = LayoutInflater.from(this)
                .inflate(R.layout.item_discover_playlist, container, false) as LinearLayout
            val tagView = itemView.findViewById<TextView>(R.id.tv_discover_tag)
            val nameView = itemView.findViewById<TextView>(R.id.tv_discover_name)
            val subView = itemView.findViewById<TextView>(R.id.tv_discover_sub)

            // 标签颜色与文字
            val palette = tagPalettes[i % tagPalettes.size]
            val tagText = when {
                card.name.contains("雷达") -> "雷达"
                card.name.contains("精选") -> "精选"
                card.name.contains("流行") -> "流行"
                card.name.contains("民谣") -> "民谣"
                card.name.contains("摇滚") -> "摇滚"
                card.name.contains("国风") || card.name.contains("古风") -> "国风"
                card.name.contains("纯音") || card.name.contains("轻音乐") -> "纯音"
                else -> palette.second
            }
            tagView.text = tagText
            val tagBg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 3 * density
                setColor(Color.parseColor(palette.first))
            }
            tagView.background = tagBg

            nameView.text = card.name

            val playCountStr = formatPlayCount(card.playCount)
            val trackCountStr = if (card.trackCount > 0) "${card.trackCount}首" else ""
            val subText = when {
                card.copywriter.isNotBlank() && playCountStr.isNotBlank() ->
                    "$playCountStr · ${card.copywriter}"
                card.copywriter.isNotBlank() -> card.copywriter
                playCountStr.isNotBlank() && trackCountStr.isNotBlank() ->
                    "$playCountStr · $trackCountStr"
                playCountStr.isNotBlank() -> "$playCountStr 播放"
                trackCountStr.isNotBlank() -> trackCountStr
                else -> "今日推荐"
            }
            subView.text = subText

            itemView.tag = card.id
            container.addView(itemView)
            discoverListRoots.add(itemView)

            if (i < playlists.size - 1) {
                container.addView(makeDivider(6, 6))
            }
        }

        NokiaFontManager.applyToViewTree(container)

        if (currentTab == TAB_DISCOVER) {
            focusItems = discoverGridRoots + discoverListRoots
            if (focusIdx >= focusItems.size) {
                focusIdx = (focusItems.size - 1).coerceAtLeast(0)
            }
            applyFocus()
        }
    }

    private fun formatPlayCount(count: Long): String {
        return when {
            count >= 100_000_000 -> "${count / 100_000_000}.${(count % 100_000_000) / 10_000_000}亿"
            count >= 10_000 -> "${count / 10_000}万"
            count > 0 -> "$count"
            else -> ""
        }
    }

    // ══════════════════════════════════════════════════════════
    //  榜单 Tab
    // ══════════════════════════════════════════════════════════
    private fun setupChartTab() {
        val chartRoot = findViewById<View>(R.id.content_chart)

        data class ChartItem(
            val color: String,
            val iconCode: String,
            val name: String,
            val update: String,
            val topSong: String
        )

        val charts = listOf(
            ChartItem("#CC3333", NokiaIcons.ICON_LEADERBOARD, "飙升榜", "（刚刚更新）", "1. 无题 - 姜云升"),
            ChartItem("#336633", NokiaIcons.ICON_MUSIC_NOTE, "新歌榜", "（刚刚更新）", "1. Emily - 房东的猫"),
            ChartItem("#224488", NokiaIcons.ICON_STAR, "原创榜", "（每周四更新）", "1. 街头婚礼 - 周以希"),
            ChartItem("#884422", NokiaIcons.ICON_FAVORITE, "热歌榜", "（更新11首）", "1. 海屿你 - 马也")
        )

        chartItemRoots = mutableListOf()
        val container = chartRoot.findViewById<LinearLayout>(R.id.ll_chart_list)
        charts.forEachIndexed { i, chart ->
            val itemView = LayoutInflater.from(this)
                .inflate(R.layout.item_chart, container, false) as LinearLayout

            itemView.findViewById<LinearLayout>(R.id.ll_chart_thumb)
                .setBackgroundColor(Color.parseColor(chart.color))

            NokiaIcons.setIcon(itemView.findViewById(R.id.icon_chart_thumb), chart.iconCode)
            NokiaIcons.setIcon(itemView.findViewById(R.id.icon_chart_arrow), NokiaIcons.ICON_CHEVRON_RIGHT)
            itemView.findViewById<TextView>(R.id.tv_chart_name).text = chart.name
            itemView.findViewById<TextView>(R.id.tv_chart_update).text = chart.update
            itemView.findViewById<TextView>(R.id.tv_chart_song).text = chart.topSong

            container.addView(itemView)
            chartItemRoots.add(itemView)
            if (i < charts.size - 1) {
                container.addView(makeDivider(6, 6))
            }
        }
    }

    // ══════════════════════════════════════════════════════════
    //  搜索 Tab
    // ══════════════════════════════════════════════════════════
    private fun setupSearchTab() {
        val searchRoot = findViewById<View>(R.id.content_search)

        NokiaIcons.setIcon(searchRoot.findViewById(R.id.icon_search_field), NokiaIcons.ICON_SEARCH)
        searchRoot.findViewById<TextView>(R.id.tv_search_input).text = "周杰伦"

        // 关键词列表
        data class Keyword(val iconCode: String, val text: String)
        val keywords = listOf(
            Keyword(NokiaIcons.ICON_HISTORY, "顺风顺水 邹念慈"),
            Keyword(NokiaIcons.ICON_HISTORY, "仙剑奇侠传 经典配乐"),
            Keyword(NokiaIcons.ICON_STAR, "姜云升 无题"),
            Keyword(NokiaIcons.ICON_STAR, "房东的猫 Emily")
        )

        searchKeywordRoots = mutableListOf()
        val container = searchRoot.findViewById<LinearLayout>(R.id.ll_search_keywords)
        keywords.forEachIndexed { i, kw ->
            val itemView = LayoutInflater.from(this)
                .inflate(R.layout.item_search_keyword, container, false) as LinearLayout
            NokiaIcons.setIcon(itemView.findViewById(R.id.icon_keyword), kw.iconCode)
            itemView.findViewById<TextView>(R.id.tv_keyword).text = kw.text
            container.addView(itemView)
            searchKeywordRoots.add(itemView)
            if (i < keywords.size - 1) {
                container.addView(makeDivider(8, 8))
            }
        }
    }

    // ══════════════════════════════════════════════════════════
    //  Tab 切换
    // ══════════════════════════════════════════════════════════
    private fun switchTab(index: Int) {
        currentTab = index

        // Tab 栏样式
        tabViews.forEachIndexed { i, v ->
            val active = (i == index)
            tabIcons[i].setTextColor(if (active) colorWhite else colorSubtext)
            tabLabels[i].setTextColor(if (active) colorWhite else colorSubtext)
            tabLabels[i].setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)
            tabIndicators[i].visibility = if (active) View.VISIBLE else View.INVISIBLE
            v.background = if (active) activeTabGradient else null
        }

        // 内容区切换
        tabContents.forEachIndexed { i, v ->
            v.visibility = if (i == index) View.VISIBLE else View.GONE
        }

        // 标题栏 & 软键栏
        when (index) {
            TAB_MINE -> {
                setPageTitle("我的音乐库")
                setTitleIcon(NokiaIcons.ICON_LIBRARY_MUSIC)
                setSoftKeys("选项", "播放/查看", "正在播放")
                focusItems = mineFocusItems()
            }
            TAB_DISCOVER -> {
                setPageTitle("发现音乐")
                setTitleIcon(NokiaIcons.ICON_EXPLORE)
                setSoftKeys("选项", "进入", "正在播放")
                focusItems = discoverGridRoots + discoverListRoots
            }
            TAB_CHART -> {
                setPageTitle("云音乐排行榜")
                setTitleIcon(NokiaIcons.ICON_LEADERBOARD)
                setSoftKeys("选项", "查看榜单", "正在播放")
                focusItems = chartItemRoots
            }
            TAB_SEARCH -> {
                setPageTitle("歌曲搜索")
                setTitleIcon(NokiaIcons.ICON_SEARCH)
                setSoftKeys("清空", "搜索", "正在播放")
                focusItems = searchKeywordRoots
            }
        }
        focusIdx = 0
        applyFocus()
    }

    // ══════════════════════════════════════════════════════════
    //  焦点渲染
    // ══════════════════════════════════════════════════════════
    private fun applyFocus() {
        var focusedView: View? = null
        focusItems.forEachIndexed { i, layout ->
            if (i == focusIdx) {
                layout.setBackgroundResource(R.drawable.bg_focused_item)
                setChildTextColors(layout, true)
                layout.requestFocus()
                focusedView = layout
            } else {
                layout.setBackgroundColor(Color.TRANSPARENT)
                setChildTextColors(layout, false)
            }
        }
        val currentScroll = when (currentTab) {
            TAB_MINE -> findViewById<ScrollView>(R.id.content_mine)
            TAB_DISCOVER -> findViewById<ScrollView>(R.id.content_discover)
            TAB_CHART -> findViewById<ScrollView>(R.id.content_chart)
            else -> null
        }
        if (currentScroll != null && focusedView != null) {
            smoothScrollToVisible(currentScroll, focusedView)
        }
    }

    /**
     * 递归设置 LinearLayout 内的 TextView 文字颜色。
     * 焦点态：主文字白、副文字浅蓝白。
     * 非焦点态：主文字白、副文字灰。
     * 图标 TextView（使用 Material Icons 字体）颜色：焦点白、非焦点 accent。
     */
    private fun setChildTextColors(parent: LinearLayout, focused: Boolean) {
        val mainColor = colorWhite
        val subColor = if (focused) colorSubtextFocused else colorSubtext
        val iconColor = if (focused) colorWhite else colorAccent

        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            when (child) {
                is TextView -> {
                    // 判断是否是图标 TextView（通过 typeface）
                    val iconTf = NokiaIcons.getTypeface(this)
                    if (child.typeface == iconTf) {
                        child.setTextColor(iconColor)
                    } else {
                        child.setTextColor(mainColor)
                    }
                }
                is LinearLayout -> {
                    // 嵌套容器：递归
                    for (j in 0 until child.childCount) {
                        val grandChild = child.getChildAt(j)
                        if (grandChild is TextView) {
                            val iconTf = NokiaIcons.getTypeface(this)
                            if (grandChild.typeface == iconTf) {
                                grandChild.setTextColor(iconColor)
                            } else {
                                // 第 0 个是主文字，第 1 个是副文字
                                grandChild.setTextColor(if (j == 0) mainColor else subColor)
                            }
                        }
                    }
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════
    //  按键处理（NokiaBaseActivity 通过 onAction 分发）
    // ══════════════════════════════════════════════════════════
    override fun onAction(action: Int): Boolean {
        Log.d("MainActivity", "onAction=$action tab=$currentTab focusIdx=$focusIdx items=${focusItems.size} favId=$favPlaylistId")
        return when (action) {
            NokiaKeyAction.UP -> {
                if (focusItems.isNotEmpty() && focusIdx > 0) {
                    focusIdx--
                    applyFocus()
                }
                true
            }
            NokiaKeyAction.DOWN -> {
                if (focusItems.isNotEmpty() && focusIdx < focusItems.size - 1) {
                    focusIdx++
                    applyFocus()
                }
                true
            }
            NokiaKeyAction.LEFT -> {
                if (currentTab > 0) switchTab(currentTab - 1)
                true
            }
            NokiaKeyAction.RIGHT -> {
                if (currentTab < 3) switchTab(currentTab + 1)
                true
            }
            NokiaKeyAction.SELECT -> {
                when (currentTab) {
                    TAB_MINE -> onSelectMineItem()
                    TAB_DISCOVER -> onSelectDiscoverItem()
                    else -> { /* 其他 Tab 暂不处理 */ }
                }
                true
            }
            NokiaKeyAction.SOFT_LEFT -> {
                // 左软键按各 Tab 标签执行：搜索 Tab = 清空搜索词；其余 = 选项菜单
                if (currentTab == TAB_SEARCH) {
                    clearSearchInput()
                } else {
                    showOptionsDialog()
                }
                true
            }
            NokiaKeyAction.SOFT_RIGHT -> {
                // 右侧软键：进入正在播放界面
                val intent = Intent(this, MusicPlayerActivity::class.java)
                startActivity(intent)
                true
            }
            else -> super.onAction(action)
        }
    }

    /**
     * 搜索 Tab 左软键「清空」：清空搜索输入框并回占位提示。
     */
    private fun clearSearchInput() {
        val input = findViewById<TextView>(R.id.tv_search_input) ?: return
        if (input.text.isNullOrBlank()) {
            Toast.makeText(this, "搜索词已为空", Toast.LENGTH_SHORT).show()
            return
        }
        input.text = ""
        input.hint = "搜索歌曲 / 歌手"
        Log.d("MainActivity", "search input cleared")
        Toast.makeText(this, "已清空搜索词", Toast.LENGTH_SHORT).show()
    }

    // ══════════════════════════════════════════════════════════
    //  "我的" Tab 选中处理
    // ══════════════════════════════════════════════════════════
    /**
     * 「我的」Tab 焦点条目：用户信息头部（头像/昵称，第 0 项）+ 固定入口 + 歌单列表。
     * 头部必须纳入焦点体系：applyFocus() 的 requestFocus() 才能在按 UP 时
     * 把被顶出屏幕的头部滚回可视区（见 NOKIA_DEVELOPMENT_RULES.md 焦点滚动规范）。
     */
    private fun mineFocusItems(): List<LinearLayout> =
        listOf(mineUserHeader) + mineFixedRoots + minePlaylistRoots

    private fun onSelectMineItem() {
        val itemCount = 1 + mineFixedRoots.size + minePlaylistRoots.size
        if (focusIdx < 0 || focusIdx >= itemCount) return

        // ── 用户信息头部：刷新个人资料 ──
        if (focusIdx == 0) {
            loadUserProfile()
            return
        }

        // ── 自建/收藏歌单（真实数据，按 ID 打开）──
        val fixedCount = mineFixedRoots.size
        if (focusIdx >= 1 + fixedCount) {
            val view = minePlaylistRoots.getOrNull(focusIdx - 1 - fixedCount) ?: return
            val plId = view.tag as? Long ?: return
            val name = view.findViewById<TextView>(R.id.tv_playlist_name).text.toString()
            PlaylistDetailActivity.start(this, plId, name, NokiaIcons.ICON_QUEUE_MUSIC)
            return
        }

        // ── 固定入口 ──
        when (focusIdx - 1) {
            0 -> {
                // 我喜欢的音乐 → specialType=5 真实歌单；无则退回 mock
                val favId = favPlaylistId
                if (favId != null) {
                    PlaylistDetailActivity.start(this, favId, "我喜欢的音乐", NokiaIcons.ICON_FAVORITE, allFav = true)
                } else {
                    PlaylistDetailActivity.start(this, getString(R.string.mine_favorites), NokiaIcons.ICON_FAVORITE, getFavoriteSongs())
                }
            }
            1 -> {
                // 最近播放历史
                val recentDisplayItems = getHistorySongs()
                if (recentDisplayItems.isEmpty()) {
                    android.widget.Toast.makeText(this, getString(R.string.toast_history_empty), android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    PlaylistDetailActivity.start(
                        this,
                        getString(R.string.mine_history),
                        NokiaIcons.ICON_HISTORY,
                        recentDisplayItems,
                        isHistory = true
                    )
                }
            }
            2 -> {
                // 本地音乐
                LocalMusicActivity.start(this)
            }
        }
    }

    /**
     * 发现 Tab 选中处理：0=私人FM，1=每日推荐，2+=推荐歌单
     */
    private fun onSelectDiscoverItem() {
        when {
            focusIdx == 0 -> openPersonalFm()
            focusIdx == 1 -> openDailyRecommend()
            focusIdx >= 2 -> {
                val playlistIdx = focusIdx - 2
                val playlist = discoverPlaylists.getOrNull(playlistIdx) ?: return
                PlaylistDetailActivity.start(
                    this,
                    playlist.id,
                    playlist.name,
                    NokiaIcons.ICON_QUEUE_MUSIC
                )
            }
        }
    }

    /**
     * 私人 FM：拉取一批电台歌曲开始播放，队列耗尽时由
     * PlaybackService 自动续批（isPersonalFm 标记）。需要登录。
     */
    private fun openPersonalFm() {
        if (RetrofitClient.getCookie().isNullOrEmpty()) {
            Toast.makeText(this, "私人 FM 需要先登录网易云账号", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "正在开启私人 FM…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            try {
                val songs = PlaylistApi.getPersonalFm()
                Log.d("MainActivity", "personal fm loaded: ${songs.size} songs")
                if (isDestroyed || isFinishing) return@launch
                if (songs.isEmpty()) {
                    Toast.makeText(this@MainActivity, "暂无私人 FM 数据，请确认已登录", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                PlaybackStateManager.setPersonalFm(true)
                PlaybackStateManager.updatePlaylist(songs, 0)
                val playIntent = Intent(this@MainActivity, PlaybackService::class.java).apply {
                    action = PlaybackService.ACTION_PLAY_INDEX
                    putExtra(PlaybackService.EXTRA_INDEX, 0)
                }
                startService(playIntent)
                startActivity(Intent(this@MainActivity, MusicPlayerActivity::class.java))
            } catch (e: Exception) {
                Log.e("MainActivity", "openPersonalFm failed", e)
                if (!isDestroyed && !isFinishing) {
                    Toast.makeText(this@MainActivity, "私人 FM 获取失败：${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * 每日推荐：拉取网易云每日推荐歌曲，成功后用歌单详情页展示。
     * 需要登录（Cookie），未登录或拉取失败给出提示。
     */
    private fun openDailyRecommend() {
        if (RetrofitClient.getCookie().isNullOrEmpty()) {
            Toast.makeText(this, "每日推荐需要先登录网易云账号", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "正在获取每日推荐…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            try {
                val songs = PlaylistApi.getDailyRecommendSongs()
                Log.d("MainActivity", "daily recommend loaded: ${songs.size} songs")
                if (isDestroyed || isFinishing) return@launch
                if (songs.isEmpty()) {
                    Toast.makeText(this@MainActivity, "暂无每日推荐数据，请确认已登录", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val displayItems = ArrayList(songs.map { s ->
                    SongDisplayItem(
                        id = s.id,
                        title = s.name,
                        artist = s.artists?.joinToString("/") { it.name } ?: "未知艺术家",
                        isVip = (s.fee ?: 0) == 1,
                        noCopyright = s.noCopyright
                    )
                })
                PlaylistDetailActivity.start(
                    this@MainActivity,
                    getString(R.string.discover_daily_title),
                    NokiaIcons.ICON_TODAY,
                    displayItems
                )
            } catch (e: Exception) {
                Log.e("MainActivity", "openDailyRecommend failed", e)
                if (!isDestroyed && !isFinishing) {
                    Toast.makeText(this@MainActivity, "获取每日推荐失败：${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * 模拟数据：我喜欢的音乐
     */
    private fun getFavoriteSongs(): ArrayList<SongDisplayItem> {
        return arrayListOf(
            SongDisplayItem(1, "顺风顺水", "邹念慈 / 繁星合唱团", isFav = true),
            SongDisplayItem(2, "唤晚风", "Night Trigger", isFav = true),
            SongDisplayItem(3, "三笑", "王朝1982 / 朱旭", isFav = false),
            SongDisplayItem(4, "什么时候告白啊？", "Hanser", isFav = true),
            SongDisplayItem(5, "折柳记", "银临 / 施夏明", isFav = false),
            SongDisplayItem(6, "Sada Nannu", "Mickey J. Meyer", isFav = false),
            SongDisplayItem(7, "归园田居", "lbg / 逆水寒", isFav = true)
        )
    }

    private fun getHistorySongs(): ArrayList<SongDisplayItem> {
        val songs = LibraryManager.recentSongs.value
        return ArrayList(songs.map { song ->
            SongDisplayItem(
                id = song.id,
                title = song.name,
                artist = song.artistName,
                isFav = LibraryManager.isFavorite(song.id),
                isVip = song.fee == 1,
                noCopyright = song.noCopyright
            )
        })
    }

    private fun getPlaylistSongs(index: Int): ArrayList<SongDisplayItem> {
        return when (index) {
            0 -> getFavoriteSongs()
            1 -> arrayListOf(
                SongDisplayItem(201, "南无阿弥陀佛", "佛乐", isFav = true),
                SongDisplayItem(202, "心经", "王菲", isFav = true),
                SongDisplayItem(203, "大悲咒", "齐豫", isFav = false)
            )
            2 -> arrayListOf(
                SongDisplayItem(301, "蝶恋", "大宇", isFav = true),
                SongDisplayItem(302, "莫失莫忘", "大宇", isFav = true),
                SongDisplayItem(303, "御剑江湖", "大宇", isFav = false)
            )
            3 -> arrayListOf(
                SongDisplayItem(401, "牵丝戏", "银临 / Aki阿杰", isFav = true),
                SongDisplayItem(402, "锦鲤抄", "云の泣 / 银临", isFav = true),
                SongDisplayItem(403, "倾尽天下", "河图", isFav = false)
            )
            else -> arrayListOf()
        }
    }

    // ══════════════════════════════════════════════════════════
    //  工具
    // ══════════════════════════════════════════════════════════
    // ══════════════════════════════════════════════════════════
    //  用户信息头部（登录态展示）
    // ══════════════════════════════════════════════════════════

    /**
     * 加载当前登录用户信息并渲染到「我的」顶部。
     *
     * 策略（缓存优先）：
     *  1. 立即渲染本地缓存（断网也能显示）
     *  2. 有 cookie 时延迟 1.5s 后台刷新，成功后更新缓存与 UI
     *  3. 刷新失败（如无网）保持缓存不动，不误报「未登录」
     *  4. 仅当服务端明确返回 userId=0（cookie 过期）才清除缓存回未登录态
     */
    private fun loadUserProfile() {
        val iconAvatar = findViewById<TextView>(R.id.icon_user_avatar) ?: return
        val ivAvatar = findViewById<ImageView>(R.id.iv_user_avatar)
        val tvNickname = findViewById<TextView>(R.id.tv_user_nickname)
        val tvSub = findViewById<TextView>(R.id.tv_user_sub)
        val badgeVip = findViewById<View>(R.id.badge_vip)

        NokiaIcons.setIcon(iconAvatar, NokiaIcons.ICON_PERSON)

        // ① 先渲染本地缓存（若有），保证离线可用；头像直接读本地文件
        val cached = UserProfileCache.load(this)
        if (cached != null) {
            Log.d("MainActivity", "show cached profile: ${cached.nickname}")
            renderUserHeader(iconAvatar, ivAvatar, tvNickname, tvSub, badgeVip, cached)
            val cachedBmp = UserProfileCache.loadAvatar(this)
            if (cachedBmp != null) {
                iconAvatar.visibility = View.GONE
                ivAvatar?.visibility = View.VISIBLE
                ivAvatar?.setImageBitmap(cachedBmp)
            }
            // 歌单也先用缓存渲染，离线可看
            val cachedPls = UserProfileCache.loadPlaylists(this)
            if (cachedPls.isNotEmpty()) renderUserPlaylists(cachedPls)
        } else if (!CookieManager.hasCookie(this)) {
            renderUserHeader(iconAvatar, ivAvatar, tvNickname, tvSub, badgeVip, null)
            return
        }

        // ② 无 cookie：只展示缓存即可，不发起网络请求
        if (!CookieManager.hasCookie(this)) return

        // ③ 延迟后台刷新，不拖慢首屏
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (isDestroyed || isFinishing) return@postDelayed
            refreshUserProfileInBackground()
        }, 1500)
    }

    private fun refreshUserProfileInBackground() {
        val iconAvatar = findViewById<TextView>(R.id.icon_user_avatar) ?: return
        val ivAvatar = findViewById<ImageView>(R.id.iv_user_avatar)
        val tvNickname = findViewById<TextView>(R.id.tv_user_nickname)
        val tvSub = findViewById<TextView>(R.id.tv_user_sub)
        val badgeVip = findViewById<View>(R.id.badge_vip)

        lifecycleScope.launch {
            try {
                val profile = PlaylistApi.getUserProfile()
                if (profile.userId == 0L) {
                    // 服务端对失效 cookie 返回空 account/profile → 判定过期
                    Log.w("MainActivity", "cookie 已过期，自动清除")
                    CookieManager.clearCookie(this@MainActivity)
                    UserProfileCache.clear(this@MainActivity)
                    RetrofitClient.updateCookie(this@MainActivity, null)
                    renderUserHeader(iconAvatar, ivAvatar, tvNickname, tvSub, badgeVip, null)
                    return@launch
                }
                Log.d("MainActivity", "profile refreshed: ${profile.nickname} uid=${profile.userId} level=${profile.level}")
                UserProfileCache.save(this@MainActivity, profile)
                renderUserHeader(iconAvatar, ivAvatar, tvNickname, tvSub, badgeVip, profile)
                loadAvatarAsync(profile.avatarUrl, iconAvatar, ivAvatar)
                // 用户信息更新后顺带刷新真实歌单（内部会更新歌单缓存）
                loadUserPlaylists(profile.userId)
            } catch (e: Exception) {
                // 断网等异常：保留缓存与当前 UI，不误报未登录
                Log.w("MainActivity", "refresh profile failed (offline?), keep cache: ${e.message}")
            }
        }
    }

    /** 异步拉头像；成功后落盘缓存；失败保持现状（可能是已显示的本地缓存头像）。 */
    private fun loadAvatarAsync(url: String, iconAvatar: TextView, ivAvatar: ImageView?) {
        if (url.isBlank()) return
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) { downloadBitmap(url) }
            if (bmp != null && !isDestroyed && !isFinishing) {
                withContext(Dispatchers.IO) { UserProfileCache.saveAvatar(this@MainActivity, bmp) }
                iconAvatar.visibility = View.GONE
                ivAvatar?.visibility = View.VISIBLE
                ivAvatar?.setImageBitmap(bmp)
            }
        }
    }

    private fun renderUserHeader(
        iconAvatar: TextView,
        ivAvatar: ImageView?,
        tvNickname: TextView?,
        tvSub: TextView?,
        badgeVip: View?,
        profile: PlaylistApi.UserProfile?
    ) {
        if (profile == null) {
            // 未登录态
            iconAvatar.visibility = View.VISIBLE
            ivAvatar?.visibility = View.GONE
            tvNickname?.text = "未登录"
            tvSub?.text = "按左软键可登录网易云"
            badgeVip?.visibility = View.GONE
        } else {
            iconAvatar.visibility = View.GONE   // 有图时隐藏；无图稍后由 loadUserProfile 恢复
            tvNickname?.text = profile.nickname
            val vipStr = if (profile.vipType > 0) "黑胶VIP · " else ""
            tvSub?.text = "Lv.${profile.level} · ${vipStr}粉丝 ${profile.followeds} · 歌单 ${profile.playlistCount}"
            badgeVip?.visibility = if (profile.vipType > 0) View.VISIBLE else View.GONE
            if (profile.avatarUrl.isBlank()) {
                // 没有头像 URL：恢复人形图标占位
                iconAvatar.visibility = View.VISIBLE
                ivAvatar?.visibility = View.GONE
            }
        }
    }

    private fun downloadBitmap(urlStr: String): Bitmap? {
        return try {
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            conn.inputStream.use { android.graphics.BitmapFactory.decodeStream(it) }
        } catch (e: Exception) {
            Log.w("MainActivity", "avatar download failed: ${e.message}")
            null
        }
    }

    // ══════════════════════════════════════════════════════════
    //  左软键：主菜单（登录 / 退出登录）
    // ══════════════════════════════════════════════════════════
    private val loginLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val cookie = CookieManager.getCookie(this)
                Log.d("MainActivity", "login返回, hasCookie=${CookieManager.hasCookie(this)}, cookie长度=${cookie?.length ?: -1}")
                if (CookieManager.hasCookie(this)) {
                    Toast.makeText(this, "登录成功 ✓", Toast.LENGTH_SHORT).show()
                    loadUserProfile()   // 刷新用户信息头部
                    loadDiscoverPlaylists() // 刷新发现页推荐
                } else {
                    Toast.makeText(this, "登录失败：cookie 未保存", Toast.LENGTH_SHORT).show()
                }
            }
        }

    private fun showOptionsDialog() {
        val iconColor = Color.WHITE
        val iconSize = (18 * resources.displayMetrics.density).toInt()
        val title = if (CookieManager.hasCookie(this)) "账户" else "选项"
        val dialog = NokiaOptionsDialog(this, title)

        // 账户项（登录 / 退出登录）
        if (CookieManager.hasCookie(this)) {
            dialog.addItem(
                1, "退出登录",
                NokiaIcons.createDrawable(this, NokiaIcons.ICON_PERSON, iconSize, iconColor)
            )
        } else {
            dialog.addItem(
                1, "登录网易云",
                NokiaIcons.createDrawable(this, NokiaIcons.ICON_PERSON, iconSize, iconColor)
            )
        }

        // 公共项：Cookie 设置 / 后台播放 / 退出应用（所有 Tab 一致）
        // 注：「正在播放」由右软键直达，不进菜单避免重复
        dialog.addItem(
            2, "网易云 Cookie 设置",
            NokiaIcons.createDrawable(this, NokiaIcons.ICON_SETTINGS, iconSize, iconColor)
        )
        dialog.addItem(
            3, "后台播放",
            NokiaIcons.createDrawable(this, NokiaIcons.ICON_VOLUME_UP, iconSize, iconColor)
        )
        dialog.addItem(
            4, "退出应用",
            NokiaIcons.createDrawable(this, NokiaIcons.ICON_CLOSE, iconSize, iconColor)
        )

        dialog.setOnOptionSelectedListener { index, _ ->
            when (index) {
                0 -> {
                    if (CookieManager.hasCookie(this)) {
                        // 退出登录
                        CookieManager.clearCookie(this)
                        UserProfileCache.clear(this)
                        PlaylistSongCache.clearAll(this)
                        RetrofitClient.updateCookie(this, null)
                        Toast.makeText(this, "已退出登录", Toast.LENGTH_SHORT).show()
                        loadUserProfile()   // 回到未登录态
                        loadDiscoverPlaylists() // 回退到默认推荐歌单
                    } else {
                        // 发起 WebView 登录
                        loginLauncher.launch(
                            Intent(this, WebLoginActivity::class.java)
                        )
                    }
                }
                1 -> {
                    // 网易云 Cookie 设置
                    startActivity(Intent(this, CookieSettingsActivity::class.java))
                }
                2 -> {
                    // 后台播放：退到桌面但应用进程与 PlaybackService 存活，音乐继续播
                    Log.d("MainActivity", "后台播放：moveTaskToBack")
                    moveTaskToBack(true)
                }
                3 -> {
                    // 退出应用：停止播放服务并结束任务
                    Log.d("MainActivity", "退出应用：停止 PlaybackService 并结束任务")
                    stopService(Intent(this, PlaybackService::class.java))
                    finishAffinity()
                }
            }
        }
        dialog.show()
    }

    private fun makeDivider(leftDp: Int, rightDp: Int): View {
        return object : View(this) {
            private val paint = Paint().apply {
                color = colorDivider
                strokeWidth = 1f
                pathEffect = DashPathEffect(floatArrayOf(4f, 3f), 0f)
                style = Paint.Style.STROKE
            }
            override fun onDraw(canvas: Canvas) {
                val left = dp(leftDp).toFloat()
                val right = width - dp(rightDp).toFloat()
                val centerY = height / 2f
                canvas.drawLine(left, centerY, right, centerY, paint)
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(8)
            )
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density + 0.5f).toInt()
    }
}
