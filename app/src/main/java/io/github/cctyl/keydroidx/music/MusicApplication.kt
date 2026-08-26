package io.github.cctyl.keydroidx.music

import android.app.Application
import io.github.cctyl.keydroidx.music.download.DownloadManager
import io.github.cctyl.keydroidx.music.library.LibraryManager
import io.github.cctyl.keydroidx.music.library.SearchHistoryManager
import io.github.cctyl.keydroidx.music.network.RetrofitClient
import io.github.cctyl.keydroidx.music.player.PlaybackStateManager
import io.github.cctyl.keydroidx.music.warmup.AppWarmup
import io.github.cctyl.nokia.keycore.feedback.NokiaFeedback
import io.github.cctyl.nokia.keycore.feedback.NokiaFeedbackConfig
import io.github.cctyl.nokia.keycore.log.NokiaLog

class MusicApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // 统一日志：尽早初始化文件日志 + 崩溃捕获，覆盖冷启动阶段的崩溃。
        // 日志落盘到 Android/data/<包名>/log/yyyyMMdd.log，与反馈模块读取目录一致。
        NokiaLog.setTag("KeydroidX-Music")
        NokiaLog.init(this)
        NokiaLog.installCrashHandler(this)
        // 启动 logcat 持续捕获：自动把现有 Log.d/e/i/w 落盘，无需逐个替换。
        // 过滤本进程 + 系统 Error，避免无关日志过多。需 READ_LOGS 权限。
        NokiaLog.startLogcatCapture(this, "*:I")
        NokiaLog.i("App", "MusicApplication onCreate, initializing managers and warmup")
        LibraryManager.init(this)
        DownloadManager.init(this)
        SearchHistoryManager.init(this)
        // 初始化播放状态广播 Context（供 Widget/Provider 监听刷新）
        PlaybackStateManager.initBroadcastContext(this)
        // 把持久化的 cookie 装载进运行时 RetrofitClient
        RetrofitClient.init(this)
        AppWarmup.startWarmup(this)

        // 初始化意见反馈组件
        NokiaFeedback.init(
            NokiaFeedbackConfig(
                BuildConfig.KDFB_SERVER_HOST,
                BuildConfig.KDFB_SERVER_PORT,
                BuildConfig.KDFB_PRIVATE_KEY,
                "KeydroidX-Music",
                BuildConfig.VERSION_NAME,
                null
            )
        )
    }
}
