package io.github.cctyl.keydroidx.music.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import io.github.cctyl.keydroidx.music.R
import io.github.cctyl.keydroidx.music.player.PlaybackStateManager
import io.github.cctyl.keydroidx.music.ui.MusicPlayerActivity

/**
 * 音乐播放小组件（系统 AppWidget）。
 * 显示当前播放歌曲、歌手、当前歌词行与播放进度。
 * 点击组件进入正在播放详情页。
 */
class MusicAppWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) {
            updateWidget(context, appWidgetManager, id)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        // 监听播放状态变化广播，刷新所有组件
        if (intent.action == PlaybackStateManager.ACTION_PLAYBACK_CHANGED) {
            refreshAllWidgets(context)
        }
    }

    private fun refreshAllWidgets(context: Context) {
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val componentName = ComponentName(context, MusicAppWidgetProvider::class.java)
        val ids = appWidgetManager.getAppWidgetIds(componentName)
        for (id in ids) {
            updateWidget(context, appWidgetManager, id)
        }
    }

    private fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
        val views = RemoteViews(context.packageName, R.layout.widget_music_player)

        val song = PlaybackStateManager.currentSong.value
        val isPlaying = PlaybackStateManager.isPlaying.value
        val position = PlaybackStateManager.currentPositionMs.value
        val duration = PlaybackStateManager.durationMs.value
        val lyricLine = PlaybackStateManager.getCurrentLyricLineSync()

        // 标题/歌手
        val title = if (song != null) {
            val artist = song.artistName
            if (artist.isNotEmpty()) "${song.name} - $artist" else song.name
        } else {
            "KeydroidX 音乐"
        }
        views.setTextViewText(R.id.widget_title, title)

        // 播放状态
        views.setTextViewText(R.id.widget_playing_state, if (isPlaying) "▶" else "‖")

        // 歌词行
        views.setTextViewText(
            R.id.widget_lyric,
            if (!lyricLine.isNullOrEmpty()) lyricLine
            else if (song != null) "暂无歌词"
            else "未在播放，点击打开音乐"
        )

        // 进度条（0-1000）
        val progress = if (duration > 0) {
            ((position.toFloat() / duration.toFloat()) * 1000).toInt().coerceIn(0, 1000)
        } else 0
        views.setProgressBar(R.id.widget_progress, 1000, progress, false)

        // 点击进入播放详情页
        val openIntent = Intent(context, MusicPlayerActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_root, pending)

        appWidgetManager.updateAppWidget(appWidgetId, views)
    }
}
