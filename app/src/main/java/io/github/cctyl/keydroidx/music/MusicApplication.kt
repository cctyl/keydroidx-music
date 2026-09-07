package io.github.cctyl.keydroidx.music

import android.app.Application
import androidx.multidex.MultiDex
import io.github.cctyl.keydroidx.music.download.DownloadManager
import io.github.cctyl.keydroidx.music.library.FavoriteStore
import io.github.cctyl.keydroidx.music.library.LibraryManager
import io.github.cctyl.keydroidx.music.library.SearchHistoryManager
import io.github.cctyl.keydroidx.music.network.RetrofitClient
import io.github.cctyl.keydroidx.music.player.PlaybackStateManager
import io.github.cctyl.keydroidx.music.warmup.AppWarmup
import io.github.cctyl.nokia.common.feedback.KeydroidxFeedback
import io.github.cctyl.nokia.common.feedback.KeydroidxFeedbackConfig
import io.github.cctyl.nokia.common.feedback.KeydroidxInstall
import io.github.cctyl.nokia.common.log.KeydroidxLog

class MusicApplication : Application() {
    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(base)
        MultiDex.install(base)
    }

    override fun onCreate() {
        super.onCreate()
        // 尽早初始化 KeydroidxClient（建立 Provider 同步与 ThemeProvider 注册）
        io.github.cctyl.nokia.keycore.KeydroidxClient.get(this)

        // 统一日志：尽早初始化文件日志 + 崩溃捕获，覆盖冷启动阶段的崩溃。
        // 日志落盘到 Android/data/<包名>/files/log/yyyyMMdd.log，与反馈模块读取目录一致。
        // 根据持久化设置自动决定等级（Debug 默认全量 DEBUG，Release 默认仅 ERROR）。
        KeydroidxLog.setTag("KeydroidX-Music")
        KeydroidxLog.init(this)
        KeydroidxLog.installCrashHandler(this)
        KeydroidxLog.i("App", "MusicApplication onCreate, initializing managers and warmup")
        LibraryManager.init(this)
        // 收藏唯一事实源：必须在 LibraryManager 之后（读得到旧 fav_songs 做迁移）、
        // AppWarmup 之前（预拉依赖已装载的 cookie）初始化
        FavoriteStore.init(this)
        DownloadManager.init(this)
        SearchHistoryManager.init(this)
        // 初始化播放状态广播 Context（供 Widget/Provider 监听刷新）
        PlaybackStateManager.initBroadcastContext(this)
        // 把持久化的 cookie 装载进运行时 RetrofitClient
        RetrofitClient.init(this)
        AppWarmup.startWarmup(this)

        // 初始化意见反馈 + 安装统计组件（共用同一份配置）
        KeydroidxFeedback.init(
            KeydroidxFeedbackConfig(
                BuildConfig.FEEDBACK_UPLOAD_URL,
                BuildConfig.FEEDBACK_INSTALL_URL,
                BuildConfig.FEEDBACK_SECRET_KEY,
                "KeydroidX-Music",
                BuildConfig.VERSION_NAME,
                null
            )
        )
        // 首次安装 / 版本升级时自动上报一次设备信息（后台、幂等、静默）
        KeydroidxInstall.reportOnce(this)
    }
}
