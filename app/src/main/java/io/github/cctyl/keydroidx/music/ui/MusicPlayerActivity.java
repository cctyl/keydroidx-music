package io.github.cctyl.keydroidx.music.ui;

import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import io.github.cctyl.keydroidx.music.R;
import io.github.cctyl.keydroidx.music.model.MusicItem;
import io.github.cctyl.nokia.keycore.model.NokiaKeyAction;
import io.github.cctyl.nokia.keycore.ui.NokiaBaseActivity;

/**
 * 正在播放界面（纯按键控制、复古界面占位）。
 */
public class MusicPlayerActivity extends NokiaBaseActivity {

    private TextView tvSongTitle;
    private TextView tvArtist;
    private TextView tvPlayStatus;
    private TextView tvCurrentTime;
    private TextView tvTotalTime;
    private ProgressBar progressBar;

    private boolean isPlaying = false;
    private int playMode = 0; // 0: 循环, 1: 单曲, 2: 随机
    private static final String[] PLAY_MODES = new String[]{"列表循环", "单曲循环", "随机播放"};

    @Override
    protected int getContentLayoutRes() {
        return R.layout.activity_music_player;
    }

    @Override
    protected void onInitViews() {
        setPageTitle(getString(R.string.title_now_playing));
        setSoftKeys("循环", getString(R.string.softkey_play), getString(R.string.softkey_back));

        tvSongTitle = findViewById(R.id.tv_player_title);
        tvArtist = findViewById(R.id.tv_player_artist);
        tvPlayStatus = findViewById(R.id.tv_player_status);
        tvCurrentTime = findViewById(R.id.tv_current_time);
        tvTotalTime = findViewById(R.id.tv_total_time);
        progressBar = findViewById(R.id.progress_music);

        TextView ivCover = findViewById(R.id.iv_player_cover);
        if (ivCover != null) {
            ivCover.setTypeface(io.github.cctyl.nokia.keycore.ui.NokiaIcons.getTypeface(this));
            ivCover.setText(io.github.cctyl.nokia.keycore.ui.NokiaIcons.ICON_MUSIC_NOTE);
        }

        MusicItem item = (MusicItem) getIntent().getSerializableExtra("music_item");
        if (item != null) {
            if (tvSongTitle != null) tvSongTitle.setText(item.getTitle());
            if (tvArtist != null) tvArtist.setText(item.getArtist());
            if (tvTotalTime != null) tvTotalTime.setText(item.getDuration());
        }
    }

    @Override
    protected boolean onAction(int action) {
        switch (action) {
            case NokiaKeyAction.ACTION_SELECT:
                togglePlay();
                return true;

            case NokiaKeyAction.ACTION_LEFT:
                Toast.makeText(this, "上一曲 (按键响应)", Toast.LENGTH_SHORT).show();
                return true;

            case NokiaKeyAction.ACTION_RIGHT:
                Toast.makeText(this, "下一曲 (按键响应)", Toast.LENGTH_SHORT).show();
                return true;

            case NokiaKeyAction.ACTION_UP:
                Toast.makeText(this, "音量 + (按键响应)", Toast.LENGTH_SHORT).show();
                return true;

            case NokiaKeyAction.ACTION_DOWN:
                Toast.makeText(this, "音量 - (按键响应)", Toast.LENGTH_SHORT).show();
                return true;

            case NokiaKeyAction.ACTION_SOFT_LEFT:
                switchPlayMode();
                return true;

            case NokiaKeyAction.ACTION_SOFT_RIGHT:
                finish();
                return true;

            default:
                return super.onAction(action);
        }
    }

    private void togglePlay() {
        isPlaying = !isPlaying;
        if (isPlaying) {
            if (tvPlayStatus != null) tvPlayStatus.setText("[ 播放中 ]");
            setSoftKeys(PLAY_MODES[playMode], getString(R.string.softkey_pause), getString(R.string.softkey_back));
        } else {
            if (tvPlayStatus != null) tvPlayStatus.setText("[ 暂停中 ]");
            setSoftKeys(PLAY_MODES[playMode], getString(R.string.softkey_play), getString(R.string.softkey_back));
        }
    }

    private void switchPlayMode() {
        playMode = (playMode + 1) % PLAY_MODES.length;
        String modeName = PLAY_MODES[playMode];
        Toast.makeText(this, "播放模式: " + modeName, Toast.LENGTH_SHORT).show();
        setSoftKeys(modeName, isPlaying ? getString(R.string.softkey_pause) : getString(R.string.softkey_play), getString(R.string.softkey_back));
    }
}
