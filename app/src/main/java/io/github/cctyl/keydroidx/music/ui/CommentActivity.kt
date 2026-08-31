package io.github.cctyl.keydroidx.music.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import io.github.cctyl.keydroidx.music.R
import io.github.cctyl.keydroidx.music.cache.CommentCache
import io.github.cctyl.keydroidx.music.network.CommentApi
import io.github.cctyl.keydroidx.music.network.CommentApi.Comment
import io.github.cctyl.keydroidx.music.util.AvatarLoader
import io.github.cctyl.keydroidx.music.util.NLog as Log
import io.github.cctyl.nokia.keycore.model.NokiaKeyAction
import io.github.cctyl.nokia.keycore.ui.NokiaBaseActivity
import io.github.cctyl.nokia.keycore.ui.NokiaFontManager
import io.github.cctyl.nokia.keycore.ui.NokiaIcons
import io.github.cctyl.nokia.keycore.ui.dialog.NokiaConfirmDialog
import io.github.cctyl.nokia.keycore.ui.page.NokiaListFocusHelper
import kotlinx.coroutines.launch

/**
 * 歌曲评论列表页。
 *
 * 数据来自 [CommentApi]（网易云 `/api/v1/resource/comments/R_SO_4_{id}`）：
 *  - 首页一次请求同时拿到「热门评论」与「最新评论」首页 + 评论总数；
 *  - 向下移动到「加载更多」行（或按中软键）按 [PAGE_SIZE] 分页追加；
 *  - 确定键用 `NokiaConfirmDialog` 查看全文（列表内正文截断为 3 行）。
 *
 * 物理按键：
 *  UP/DOWN 移动光标（首尾循环）、SELECT 查看全文、左软键刷新、中软键加载更多、右软键返回。
 *
 * 焦点与滚动统一交给 [NokiaListFocusHelper]（循环导航 + 防出界平滑滚动），
 * 配色一律取自 [MusicTheme]，不硬编码颜色。
 */
class CommentActivity : NokiaBaseActivity() {

    companion object {
        private const val TAG = "CommentActivity"

        private const val EXTRA_SONG_ID = "song_id"
        private const val EXTRA_SONG_NAME = "song_name"
        private const val EXTRA_TOTAL = "total"
        private const val NO_TOTAL = -1

        /** 每页评论条数 */
        private const val PAGE_SIZE = 20

        /** 「加载更多」行的状态 */
        private const val ROW_LOADING = 0
        private const val ROW_MORE = 1
        private const val ROW_END = 2
        private const val ROW_FAILED = 3

        /**
         * 进入评论区。
         *
         * @param knownTotal 播放页已拉到的评论总数（可为空），用于顶部数字秒显，避免空一拍
         */
        fun start(context: Context, songId: Long, songName: String, knownTotal: Int? = null) {
            val intent = Intent(context, CommentActivity::class.java).apply {
                putExtra(EXTRA_SONG_ID, songId)
                putExtra(EXTRA_SONG_NAME, songName)
                putExtra(EXTRA_TOTAL, knownTotal ?: NO_TOTAL)
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    // ── 数据 ─────────────────────────────────────────────────
    private var songId = -1L
    private var songName = ""
    private var total = 0
    /** 最新评论已拉取的条数，作为下一次分页的 offset */
    private var offset = 0
    private var hasMore = false
    private var loading = false

    private val hotComments = mutableListOf<Comment>()
    private val comments = mutableListOf<Comment>()

    // ── 视图 ─────────────────────────────────────────────────
    private lateinit var scrollComment: ScrollView
    private lateinit var tvCommentTotal: TextView
    private lateinit var llHotHeader: LinearLayout
    private lateinit var llHotContainer: LinearLayout
    private lateinit var llNewHeader: LinearLayout
    private lateinit var llCommentContainer: LinearLayout
    private lateinit var llLoadMore: LinearLayout
    private lateinit var tvLoadMore: TextView

    // ── 焦点 ─────────────────────────────────────────────────
    private val hotItemViews = mutableListOf<View>()
    private val commentItemViews = mutableListOf<View>()
    private lateinit var focusHelper: NokiaListFocusHelper
    private val focusIdx: Int get() = if (::focusHelper.isInitialized) focusHelper.focusIndex else -1

    // ══════════════════════════════════════════════════════════
    //  NokiaBaseActivity 回调
    // ══════════════════════════════════════════════════════════
    override fun getContentLayoutRes(): Int = R.layout.activity_comment

    override fun onInitViews() {
        // XML 静态经典蓝配色 → 当前主题色
        findViewById<View?>(android.R.id.content)?.let { MusicTheme.applyToViewTree(it) }

        songId = intent.getLongExtra(EXTRA_SONG_ID, -1L)
        songName = intent.getStringExtra(EXTRA_SONG_NAME) ?: ""
        val presetTotal = intent.getIntExtra(EXTRA_TOTAL, NO_TOTAL)
        if (presetTotal != NO_TOTAL) total = presetTotal

        setPageTitle(getString(R.string.title_song_comment))
        setTitleIcon(MusicIcons.COMMENT)
        setStatusBarVisible(true)
        registerBatteryReceiver()
        setSoftKeys(
            getString(R.string.softkey_refresh),
            getString(R.string.softkey_more),
            getString(R.string.softkey_back)
        )

        scrollComment = findViewById(R.id.scroll_comment)
        tvCommentTotal = findViewById(R.id.tv_comment_total)
        llHotHeader = findViewById(R.id.ll_hot_header)
        llHotContainer = findViewById(R.id.ll_hot_container)
        llNewHeader = findViewById(R.id.ll_new_header)
        llCommentContainer = findViewById(R.id.ll_comment_container)
        llLoadMore = findViewById(R.id.ll_load_more)
        tvLoadMore = findViewById(R.id.tv_load_more)

        NokiaIcons.setIcon(findViewById(R.id.icon_comment_total), MusicIcons.COMMENT)

        focusHelper = NokiaListFocusHelper(this, scrollComment)
        focusHelper.setOnFocusChangedListener { oldIdx, _, newView ->
            // 基类 setFocusIndex 已铺好生态主题高亮，这里换成音乐 App 的主题焦点色，
            // 并同步刷新副文字颜色（与 PlaylistDetailActivity 一致的做法）
            focusHelper.getItem(oldIdx)?.let { applyItemStyle(it, false) }
            newView?.let { applyItemStyle(it, true) }
        }

        // 触屏点击「加载更多」行 = 确定键
        llLoadMore.setOnClickListener { loadMore() }

        if (songId <= 0L) {
            showFailed(getString(R.string.comment_load_failed))
            return
        }

        // 播放页已把评论总数透传过来，先秒显
        if (total > 0) updateTotalText()
        loadFirstPage()
    }

    // ══════════════════════════════════════════════════════════
    //  数据加载
    // ══════════════════════════════════════════════════════════

    private fun loadFirstPage() {
        loading = true
        offset = 0
        hasMore = false
        tvCommentTotal.text = getString(R.string.comment_loading)
        setLoadMoreRow(ROW_LOADING)
        lifecycleScope.launch {
            try {
                val page = CommentApi.getSongComments(songId, offset = 0, limit = PAGE_SIZE)
                if (isDestroyed || isFinishing) return@launch
                total = page.total
                CommentCache.putCount(songId, page.total)
                hotComments.clear()
                hotComments.addAll(page.hot)
                comments.clear()
                comments.addAll(page.comments)
                offset = comments.size
                hasMore = page.hasMore
                Log.d(TAG, "loaded songId=$songId total=$total hot=${page.hot.size} page=${page.comments.size} hasMore=$hasMore")
                renderAll()
            } catch (e: Exception) {
                Log.w(TAG, "load comments failed songId=$songId: ${e.message}")
                if (isDestroyed || isFinishing) return@launch
                showFailed(getString(R.string.comment_load_failed))
            } finally {
                loading = false
            }
        }
    }

    private fun loadMore() {
        if (loading || !hasMore || songId <= 0L) return
        loading = true
        setLoadMoreRow(ROW_LOADING)
        val from = offset
        lifecycleScope.launch {
            try {
                val page = CommentApi.getSongComments(songId, offset = from, limit = PAGE_SIZE)
                if (isDestroyed || isFinishing) return@launch
                if (page.comments.isEmpty()) {
                    hasMore = false
                } else {
                    val keepIdx = focusIdx
                    comments.addAll(page.comments)
                    offset += page.comments.size
                    hasMore = page.hasMore
                    if (page.total > total) {
                        total = page.total
                        CommentCache.putCount(songId, total)
                        updateTotalText()
                    }
                    appendCommentViews(page.comments)
                    rebuildFocusItems(keepIdx)
                }
                loading = false
                setLoadMoreRow(if (hasMore) ROW_MORE else ROW_END)
            } catch (e: Exception) {
                Log.w(TAG, "load more failed songId=$songId offset=$from: ${e.message}")
                if (isDestroyed || isFinishing) return@launch
                loading = false
                setLoadMoreRow(ROW_FAILED)
            }
        }
    }

    // ══════════════════════════════════════════════════════════
    //  渲染
    // ══════════════════════════════════════════════════════════

    private fun renderAll() {
        updateTotalText()

        // 热门评论（仅首页有）
        llHotContainer.removeAllViews()
        hotItemViews.clear()
        llHotHeader.visibility = if (hotComments.isEmpty()) View.GONE else View.VISIBLE
        hotComments.forEach { c ->
            val v = buildCommentView(c)
            llHotContainer.addView(v)
            hotItemViews.add(v)
        }

        // 最新评论
        llCommentContainer.removeAllViews()
        commentItemViews.clear()
        llNewHeader.visibility = if (comments.isEmpty()) View.GONE else View.VISIBLE
        comments.forEach { c ->
            val v = buildCommentView(c)
            llCommentContainer.addView(v)
            commentItemViews.add(v)
        }

        // 动态 inflate 的条目错过基类字体初始化，补一次点阵字体 + 缩放
        NokiaFontManager.applyToViewTree(llHotContainer)
        NokiaFontManager.applyToViewTree(llCommentContainer)

        if (hotComments.isEmpty() && comments.isEmpty()) {
            tvLoadMore.text = getString(R.string.comment_empty)
            llLoadMore.visibility = View.VISIBLE
        } else {
            setLoadMoreRow(if (hasMore) ROW_MORE else ROW_END)
        }
        rebuildFocusItems(0)
    }

    private fun appendCommentViews(newOnes: List<Comment>) {
        newOnes.forEach { c ->
            val v = buildCommentView(c)
            llCommentContainer.addView(v)
            commentItemViews.add(v)
        }
        llNewHeader.visibility = if (comments.isEmpty()) View.GONE else View.VISIBLE
        NokiaFontManager.applyToViewTree(llCommentContainer)
    }

    private fun buildCommentView(c: Comment): View {
        val v = LayoutInflater.from(this)
            .inflate(R.layout.item_comment, llCommentContainer, false)

        val iconAvatar = v.findViewById<TextView>(R.id.icon_comment_avatar)
        val ivAvatar = v.findViewById<ImageView>(R.id.iv_comment_avatar)
        val tvNickname = v.findViewById<TextView>(R.id.tv_comment_nickname)
        val tvTime = v.findViewById<TextView>(R.id.tv_comment_time)
        val tvContent = v.findViewById<TextView>(R.id.tv_comment_content)
        val iconLike = v.findViewById<TextView>(R.id.icon_comment_like)
        val tvLike = v.findViewById<TextView>(R.id.tv_comment_like)

        NokiaIcons.setIcon(iconAvatar, NokiaIcons.ICON_PERSON)
        NokiaIcons.setIcon(iconLike, MusicIcons.THUMB_UP)

        tvNickname.text = c.nickname
        tvTime.text = formatRelativeTime(c.timeMs)
        tvContent.text = c.content
        tvLike.text = CommentApi.formatCount(c.likedCount)

        // 触屏点击 = 确定键：查看全文
        v.setOnClickListener { showFullComment(c) }

        if (c.avatarUrl.isNotBlank()) {
            loadAvatarInto(c.avatarUrl, iconAvatar, ivAvatar)
        }
        return v
    }

    private fun loadAvatarInto(url: String, iconAvatar: TextView, ivAvatar: ImageView) {
        lifecycleScope.launch {
            val bmp = AvatarLoader.load(url) ?: return@launch
            if (isDestroyed || isFinishing) return@launch
            iconAvatar.visibility = View.GONE
            ivAvatar.visibility = View.VISIBLE
            ivAvatar.setImageBitmap(bmp)
        }
    }

    private fun updateTotalText() {
        tvCommentTotal.text = getString(R.string.comment_total, CommentApi.formatCount(total))
    }

    private fun setLoadMoreRow(state: Int) {
        tvLoadMore.text = when (state) {
            ROW_LOADING -> getString(R.string.comment_loading)
            ROW_END -> getString(R.string.comment_no_more)
            ROW_FAILED -> getString(R.string.comment_load_failed)
            else -> getString(R.string.comment_load_more)
        }
        llLoadMore.visibility = View.VISIBLE
    }

    private fun showFailed(msg: String) {
        tvCommentTotal.text = msg
        llHotHeader.visibility = View.GONE
        llNewHeader.visibility = View.GONE
        tvLoadMore.text = msg
        llLoadMore.visibility = View.VISIBLE
        rebuildFocusItems(0)
    }

    // ══════════════════════════════════════════════════════════
    //  焦点
    // ══════════════════════════════════════════════════════════

    private fun rebuildFocusItems(preserveIdx: Int) {
        val views = ArrayList<View>(hotItemViews.size + commentItemViews.size + 1)
        views.addAll(hotItemViews)
        views.addAll(commentItemViews)
        if (llLoadMore.visibility == View.VISIBLE) views.add(llLoadMore)
        focusHelper.setItems(views)
        focusHelper.setFocusIndex(preserveIdx.coerceIn(0, (views.size - 1).coerceAtLeast(0)), true)
    }

    /** 焦点态：主题高亮背景 + 副文字提亮；非焦点：透明背景 + 常规副文字。 */
    private fun applyItemStyle(view: View, focused: Boolean) {
        view.background = if (focused) MusicTheme.createFocusDrawable(this, 4f) else null
        val subColor = if (focused) MusicTheme.SUBTEXT_FOCUSED else MusicTheme.current(this).subtext
        view.findViewById<TextView>(R.id.tv_comment_time)?.setTextColor(subColor)
        view.findViewById<TextView>(R.id.tv_comment_content)?.setTextColor(subColor)
        view.findViewById<TextView>(R.id.tv_comment_like)?.setTextColor(subColor)
        view.findViewById<TextView>(R.id.icon_comment_like)?.setTextColor(subColor)
    }

    /** 焦点索引 → 评论对象；命中「加载更多」行返回 null。 */
    private fun commentAt(index: Int): Comment? {
        if (index < 0) return null
        if (index < hotComments.size) return hotComments[index]
        return comments.getOrNull(index - hotComments.size)
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
                val atLoadMoreRow = llLoadMore.visibility == View.VISIBLE &&
                    focusIdx >= 0 && focusIdx == focusHelper.itemCount - 1
                if (atLoadMoreRow && hasMore) {
                    // 光标停在「加载更多」行且有更多：直接加载，不循环回顶
                    loadMore()
                } else {
                    focusHelper.onDirection(action)
                }
                true
            }
            NokiaKeyAction.SELECT -> {
                val c = commentAt(focusIdx)
                if (c != null) showFullComment(c) else if (hasMore) loadMore()
                true
            }
            NokiaKeyAction.SOFT_LEFT -> {
                if (!loading) loadFirstPage()
                true
            }
            NokiaKeyAction.SOFT_RIGHT -> {
                finish()
                true
            }
            else -> super.onAction(action)
        }
    }

    /** 确定键：列表内正文截断为 3 行，全文在复古确认弹窗里看。 */
    private fun showFullComment(c: Comment) {
        NokiaConfirmDialog(this, c.nickname, c.content)
            .setPositiveButton(getString(R.string.dialog_confirm)) { }
            .show()
    }

    // ══════════════════════════════════════════════════════════
    //  工具
    // ══════════════════════════════════════════════════════════

    private fun formatRelativeTime(timeMs: Long): String {
        if (timeMs <= 0L) return ""
        val diff = System.currentTimeMillis() - timeMs
        if (diff < 60_000L) return getString(R.string.comment_time_just_now)
        val minutes = diff / 60_000L
        return when {
            minutes < 60 -> getString(R.string.comment_time_minutes, minutes.toInt())
            minutes < 60 * 24 -> getString(R.string.comment_time_hours, (minutes / 60).toInt())
            else -> getString(R.string.comment_time_days, (minutes / (60 * 24)).toInt())
        }
    }
}
