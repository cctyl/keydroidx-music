package io.github.cctyl.keydroidx.music

import android.app.Application
import android.util.Log
import io.github.cctyl.keydroidx.music.library.LibraryManager
import io.github.cctyl.keydroidx.music.library.SearchHistoryManager
import io.github.cctyl.keydroidx.music.warmup.AppWarmup

class MusicApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Log.i("MusicApplication", "MusicApplication onCreate, initializing managers and warmup")
        LibraryManager.init(this)
        SearchHistoryManager.init(this)
        AppWarmup.startWarmup(this)
    }
}
