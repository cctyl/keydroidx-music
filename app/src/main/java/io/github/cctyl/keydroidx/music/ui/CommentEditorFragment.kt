package io.github.cctyl.keydroidx.music.ui

import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.TextView
import io.github.cctyl.keydroidx.music.R
import io.github.cctyl.keydroidx.music.network.CommentApi
import io.github.cctyl.nokia.keycore.ui.NokiaFontManager
import io.github.cctyl.nokia.keycore.ui.NokiaIcons
import io.github.cctyl.nokia.keycore.ui.dialog.NokiaConfirmDialog
import io.github.cctyl.nokia.keycore.ui.dialog.NokiaOptionsDialog
import io.github.cctyl.nokia.keycore.ui.page.NokiaPageFragment

/**
 * 歌曲评论「全屏编辑页」。
 *
 * 为什么不用 SDK 的 `NokiaTextInputFragment`：它左软键菜单是硬编码的
 * 「粘贴 / 复制全部 / 清空全部 / 保存并退出 / 退出（不保存内容）」，
 * `showOptionsMenu()` 为 private 无法覆写，而本页要求菜单只有「发送 / 退出」。
 * 因此这里继承同一个生态基类 [NokiaPageFragment] 自建页面 —— **不改动 SDK / common 组件**，
 * 字体、主题、软键栏、返回栈等能力全部照旧由宿主骨架提供。
 *
 * 物理按键：
 *  - 左软键「菜单」：选项菜单（发送评论 / 退出编辑）；
 *  - 中软键「发送」：直接发送（与菜单里的「发送评论」等价）；
 *  - 右软键「返回」：放弃内容回到评论列表；
 *  - 方向键：全部透传给 [EditText] 移动光标（`onDirection` 返回 false）。
 *
 * 本页只负责「取到正文并交给宿主」，网络请求交给 [androidx.fragment.app.FragmentActivity]
 * 的作用域执行 —— 发送过程中本页就已出栈，协程挂在 Activity 上才不会被销毁中断。
 */
class CommentEditorFragment : NokiaPageFragment() {

    companion object {
        private const val ARG_SONG_NAME = "song_name"

        private const val OPT_SEND = 1
        private const val OPT_EXIT = 2

        fun newInstance(songName: String): CommentEditorFragment =
            CommentEditorFragment().apply {
                arguments = Bundle().apply { putString(ARG_SONG_NAME, songName) }
            }
    }

    private var songName: String = ""
    private var editInput: EditText? = null
    private var tvCounter: TextView? = null
    private var sending = false

    /** 发送回调：参数为已 trim 的评论正文。宿主负责真正提交网络请求。 */
    var onSend: ((String) -> Unit)? = null

    override fun getLayoutRes(): Int = R.layout.fragment_comment_editor

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        songName = arguments?.getString(ARG_SONG_NAME).orEmpty()
    }

    override fun onPageCreated(view: View, savedInstanceState: Bundle?) {
        editInput = view.findViewById(R.id.edit_comment)
        tvCounter = view.findViewById(R.id.tv_editor_counter)

        NokiaIcons.setIcon(view.findViewById(R.id.icon_editor_target), MusicIcons.COMMENT)
        view.findViewById<TextView>(R.id.tv_editor_song).text =
            getString(R.string.comment_editor_song, songName)

        editInput?.apply {
            setHint(getString(R.string.comment_editor_hint))
            filters = arrayOf(InputFilter.LengthFilter(CommentApi.MAX_COMMENT_LENGTH))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = updateCounter()
                override fun afterTextChanged(s: Editable?) = Unit
            })
            // 初始即聚焦，物理键盘可直接输入
            requestFocus()
        }

        applyTheme(view)
        updateCounter()

        // 基类已整树应用过字体，这里再补一次 key-core 的点阵字体与缩放，
        // 保证与评论列表页（Activity 侧）视觉一致
        NokiaFontManager.applyToViewTree(view)
    }

    override fun onResume() {
        super.onResume()
        view?.let { applyTheme(it) }
        editInput?.requestFocus()
    }

    // ══════════════════════════════════════════════════════════
    //  NokiaPage 契约
    // ══════════════════════════════════════════════════════════

    override fun getPageTitle(): CharSequence = getString(R.string.title_comment_editor)

    override fun getSoftLeftText(): CharSequence = getString(R.string.softkey_menu)

    override fun getSoftCenterText(): CharSequence = getString(R.string.softkey_send)

    override fun getSoftRightText(): CharSequence = getString(R.string.softkey_back)

    /** 方向键不走列表焦点导航，返回 false 让事件继续透传给 EditText 移动光标。 */
    override fun onDirection(direction: Int): Boolean = false

    override fun onSelect(): Boolean {
        doSend()
        return true
    }

    override fun onSoftLeft(): Boolean {
        showOptionsMenu()
        return true
    }

    override fun onSoftRight(): Boolean {
        exitEditor()
        return true
    }

    /**
     * 返回键交给宿主的 `exitCurrent()`（弹返回栈），与右软键殊途同归；
     * 返回 false 而不是自己 pop，可避免重复出栈。
     */
    override fun onBack(): Boolean = false

    // ══════════════════════════════════════════════════════════
    //  业务逻辑
    // ══════════════════════════════════════════════════════════

    private fun showOptionsMenu() {
        NokiaOptionsDialog(requireContext(), getString(R.string.comment_editor_options))
            .addItem(OPT_SEND, getString(R.string.opt_comment_send))
            .addItem(OPT_EXIT, getString(R.string.opt_comment_exit))
            .setOnOptionSelectedListener { _, item ->
                when (item.id) {
                    OPT_SEND -> doSend()
                    OPT_EXIT -> exitEditor()
                }
            }
            .show()
    }

    private fun doSend() {
        val text = editInput?.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) {
            NokiaConfirmDialog(
                requireContext(),
                getString(R.string.title_comment_editor),
                getString(R.string.comment_empty_content)
            )
                .setPositiveButton(getString(R.string.dialog_confirm)) { }
                .show()
            return
        }
        if (sending) return
        sending = true
        // 先交给宿主（Activity 作用域）再出栈：本页销毁不影响已在飞的协程
        onSend?.invoke(text)
        exitEditor()
    }

    private fun exitEditor() {
        if (!isAdded) return
        activity?.supportFragmentManager?.popBackStack()
    }

    private fun updateCounter() {
        val len = editInput?.text?.length ?: 0
        tvCounter?.text = "$len/${CommentApi.MAX_COMMENT_LENGTH}"
    }

    /** 输入区按当前生态主题着色（XML 里的静态色只是经典深蓝兜底） */
    private fun applyTheme(view: View) {
        val p = MusicTheme.current(view.context)
        view.setBackgroundColor(p.bodyBg)
        view.findViewById<TextView>(R.id.tv_editor_song)?.setTextColor(p.text)
        view.findViewById<TextView>(R.id.icon_editor_target)?.setTextColor(MusicTheme.BRAND_ACCENT)
        tvCounter?.setTextColor(p.subtext)
        editInput?.apply {
            background = MusicTheme.createCardDrawable(view.context, 3f)
            setTextColor(p.text)
            setHintTextColor(p.subtext)
        }
    }
}
