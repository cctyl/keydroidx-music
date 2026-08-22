package io.github.cctyl.keydroidx.music.ui

import android.graphics.Color
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import io.github.cctyl.keydroidx.music.R
import io.github.cctyl.nokia.keycore.model.NokiaKeyAction
import io.github.cctyl.nokia.keycore.ui.NokiaBaseActivity
import io.github.cctyl.nokia.keycore.ui.NokiaIcons

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
    private lateinit var mineFixedRoots: List<LinearLayout>
    private lateinit var minePlaylistRoots: MutableList<LinearLayout>

    // ── 发现 Tab ──
    private lateinit var discoverGridRoots: List<LinearLayout>
    private lateinit var discoverListRoots: MutableList<LinearLayout>

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
    private val colorTabActiveBg by lazy { Color.parseColor("#330055AA") }
    private val colorDivider by lazy { Color.parseColor("#2D426B") }
    private val colorSectionBg by lazy { Color.parseColor("#4D000000") }

    // ══════════════════════════════════════════════════════════
    //  NokiaBaseActivity 回调
    // ══════════════════════════════════════════════════════════
    override fun getContentLayoutRes(): Int = R.layout.activity_music_main

    override fun onInitViews() {
        setupTabBar()
        setupMineTab()
        setupDiscoverTab()
        setupChartTab()
        setupSearchTab()
        switchTab(TAB_MINE)
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
        mineRoot.findViewById<TextView>(R.id.tv_favorites_sub).text = "496 首歌曲 · 云端已同步"
        mineRoot.findViewById<TextView>(R.id.badge_favorites).text = "496"
        mineRoot.findViewById<TextView>(R.id.tv_history_sub).text = "100 首播放记录"
        mineRoot.findViewById<TextView>(R.id.badge_history).text = "100"

        // Section Header
        mineRoot.findViewById<TextView>(R.id.tv_section_playlist).text = "自建与收藏歌单 (4)"

        // 歌单列表
        val playlists = listOf(
            Triple(NokiaIcons.ICON_FAVORITE, "我喜欢的音乐", "496 首歌曲"),
            Triple(NokiaIcons.ICON_QUEUE_MUSIC, "佛经禅乐男声清念", "6 首歌曲"),
            Triple(NokiaIcons.ICON_ALBUM, "仙剑经典原声大碟", "58 首歌曲"),
            Triple(NokiaIcons.ICON_MUSIC_NOTE, "古风国风精选", "72 首歌曲")
        )
        minePlaylistRoots = mutableListOf()
        val container = mineRoot.findViewById<LinearLayout>(R.id.ll_playlist_container)
        playlists.forEachIndexed { i, (iconCode, name, sub) ->
            val itemView = LayoutInflater.from(this)
                .inflate(R.layout.item_playlist, container, false) as LinearLayout
            NokiaIcons.setIcon(itemView.findViewById(R.id.icon_playlist), iconCode)
            NokiaIcons.setIcon(itemView.findViewById(R.id.icon_playlist_arrow), NokiaIcons.ICON_CHEVRON_RIGHT)
            itemView.findViewById<TextView>(R.id.tv_playlist_name).text = name
            itemView.findViewById<TextView>(R.id.tv_playlist_sub).text = sub
            container.addView(itemView)
            minePlaylistRoots.add(itemView)

            if (i < playlists.size - 1) {
                container.addView(makeDivider(6, 6))
            }
        }
    }

    // ══════════════════════════════════════════════════════════
    //  发现 Tab
    // ══════════════════════════════════════════════════════════
    private fun setupDiscoverTab() {
        val discRoot = findViewById<View>(R.id.content_discover)

        // 宫格图标
        NokiaIcons.setIcon(discRoot.findViewById(R.id.icon_grid_fm), NokiaIcons.ICON_RADIO)
        NokiaIcons.setIcon(discRoot.findViewById(R.id.icon_grid_daily), NokiaIcons.ICON_TODAY)
        NokiaIcons.setIcon(discRoot.findViewById(R.id.icon_grid_playlist), NokiaIcons.ICON_QUEUE_MUSIC)
        NokiaIcons.setIcon(discRoot.findViewById(R.id.icon_grid_chart), NokiaIcons.ICON_LEADERBOARD)

        discoverGridRoots = listOf(
            discRoot.findViewById(R.id.grid_fm),
            discRoot.findViewById(R.id.grid_daily),
            discRoot.findViewById(R.id.grid_playlist),
            discRoot.findViewById(R.id.grid_chart)
        )

        // 今日推荐歌单
        val todayPlaylists = listOf(
            Triple("#1A3A6B", "纯蓝", Pair("2026洛天依纯蓝幻乐", "演唱会官方精选 · 1.4万播")),
            Triple("#3A2A1A", "古韵", Pair("国风竹笛与古筝禅意精选", "传统器乐名作 · 25万播"))
        )
        discoverListRoots = mutableListOf()
        val container = discRoot.findViewById<LinearLayout>(R.id.ll_discover_playlist)
        todayPlaylists.forEachIndexed { i, (color, tag, info) ->
            val itemView = LayoutInflater.from(this)
                .inflate(R.layout.item_discover_playlist, container, false) as LinearLayout
            val tagView = itemView.findViewById<TextView>(R.id.tv_discover_tag)
            tagView.text = tag
            tagView.setBackgroundColor(Color.parseColor(color))
            itemView.findViewById<TextView>(R.id.tv_discover_name).text = info.first
            itemView.findViewById<TextView>(R.id.tv_discover_sub).text = info.second
            container.addView(itemView)
            discoverListRoots.add(itemView)
            if (i < todayPlaylists.size - 1) {
                container.addView(makeDivider(6, 6))
            }
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
            v.setBackgroundColor(if (active) colorTabActiveBg else Color.TRANSPARENT)
        }

        // 内容区切换
        tabContents.forEachIndexed { i, v ->
            v.visibility = if (i == index) View.VISIBLE else View.GONE
        }

        // 标题栏 & 软键栏
        when (index) {
            TAB_MINE -> {
                setPageTitle("我的音乐库")
                setSoftKeys("选项", "播放/查看", "正在播放")
                focusItems = mineFixedRoots + minePlaylistRoots
            }
            TAB_DISCOVER -> {
                setPageTitle("发现音乐")
                setSoftKeys("连项", "进入", "返回")
                focusItems = discoverGridRoots + discoverListRoots
            }
            TAB_CHART -> {
                setPageTitle("云音乐排行榜")
                setSoftKeys("连项", "查看榜单", "返回")
                focusItems = chartItemRoots
            }
            TAB_SEARCH -> {
                setPageTitle("歌曲搜索")
                setSoftKeys("清空", "搜索", "返回")
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
        focusItems.forEachIndexed { i, layout ->
            if (i == focusIdx) {
                layout.setBackgroundResource(R.drawable.bg_focused_item)
                setChildTextColors(layout, true)
            } else {
                layout.setBackgroundColor(Color.TRANSPARENT)
                setChildTextColors(layout, false)
            }
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
                // TODO: 进入选中项详情
                true
            }
            NokiaKeyAction.SOFT_LEFT -> {
                // TODO: 呼出 NokiaOptionsDialog
                true
            }
            NokiaKeyAction.SOFT_RIGHT -> {
                finish()
                true
            }
            else -> super.onAction(action)
        }
    }

    // ══════════════════════════════════════════════════════════
    //  工具
    // ══════════════════════════════════════════════════════════
    private fun makeDivider(leftDp: Int, rightDp: Int): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            ).also { lp ->
                lp.leftMargin = dp(leftDp)
                lp.rightMargin = dp(rightDp)
            }
            setBackgroundColor(colorDivider)
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density + 0.5f).toInt()
    }
}
