package io.github.cctyl.keydroidx.music.player

import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.cctyl.keydroidx.music.R
import io.github.cctyl.keydroidx.music.ui.LyricLockScreenActivity
import io.github.cctyl.keydroidx.music.util.NLog as Log

/**
 * 锁屏歌词触发器：让歌词页在「锁屏后被唤醒」时自动出现。
 *
 * ### 触发时机为什么是「屏幕点亮」而不是「熄屏」
 * 熄屏瞬间把界面拉起来，等于刚灭屏就立刻点亮，既耗电也和用户的锁屏动作相对抗；
 * 用户真正想看歌词的时刻，是按键唤醒屏幕之后。因此在 `ACTION_SCREEN_ON` 时判断：
 * 正在播放 + 当前处于锁屏态 → 把歌词锁屏页顶到最前。
 *
 * ### 为什么要分两条拉起路径
 * Android 10（API 29）起禁止应用在后台启动 Activity，直接 `startActivity` 会被系统丢弃
 * （日志为 "Background activity start ... blocked"）。官方留的合法通道是**全屏意图通知**：
 * 锁屏态下系统会把该 PendingIntent 的界面直接全屏拉起，且不受后台启动限制。因此：
 *   · API ≥ 29 → 全屏意图通知
 *   · API < 29 → 直接 startActivity（低版本无限制，更直接）
 *
 * ### 宿主选择
 * 注册在 `MusicApplication`（进程级）而非 `PlaybackService`：
 * `PlaybackStateManager` 与 Service 同进程，播放时进程必然存活；
 * 进程级注册既避免 Service 反复重建时的注册/反注册抖动，也不依赖 Service 生命周期。
 */
object LockScreenLyricTrigger {

    private const val TAG = "LockScreenLyric"

    /** 屏幕点亮后等锁屏界面稳定再判断的延时，避开与 Keyguard 收放的竞态 */
    private const val SCREEN_ON_SETTLE_MS = 400L

    /** 全屏意图通知的渠道与 id */
    private const val CHANNEL_ID = "keydroidx_music_lock_lyric_channel"
    private const val NOTIFICATION_ID = 1002

    private val handler = Handler(Looper.getMainLooper())
    private var registered = false

    private val screenOnReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_ON) {
                handler.postDelayed({ maybeShow(context) }, SCREEN_ON_SETTLE_MS)
            }
        }
    }

    /** 在 Application.onCreate 调用一次即可，进程存活期间一直有效。 */
    fun register(context: Context) {
        if (registered) return
        val appContext = context.applicationContext
        try {
            ContextCompat.registerReceiver(
                appContext,
                screenOnReceiver,
                IntentFilter(Intent.ACTION_SCREEN_ON),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            registered = true
            Log.d(TAG, "screen-on receiver registered")
        } catch (e: Exception) {
            Log.w(TAG, "register screen-on receiver failed: ${e.message}")
        }
    }

    /** 歌词页进入后清掉全屏意图通知，避免高重要度通知残留在通知栏。 */
    fun cancelNotification(context: Context) {
        try {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
                ?.cancel(NOTIFICATION_ID)
        } catch (e: Exception) {
            Log.w(TAG, "cancel lock lyric notification failed: ${e.message}")
        }
    }

    private fun maybeShow(context: Context?) {
        val ctx = context ?: return
        if (!PlaybackPrefs.lockScreenLyricEnabled(ctx)) return
        if (!PlaybackStateManager.isPlaying.value) return
        if (PlaybackStateManager.currentSong.value == null) return
        val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager ?: return
        // 未锁屏（用户已解锁）时不打扰
        if (!km.isKeyguardLocked) return

        val intent = Intent(ctx, LyricLockScreenActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            postFullScreenIntent(ctx, intent)
        } else {
            try {
                ctx.startActivity(intent)
                Log.d(TAG, "lock screen lyric started directly")
            } catch (e: Exception) {
                Log.w(TAG, "start lock screen lyric failed: ${e.message}")
            }
        }
    }

    private fun postFullScreenIntent(context: Context, intent: Intent) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // 全屏意图只在渠道重要度为「高」时才会被系统在锁屏上全屏执行；
            // 静音 + 不震动，否则每次唤醒都会响一声。
            val channel = NotificationChannel(
                CHANNEL_ID,
                "锁屏歌词",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "锁屏唤醒时全屏显示歌词"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
            manager.createNotificationChannel(channel)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Android 14+ 起非通话/闹钟类应用默认不再自动获得全屏意图能力，
        // 未获准时降级为普通高优先通知（用户点一下仍可进入歌词页）
        val canFullScreen = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            manager.canUseFullScreenIntent()
        } else {
            true
        }
        if (!canFullScreen) {
            Log.w(TAG, "full screen intent not allowed, fall back to normal notification")
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_music_launcher)
            .setContentTitle(PlaybackStateManager.currentSong.value?.name.orEmpty())
            .setContentText(PlaybackStateManager.getCurrentLyricLineSync().orEmpty())
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
        if (canFullScreen) {
            builder.setFullScreenIntent(pendingIntent, true)
        }

        try {
            manager.notify(NOTIFICATION_ID, builder.build())
            Log.d(TAG, "post lock screen lyric notification, fullScreen=$canFullScreen")
        } catch (e: Exception) {
            Log.w(TAG, "post lock screen lyric notification failed: ${e.message}")
        }
    }
}
