package io.github.cctyl.keydroidx.music.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.provider.MediaStore
import io.github.cctyl.keydroidx.music.util.NLog as Log
import java.io.File
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import android.os.Environment
import io.github.cctyl.keydroidx.music.R
import io.github.cctyl.keydroidx.music.download.DownloadManager
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 本地音乐页
 *
 * 顶部为「扫描本地音乐」行（焦点第 0 项，SELECT 触发扫描），
 * 下方为 MediaStore 扫描出的本地歌曲列表。
 * 本地歌曲通过 SongItem.localPath 直接走 ExoPlayer 文件播放，
 * 不经过网易云取链与 VIP/版权检查。
 */
class LocalMusicActivity : NokiaBaseActivity() {

    companion object {
        private const val TAG = "LocalMusic"
        private const val REQ_AUDIO_PERMISSION = 1001
        private const val REQ_PICK_FOLDER = 1002
        private const val KEY_PICKED_FOLDER = "picked_folder"
        private const val KEY_SCANNED_SONGS = "scanned_songs"
        /** 懒加载页大小：每次只创建这么多行视图 */
        private const val PAGE_SIZE = 20

        fun start(context: android.content.Context) {
            context.startActivity(Intent(context, LocalMusicActivity::class.java))
        }
    }

    // ── 数据 ──
    /** 扫描结果：MediaStore 行 id → 歌曲（id 使用负数偏移避免与网易云歌曲 id 冲突） */
    private data class LocalSong(
        val rowId: Long,
        val title: String,
        val artist: String,
        val path: String,
        val durationMs: Long
    )

    private var localSongs: List<LocalSong> = emptyList()
    private var scanning = false

    // ── 文件夹选择 ──
    /** 用户选定的扫描根目录；null = 默认策略（MediaStore + 常用目录降级） */
    private var pickedFolder: File? = null

    // ── UI 控件 ──
    private lateinit var llPickFolder: LinearLayout
    private lateinit var tvPickFolderSub: TextView
    private lateinit var llScanBar: LinearLayout
    private lateinit var tvScanSub: TextView
    private lateinit var llSongContainer: LinearLayout

    // ── 焦点 / 懒加载 ──
    private val songItemViews = mutableListOf<LinearLayout>()
    private lateinit var focusHelper: NokiaListFocusHelper
    val focusIdx: Int get() = if (::focusHelper.isInitialized) focusHelper.focusIndex else 0
    private var renderedCount = 0

    // ── 颜色（跟随当前主题，取值见 MusicTheme / HTML 原型）──
    private val colorSubtext get() = MusicTheme.current(this).subtext
    private val colorSubtextFocused get() = MusicTheme.SUBTEXT_FOCUSED
    private val colorAccent get() = MusicTheme.BRAND_ACCENT
    private val colorWhite get() = Color.WHITE
    private val colorDivider get() = MusicTheme.current(this).dashed

    // ══════════════════════════════════════════════════════════
    //  NokiaBaseActivity 回调
    // ══════════════════════════════════════════════════════════
    override fun getContentLayoutRes(): Int = R.layout.activity_local_music

    override fun onInitViews() {
        setPageTitle("本地音乐")
        setTitleIcon(NokiaIcons.ICON_SD_CARD)
        setStatusBarVisible(true)
        setSignalIcon(NokiaIcons.ICON_SIGNAL_CELLULAR_4_BAR)
        // XML 静态经典蓝配色 → 当前主题色
        findViewById<View?>(android.R.id.content)?.let { MusicTheme.applyToViewTree(it) }
        setBatteryPercent("70%")
        setSoftKeys(
            getString(R.string.softkey_options),
            getString(R.string.softkey_play_selected),
            getString(R.string.softkey_back_to_list)
        )

        llPickFolder = findViewById(R.id.ll_pick_folder)
        tvPickFolderSub = findViewById(R.id.tv_pick_folder_sub)
        llScanBar = findViewById(R.id.ll_scan_bar)
        tvScanSub = findViewById(R.id.tv_scan_sub)
        llSongContainer = findViewById(R.id.ll_song_container)

        // 初始化焦点辅助器
        val scroll = findViewById<android.widget.ScrollView>(R.id.scroll_local_music)
        focusHelper = NokiaListFocusHelper(this, scroll)
        focusHelper.setOnFocusChangedListener { oldIdx, newIdx, newView ->
            if (newView is LinearLayout) {
                setChildTextColors(newView, true)
            }
            val oldView = focusHelper.items.getOrNull(oldIdx) as? LinearLayout
            if (oldView != null) {
                if (oldView == llPickFolder || oldView == llScanBar) {
                    oldView.setBackgroundColor(Color.parseColor("#40000000"))
                }
                setChildTextColors(oldView, false)
            }
        }

        NokiaIcons.setIcon(findViewById(R.id.icon_scan), NokiaIcons.ICON_REFRESH)
        NokiaIcons.setIcon(findViewById(R.id.icon_pick_folder), NokiaIcons.ICON_FOLDER)
        pickedFolder = loadPickedFolder()
        val loaded = loadScannedSongs()
        val downloaded = DownloadManager.getDownloadedLocalSongs().map {
            LocalSong(
                rowId = -it.id,
                title = it.name,
                artist = it.artistName,
                path = it.localPath ?: "",
                durationMs = it.duration ?: 0L
            )
        }
        localSongs = mergeSongs(downloaded, loaded)
        if (downloaded.isNotEmpty()) {
            saveScannedSongs()
        }
        updatePickFolderSub()
        if (localSongs.isNotEmpty()) {
            tvScanSub.text = "共 ${localSongs.size} 首歌曲 · 点击可继续扫描"
            renderSongs()
        }
        tvScanSub.text = "点击开始扫描内部存储"

        // 首次进入自动扫描一次
        beginScan()
    }

    // ══════════════════════════════════════════════════════════
    //  扫描
    // ══════════════════════════════════════════════════════════
    private fun beginScan() {
        if (scanning) return
        if (!hasAudioPermission()) {
            Log.d(TAG, "request audio permission: $audioPermission")
            ActivityCompat.requestPermissions(this, arrayOf(audioPermission), REQ_AUDIO_PERMISSION)
            return
        }
        val custom = pickedFolder?.takeIf { it.exists() }
        scanning = true
        tvScanSub.text = "正在扫描…"
        lifecycleScope.launch {
            val result = if (custom != null) {
                Log.d(TAG, "scan custom folder: ${custom.absolutePath}")
                scanFolder(custom)
            } else {
                scanLocalSongs()
            }
            localSongs = mergeSongs(localSongs, result)
            saveScannedSongs()
            scanning = false
            if (isDestroyed || isFinishing) return@launch
            Log.d(TAG, "scan done: +${result.size}, total ${localSongs.size} songs")
            tvScanSub.text = "本次新增 ${result.size} 首，共 ${localSongs.size} 首 · 再次点击可重新扫描"
            renderSongs()
            val targetIdx = focusHelper.focusIndex.coerceIn(0, (getFocusableViews().size - 1).coerceAtLeast(0))
            focusHelper.setFocusIndex(targetIdx, true)
        }
    }

    private val audioPermission: String
        get() = if (android.os.Build.VERSION.SDK_INT >= 33)
            // Android 13+ 细分媒体权限：READ_EXTERNAL_STORAGE 已废弃，申请直接拒绝且不弹窗
            Manifest.permission.READ_MEDIA_AUDIO
        else
            Manifest.permission.READ_EXTERNAL_STORAGE

    private fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, audioPermission) ==
            PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_AUDIO_PERMISSION) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                beginScan()
            } else {
                Toast.makeText(this, "未授予存储权限，无法扫描本地音乐", Toast.LENGTH_SHORT).show()
                tvScanSub.text = "缺少存储权限，无法扫描"
            }
        }
    }

    /**
     * 扫描本地歌曲：优先 MediaStore；若结果为空（如 adb push 的文件未被媒体库索引），
     * 降级为文件系统扫描常用音乐目录，并对新文件触发媒体索引。
     */
    private suspend fun scanLocalSongs(): List<LocalSong> = withContext(Dispatchers.IO) {
        val out = queryMediaStore()
        Log.d(TAG, "MediaStore scan: ${out.size} songs")
        if (out.isEmpty()) {
            val fallback = scanFilesystem()
            Log.d(TAG, "filesystem fallback scan: ${fallback.size} songs")
            // 异步触发媒体扫描，让文件进入 MediaStore（下次查询即可命中）
            if (fallback.isNotEmpty()) {
                android.media.MediaScannerConnection.scanFile(
                    this@LocalMusicActivity,
                    fallback.map { it.path }.toTypedArray(),
                    null,
                    null
                )
            }
            return@withContext fallback
        }
        out
    }

    private fun queryMediaStore(): List<LocalSong> {
        val out = mutableListOf<LocalSong>()
        try {
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.DURATION
            )
            contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                MediaStore.Audio.Media.TITLE + " ASC"
            )?.use { cursor ->
                val idxId = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val idxTitle = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val idxArtist = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val idxData = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                val idxDur = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                while (cursor.moveToNext()) {
                    val path = cursor.getString(idxData) ?: continue
                    if (path.isBlank()) continue
                    out.add(
                        LocalSong(
                            rowId = cursor.getLong(idxId),
                            title = cursor.getString(idxTitle)?.takeIf { it.isNotBlank() }
                                ?: path.substringAfterLast('/'),
                            artist = cursor.getString(idxArtist)?.takeIf { it.isNotBlank() && it != "<unknown>" }
                                ?: "未知艺术家",
                            path = path,
                            durationMs = cursor.getLong(idxDur)
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "queryMediaStore failed", e)
        }
        return out
    }

    /** 音频扩展名 → 用于文件系统降级扫描 */
    private val audioExtensions = setOf("mp3", "flac", "m4a", "aac", "ogg", "wav", "wma", "ape")

    /** 对指定文件夹递归扫描音频（IO 线程），上限 500 首 */
    private suspend fun scanFolder(root: File): List<LocalSong> = withContext(Dispatchers.IO) {
        val out = mutableListOf<LocalSong>()
        val queue = ArrayDeque<File>().apply { addLast(root) }
        while (queue.isNotEmpty() && out.size < 500) {
            val dir = queue.removeFirst()
            val files = dir.listFiles() ?: continue
            for (f in files) {
                if (f.isDirectory) {
                    queue.addLast(f)
                } else if (f.extension.lowercase() in audioExtensions) {
                    out.add(
                        LocalSong(
                            rowId = f.absolutePath.hashCode().toLong(),
                            title = f.nameWithoutExtension,
                            artist = "未知艺术家",
                            path = f.absolutePath,
                            durationMs = 0L
                        )
                    )
                }
            }
        }
        Log.d(TAG, "scanFolder ${root.absolutePath}: ${out.size} songs")
        out.sortedBy { it.title.lowercase() }
    }

    // ════════════════════════════════════════════════════════
    //  文件夹选择器（NokiaOptionsDialog 目录浏览器）
    // ════════════════════════════════════════════════════════

    private fun prefs() = getSharedPreferences("local_music", MODE_PRIVATE)

    private fun loadPickedFolder(): File? {
        val path = prefs().getString(KEY_PICKED_FOLDER, null) ?: return null
        val f = File(path)
        return f.takeIf { it.exists() }
    }

    private fun savePickedFolder(f: File?) {
        prefs().edit().putString(KEY_PICKED_FOLDER, f?.absolutePath).apply()
    }

    private fun updatePickFolderSub() {
        tvPickFolderSub.text = pickedFolder?.absolutePath ?: "默认：内部存储常用目录"
    }

    // ══════════════════════════════════════════════════════
    //  已扫歌曲累加与持久化（跨文件夹合并，按路径去重）
    // ══════════════════════════════════════════════════════

    /** 合并新旧扫描结果：按文件路径去重，保留先扫到的元数据 */
    private fun mergeSongs(old: List<LocalSong>, new: List<LocalSong>): List<LocalSong> {
        val seen = old.mapTo(mutableSetOf()) { it.path }
        return old + new.filter { seen.add(it.path) }
    }

    private fun saveScannedSongs() {
        val json = com.google.gson.Gson().toJson(
            localSongs.map { listOf(it.title, it.artist, it.path, it.durationMs) }
        )
        prefs().edit().putString(KEY_SCANNED_SONGS, json).apply()
    }

    @Suppress("UNCHECKED_CAST")
    private fun loadScannedSongs(): List<LocalSong> {
        val json = prefs().getString(KEY_SCANNED_SONGS, null) ?: return emptyList()
        return try {
            val rows = com.google.gson.Gson()
                .fromJson(json, ArrayList::class.java) as? ArrayList<ArrayList<Any>> ?: return emptyList()
            rows.mapNotNull { row ->
                if (row.size < 3) return@mapNotNull null
                LocalSong(
                    rowId = (row[2] as String).hashCode().toLong(),
                    title = row[0] as String,
                    artist = row[1] as String,
                    path = row[2] as String,
                    durationMs = (row.getOrNull(3) as? Double)?.toLong() ?: 0L
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "loadScannedSongs failed", e)
            emptyList()
        }
    }

    private fun clearScannedSongs() {
        localSongs = emptyList()
        saveScannedSongs()
        renderSongs()
        focusHelper.setFocusIndex(1, true)
        Toast.makeText(this, "已清空本地歌曲列表", Toast.LENGTH_SHORT).show()
    }

    /** 调起系统文件夹选择器（ACTION_OPEN_DOCUMENT_TREE） */
    private fun showFolderPicker() {
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            startActivityForResult(intent, REQ_PICK_FOLDER)
        } catch (e: Exception) {
            Log.e(TAG, "no system folder picker", e)
            Toast.makeText(this, "无法打开系统文件夹选择器", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK_FOLDER || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        Log.d(TAG, "picked tree uri: $uri")
        val folder = treeUriToFile(uri)
        if (folder != null && folder.isDirectory) {
            pickedFolder = folder
            savePickedFolder(folder)
            updatePickFolderSub()
            Toast.makeText(this, "已选择：${folder.absolutePath}", Toast.LENGTH_SHORT).show()
            beginScan()
        } else {
            Toast.makeText(this, "仅支持内部存储（primary）目录，请重新选择", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 将系统选择器返回的 tree Uri（如 primary:Download/sub）
     * 映射为真实文件系统路径 /storage/emulated/0/Download/sub。
     * 仅支持主存储分区；SD 卡等二级存储返回 null。
     */
    private fun treeUriToFile(uri: android.net.Uri): File? {
        return try {
            val docId = android.provider.DocumentsContract.getTreeDocumentId(uri) ?: return null
            val parts = docId.split(":")
            if (parts.size < 2 || !parts[0].equals("primary", true)) return null
            File(android.os.Environment.getExternalStorageDirectory(), parts[1]).takeIf { it.exists() }
        } catch (e: Exception) {
            Log.e(TAG, "treeUriToFile failed", e)
            null
        }
    }

    /**
     * 文件系统降级扫描：遍历常用音乐目录（Music、Download、netease/cloudmusic），
     * 递归收集音频扩展名文件，最多扫 500 个防止极端情况卡死。
     */
    private fun scanFilesystem(): List<LocalSong> {
        val roots = listOfNotNull(
            getExternalFilesDir(Environment.DIRECTORY_MUSIC),
            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MUSIC),
            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
            java.io.File(android.os.Environment.getExternalStorageDirectory(), "netease/cloudmusic/Music")
        )
        val out = mutableListOf<LocalSong>()
        val queue = ArrayDeque<File>().apply { addAll(roots.filter { it.exists() }) }
        while (queue.isNotEmpty() && out.size < 500) {
            val dir = queue.removeFirst()
            val files = dir.listFiles() ?: continue
            for (f in files) {
                if (f.isDirectory) {
                    queue.addLast(f)
                } else if (f.extension.lowercase() in audioExtensions) {
                    out.add(
                        LocalSong(
                            rowId = f.absolutePath.hashCode().toLong(),
                            title = f.nameWithoutExtension,
                            artist = "未知艺术家",
                            path = f.absolutePath,
                            durationMs = 0L
                        )
                    )
                }
            }
        }
        return out.sortedBy { it.title.lowercase() }
}

    // ══════════════════════════════════════════════════════════
    //  填充歌曲列表（懒加载：每次只渲染一页，焦点到底部时追加）
    // ══════════════════════════════════════════════════════════
    private fun renderSongs() {
        llSongContainer.removeAllViews()
        songItemViews.clear()
        renderedCount = 0
        appendSongs(PAGE_SIZE)
        NokiaFontManager.applyToViewTree(llSongContainer)
        focusHelper.setItems(getFocusableViews())
    }

    private fun appendSongs(count: Int) {
        val from = renderedCount
        val to = minOf(from + count, localSongs.size)

        for (idx in from until to) {
            val song = localSongs[idx]
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

            itemView.findViewById<TextView>(R.id.tv_song_index).text = (idx + 1).toString()
            itemView.findViewById<TextView>(R.id.tv_song_title).text = song.title
            itemView.findViewById<TextView>(R.id.icon_playing).visibility = View.GONE
            itemView.findViewById<TextView>(R.id.tv_song_artist).text =
                "${song.artist} · ${formatDuration(song.durationMs)}"

            llSongContainer.addView(itemView)
            songItemViews.add(itemView)
        }
        renderedCount = to
        NokiaFontManager.applyToViewTree(llSongContainer)
    }

    private fun formatDuration(ms: Long): String {
        if (ms <= 0) return "--:--"
        return "%d:%02d".format(ms / 60000, (ms % 60000) / 1000)
    }

    // ══════════════════════════════════════════════════════════
    //  焦点渲染
    // ══════════════════════════════════════════════════════════
    private fun getFocusableViews(): List<View> = listOf(llPickFolder, llScanBar) + songItemViews

    private fun applyFocus() {
        getFocusableViews().forEachIndexed { i, view ->
            if (view is LinearLayout) {
                if (i == focusIdx) {
                    view.background = MusicTheme.createFocusDrawable(this, 4f)
                    setChildTextColors(view, true)
                    // 规范要求（NOKIA_DEVELOPMENT_RULES.md）：焦点移动必须 requestFocus()
                    view.requestFocus()
                } else {
                    view.setBackgroundColor(Color.TRANSPARENT)
                    if (view == llPickFolder || view == llScanBar) {
                        view.setBackgroundColor(Color.parseColor("#40000000"))
                    }
                    setChildTextColors(view, false)
                }
            }
        }
    }

    private fun setChildTextColors(parent: LinearLayout, focused: Boolean) {
        val mainColor = colorWhite
        val subColor = if (focused) colorSubtextFocused else colorSubtext
        val iconColor = if (focused) colorWhite else colorAccent

        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            when (child) {
                is TextView -> {
                    if (child.typeface == NokiaIcons.getTypeface(this)) {
                        child.setTextColor(iconColor)
                    } else {
                        child.setTextColor(mainColor)
                    }
                }
                is LinearLayout -> {
                    for (j in 0 until child.childCount) {
                        val grandChild = child.getChildAt(j)
                        if (grandChild is TextView) {
                            if (grandChild.typeface == NokiaIcons.getTypeface(this)) {
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
    //  按键处理：0 = 选文件夹, 1 = 扫描行, 2..N+1 = 歌曲
    // ══════════════════════════════════════════════════════════
    override fun onAction(action: Int): Boolean {
        Log.d(TAG, "onAction=$action focusIdx=$focusIdx songs=${localSongs.size}")
        return when (action) {
            NokiaKeyAction.UP -> {
                focusHelper.onDirection(action)
                true
            }
            NokiaKeyAction.DOWN -> {
                // 懒加载：焦点接近已渲染末尾时追加下一页
                if (renderedCount < localSongs.size && focusHelper.focusIndex >= renderedCount + 1) {
                    appendSongs(PAGE_SIZE)
                    focusHelper.setItems(getFocusableViews())
                    Log.d(TAG, "lazy load: rendered $renderedCount/${localSongs.size}")
                }
                focusHelper.onDirection(action)
                true
            }
            NokiaKeyAction.SELECT -> {
                when (focusHelper.focusIndex) {
                    0 -> showFolderPicker()
                    1 -> beginScan()
                    else -> {
                        val songIdx = focusHelper.focusIndex - 2
                        if (songIdx in localSongs.indices) playSong(songIdx)
                    }
                }
                true
            }
            NokiaKeyAction.SOFT_LEFT -> {
                showOptionsMenu()
                true
            }
            NokiaKeyAction.SOFT_RIGHT -> { finish(); true }
            else -> super.onAction(action)
        }
    }

    // ══════════════════════════════════════════════════════════
    //  播放
    // ══════════════════════════════════════════════════════════
    private fun playSong(startIndex: Int) {
        if (localSongs.isEmpty()) return
        // LocalSong → SongItem（localPath 标记本地播放），id 用负数避免与网易云 id 冲突
        val queue: List<SongItem> = localSongs.map { s ->
            SongItem(
                id = -s.rowId,
                name = s.title,
                artists = listOf(ArtistItem(name = s.artist)),
                album = AlbumItem(name = null, picUrl = null),
                duration = s.durationMs,
                fee = 0,
                noCopyright = false,
                localPath = s.path
            )
        }
        val safeIndex = startIndex.coerceIn(0, queue.lastIndex)

        PlaybackStateManager.setPersonalFm(false)  // 本地播放，退出 FM 模式
        PlaybackStateManager.updatePlaylist(queue, safeIndex)
        val playIntent = Intent(this, PlaybackService::class.java).apply {
            action = PlaybackService.ACTION_PLAY_INDEX
            putExtra(PlaybackService.EXTRA_INDEX, safeIndex)
        }
        startService(playIntent)

        MusicPlayerActivity.start(this)
    }

    private fun showOptionsMenu() {
        val dialog = NokiaOptionsDialog(this, getString(R.string.softkey_options))
        val actions = mutableListOf<() -> Unit>()
        val iconColor = Color.WHITE
        val iconSize = dp(18)

        dialog.addItem(1, getString(R.string.local_menu_rescan), NokiaIcons.createDrawable(this, NokiaIcons.ICON_REFRESH, iconSize, iconColor))
        actions.add { beginScan() }

        dialog.addItem(2, getString(R.string.local_pick_folder), NokiaIcons.createDrawable(this, NokiaIcons.ICON_FOLDER, iconSize, iconColor))
        actions.add { showFolderPicker() }

        if (focusHelper.focusIndex >= 2) {
            val songIdx = focusHelper.focusIndex - 2
            if (songIdx in localSongs.indices) {
                val song = localSongs[songIdx]
                dialog.addItem(actions.size + 1, "删除歌曲文件", NokiaIcons.createDrawable(this, NokiaIcons.ICON_DELETE, iconSize, iconColor))
                actions.add {
                    val confirm = NokiaConfirmDialog(this, "删除本地歌曲", "确定要删除「${song.title}」及其本地文件吗？")
                    confirm.setPositiveButton("删除") {
                        val file = File(song.path)
                        if (file.exists()) {
                            file.delete()
                        }
                        // 同步删除同名 lrc 歌词
                        val lrcFile = File(file.parentFile, "${file.nameWithoutExtension}.lrc")
                        if (lrcFile.exists()) {
                            lrcFile.delete()
                        }
                        // 如果是下载任务库中的，一并从下载管理中清理
                        DownloadManager.deleteDownloadByPath(song.path)

                        val updated = localSongs.toMutableList()
                        updated.removeAt(songIdx)
                        localSongs = updated
                        saveScannedSongs()
                        renderSongs()
                        focusHelper.setFocusIndex(focusHelper.focusIndex.coerceAtMost(getFocusableViews().size - 1), true)
                        Toast.makeText(this, "已删除本地文件", Toast.LENGTH_SHORT).show()
                    }
                    confirm.show()
                }
            }
        }

        if (localSongs.isNotEmpty()) {
            dialog.addItem(actions.size + 1, "清空列表", NokiaIcons.createDrawable(this, NokiaIcons.ICON_DELETE, iconSize, iconColor))
            actions.add { clearScannedSongs() }
        }

        if (pickedFolder != null) {
            dialog.addItem(3, getString(R.string.local_menu_clear_folder), NokiaIcons.createDrawable(this, NokiaIcons.ICON_CLOSE, iconSize, iconColor))
            actions.add {
                pickedFolder = null
                savePickedFolder(null)
                updatePickFolderSub()
                Toast.makeText(this, "已恢复默认扫描范围", Toast.LENGTH_SHORT).show()
                beginScan()
            }
        }

        dialog.setOnOptionSelectedListener { index, _ ->
            if (index in actions.indices) actions[index].invoke()
        }
        dialog.show()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()
}
