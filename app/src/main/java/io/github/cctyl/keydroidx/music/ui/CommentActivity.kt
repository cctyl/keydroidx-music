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
import io.github.cctyl.keydroidx.music.auth.CookieManager
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
 * 物理按键（列表层）：
 *  UP/DOWN 移动光标（**非循环**，到头即停）、SELECT 查看全文、
 *  左软键「评论」进入全屏编辑页、中软键加载更多、右软键返回。
 *
 * 编辑层（[CommentEditorFragment]）叠加在列表层之上，此时按键全部交给它：
 *  左软键菜单 = 发送评论 / 退出编辑，中软键 = 发送，右软键 = 返回列表。
 * 出栈后本页自动恢复列表的标题、软键与光标。
 *
 * 滚动策略（本页刻意与其它列表页不同，通过 [NokiaListFocusHelper.setCyclic] 关闭循环，
 * 不改动 SDK / common 组件）：
 *  - 顶部按上键停住，不跳到末尾；
 *  - 底部按下键停住，不回弹到顶部 —— 若还有评论则继续加载下一页；
 *  - 光标接近底部时静默预取下一页，走到底部时内容通常已就绪，滚动不中断。
 *
 * 焦点与滚动统一交给 [NokiaListFocusHelper]（防出界平滑滚动），
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

        /**
         * 预加载提前量：光标进入末尾这么多行以内时，静默预取下一页。
         *
         * 让用户在「加载更多」行按下键时内容往往已就绪，滚动不会被打断。
         * 新评论是追加在列表尾部，预取不会改变既有条目索引，因此光标不会跳位。
         */
        private const val PRELOAD_AHEAD = 3

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

    // ── 评论编辑层 ───────────────────────────────────────────
    /** 非 null 且已挂载时表示当前处于编辑模式，按键全部转发给它 */
    private var editorFragment: CommentEditorFragment? = null
    /** 首页是否加载失败（失败时「加载更多」行变成重试入口，因为左软键已让位给「评论」） */
    private var loadFailed = false

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
            getString(R.string.softkey_comment),
            getString(R.string.softkey_more),
            getString(R.string.softkey_back)
        )

        // 编辑页出栈后要恢复列表的标题/软键/光标：列表不是 Fragment，
        // 骨架的 refreshPageBar() 只会跟随 NokiaPage，故需自行订阅返回栈变化
        supportFragmentManager.addOnBackStackChangedListener {
            if (supportFragmentManager.backStackEntryCount == 0) {
                editorFragment = null
                restoreListBar()
            }
        }

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
        // 关闭首尾循环：本页是「无限追加」的长列表，循环滚动会把用户从底部弹回顶部、
        // 从顶部弹到底部，与分页浏览方向完全冲突。到底 / 到头一律停住。
        focusHelper.setCyclic(false)
        focusHelper.setOnFocusChangedListener { oldIdx, _, newView ->
            // 基类 setFocusIndex 已铺好生态主题高亮，这里换成音乐 App 的主题焦点色，
            // 并同步刷新副文字颜色（与 PlaylistDetailActivity 一致的做法）
            focusHelper.getItem(oldIdx)?.let { applyItemStyle(it, false) }
            newView?.let { applyItemStyle(it, true) }
        }

        // 触屏点击「加载更多」行 = 确定键；首页失败时该行兼作「重试」入口
        llLoadMore.setOnClickListener {
            if (loadFailed) loadFirstPage() else loadMore()
        }

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
                loadFailed = false
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
        // 左软键已让位给「评论」，刷新入口改挂在「加载更多」行上（点按或选中确定键）
        loadFailed = true
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

    /**
     * 光标接近底部时静默预取下一页。
     *
     * 新评论追加在列表尾部，既有条目索引不变，因此预取后光标仍停在原条目上，不会跳位；
     * 加载中的状态由 [loading] 与 `loadMore()` 自身守卫，不会重复触发。
     */
    private fun maybePreload() {
        if (loading || !hasMore || songId <= 0L) return
        val count = focusHelper.itemCount
        if (count <= 0 || focusIdx < 0) return
        if (focusIdx >= count - 1 - PRELOAD_AHEAD) loadMore()
    }

    // ══════════════════════════════════════════════════════════
    //  按键处理
    // ══════════════════════════════════════════════════════════
    override fun onAction(action: Int): Boolean {
        // 编辑层置顶时全权接管：软键由 Fragment 消费，方向键它返回 false，
        // 事件便继续透传给 EditText 用于移动光标
        val editor = editorFragment
        if (editor != null && editor.isAdded && !editor.isHidden) {
            return super.onAction(action)
        }

        return when (action) {
            NokiaKeyAction.UP -> {
                // 非循环（setCyclic(false)）：已在首项时按上键停住，不会跳到末尾
                focusHelper.onDirection(action)
                true
            }
            NokiaKeyAction.DOWN -> {
                val count = focusHelper.itemCount
                when {
                    count == 0 || focusIdx < 0 -> focusHelper.onDirection(action)
                    // 已停在最后一行（「加载更多」）：还有评论就继续加载，失败过则重试，
                    // 否则原地停住 —— 绝不循环回顶部
                    focusIdx >= count - 1 -> when {
                        hasMore -> loadMore()
                        loadFailed -> loadFirstPage()
                    }
                    else -> {
                        focusHelper.onDirection(action)
                        // 光标接近底部时静默预取下一页
                        maybePreload()
                    }
                }
                true
            }
            NokiaKeyAction.SELECT -> {
                val c = commentAt(focusIdx)
                when {
                    c != null -> showFullComment(c)
                    hasMore -> loadMore()
                    loadFailed -> loadFirstPage()
                }
                true
            }
            NokiaKeyAction.SOFT_LEFT -> {
                openCommentEditor()
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
    //  发表评论（左软键「评论」→ 全屏编辑页）
    // ══════════════════════════════════════════════════════════

    /** 左软键：叠加全屏编辑页。未登录时先用复古弹窗说明原因，避免白走一趟。 */
    private fun openCommentEditor() {
        if (editorFragment?.isAdded == true) return
        if (songId <= 0L) return

        if (!CookieManager.hasCookie(this)) {
            NokiaConfirmDialog(
                this,
                getString(R.string.comment_need_login_title),
                getString(R.string.comment_need_login_msg)
            )
                .setPositiveButton(getString(R.string.dialog_confirm)) { }
                .show()
            return
        }

        val editor = CommentEditorFragment.newInstance(songName).apply {
            onSend = { text -> submitComment(text) }
        }
        editorFragment = editor
        // add（而非 replace）：评论列表是直接 inflate 的普通 View，不受
        // FragmentManager 管辖，编辑页不透明地盖在上面，出栈后列表自动显现
        supportFragmentManager.beginTransaction()
            .add(R.id.comment_root, editor)
            .addToBackStack(TAG)
            .commit()
    }

    /**
     * 提交评论。
     *
     * 刻意用 Activity 的 `lifecycleScope` 而不是编辑页的：编辑页在回调后立刻出栈销毁，
     * 协程挂在这里才跑得完、才能刷新列表。
     */
    private fun submitComment(text: String) {
        lifecycleScope.launch {
            // 区分「服务端有明确原因」与「网络/未知错误」：前者原样展示服务端文案，
            // 后者才用通用提示，便于判断是限频、歌曲禁评还是网络问题
            var serverReason: String? = null
            val ok = try {
                CommentApi.sendComment(songId, text)
                true
            } catch (e: CommentApi.SendException) {
                Log.w(TAG, "send comment rejected songId=$songId code=${e.code} msg=${e.serverMessage}")
                serverReason = e.serverMessage
                false
            } catch (e: Exception) {
                Log.w(TAG, "send comment failed songId=$songId: ${e.message}")
                false
            }
            if (isDestroyed || isFinishing) return@launch

            if (!ok) {
                NokiaConfirmDialog(
                    this@CommentActivity,
                    getString(R.string.comment_send_failed_title),
                    serverReason?.let { getString(R.string.comment_send_rejected, it) }
                        ?: getString(R.string.comment_send_failed_msg)
                )
                    .setPositiveButton(getString(R.string.dialog_confirm)) { }
                    .show()
                updateTotalText()
                return@launch
            }

            // 新评论落在「最新评论」首位，重拉首页即可看到（完成后文案自动恢复为总数）
            loadFirstPage()
            tvCommentTotal.text = getString(R.string.comment_sent)
        }
    }

    /**
     * 编辑页出栈后恢复列表层的标题、软键与光标。
     *
     * 焦点必须主动拿回来：编辑期间焦点在 EditText 上，出栈后若不重新请求焦点，
     * 第一次方向键会被系统拿去「退出触摸模式」而吞掉（首键防吞规范）。
     */
    private fun restoreListBar() {
        setPageTitle(getString(R.string.title_song_comment))
        setTitleIcon(MusicIcons.COMMENT)
        setSoftKeys(
            getString(R.string.softkey_comment),
            getString(R.string.softkey_more),
            getString(R.string.softkey_back)
        )
        if (::focusHelper.isInitialized && focusHelper.itemCount > 0) {
            focusHelper.setFocusIndex(focusIdx.coerceAtLeast(0), true)
        }
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
