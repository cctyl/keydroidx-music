package io.github.cctyl.keydroidx.music.util

import io.github.cctyl.nokia.common.log.KeydroidxLog

/**
 * 音乐播放器全局统一日志门面。
 * 转发至 `keydroidx-core` 的 [KeydroidxLog]，同时输出 Logcat 与本地日志文件。
 * 本地日志落盘级别由 [KeydroidxLog.isDetailedLogEnabled] 动态控制。
 *
 * 可以通过 `import io.github.cctyl.keydroidx.music.util.NLog as Log` 实现全文件零改造桥接。
 */
object NLog {
    @JvmStatic
    fun v(tag: String, msg: String): Int {
        KeydroidxLog.v(tag, msg)
        return 0
    }

    @JvmStatic
    fun v(tag: String, msg: String, tr: Throwable?): Int {
        KeydroidxLog.v(tag, if (tr != null) "$msg\n" + android.util.Log.getStackTraceString(tr) else msg)
        return 0
    }

    @JvmStatic
    fun d(tag: String, msg: String): Int {
        KeydroidxLog.d(tag, msg)
        return 0
    }

    @JvmStatic
    fun d(tag: String, msg: String, tr: Throwable?): Int {
        KeydroidxLog.d(tag, if (tr != null) "$msg\n" + android.util.Log.getStackTraceString(tr) else msg)
        return 0
    }

    @JvmStatic
    fun i(tag: String, msg: String): Int {
        KeydroidxLog.i(tag, msg)
        return 0
    }

    @JvmStatic
    fun i(tag: String, msg: String, tr: Throwable?): Int {
        KeydroidxLog.i(tag, if (tr != null) "$msg\n" + android.util.Log.getStackTraceString(tr) else msg)
        return 0
    }

    @JvmStatic
    fun w(tag: String, msg: String): Int {
        KeydroidxLog.w(tag, msg)
        return 0
    }

    @JvmStatic
    fun w(tag: String, tr: Throwable?): Int {
        KeydroidxLog.w(tag, "", tr)
        return 0
    }

    @JvmStatic
    fun w(tag: String, msg: String, tr: Throwable?): Int {
        KeydroidxLog.w(tag, msg, tr)
        return 0
    }

    @JvmStatic
    fun e(tag: String, msg: String): Int {
        KeydroidxLog.e(tag, msg)
        return 0
    }

    @JvmStatic
    fun e(tag: String, msg: String, tr: Throwable?): Int {
        KeydroidxLog.e(tag, msg, tr)
        return 0
    }
}
