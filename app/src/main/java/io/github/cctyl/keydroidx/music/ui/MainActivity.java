package io.github.cctyl.keydroidx.music.ui;

import android.content.Intent;
import android.widget.TextView;
import android.widget.Toast;

import io.github.cctyl.nokia.keycore.ui.dialog.NokiaConfirmDialog;
import io.github.cctyl.nokia.keycore.ui.dialog.NokiaOptionsDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import io.github.cctyl.keydroidx.music.R;
import io.github.cctyl.keydroidx.music.adapter.MusicAdapter;
import io.github.cctyl.keydroidx.music.model.MusicItem;
import io.github.cctyl.nokia.keycore.NokiaKeyClient;
import io.github.cctyl.nokia.keycore.model.NokiaKeyAction;
import io.github.cctyl.nokia.keycore.model.NokiaKeyBinding;
import io.github.cctyl.nokia.keycore.ui.NokiaBaseActivity;
import io.github.cctyl.nokia.keycore.ui.NokiaKeyWizardActivity;

/**
 * 音乐列表主界面（纯按键导航、复古 240dp 风格）。
 */
public class MainActivity extends NokiaBaseActivity implements NokiaKeyClient.OnKeyBindingChangedListener {

    private RecyclerView rvMusic;
    private TextView tvEmptyHint;
    private TextView tvProviderStatus;
    private MusicAdapter adapter;

    @Override
    protected int getContentLayoutRes() {
        return R.layout.activity_music_main;
    }

    @Override
    protected void onInitViews() {
        // 设置标题与底部按键标签
        setPageTitle(getString(R.string.title_music_list));
        setSoftKeys(
                getString(R.string.softkey_options),
                getString(R.string.softkey_play),
                getString(R.string.softkey_exit)
        );

        rvMusic = findViewById(R.id.recycler_music);
        tvEmptyHint = findViewById(R.id.tv_hint);
        tvProviderStatus = findViewById(R.id.tv_provider_status);

        rvMusic.setLayoutManager(new LinearLayoutManager(this));
        adapter = new MusicAdapter();
        rvMusic.setAdapter(adapter);

        adapter.setOnItemClickListener((item, position) -> openPlayer(item));

        // 注册按键变动监听
        NokiaKeyClient.get(this).registerListener(this);
        updateKeyStatus(NokiaKeyClient.get(this).isFromDesktop());

        // 加载占位展示数据
        loadPlaceholderData();
    }

    private void updateKeyStatus(boolean isFromDesktop) {
        if (tvProviderStatus != null) {
            if (isFromDesktop) {
                tvProviderStatus.setText("按键来源: KeydroidX 桌面 (已热同步)");
            } else {
                tvProviderStatus.setText("按键来源: 独立配置/默认兜底");
            }
        }
    }

    @Override
    public void onKeyBindingChanged(NokiaKeyBinding binding, boolean fromDesktop) {
        updateKeyStatus(fromDesktop);
    }

    @Override
    protected boolean onAction(int action) {
        switch (action) {
            case NokiaKeyAction.ACTION_UP:
                adapter.selectPrev();
                rvMusic.smoothScrollToPosition(adapter.getSelectedPosition());
                return true;

            case NokiaKeyAction.ACTION_DOWN:
                adapter.selectNext();
                rvMusic.smoothScrollToPosition(adapter.getSelectedPosition());
                return true;

            case NokiaKeyAction.ACTION_SELECT:
                MusicItem selected = adapter.getSelectedItem();
                if (selected != null) {
                    openPlayer(selected);
                }
                return true;

            case NokiaKeyAction.ACTION_SOFT_LEFT:
                showOptionsMenu();
                return true;

            case NokiaKeyAction.ACTION_SOFT_RIGHT:
                finish();
                return true;

            default:
                return super.onAction(action);
        }
    }

    private void openPlayer(MusicItem item) {
        Intent intent = new Intent(this, MusicPlayerActivity.class);
        intent.putExtra("music_item", item);
        startActivity(intent);
    }

    private void showOptionsMenu() {
        new NokiaOptionsDialog(this, "选项")
                .addItem(0, getString(R.string.menu_key_wizard))
                .addItem(1, getString(R.string.menu_reload_keys))
                .addItem(2, getString(R.string.menu_about))
                .setOnOptionSelectedListener((index, item) -> {
                    switch (item.getId()) {
                        case 0:
                            // 启动 SDK 自带的按键配置向导
                            NokiaKeyWizardActivity.start(this);
                            break;
                        case 1:
                            NokiaKeyClient.get(this).reload();
                            Toast.makeText(this, "按键配置已重新加载", Toast.LENGTH_SHORT).show();
                            break;
                        case 2:
                            new NokiaConfirmDialog(this, "关于", "KeydroidX Music 脚手架\n按键机专用轻量音乐播放器")
                                    .setPositiveButton("确定", null)
                                    .setNegativeButton("关闭", null)
                                    .show();
                            break;
                    }
                })
                .show();
    }

    private void loadPlaceholderData() {
        List<MusicItem> list = new ArrayList<>();
        list.add(new MusicItem("1", "七里香", "周杰伦", "04:59", "/sdcard/Music/1.mp3"));
        list.add(new MusicItem("2", "晴天", "周杰伦", "04:29", "/sdcard/Music/2.mp3"));
        list.add(new MusicItem("3", "夜曲", "周杰伦", "03:46", "/sdcard/Music/3.mp3"));
        list.add(new MusicItem("4", "青花瓷", "周杰伦", "03:59", "/sdcard/Music/4.mp3"));
        list.add(new MusicItem("5", "江南", "林俊杰", "04:28", "/sdcard/Music/5.mp3"));
        list.add(new MusicItem("6", "曹操", "林俊杰", "04:01", "/sdcard/Music/6.mp3"));
        list.add(new MusicItem("7", "突然好想你", "五月天", "04:25", "/sdcard/Music/7.mp3"));
        list.add(new MusicItem("8", "知足", "五月天", "05:16", "/sdcard/Music/8.mp3"));
        list.add(new MusicItem("9", "海阔天空", "Beyond", "05:24", "/sdcard/Music/9.mp3"));
        list.add(new MusicItem("10", "光辉岁月", "Beyond", "05:05", "/sdcard/Music/10.mp3"));

        adapter.setData(list);
        if (tvEmptyHint != null) {
            tvEmptyHint.setVisibility(list.isEmpty() ? android.view.View.VISIBLE : android.view.View.GONE);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateKeyStatus(NokiaKeyClient.get(this).isFromDesktop());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        NokiaKeyClient.get(this).unregisterListener(this);
    }
}
