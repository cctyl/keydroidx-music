package io.github.cctyl.keydroidx.music.download

import android.content.Context
import android.os.Environment
import io.github.cctyl.keydroidx.music.util.NLog as Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.github.cctyl.keydroidx.music.model.MusicItem
import io.github.cctyl.keydroidx.music.network.RetrofitClient
import io.github.cctyl.keydroidx.music.network.model.SongItem
import io.github.cctyl.keydroidx.music.player.SongUrlFetcher
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 全局下载管理器
 * 负责音频与歌词的后台异步下载、持久化任务状态、断点续传/暂停/重试/物理删除等管理
 */
object DownloadManager {
    private const val TAG = "DownloadManager"
    private const val PREFS_NAME = "download_tasks_prefs"
    private const val KEY_TASKS = "tasks_json"

    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var appContext: Context? = null
    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

    // 活跃下载作业管理
    private val runningJobs = ConcurrentHashMap<Long, Job>()

    // 下载专用 OkHttpClient
    private val downloadHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun init(context: Context) {
        appContext = context.applicationContext
        loadPersistedTasks()
        // 恢复未完成状态：如果上次退出时是在下载中，置为暂停或等待状态
        val updated = _tasks.value.map { task ->
            if (task.status == DownloadStatus.DOWNLOADING) {
                task.copy(status = DownloadStatus.PAUSED)
            } else {
                task
            }
        }
        _tasks.value = updated
        saveTasks()
    }

    /**
     * 获取下载存储目录
     */
    fun getDownloadDir(): File {
        val context = appContext ?: error("DownloadManager not initialized")
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
            ?: File(context.filesDir, "Music")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * 清洗文件名非法字符
     */
    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
    }

    /**
     * 根据歌曲信息生成目标音频与歌词文件路径
     */
    fun getTargetAudioFile(songId: Long, artist: String, title: String): File {
        val safeArtist = sanitizeFileName(if (artist.isBlank()) "未知歌手" else artist)
        val safeTitle = sanitizeFileName(if (title.isBlank()) "未知歌曲" else title)
        return File(getDownloadDir(), "$safeArtist - $safeTitle [$songId].mp3")
    }

    fun getTargetLyricFile(songId: Long, artist: String, title: String): File {
        val safeArtist = sanitizeFileName(if (artist.isBlank()) "未知歌手" else artist)
        val safeTitle = sanitizeFileName(if (title.isBlank()) "未知歌曲" else title)
        return File(getDownloadDir(), "$safeArtist - $safeTitle [$songId].lrc")
    }

    /**
     * 查询某首歌曲是否已下载完成且物理文件存在
     */
    fun isDownloaded(songId: Long): Boolean {
        val task = _tasks.value.firstOrNull { it.songId == songId } ?: return false
        if (task.status == DownloadStatus.COMPLETED && task.audioPath != null) {
            return File(task.audioPath!!).exists()
        }
        return false
    }

    /**
     * 获取已下载完成的任务实体（供离线播放读取本地文件）
     */
    fun getDownloadedSong(songId: Long): DownloadTask? {
        val task = _tasks.value.firstOrNull { it.songId == songId } ?: return null
        if (task.status == DownloadStatus.COMPLETED && task.audioPath != null && File(task.audioPath!!).exists()) {
            return task
        }
        return null
    }

    /**
     * 获取指定歌曲的当前下载任务
     */
    fun getTask(songId: Long): DownloadTask? {
        return _tasks.value.firstOrNull { it.songId == songId }
    }

    /**
     * 加入下载队列 (重载方法支持 SongItem)
     */
    fun enqueueDownload(song: SongItem) {
        enqueueDownload(
            songId = song.id,
            title = song.name,
            artist = song.artistName,
            album = song.album?.name ?: "",
            durationMs = song.duration ?: 0L,
            coverUrl = song.album?.picUrl,
            fee = song.fee ?: 0
        )
    }

    /**
     * 加入下载队列
     */
    fun enqueueDownload(
        songId: Long,
        title: String,
        artist: String,
        album: String = "",
        durationMs: Long = 0,
        coverUrl: String? = null,
        fee: Int = 0
    ) {
        val existing = _tasks.value.firstOrNull { it.songId == songId }
        if (existing != null) {
            if (existing.status == DownloadStatus.COMPLETED && existing.audioPath != null && File(existing.audioPath!!).exists()) {
                Log.d(TAG, "Song $songId already downloaded")
                return
            }
            // 重新开始
            existing.status = DownloadStatus.PENDING
            existing.errorMessage = null
            notifyTasksChanged()
            startDownloadInternal(existing)
            return
        }

        val newTask = DownloadTask(
            songId = songId,
            title = title,
            artist = artist,
            album = album,
            durationMs = durationMs,
            coverUrl = coverUrl,
            fee = fee,
            status = DownloadStatus.PENDING
        )
        val list = _tasks.value.toMutableList()
        list.add(0, newTask) // 最新加到最前
        _tasks.value = list
        saveTasks()

        startDownloadInternal(newTask)
    }

    /**
     * 暂停任务
     */
    fun pauseDownload(songId: Long) {
        runningJobs[songId]?.cancel()
        runningJobs.remove(songId)
        updateTask(songId) { it.status = DownloadStatus.PAUSED }
    }

    /**
     * 恢复/继续下载
     */
    fun resumeDownload(songId: Long) {
        val task = _tasks.value.firstOrNull { it.songId == songId } ?: return
        if (task.status == DownloadStatus.PAUSED || task.status == DownloadStatus.FAILED) {
            task.status = DownloadStatus.PENDING
            task.errorMessage = null
            notifyTasksChanged()
            startDownloadInternal(task)
        }
    }

    /**
     * 全部开始
     */
    fun resumeAll() {
        _tasks.value.forEach { task ->
            if (task.status == DownloadStatus.PAUSED || task.status == DownloadStatus.FAILED) {
                resumeDownload(task.songId)
            }
        }
    }

    /**
     * 全部暂停
     */
    fun pauseAll() {
        _tasks.value.forEach { task ->
            if (task.status == DownloadStatus.DOWNLOADING || task.status == DownloadStatus.PENDING) {
                pauseDownload(task.songId)
            }
        }
    }

    /**
     * 删除下载（取消下载 + 删除本地音频与歌词物理文件 + 移除记录）
     */
    fun deleteDownload(songId: Long) {
        // 取消协程
        runningJobs[songId]?.cancel()
        runningJobs.remove(songId)

        val task = _tasks.value.firstOrNull { it.songId == songId }
        if (task != null) {
            // 物理删除音频
            val audioP = task.audioPath
            if (audioP != null) {
                try {
                    val f = File(audioP)
                    if (f.exists()) {
                        f.delete()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to delete audio file: $audioP", e)
                }
            }
            // 物理删除歌词
            val lyricP = task.lyricPath
            if (lyricP != null) {
                try {
                    val f = File(lyricP)
                    if (f.exists()) {
                        f.delete()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to delete lyric file: $lyricP", e)
                }
            }
            // 备用按名称匹配删除
            try {
                val targetAudio = getTargetAudioFile(task.songId, task.artist, task.title)
                if (targetAudio.exists()) {
                    targetAudio.delete()
                }
                Log.d(TAG, "Audio file cleaned")
            } catch (e: Exception) {
                Log.w(TAG, "Clean audio error", e)
            }
            try {
                val targetLrc = getTargetLyricFile(task.songId, task.artist, task.title)
                if (targetLrc.exists()) {
                    targetLrc.delete()
                }
                Log.d(TAG, "Lyric file cleaned")
            } catch (e: Exception) {
                Log.w(TAG, "Clean lyric error", e)
            }
        }

        val list = _tasks.value.toMutableList()
        list.removeAll { it.songId == songId }
        _tasks.value = list
        saveTasks()
    }

    /**
     * 根据本地路径删除下载记录及物理文件
     */
    fun deleteDownloadByPath(filePath: String) {
        val task = _tasks.value.firstOrNull { it.audioPath == filePath }
        if (task != null) {
            deleteDownload(task.songId)
        }
    }

    /**
     * 获取所有已下载完成且本地文件依然存在的本地歌曲列表（供本地音乐页使用）
     */
    fun getDownloadedLocalSongs(): List<SongItem> {
        val completed = _tasks.value.filter {
            it.status == DownloadStatus.COMPLETED &&
            it.audioPath != null &&
            File(it.audioPath!!).exists()
        }
        return completed.map { it.toSongItem() }
    }

    /**
     * 清空所有已完成任务（仅清空记录还是连带文件？支持连带删除）
     */
    fun clearCompleted(deleteFiles: Boolean = true) {
        val completed = _tasks.value.filter { it.status == DownloadStatus.COMPLETED }
        if (deleteFiles) {
            completed.forEach { task ->
                deleteDownload(task.songId)
            }
        } else {
            val list = _tasks.value.toMutableList()
            list.removeAll { it.status == DownloadStatus.COMPLETED }
            _tasks.value = list
            saveTasks()
        }
    }

    private fun startDownloadInternal(task: DownloadTask) {
        val songId = task.songId
        runningJobs[songId]?.cancel()

        val job = scope.launch {
            try {
                updateTask(songId) {
                    it.status = DownloadStatus.DOWNLOADING
                    it.progress = 0
                    it.errorMessage = null
                }

                // 1. 获取真实播放链接
                Log.d(TAG, "Fetching song url for songId=$songId...")
                val songUrlResult = SongUrlFetcher.fetch(songId)
                val songUrl = songUrlResult.url
                if (songUrl.isNullOrEmpty() || songUrl == "null") {
                    throw IllegalStateException("获取音频链接失败（可能受版权保护或需VIP）")
                }
                Log.d(TAG, "Got url for songId=$songId: $songUrl")

                // 2. 准备文件路径
                val targetAudioFile = getTargetAudioFile(songId, task.artist, task.title)
                val targetAudioParent = targetAudioFile.parentFile ?: getDownloadDir()
                val tempAudioFile = File(targetAudioParent, "${targetAudioFile.name}.downloading")
                if (tempAudioFile.exists()) tempAudioFile.delete()

                // 3. 下载音频文件（带上网易云 UA 与 Referer，防止被防盗链拦截为风控 JSON）
                val request = Request.Builder()
                    .url(songUrl.toString())
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .header("Referer", "https://music.163.com/")
                    .build()
                val response = downloadHttpClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    throw IllegalStateException("下载音频失败: HTTP ${response.code}")
                }
                val body = response.body ?: throw IllegalStateException("响应体为空")
                val totalLength = body.contentLength()
                updateTask(songId) {
                    it.totalBytes = totalLength
                }

                var downloaded: Long = 0
                val buffer = ByteArray(8192)
                var lastProgress = 0
                var lastNotifyTime = System.currentTimeMillis()

                val inputStream: InputStream = body.byteStream()
                val outputStream = FileOutputStream(tempAudioFile)

                try {
                    var read: Int
                    while (inputStream.read(buffer).also { read = it } != -1) {
                        if (!isActive) {
                            outputStream.close()
                            inputStream.close()
                            tempAudioFile.delete()
                            return@launch
                        }
                        outputStream.write(buffer, 0, read)
                        downloaded += read

                        val progress = if (totalLength > 0) ((downloaded * 100) / totalLength).toInt() else 0
                        val now = System.currentTimeMillis()
                        // 节流刷新：进度改变或超过 300ms
                        if (progress != lastProgress || now - lastNotifyTime > 300) {
                            lastProgress = progress
                            lastNotifyTime = now
                            updateTask(songId) {
                                it.progress = progress
                                it.downloadedBytes = downloaded
                            }
                        }
                    }
                    outputStream.flush()
                } finally {
                    try { outputStream.close() } catch (_: Exception) {}
                    try { inputStream.close() } catch (_: Exception) {}
                }

                if (targetAudioFile.exists()) targetAudioFile.delete()
                if (!tempAudioFile.renameTo(targetAudioFile)) {
                    // 如果重命名失败，尝试拷贝
                    tempAudioFile.copyTo(targetAudioFile, overwrite = true)
                    tempAudioFile.delete()
                }

                // 校验下载得到的文件是否有效（大于 50KB，防止防盗链或空文件）
                val fileSize = targetAudioFile.length()
                if (fileSize < 1024 * 50) {
                    targetAudioFile.delete()
                    throw IllegalStateException("下载文件异常或受网络风控限制(大小仅${fileSize}B)")
                }

                // 4. 下载歌词文件
                var lyricSavedPath: String? = null
                try {
                    val lyricResp = RetrofitClient.api.getLyric(songId)
                    val lyricText = lyricResp.lrc?.lyric
                    if (!lyricText.isNullOrBlank()) {
                        val targetLrcFile = getTargetLyricFile(songId, task.artist, task.title)
                        targetLrcFile.writeText(lyricText, Charsets.UTF_8)
                        lyricSavedPath = targetLrcFile.absolutePath
                        Log.d(TAG, "Lyric saved to: ${targetLrcFile.absolutePath}")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Download lyric failed for songId=$songId", e)
                }

                // 5. 标记完成
                updateTask(songId) {
                    it.status = DownloadStatus.COMPLETED
                    it.progress = 100
                    it.audioPath = targetAudioFile.absolutePath
                    it.lyricPath = lyricSavedPath
                    it.finishTime = System.currentTimeMillis()
                    it.downloadedBytes = targetAudioFile.length()
                    it.totalBytes = targetAudioFile.length()
                }
                Log.d(TAG, "Song download completed successfully: ${targetAudioFile.absolutePath}")

            } catch (e: CancellationException) {
                Log.d(TAG, "Download cancelled for songId=$songId")
            } catch (e: Exception) {
                Log.e(TAG, "Download failed for songId=$songId", e)
                updateTask(songId) {
                    it.status = DownloadStatus.FAILED
                    it.errorMessage = e.message ?: "下载失败"
                }
            } finally {
                runningJobs.remove(songId)
            }
        }
        runningJobs[songId] = job
    }

    private fun updateTask(songId: Long, block: (DownloadTask) -> Unit) {
        val list = _tasks.value.toMutableList()
        val index = list.indexOfFirst { it.songId == songId }
        if (index >= 0) {
            val updated = list[index].copy()
            block(updated)
            list[index] = updated
            _tasks.value = list
            saveTasks()
        }
    }

    private fun notifyTasksChanged() {
        _tasks.value = _tasks.value.toList()
        saveTasks()
    }

    private fun saveTasks() {
        val context = appContext ?: return
        try {
            val json = gson.toJson(_tasks.value)
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_TASKS, json)
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "Save tasks error", e)
        }
    }

    private fun loadPersistedTasks() {
        val context = appContext ?: return
        try {
            val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val json = sp.getString(KEY_TASKS, null)
            if (!json.isNullOrBlank()) {
                val type = object : TypeToken<List<DownloadTask>>() {}.type
                val loaded: List<DownloadTask> = gson.fromJson(json, type) ?: emptyList()
                _tasks.value = loaded
            }
        } catch (e: Exception) {
            Log.e(TAG, "Load tasks error", e)
        }
    }
}
