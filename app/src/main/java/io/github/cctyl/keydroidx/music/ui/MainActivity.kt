package io.github.cctyl.keydroidx.music.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.cctyl.keydroidx.music.R
import io.github.cctyl.keydroidx.music.adapter.MusicAdapter
import io.github.cctyl.keydroidx.music.model.MusicItem
import io.github.cctyl.keydroidx.music.network.RetrofitClient
import io.github.cctyl.keydroidx.music.network.model.ArtistItem
import io.github.cctyl.keydroidx.music.network.model.SongItem
import io.github.cctyl.keydroidx.music.player.PlaybackService
import io.github.cctyl.keydroidx.music.player.PlaybackStateManager
import io.github.cctyl.nokia.keycore.NokiaClient
import io.github.cctyl.nokia.keycore.NokiaKeyClient
import io.github.cctyl.nokia.keycore.model.NokiaKeyAction
import io.github.cctyl.nokia.keycore.model.NokiaKeyBinding
import io.github.cctyl.nokia.keycore.ui.NokiaBaseActivity
import io.github.cctyl.nokia.keycore.ui.NokiaKeyWizardActivity
import io.github.cctyl.nokia.keycore.ui.dialog.NokiaConfirmDialog
import io.github.cctyl.nokia.keycore.ui.dialog.NokiaOptionsDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : NokiaBaseActivity() {
    private var rvMusic: RecyclerView? = null
    private var tvEmptyHint: TextView? = null
    private var tvProviderStatus: TextView? = null
    private var adapter: MusicAdapter? = null
    private var rawSongList: List<SongItem> = emptyList()

    override fun getContentLayoutRes(): Int = R.layout.activity_music_main

    override fun onInitViews() {
        setPageTitle(getString(R.string.title_music_list))
        setSoftKeys(
            getString(R.string.softkey_options),
            getString(R.string.softkey_play),
            getString(R.string.softkey_exit)
        )

        rvMusic = findViewById(R.id.recycler_music)
        tvEmptyHint = findViewById(R.id.tv_hint)
        tvProviderStatus = findViewById(R.id.tv_provider_status)

        rvMusic?.layoutManager = LinearLayoutManager(this)
        adapter = MusicAdapter()
        rvMusic?.adapter = adapter

        adapter?.setOnItemClickListener { _, position ->
            playSongAt(position)
        }

        updateKeyStatus(NokiaKeyClient.get(this).isFromDesktop)
        loadNcmData()
    }

    private fun loadNcmData() {
        lifecycleScope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    RetrofitClient.api.search("周杰伦")
                }
                val songs = response.result?.songs ?: emptyList()
                rawSongList = songs

                val musicList = songs.map { song ->
                    MusicItem(
                        song.id.toString(),
                        song.name,
                        song.artists?.joinToString("/") { it.name } ?: "",
                        "",
                        ""
                    )
                }

                adapter?.setData(musicList)
                tvEmptyHint?.visibility = if (musicList.isEmpty()) View.VISIBLE else View.GONE
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "加载歌单失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onKeysChanged(binding: NokiaKeyBinding, source: NokiaClient.ConfigSource) {
        updateKeyStatus(NokiaKeyClient.get(this).isFromDesktop)
    }

    private fun updateKeyStatus(isFromDesktop: Boolean) {
        tvProviderStatus?.text = if (isFromDesktop) "按键来源: KeydroidX 桌面" else "按键来源: 本地降级"
    }

    override fun onAction(action: Int): Boolean {
        return when (action) {
            NokiaKeyAction.ACTION_UP -> {
                adapter?.selectPrev()
                rvMusic?.smoothScrollToPosition(adapter?.selectedPosition ?: 0)
                true
            }
            NokiaKeyAction.ACTION_DOWN -> {
                adapter?.selectNext()
                rvMusic?.smoothScrollToPosition(adapter?.selectedPosition ?: 0)
                true
            }
            NokiaKeyAction.ACTION_SELECT -> {
                val pos = adapter?.selectedPosition ?: -1
                if (pos >= 0 && pos < rawSongList.size) {
                    playSongAt(pos)
                }
                true
            }
            NokiaKeyAction.ACTION_SOFT_LEFT -> {
                showOptionsMenu()
                true
            }
            NokiaKeyAction.ACTION_SOFT_RIGHT -> {
                finish()
                true
            }
            else -> super.onAction(action)
        }
    }

    private fun playSongAt(position: Int) {
        if (rawSongList.isEmpty() || position !in rawSongList.indices) return

        PlaybackStateManager.updatePlaylist(rawSongList, position)

        val intent = Intent(this, PlaybackService::class.java).apply {
            action = PlaybackService.ACTION_PLAY_INDEX
            putExtra(PlaybackService.EXTRA_INDEX, position)
        }
        startService(intent)

        startActivity(Intent(this, MusicPlayerActivity::class.java))
    }

    private fun showOptionsMenu() {
        NokiaOptionsDialog(this, "选项")
            .addItem(0, "正在播放")
            .addItem(1, "按键向导")
            .addItem(2, "重新加载")
            .addItem(3, "关于")
            .setOnOptionSelectedListener { _, item ->
                when (item.id) {
                    0 -> startActivity(Intent(this, MusicPlayerActivity::class.java))
                    1 -> NokiaKeyWizardActivity.start(this)
                    2 -> {
                        NokiaKeyClient.get(this).reload()
                        loadNcmData()
                    }
                    3 -> NokiaConfirmDialog(this, "关于", "KeydroidX Music v1.0.0\n专为按键机打造的复古音乐播放器").show()
                }
            }
            .show()
    }
}
