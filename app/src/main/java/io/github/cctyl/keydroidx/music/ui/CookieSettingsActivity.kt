package io.github.cctyl.keydroidx.music.ui

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import io.github.cctyl.keydroidx.music.util.NLog as Log
import android.view.KeyEvent
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import io.github.cctyl.keydroidx.music.R
import io.github.cctyl.keydroidx.music.auth.CookieManager
import io.github.cctyl.keydroidx.music.network.RetrofitClient
import io.github.cctyl.nokia.keycore.model.NokiaKeyAction
import io.github.cctyl.nokia.keycore.ui.NokiaBaseActivity
import io.github.cctyl.nokia.keycore.ui.NokiaIcons
import io.github.cctyl.nokia.keycore.ui.dialog.NokiaOptionsDialog
import android.graphics.Color

/**
 * 网易云 Cookie 设置页。
 *
 * 按键设计（适配物理按键机的「右键=删除」习惯）：
 * - 右键：删除光标前一个字符（长按连续删除由系统 REPEAT 触发）
 * - 左软键：选项菜单（粘贴 / 清空 / 保存 / 退出）
 * - 中键(SELECT)：弹出输入法打字；触屏点击输入框同效
 */
class CookieSettingsActivity : NokiaBaseActivity() {

    companion object {
        private const val TAG = "CookieSettings"
        fun start(context: android.content.Context) {
            context.startActivity(Intent(context, CookieSettingsActivity::class.java))
        }
    }

    private lateinit var etCookie: EditText
    private lateinit var tvStatus: TextView

    override fun getContentLayoutRes(): Int = R.layout.activity_cookie_settings

    override fun onInitViews() {
        setPageTitle(getString(R.string.cookie_settings_title))
        setTitleIcon(NokiaIcons.ICON_SETTINGS)
        setStatusBarVisible(true)
        registerBatteryReceiver()
        setSoftKeys(
            getString(R.string.softkey_options),
            getString(R.string.softkey_input),
            getString(R.string.softkey_delete)
        )

        etCookie = findViewById(R.id.et_cookie_input)
        tvStatus = findViewById(R.id.tv_cookie_status)

        // 回显当前已保存的 Cookie
        val current = CookieManager.getCookie(this)
        if (!current.isNullOrBlank()) {
            etCookie.setText(current)
            etCookie.setSelection(current.length)
            updateStatus("当前已有 Cookie")
        } else {
            updateStatus(null)
        }

        // 触屏点击弹输入法打字
        etCookie.setOnClickListener { showIme() }
    }

    /** 更新状态提示行；hint 为 null 时按内容长度生成默认文案 */
    private fun updateStatus(hint: String?) {
        val len = etCookie.text.length
        tvStatus.text = when {
            hint != null && len > 0 -> "$hint（长度 $len）· 左键=菜单 右键=删除"
            hint != null -> hint
            len > 0 -> "已输入 $len 字符 · 左键=菜单 可保存"
            else -> "尚未设置 Cookie · 按中键或点击输入框开始输入"
        }
    }

    private fun showIme() {
        etCookie.requestFocus()
        etCookie.setSelection(etCookie.text.length)
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(etCookie, InputMethodManager.SHOW_IMPLICIT)
    }

    // ══════════════════════════════════════════════════════════
    //  按键处理
    // ══════════════════════════════════════════════════════════

    /**
     * 拦截物理 BACK：与右键语义一致，当作「删除一个字符」，不退出页面。
     * （基类不解析 BACK，会走系统默认 finish()，必须在这里截住）
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_BACK) {
            Log.d(TAG, "BACK → treat as delete char")
            deleteChar()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onAction(action: Int): Boolean {
        Log.d(TAG, "onAction=$action")
        return when (action) {
            NokiaKeyAction.SOFT_LEFT -> {
                showOptionsMenu()
                true
            }
            NokiaKeyAction.SOFT_RIGHT -> {
                deleteChar()
                true
            }
            NokiaKeyAction.SELECT -> {
                showIme()
                true
            }
            else -> super.onAction(action)
        }
    }

    /** 删除光标前一个字符（选中文本时删除选中部分），并刷新状态行 */
    private fun deleteChar() {
        val start = etCookie.selectionStart
        val end = etCookie.selectionEnd
        val text = etCookie.text
        if (text.isEmpty()) {
            Toast.makeText(this, "已经没有可删除的内容了", Toast.LENGTH_SHORT).show()
            return
        }
        if (start != end && start >= 0 && end <= text.length) {
            // 有选中区域：整段删除
            text.replace(start.coerceAtLeast(0), end, "")
        } else if (start > 0) {
            text.delete(start - 1, start)
            etCookie.setSelection(start - 1)
        } else {
            Toast.makeText(this, "光标已在最前面", Toast.LENGTH_SHORT).show()
            return
        }
        Log.d(TAG, "deleteChar → length=${text.length}")
        updateStatus(null)
    }

    // ══════════════════════════════════════════════════════════
    //  左软键选项菜单
    // ══════════════════════════════════════════════════════════

    private fun showOptionsMenu() {
        val iconColor = Color.WHITE
        val iconSize = (18 * resources.displayMetrics.density).toInt()
        val dialog = NokiaOptionsDialog(this, "Cookie 操作")

        dialog.addItem(
            1, "粘贴",
            NokiaIcons.createDrawable(this, NokiaIcons.ICON_EDIT, iconSize, iconColor)
        )
        dialog.addItem(
            2, "清空",
            NokiaIcons.createDrawable(this, NokiaIcons.ICON_DELETE, iconSize, iconColor)
        )
        dialog.addItem(
            3, "保存并退出",
            NokiaIcons.createDrawable(this, NokiaIcons.ICON_CHECK, iconSize, iconColor)
        )
        dialog.addItem(
            4, "退出",
            NokiaIcons.createDrawable(this, NokiaIcons.ICON_CLOSE, iconSize, iconColor)
        )

        dialog.setOnOptionSelectedListener { index, _ ->
            when (index) {
                0 -> pasteFromClipboard()
                1 -> clearInput()
                2 -> saveCookie()
                3 -> {
                    Log.d(TAG, "menu exit page")
                    finish()
                }
            }
        }
        dialog.show()
    }

    /** 从剪贴板粘贴到光标处（有选中则替换选中内容） */
    private fun pasteFromClipboard() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clipText = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
        if (clipText.isNullOrEmpty()) {
            Toast.makeText(this, "剪贴板为空", Toast.LENGTH_SHORT).show()
            return
        }
        val start = etCookie.selectionStart.coerceAtLeast(0)
        val end = etCookie.selectionEnd.coerceAtLeast(0)
        val from = minOf(start, end)
        val to = maxOf(start, end)
        etCookie.text.replace(from, to, clipText)
        etCookie.setSelection(from + clipText.length)
        Log.d(TAG, "paste ${clipText.length} chars at $from")
        updateStatus("已粘贴")
        Toast.makeText(this, "已粘贴 ${clipText.length} 个字符", Toast.LENGTH_SHORT).show()
    }

    /** 清空全部输入（不清除已保存的 Cookie，需再按保存才会生效清除） */
    private fun clearInput() {
        if (etCookie.text.isEmpty()) return
        etCookie.setText("")
        updateStatus(null)
        Toast.makeText(this, "已清空输入框", Toast.LENGTH_SHORT).show()
    }

    // ══════════════════════════════════════════════════════════
    //  保存
    // ══════════════════════════════════════════════════════════

    /**
     * 保存输入框中的 Cookie：
     * - 非空：去首尾空白后保存并同步网络层（只复制 MUSIC_U 值时自动补全）
     * - 空白：视为清除 Cookie（退出登录）
     */
    private fun saveCookie() {
        val input = etCookie.text.toString().trim()
        if (input.isEmpty()) {
            Log.d(TAG, "saveCookie: empty input → clear")
            CookieManager.clearCookie(this)
            RetrofitClient.updateCookie(this, null)
            updateStatus("已清除 Cookie（未登录）")
            Toast.makeText(this, "Cookie 已清除", Toast.LENGTH_SHORT).show()
            // 通知宿主刷新登录态并返回
            setResult(RESULT_OK)
            finish()
            return
        }
        val normalized = if (input.contains("=")) input else "MUSIC_U=$input"
        Log.d(TAG, "saveCookie: length=${normalized.length} hasMUSICU=${normalized.contains("MUSIC_U")}")
        RetrofitClient.updateCookie(this, normalized)  // 内部会持久化
        updateStatus("已保存")
        Toast.makeText(this, "Cookie 已保存 ✓", Toast.LENGTH_SHORT).show()
        // 保存成功立即通知宿主刷新登录态，并返回设置页
        setResult(RESULT_OK)
        finish()
    }

    override fun onDestroy() {
        // 兜底：离开页面时收起软键盘
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(etCookie.windowToken, 0)
        super.onDestroy()
    }
}
