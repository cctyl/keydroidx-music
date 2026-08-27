package io.github.cctyl.keydroidx.music.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import io.github.cctyl.keydroidx.music.util.NLog as Log
import android.widget.Toast
import io.github.cctyl.keydroidx.music.R
import io.github.cctyl.nokia.keycore.log.NokiaLog
import io.github.cctyl.nokia.keycore.model.NokiaKeyAction
import io.github.cctyl.nokia.keycore.ui.NokiaBaseActivity
import io.github.cctyl.nokia.keycore.ui.NokiaIcons
import io.github.cctyl.nokia.keycore.ui.dialog.NokiaOptionsDialog
import android.graphics.Color

/**
 * 关于页：展示应用名、版本号、简介、作者与开源地址。
 *
 * 按键设计：
 * - 左软键：选项菜单（浏览器打开 / 复制地址 / 返回）
 * - 右软键：返回上一级
 */
class AboutActivity : NokiaBaseActivity() {

    companion object {
        private const val TAG = "AboutActivity"
        private const val REPO_URL = "https://github.com/cctyl/keydroidx-music"

        fun start(context: Context) {
            context.startActivity(Intent(context, AboutActivity::class.java))
        }
    }

    override fun getContentLayoutRes(): Int = R.layout.activity_about

    override fun onInitViews() {
        setPageTitle(getString(R.string.about_title))
        setTitleIcon(NokiaIcons.ICON_INFO)
        setStatusBarVisible(true)
        registerBatteryReceiver()
        setSoftKeys(
            getString(R.string.softkey_options),
            getString(R.string.softkey_open),
            getString(R.string.softkey_back)
        )

        // 图标
        NokiaIcons.setIcon(findViewById(R.id.icon_about_author), NokiaIcons.ICON_PERSON)
        NokiaIcons.setIcon(findViewById(R.id.icon_about_repo), NokiaIcons.ICON_INFO)

        // 版本号：取 PackageInfo.versionName，兜底 "1.0.0"
        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) {
            Log.w(TAG, "get versionName failed: ${e.message}")
            "1.0.0"
        }
        findViewById<android.widget.TextView>(R.id.tv_about_version).text = "v${versionName ?: "1.0.0"}"

        // 开源地址行持焦，物理键确认/触屏点击均跳浏览器
        val repoRow = findViewById<android.widget.LinearLayout>(R.id.ll_about_repo)
        repoRow.setOnClickListener { openRepoInBrowser() }
        repoRow.post { repoRow.requestFocus() }
    }

    override fun onAction(action: Int): Boolean {
        Log.d(TAG, "onAction=$action")
        return when (action) {
            NokiaKeyAction.SOFT_LEFT -> {
                showOptionsMenu()
                true
            }
            NokiaKeyAction.SELECT -> {
                // 确认键：直接用浏览器打开开源地址
                openRepoInBrowser()
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
    //  左软键选项菜单
    // ══════════════════════════════════════════════════════════
    private fun showOptionsMenu() {
        val iconColor = Color.WHITE
        val iconSize = (18 * resources.displayMetrics.density).toInt()
        val dialog = NokiaOptionsDialog(this, getString(R.string.about_title))

        val detailedLogEnabled = NokiaLog.isDetailedLogEnabled(this)
        val logToggleTitle = if (detailedLogEnabled) "详细日志：已开启" else "详细日志：已关闭"

        dialog.addItem(
            1, getString(R.string.about_open_repo),
            NokiaIcons.createDrawable(this, NokiaIcons.ICON_ARROW_FORWARD, iconSize, iconColor)
        )
        dialog.addItem(
            2, getString(R.string.about_copy_repo),
            NokiaIcons.createDrawable(this, NokiaIcons.ICON_EDIT, iconSize, iconColor)
        )
        dialog.addItem(
            3, logToggleTitle,
            NokiaIcons.createDrawable(this, NokiaIcons.ICON_INFO, iconSize, iconColor)
        )
        dialog.addItem(
            4, getString(R.string.about_return),
            NokiaIcons.createDrawable(this, NokiaIcons.ICON_ARROW_BACK, iconSize, iconColor)
        )

        dialog.setOnOptionSelectedListener { index, _ ->
            when (index) {
                0 -> openRepoInBrowser()
                1 -> copyRepoUrl()
                2 -> toggleDetailedLog()
                3 -> finish()
            }
        }
        dialog.show()
    }

    private fun toggleDetailedLog() {
        val newState = !NokiaLog.isDetailedLogEnabled(this)
        NokiaLog.setDetailedLogEnabled(this, newState)
        val tip = if (newState) "详细日志已开启 (记录调试日志)" else "详细日志已关闭 (仅记录错误与崩溃)"
        Toast.makeText(this, tip, Toast.LENGTH_SHORT).show()
        NokiaLog.i(TAG, tip)
    }

    /** 调系统浏览器打开开源仓库；无可用浏览器时给出提示。 */
    private fun openRepoInBrowser() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(REPO_URL))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(packageManager) != null) {
            startActivity(intent)
        } else {
            Log.w(TAG, "no browser to open $REPO_URL")
            Toast.makeText(this, getString(R.string.about_no_browser), Toast.LENGTH_SHORT).show()
            copyRepoUrl()
        }
    }

    /** 复制开源地址到剪贴板。 */
    private fun copyRepoUrl() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("repo", REPO_URL))
        Log.d(TAG, "repo url copied")
        Toast.makeText(this, getString(R.string.about_copied), Toast.LENGTH_SHORT).show()
    }
}
