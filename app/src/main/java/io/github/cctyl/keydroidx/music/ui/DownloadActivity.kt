package io.github.cctyl.keydroidx.music.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.cctyl.keydroidx.music.R
import io.github.cctyl.keydroidx.music.download.DownloadManager
import io.github.cctyl.keydroidx.music.download.DownloadStatus
import io.github.cctyl.keydroidx.music.download.DownloadTask
import io.github.cctyl.keydroidx.music.player.PlaybackService
import io.github.cctyl.nokia.keycore.model.NokiaKeyBinding
import io.github.cctyl.nokia.keycore.model.NokiaKeyAction
import io.github.cctyl.nokia.keycore.ui.NokiaBaseActivity
import io.github.cctyl.nokia.keycore.ui.NokiaFontManager
import io.github.cctyl.nokia.keycore.ui.NokiaIcons
import io.github.cctyl.nokia.keycore.ui.dialog.NokiaConfirmDialog
import io.github.cctyl.nokia.keycore.ui.dialog.NokiaOptionsDialog
import io.github.cctyl.nokia.keycore.ui.page.NokiaListFocusHelper
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * 下载管理页面（展示下载中与已完成任务）
 */
class DownloadActivity : NokiaBaseActivity() {

    private lateinit var scrollView: ScrollView
    private lateinit var taskContainer: LinearLayout
    private lateinit var focusHelper: NokiaListFocusHelper

    private var displayedTasks: List<DownloadTask> = emptyList()
    private val taskViews = mutableListOf<View>()

    private val bgFocused by lazy {
        GradientDrawable().apply {
            setColor(Color.parseColor("#38BDF8"))
            cornerRadius = dp(4).toFloat()
        }
    }

    override fun getContentLayoutRes(): Int = R.layout.activity_download

    override fun onInitViews() {
        setPageTitle("下载管理")
        setTitleIcon(NokiaIcons.ICON_DOWNLOAD)
        setStatusBarVisible(true)
        setSignalIcon(NokiaIcons.ICON_SIGNAL_CELLULAR_4_BAR)
        setBatteryPercent("70%")
        scrollView = findViewById(R.id.scroll_view)
        taskContainer = findViewById(R.id.ll_task_container)

        focusHelper = NokiaListFocusHelper(this, scrollView)
        focusHelper.setOnFocusChangedListener { oldIdx, newIdx, newView ->
            val oldV = taskViews.getOrNull(oldIdx)
            if (oldV != null) {
                oldV.setBackgroundColor(Color.TRANSPARENT)
                setChildTextColors(oldV, false)
            }
            if (newView != null) {
                newView.background = bgFocused
                setChildTextColors(newView, true)
            }
            updateSoftKeys()
        }

        updateSoftKeys()

        // 立即渲染一次当前已有的任务（确保进入页面时首项立即获焦且按键生效）
        renderTasks(DownloadManager.tasks.value)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                var isFirst = true
                DownloadManager.tasks.collect { tasks ->
                    if (isFirst) {
                        isFirst = false
                        return@collect
                    }
                    renderTasks(tasks)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateSoftKeys()
        // 确保物理按键焦点生效
        window?.decorView?.post {
            focusHelper.focusedView?.let {
                it.isFocusableInTouchMode = true
                it.requestFocus()
            } ?: findViewById<View>(android.R.id.content)?.requestFocus()
        }
    }

    private fun updateSoftKeys() {
        val selectedTask = getFocusedTask()
        val centerText = when (selectedTask?.status) {
            DownloadStatus.DOWNLOADING -> "暂停"
            DownloadStatus.PAUSED, DownloadStatus.FAILED -> "重试"
            DownloadStatus.COMPLETED -> "播放"
            else -> "操作"
        }
        setSoftKeys("选项", centerText, "返回")
    }

    private fun renderTasks(allTasks: List<DownloadTask>) {
        val currentFocusSongId = getFocusedTask()?.songId

        val downloadingTasks = allTasks.filter {
            it.status == DownloadStatus.DOWNLOADING ||
            it.status == DownloadStatus.PENDING ||
            it.status == DownloadStatus.PAUSED ||
            it.status == DownloadStatus.FAILED
        }
        val completedTasks = allTasks.filter { it.status == DownloadStatus.COMPLETED }

        taskContainer.removeAllViews()
        taskViews.clear()

        val newDisplayed = mutableListOf<DownloadTask>()
        val inflater = LayoutInflater.from(this)

        if (allTasks.isEmpty()) {
            val emptyTv = TextView(this).apply {
                text = "暂无下载任务\n可在歌单中按左软键选择「下载歌曲」"
                textSize = 12f
                setTextColor(Color.parseColor("#80FFFFFF"))
                setPadding(dp(16), dp(40), dp(16), dp(40))
                gravity = android.view.Gravity.CENTER
                includeFontPadding = false
            }
            taskContainer.addView(emptyTv)
            displayedTasks = emptyList()
            focusHelper.setItems(emptyList())
            updateSoftKeys()
            return
        }

        // 1. 正在下载区域
        if (downloadingTasks.isNotEmpty()) {
            val header = makeSectionHeader("正在下载 (${downloadingTasks.size})")
            taskContainer.addView(header)
            downloadingTasks.forEach { task ->
                val itemView = inflater.inflate(R.layout.item_download_task, taskContainer, false)
                bindTaskView(itemView, task)
                taskContainer.addView(itemView)
                taskViews.add(itemView)
                newDisplayed.add(task)
            }
        }

        // 2. 已完成区域
        if (completedTasks.isNotEmpty()) {
            val header = makeSectionHeader("已完成 (${completedTasks.size})")
            taskContainer.addView(header)
            completedTasks.forEach { task ->
                val itemView = inflater.inflate(R.layout.item_download_task, taskContainer, false)
                bindTaskView(itemView, task)
                taskContainer.addView(itemView)
                taskViews.add(itemView)
                newDisplayed.add(task)
            }
        }

        displayedTasks = newDisplayed
        NokiaFontManager.applyToViewTree(taskContainer)
        focusHelper.setItems(taskViews)

        val restoreIdx = if (currentFocusSongId != null) {
            newDisplayed.indexOfFirst { it.songId == currentFocusSongId }.coerceAtLeast(0)
        } else 0
        focusHelper.setFocusIndex(restoreIdx, false)
        updateSoftKeys()
    }

    private fun bindTaskView(view: View, task: DownloadTask) {
        val tvIcon = view.findViewById<TextView>(R.id.tv_task_icon)
        val tvTitle = view.findViewById<TextView>(R.id.tv_task_title)
        val tvSub = view.findViewById<TextView>(R.id.tv_task_sub)
        val tvBadge = view.findViewById<TextView>(R.id.tv_task_badge)

        tvTitle.text = task.title

        when (task.status) {
            DownloadStatus.PENDING -> {
                NokiaIcons.setIcon(tvIcon, NokiaIcons.ICON_REFRESH)
                tvSub.text = "${task.artist} · 等待中"
                tvBadge.text = "排队"
            }
            DownloadStatus.DOWNLOADING -> {
                NokiaIcons.setIcon(tvIcon, NokiaIcons.ICON_DOWNLOAD)
                val mbStr = if (task.totalBytes > 0) {
                    val curMb = task.downloadedBytes / 1024.0 / 1024.0
                    val totMb = task.totalBytes / 1024.0 / 1024.0
                    String.format(Locale.getDefault(), "%.1f/%.1f MB", curMb, totMb)
                } else ""
                tvSub.text = "${task.artist} · ${task.progress}% $mbStr"
                tvBadge.text = "${task.progress}%"
            }
            DownloadStatus.PAUSED -> {
                NokiaIcons.setIcon(tvIcon, NokiaIcons.ICON_PAUSE)
                tvSub.text = "${task.artist} · 已暂停"
                tvBadge.text = "暂停"
            }
            DownloadStatus.FAILED -> {
                NokiaIcons.setIcon(tvIcon, NokiaIcons.ICON_ERROR)
                tvSub.text = "${task.artist} · ${task.errorMessage ?: "下载失败"}"
                tvBadge.text = "失败"
            }
            DownloadStatus.COMPLETED -> {
                NokiaIcons.setIcon(tvIcon, NokiaIcons.ICON_CHECK)
                val mbStr = if (task.totalBytes > 0) String.format(Locale.getDefault(), "%.1f MB", task.totalBytes / 1024.0 / 1024.0) else "已下载"
                val lrcStr = if (task.lyricPath != null) " · 含歌词" else ""
                tvSub.text = "${task.artist} · $mbStr$lrcStr"
                tvBadge.text = "本地"
            }
        }
    }

    private fun setChildTextColors(view: View, focused: Boolean) {
        val tvIcon = view.findViewById<TextView>(R.id.tv_task_icon)
        val tvTitle = view.findViewById<TextView>(R.id.tv_task_title)
        val tvSub = view.findViewById<TextView>(R.id.tv_task_sub)
        val tvBadge = view.findViewById<TextView>(R.id.tv_task_badge)

        val titleColor = if (focused) Color.BLACK else Color.WHITE
        val subColor = if (focused) Color.parseColor("#1E293B") else Color.parseColor("#94A3B8")
        val iconColor = if (focused) Color.BLACK else Color.parseColor("#38BDF8")

        tvTitle?.setTextColor(titleColor)
        tvSub?.setTextColor(subColor)
        tvIcon?.setTextColor(iconColor)
        tvBadge?.setTextColor(titleColor)
    }

    private fun makeSectionHeader(title: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            minimumHeight = dp(24)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setBackgroundColor(Color.parseColor("#4D000000"))

            val bar = View(this@DownloadActivity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(3), LinearLayout.LayoutParams.MATCH_PARENT)
                setBackgroundColor(Color.parseColor("#38BDF8"))
            }
            addView(bar)

            val tv = TextView(this@DownloadActivity).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    leftMargin = dp(6)
                }
                text = title
                textSize = 11f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#90CAF9"))
                includeFontPadding = false
            }
            addView(tv)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return super.onKeyDown(keyCode, event)
    }

    override fun onAction(action: Int): Boolean {
        return when (action) {
            NokiaKeyAction.UP -> {
                focusHelper.onDirection(action)
                true
            }
            NokiaKeyAction.DOWN -> {
                focusHelper.onDirection(action)
                true
            }
            NokiaKeyAction.SELECT -> {
                onItemClick()
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

    private fun getFocusedTask(): DownloadTask? {
        if (!::focusHelper.isInitialized) return null
        val idx = focusHelper.focusIndex
        return displayedTasks.getOrNull(idx)
    }

    private fun onItemClick() {
        val task = getFocusedTask() ?: return
        when (task.status) {
            DownloadStatus.COMPLETED -> {
                // 立即播放本地歌曲
                playTask(task)
            }
            DownloadStatus.DOWNLOADING, DownloadStatus.PENDING -> {
                DownloadManager.pauseDownload(task.songId)
                Toast.makeText(this, "已暂停", Toast.LENGTH_SHORT).show()
            }
            DownloadStatus.PAUSED, DownloadStatus.FAILED -> {
                DownloadManager.resumeDownload(task.songId)
                Toast.makeText(this, "继续下载", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun playTask(task: DownloadTask) {
        val allCompleted = displayedTasks.filter { it.status == DownloadStatus.COMPLETED }.map { it.toSongItem() }
        val targetIndex = allCompleted.indexOfFirst { it.id == task.songId }.coerceAtLeast(0)
        PlaybackService.startPlay(this, allCompleted, targetIndex)
        MusicPlayerActivity.start(this)
    }

    private fun showOptionsMenu() {
        val task = getFocusedTask()
        val dialog = NokiaOptionsDialog(this)
        val iconSize = dp(18)
        val iconColor = Color.parseColor("#38BDF8")

        val actions = mutableListOf<() -> Unit>()

        if (task != null) {
            when (task.status) {
                DownloadStatus.COMPLETED -> {
                    dialog.addItem(1, "播放歌曲", NokiaIcons.createDrawable(this, NokiaIcons.ICON_PLAY, iconSize, iconColor))
                    actions.add { playTask(task) }
                }
                DownloadStatus.DOWNLOADING -> {
                    dialog.addItem(2, "暂停下载", NokiaIcons.createDrawable(this, NokiaIcons.ICON_PAUSE, iconSize, iconColor))
                    actions.add { DownloadManager.pauseDownload(task.songId) }
                }
                DownloadStatus.PAUSED, DownloadStatus.FAILED -> {
                    dialog.addItem(3, "继续下载", NokiaIcons.createDrawable(this, NokiaIcons.ICON_REFRESH, iconSize, iconColor))
                    actions.add { DownloadManager.resumeDownload(task.songId) }
                }
                else -> {}
            }

            dialog.addItem(4, "删除该任务", NokiaIcons.createDrawable(this, NokiaIcons.ICON_DELETE, iconSize, iconColor))
            actions.add { confirmDeleteTask(task) }
        }

        val hasDownloading = displayedTasks.any { it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.PENDING }
        val hasPaused = displayedTasks.any { it.status == DownloadStatus.PAUSED || it.status == DownloadStatus.FAILED }

        if (hasDownloading) {
            dialog.addItem(5, "全部暂停", NokiaIcons.createDrawable(this, NokiaIcons.ICON_PAUSE, iconSize, iconColor))
            actions.add { DownloadManager.pauseAll() }
        }
        if (hasPaused) {
            dialog.addItem(6, "全部继续", NokiaIcons.createDrawable(this, NokiaIcons.ICON_REFRESH, iconSize, iconColor))
            actions.add { DownloadManager.resumeAll() }
        }

        val hasCompleted = displayedTasks.any { it.status == DownloadStatus.COMPLETED }
        if (hasCompleted) {
            dialog.addItem(7, "清空已完成", NokiaIcons.createDrawable(this, NokiaIcons.ICON_DELETE, iconSize, iconColor))
            actions.add {
                val confirm = NokiaConfirmDialog(this, "清空已完成", "确定清空已完成的下载记录及物理文件吗？")
                confirm.setPositiveButton("清空") {
                    DownloadManager.clearCompleted(deleteFiles = true)
                    Toast.makeText(this, "已清空已完成任务", Toast.LENGTH_SHORT).show()
                }
                confirm.show()
            }
        }

        dialog.setOnOptionSelectedListener { index, _ ->
            if (index in actions.indices) {
                actions[index].invoke()
            }
        }
        dialog.show()
    }

    private fun confirmDeleteTask(task: DownloadTask) {
        val dialog = NokiaConfirmDialog(this, "删除下载", "确定要删除「${task.title}」及其本地文件吗？")
        dialog.setPositiveButton("删除") {
            DownloadManager.deleteDownload(task.songId)
            Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
        }
        dialog.show()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()
}
