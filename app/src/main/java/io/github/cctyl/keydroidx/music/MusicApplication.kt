package io.github.cctyl.keydroidx.music

import android.app.Application
import android.util.Log
import io.github.cctyl.keydroidx.music.download.DownloadManager
import io.github.cctyl.keydroidx.music.library.LibraryManager
import io.github.cctyl.keydroidx.music.library.SearchHistoryManager
import io.github.cctyl.keydroidx.music.network.RetrofitClient
import io.github.cctyl.keydroidx.music.warmup.AppWarmup

class MusicApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Log.i("MusicApplication", "MusicApplication onCreate, initializing managers and warmup")
        LibraryManager.init(this)
        DownloadManager.init(this)
        SearchHistoryManager.init(this)
        // 把持久化的 cookie 装载进运行时 RetrofitClient
        RetrofitClient.init(this)
        AppWarmup.startWarmup(this)
    }
}
