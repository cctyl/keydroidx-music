package io.github.cctyl.keydroidx.music.library

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 搜索历史记录管理
 */
object SearchHistoryManager {
    private const val PREFS_NAME = "keydroidx_music_search_history"
    private const val KEY_HISTORY = "search_history"
    private const val MAX_HISTORY_COUNT = 20

    private val gson = Gson()
    private var prefs: SharedPreferences? = null

    private val _history = MutableStateFlow<List<String>>(emptyList())
    val history: StateFlow<List<String>> = _history.asStateFlow()

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            loadFromPrefs()
        }
    }

    private fun loadFromPrefs() {
        val sp = prefs ?: return
        val json = sp.getString(KEY_HISTORY, null)
        if (!json.isNullOrBlank()) {
            val type = object : TypeToken<List<String>>() {}.type
            _history.value = runCatching { gson.fromJson<List<String>>(json, type) }.getOrDefault(emptyList())
        }
    }

    fun addHistory(keyword: String) {
        val trimmed = keyword.trim()
        if (trimmed.isBlank()) return

        val current = _history.value.toMutableList()
        current.removeAll { it.equals(trimmed, ignoreCase = true) }
        current.add(0, trimmed)
        if (current.size > MAX_HISTORY_COUNT) {
            current.removeAt(current.lastIndex)
        }
        _history.value = current
        save()
    }

    fun removeHistory(keyword: String) {
        val current = _history.value.toMutableList()
        current.removeAll { it.equals(keyword, ignoreCase = true) }
        _history.value = current
        save()
    }

    fun clearHistory() {
        _history.value = emptyList()
        prefs?.edit()?.remove(KEY_HISTORY)?.apply()
    }

    private fun save() {
        prefs?.edit()?.putString(KEY_HISTORY, gson.toJson(_history.value))?.apply()
    }
}
