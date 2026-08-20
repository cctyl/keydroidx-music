package io.github.cctyl.keydroidx.music.model;

import java.io.Serializable;

/**
 * 音乐条目实体类（脚手架数据模型）
 */
public class MusicItem implements Serializable {
    private final String id;
    private final String title;
    private final String artist;
    private final String duration;
    private final String path;

    public MusicItem(String id, String title, String artist, String duration, String path) {
        this.id = id;
        this.title = title;
        this.artist = artist;
        this.duration = duration;
        this.path = path;
    }

    public String getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getArtist() {
        return artist;
    }

    public String getDuration() {
        return duration;
    }

    public String getPath() {
        return path;
    }
}
