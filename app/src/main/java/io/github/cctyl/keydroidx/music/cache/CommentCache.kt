package io.github.cctyl.keydroidx.music.cache

import java.util.concurrent.ConcurrentHashMap

/**
 * 歌曲评论总数内存缓存。
 *
 * 播放页切歌时会拉取一次评论数，来回切换上一首/下一首不应重复打网络。
 * 只缓存「总数」这一个标量，评论正文不缓存（时效性强、占用大）。
 */
object CommentCache {
    private const val MAX_ENTRIES = 200

    private val counts = ConcurrentHashMap<Long, Int>()

    /** 命中返回评论总数，未命中返回 null */
    fun getCount(songId: Long): Int? = counts[songId]

    fun putCount(songId: Long, total: Int) {
        // 极简 LRU：满了直接清空重建（评论数体量极小，够用且无维护成本）
        if (counts.size >= MAX_ENTRIES && !counts.containsKey(songId)) {
            counts.clear()
        }
        counts[songId] = total
    }

    fun clear() {
        counts.clear()
    }
}
