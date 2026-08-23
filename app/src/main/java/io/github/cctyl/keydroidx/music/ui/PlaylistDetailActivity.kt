package io.github.cctyl.keydroidx.music.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import io.github.cctyl.keydroidx.music.R
import io.github.cctyl.nokia.keycore.model.NokiaKeyAction
import io.github.cctyl.nokia.keycore.ui.NokiaBaseActivity
import io.github.cctyl.nokia.keycore.ui.NokiaIcons
import java.io.Serializable

/**
 * 歌单详情页（歌曲列表）
 *
 * 从 MainActivity 或其他入口进入，显示一个歌单的全部歌曲。
 * 支持物理按键导航：UP/DOWN 移动焦点，SELECT 播放，
 * SOFT_LEFT 呼出选项菜单，SOFT_RIGHT 返回列表。
 */
class PlaylistDetailActivity : NokiaBaseActivity() {

    companion object {
        private const val EXTRA_PLAYLIST_NAME = "playlist_name"
        private const val EXTRA_PLAYLIST_ICON = "playlist_icon"
        private const val EXTRA_SONGS = "songs"

        /**
         * 启动歌单详情页
         * @param context 上下文
         * @param playlistName 歌单名称
         * @param playlistIcon 歌单图标（Material Icons 编码）
         * @param songs 歌曲列表
         */
        fun start(
            context: Context,
            playlistName: String,
            playlistIcon: String,
            songs: ArrayList<SongDisplayItem>
        ) {
            val intent = Intent(context, PlaylistDetailActivity::class.java).apply {
                putExtra(EXTRA_PLAYLIST_NAME, playlistName)
                putExtra(EXTRA_PLAYLIST_ICON, playlistIcon)
                putExtra(EXTRA_SONGS, songs)
            }
            context.startActivity(intent)
        }
    }

    // ── 数据 ──
    private var playlistName: String = ""
    private var playlistIcon: String = NokiaIcons.ICON_QUEUE_MUSIC
    private lateinit var songs: List<SongDisplayItem>

    // ── UI 控件 ──
    private lateinit var llPlayAll: LinearLayout
    private lateinit var tvPlayAllSub: TextView
    private lateinit var badgePlayMode: TextView
    private lateinit var llSongContainer: LinearLayout

    // ── 焦点状态 ──
    private val songItemViews = mutableListOf<LinearLayout>()
    private var focusIdx = 0

    // ── 颜色缓存 ──
    private val colorWhite by lazy { Color.WHITE }
    private val colorSubtext by lazy { Color.parseColor("#B0B0B0") }
    private val colorSubtextFocused by lazy { Color.parseColor("#E0F2FE") }
    private val colorAccent by lazy { Color.parseColor("#38BDF8") }
    private val colorFavRed by lazy { Color.parseColor("#EF4444") }
    private val colorFavGray by lazy { Color.parseColor("#64748B") }
    private val colorDivider by lazy { Color.parseColor("#2D426B") }

    // ══════════════════════════════════════════════════════════
    //  NokiaBaseActivity 回调
    // ══════════════════════════════════════════════════════════
    override fun getContentLayoutRes(): Int = R.layout.activity_playlist_detail

    override fun onInitViews() {
        // 读取 Intent 数据
        playlistName = intent.getStringExtra(EXTRA_PLAYLIST_NAME) ?: "歌单"
        playlistIcon = intent.getStringExtra(EXTRA_PLAYLIST_ICON) ?: NokiaIcons.ICON_QUEUE_MUSIC
        @Suppress("UNCHECKED_CAST")
        songs = (intent.getSerializableExtra(EXTRA_SONGS) as? ArrayList<SongDisplayItem>) ?: emptyList()

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

        // 设置播放全部行
        NokiaIcons.setIcon(findViewById(R.id.icon_play_all), NokiaIcons.ICON_PLAY_CIRCLE)
        tvPlayAllSub.text = "共 ${songs.size} 首歌曲"

        // 填充歌曲列表
        populateSongs()

        // 初始焦点
        buildFocusList()
        focusIdx = 0
        applyFocus()
    }

    // ══════════════════════════════════════════════════════════
    //  填充歌曲列表
    // ══════════════════════════════════════════════════════════
    private fun populateSongs() {
        llSongContainer.removeAllViews()
        songItemViews.clear()

        songs.forEachIndexed { idx, song ->
            val itemView = LayoutInflater.from(this)
                .inflate(R.layout.item_song, llSongContainer, false) as LinearLayout

            val tvIndex = itemView.findViewById<TextView>(R.id.tv_song_index)
            val tvTitle = itemView.findViewById<TextView>(R.id.tv_song_title)
            val tvArtist = itemView.findViewById<TextView>(R.id.tv_song_artist)
            val iconPlaying = itemView.findViewById<TextView>(R.id.icon_playing)
            val iconFav = itemView.findViewById<TextView>(R.id.icon_fav)

            // 序号
            tvIndex.text = (idx + 1).toString()

            // 歌曲名
            tvTitle.text = song.title

            // 播放指示器（当前歌曲预留，后续由播放状态管理）
            iconPlaying.visibility = View.GONE
            NokiaIcons.setIcon(iconPlaying, NokiaIcons.ICON_VOLUME_UP)

            // 歌手
            tvArtist.text = song.artist

            // 收藏心
            if (song.isFav) {
                NokiaIcons.setIcon(iconFav, NokiaIcons.ICON_FAVORITE)
                iconFav.setTextColor(colorFavRed)
            } else {
                NokiaIcons.setIcon(iconFav, NokiaIcons.ICON_FAVORITE_BORDER)
                iconFav.setTextColor(colorFavGray)
            }

            // 分隔线
            val divider = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1
                ).also { lp ->
                    lp.leftMargin = dp(8)
                    lp.rightMargin = dp(8)
                }
                setBackgroundColor(colorDivider)
            }

            llSongContainer.addView(itemView)
            songItemViews.add(itemView)

            if (idx < songs.size - 1) {
                llSongContainer.addView(divider)
            }
        }
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
                    view.setBackgroundResource(R.drawable.bg_focused_item)
                    setChildTextColors(view, true)
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
        val maxIdx = songs.size  // 0 = 播放全部, 1..N = 歌曲

        return when (action) {
            NokiaKeyAction.UP -> {
                if (focusIdx > 0) {
                    focusIdx--
                    applyFocus()
                }
                true
            }
            NokiaKeyAction.DOWN -> {
                if (focusIdx < maxIdx) {
                    focusIdx++
                    applyFocus()
                }
                true
            }
            NokiaKeyAction.SELECT -> {
                // 选中播放
                if (focusIdx == 0) {
                    // 播放全部
                    if (songs.isNotEmpty()) {
                        playSong(songs[0])
                    }
                } else {
                    val songIdx = focusIdx - 1
                    if (songIdx in songs.indices) {
                        playSong(songs[songIdx])
                    }
                }
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

    private fun playSong(song: SongDisplayItem) {
        // TODO: 启动 MusicPlayerActivity 并播放选中的歌曲
        // 传递给 MusicPlayerActivity 或 PlaybackService
        // 暂时用 Toast 提示
        android.widget.Toast.makeText(this, "播放: ${song.title}", android.widget.Toast.LENGTH_SHORT).show()
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
    val isFav: Boolean = false
) : Serializable