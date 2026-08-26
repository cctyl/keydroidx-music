# KeydroidX Music Widget 设计方案

## 概述

为音乐播放器添加桌面小组件支持，使其能在 KeydroidX Launcher 的组件区显示当前播放歌曲与歌词，按确认键进入播放详情页。

## 架构决策：双轨制

考虑到 Launcher 已有完善的**原生组件系统**（`NokiaWidgetItem` + `NokiaDesktopFragment` 焦点导航），且需适配 240×320 / 320×480 点阵屏，采用**双轨制**：

| 轨道 | 目的 | 实现方式 |
|------|------|----------|
| **系统 AppWidget** | 兼容第三方桌面、系统原生组件选择器 | `AppWidgetProvider` + `RemoteViews`，标准 Android 协议 |
| **原生数据源** | 无缝融入 Launcher 现有组件区、复用焦点导航 | `ContentProvider` 暴露播放态，Launcher 新增 `TYPE_MUSIC_PLAYER` 原生组件类型 |

> **核心原则**：Launcher 组件区只渲染**原生组件**（`NokiaWidgetItem` 行布局），不接入 `AppWidgetHost`。Music App 双轨并行，系统组件给第三方桌面用，原生数据源给 KeydroidX Launcher 用。

---

## 1. Music App 侧变更

### 1.1 新增：播放状态 ContentProvider

```kotlin
// package: io.github.cctyl.keydroidx.music.provider
class PlaybackProvider : ContentProvider() {
    // Authority: io.github.cctyl.keydroidx.music.playback
    // URI: content://io.github.cctyl.keydroidx.music.playback/state
    // Columns: song_id, title, artist, album_art_uri, is_playing, position_ms, duration_ms, lyric_text, updated_at
}
```

- 导出 `android:exported="true"`，`android:grantUriPermissions="true"`
- 仅读权限（`android:readPermission` 可选，或由 Launcher 同签名免检）
- 实时查询 `PlaybackStateManager` 的 StateFlow，转为 `MatrixCursor` 返回

### 1.2 新增：系统 AppWidgetProvider

```kotlin
// package: io.github.cctyl.keydroidx.music.widget
class MusicAppWidgetProvider : AppWidgetProvider() {
    // 4x1 / 4x2 尺寸，RemoteViews 显示：标题、歌手、进度条、当前歌词行
    // 点击 RemoteViews → PendingIntent 启动 MusicPlayerActivity
    // 监听 PlaybackStateManager 广播更新 RemoteViews
}
```

- `res/xml/music_app_widget_info.xml`：`minWidth="240dp"` `minHeight="80dp"` `resizeMode="horizontal|vertical"`
- `res/layout/widget_music_player.xml`：RemoteViews 布局（MaterialIcons 字体不可用，需用 ImageView/文本）
- `AndroidManifest.xml` 注册 `<receiver android:name=".widget.MusicAppWidgetProvider">` + `<intent-filter>` + `<meta-data android:name="android.appwidget.provider">`

### 1.3 PlaybackStateManager 广播播放态变化

```kotlin
// 现有 StateFlow 不变，新增发送广播 + notifyChange
private const val ACTION_PLAYBACK_CHANGED = "io.github.cctyl.keydroidx.music.PLAYBACK_CHANGED"
private const val EXTRA_SONG_ID = "song_id"        // String 歌曲 ID
private const val EXTRA_TITLE = "title"            // 歌曲名
private const val EXTRA_ARTIST = "artist"          // 歌手名
private const val EXTRA_ALBUM_ART = "album_art"    // 封面 URL
private const val EXTRA_PLAYING = "playing"
private const val EXTRA_POSITION = "position"
private const val EXTRA_DURATION = "duration"
private const val EXTRA_LYRIC_LINE = "lyric_line"  // 当前歌词文本
```

- `AppWidgetProvider`、`PlaybackProvider` 均可监听此广播刷新
- 广播使用**字符串/原始类型 extra**（不传整个 SongItem 对象，避免 Parcelize 复杂度）
- 每次广播同时 `contentResolver.notifyChange(PLAYBACK_URI, null)` 触发 ContentObserver

### 1.4 后台歌词跟踪

歌词行跟踪放在 `PlaybackService`（后台常驻），这样即使播放页关闭、在 Launcher 桌面也能实时显示歌词：

- 切歌时 `loadLyrics(songId)`：优先读本地下载歌词文件，否则联网 `RetrofitClient.api.getLyric`，解析为 `LrcLine` 列表缓存
- `progressRunnable`（每 500ms）计算当前歌词行并调用 `PlaybackStateManager.updateCurrentLyricLine()`，仅当歌词行变化时才推送（避免高频广播）
- `MusicPlayerActivity` 的 `updateLyricHighlight` 也推送（播放页打开时保持一致）

---

## 2. Launcher 侧变更

### 2.1 NokiaWidgetItem 新增类型常量

```java
// NokiaWidgetItem.java
public static final int TYPE_MUSIC_PLAYER = 11;  // 原 TYPE_COUNT=11，改为 12
public static final int TYPE_COUNT = 12;
public static final int MAX_COUNT = 15;  // 保持不变
```

### 2.2 类型元数据补充

| 方法 | 返回值 |
|------|--------|
| `getTypeName(11)` | "正在播放" |
| `getDefaultLabel(11)` | "正在播放" |
| `getTypeTag(11)` | "[音乐]" |
| `getTypeIconUnicode(11)` | `NokiaIcons.ICON_MUSIC_NOTE` |
| `getTypeIcon(11)` | `R.drawable.ic_nokia_music` (新增) |
| `isEditable()` | `false` |

### 2.3 NokiaDesktopFragment 组件行渲染

在 `createWidgetRow(NokiaWidgetItem)` 中新增分支：

```java
case NokiaWidgetItem.TYPE_MUSIC_PLAYER:
    return createMusicPlayerWidgetRow(item);
```

#### `createMusicPlayerWidgetRow` 设计

```java
private View createMusicPlayerWidgetRow(NokiaWidgetItem item) {
    // 返回一个 LinearLayout 行，包含：
    // 1. 图标 (MaterialIcons ICON_MUSIC_NOTE)
    // 2. 主标签：歌曲名 - 歌手（跑马灯或截断）
    // 3. 副标签：当前歌词行（若正在播放），或 "暂停中" / "无播放"
    // 4. 进度条（可选，极简横条）
    // 数据来源：ContentResolver.query(PlaybackProvider.CONTENT_URI)
    // 点击/SELECT：启动 MusicPlayerActivity
    // 焦点高亮：复用 bg_nokia_selected / NokiaTheme.createFocusDrawable
}
```

#### 数据刷新机制

- **方案 A（推荐）**：`ContentObserver` 监听 `PlaybackProvider` URI 变化，主线程 `rebuildWidgetArea()` 仅重建音乐组件行
- **方案 B**：`BroadcastReceiver` 监听 `ACTION_PLAYBACK_CHANGED`，同上
- 两者结合：Provider 变更时 `notifyChange(uri)` 触发 Observer，广播兜底

> 关键点：**只刷新音乐组件行**，不 `rebuildWidgetArea()` 全量重建，避免焦点丢失与闪烁。

### 2.4 焦点导航与 SELECT 行为

- 组件区焦点导航（UP/DOWN）复用现有逻辑，音乐组件作为普通行参与
- `onSelect()` → `performClick()` → 启动 `MusicPlayerActivity`
  ```java
  Intent intent = new Intent(Intent.ACTION_MAIN);
  intent.setClassName("io.github.cctyl.keydroidx.music", "io.github.cctyl.keydroidx.music.ui.MusicPlayerActivity");
  intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
  startActivity(intent);
  ```
- `onSoftRight()` 保持原样（打开桌面设置）

### 2.5 权限与包可见性

`AndroidManifest.xml` `<queries>` 新增：

```xml
<queries>
    <package android:name="io.github.cctyl.keydroidx.music" />
    <!-- 或 -->
    <intent>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
    </intent>
</queries>
```

---

## 3. 交互流程图

```
┌─────────────────────────────────────────────────────────────────┐
│                        用户在桌面待机屏                          │
├─────────────────────────────────────────────────────────────────┤
│  焦点在组件区 → UP/DOWN 移动到 "正在播放" 行                    │
│       │                                                         │
│       ▼                                                         │
│  行高亮（青色背景 + 白字加粗），显示：                          │
│  ♪ 顺风顺水 - 邹念慈/繁星合唱团                                 │
│  ♫ 风起的时候 谁在等候      ← 当前歌词行                        │
│  ━━━━━━━━━━━━━━━━━━━━━━━  ← 进度条（可选）                       │
│       │                                                         │
│       ▼ 用户按 确认键 (SELECT)                                  │
│  启动 MusicPlayerActivity (singleTop)                           │
│       │                                                         │
│       ▼                                                         │
│  进入黑胶唱机详情页，软键：选项 / 暂停 / 返回                   │
│       │                                                         │
│       ▼ 用户按 右软键 (SOFT_RIGHT)                              │
│  finish() 返回桌面待机屏，焦点自动落回 "正在播放" 行            │
└─────────────────────────────────────────────────────────────────┘
```

---

## 4. 文件变更清单

### Music App (keydroidx-music)

| 文件 | 变更类型 | 说明 |
|------|----------|------|
| `app/src/main/java/.../provider/PlaybackProvider.kt` | 新建 | ContentProvider 暴露播放态 |
| `app/src/main/java/.../widget/MusicAppWidgetProvider.kt` | 新建 | 系统 AppWidget |
| `app/src/main/java/.../widget/MusicWidgetService.kt` | 新建 | RemoteViewsService（如需列表） |
| `app/src/main/res/xml/music_app_widget_info.xml` | 新建 | AppWidget 元数据 |
| `app/src/main/res/layout/widget_music_player.xml` | 新建 | RemoteViews 布局 |
| `app/src/main/java/.../player/PlaybackStateManager.kt` | 修改 | 新增广播发送、歌词行 StateFlow |
| `app/src/main/java/.../player/PlaybackService.kt` | 修改 | 后台歌词加载与跟踪 |
| `app/src/main/AndroidManifest.xml` | 修改 | 注册 Provider、Receiver、AppWidgetProvider |

### Launcher (keydroidx-launcher)

| 文件 | 变更类型 | 说明 |
|------|----------|------|
| `app/src/main/java/.../nokia/NokiaWidgetItem.java` | 修改 | 新增 TYPE_MUSIC_PLAYER 常量与元数据 |
| `app/src/main/java/.../nokia/NokiaDesktopFragment.java` | 修改 | 新增 `createMusicPlayerWidgetRow`、ContentObserver、点击启动 |
| `app/src/main/res/drawable/ic_nokia_music.xml` | 新建 | 音乐组件图标（矢量） |
| `app/src/main/AndroidManifest.xml` | 修改 | `<queries>` 包可见性 |

---

## 5. 关键实现细节

### 5.1 ContentProvider 查询示例

```kotlin
// PlaybackProvider.kt
override fun query(uri, projection, selection, selectionArgs, sortOrder): Cursor? {
    val song = PlaybackStateManager.currentSong.value
    val playing = PlaybackStateManager.isPlaying.value
    val pos = PlaybackStateManager.currentPositionMs.value
    val dur = PlaybackStateManager.durationMs.value
    val lyricLine = getCurrentLyricLine(pos)  // 从 lrcLines 二分查找

    val matrix = MatrixCursor(arrayOf(
        "song_id", "title", "artist", "album_art_uri",
        "is_playing", "position_ms", "duration_ms",
        "lyric_text", "updated_at"
    )).apply {
        addRow(arrayOf(
            song?.id?.toString() ?: "",
            song?.name ?: "",
            song?.artistName ?: "",
            song?.album?.picUrl ?: "",
            playing.toString(),
            pos.toString(),
            dur.toString(),
            lyricLine ?: "",
            System.currentTimeMillis().toString()
        ))
    }
    matrix.setNotificationUri(context?.contentResolver, uri)
    return matrix
}

override fun getType(uri: Uri): String = "vnd.android.cursor.item/vnd.keydroidx.music.playback"
```

### 5.2 Launcher 端 ContentObserver

```java
// NokiaDesktopFragment.java
private final ContentObserver playbackObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
    @Override
    public void onChange(boolean selfChange, Uri uri) {
        if (PlaybackProvider.CONTENT_URI.equals(uri)) {
            refreshMusicWidgetRowOnly();
        }
    }
};

@Override
public void onResume() {
    super.onResume();
    requireContext().getContentResolver().registerContentObserver(
        PlaybackProvider.CONTENT_URI, false, playbackObserver);
    // 首次加载
    refreshMusicWidgetRowOnly();
}

@Override
public void onPause() {
    super.onPause();
    requireContext().getContentResolver().unregisterContentObserver(playbackObserver);
}

private void refreshMusicWidgetRowOnly() {
    View view = getView();
    if (view == null) return;
    LinearLayout notifArea = view.findViewById(R.id.notificationArea);
    if (notifArea == null) return;

    // 找到音乐组件行索引
    for (int i = 0; i < widgetItems.size(); i++) {
        if (widgetItems.get(i).type == NokiaWidgetItem.TYPE_MUSIC_PLAYER) {
            // 仅替换该行 View
            View oldRow = notifArea.getChildAt(i);
            View newRow = createMusicPlayerWidgetRow(widgetItems.get(i));
            if (oldRow != null && newRow != null) {
                notifArea.removeViewAt(i);
                notifArea.addView(newRow, i);
                // 更新焦点列表对应项
                int focusIdx = shortcutCount + i;
                if (focusIdx < focusTargets.size()) {
                    focusTargets.set(focusIdx, newRow);
                }
            }
            break;
        }
    }
}
```

### 5.3 进度条与歌词文本节流

- 进度每秒变化 → 直接在 `onChange` 刷新会太频繁
- **策略**：Provider 返回的 `position_ms` 仅供参考，**Launcher 端不渲染精确进度条**，只显示当前歌词行文本
- 歌词行变化频率低（约 3-10 秒一次），完全可接受
- 若需进度条：Launcher 端维护本地 `Handler` 每 500ms 更新一次进度条宽度，仅读取 Provider 的 `duration_ms` 与 `position_ms` 快照

---

## 6. 兼容性与降级

| 场景 | 行为 |
|------|------|
| Music App 未安装 | Launcher 组件区不显示音乐组件（`widgetStorage` 中无该类型） |
| Music App 已安装但未播放 | 显示 "暂无播放" / "点击打开音乐"，SELECT 仍可启动 App |
| Music App 播放本地歌曲 | 正常显示，album_art_uri 为空时显示默认唱片图标 |
| 网络歌词加载中 | 显示 "加载歌词中..." |
| 无歌词 | 显示 "纯音乐" 或空 |

---

## 7. 测试验收清单

- [ ] Music App 编译通过，系统桌面长按可添加 "KeydroidX Music" 组件
- [ ] 系统组件显示歌名/歌手/歌词，点击进入播放页
- [ ] KeydroidX Launcher 组件区可添加 "正在播放" 类型
- [ ] 组件区焦点导航：快捷栏 ↔ 组件区 ↔ 开关栏 正常循环
- [ ] 音乐组件行高亮样式与其他组件一致（青色背景 + 白字加粗）
- [ ] 播放状态变化（播放/暂停/切歌）组件行实时刷新，焦点不丢失
- [ ] 组件行 SELECT 启动 MusicPlayerActivity，返回桌面焦点回到该行
- [ ] 240×320 / 320×480 真机无横向溢出、字体清晰、图标对齐
- [ ] 卸载 Music App 后 Launcher 不崩溃，组件区正常显示其余组件

---

## 8. 里程碑拆解

| 阶段 | 任务 | 产出 |
|------|------|------|
| **M1: Music App 数据源** | PlaybackProvider + 广播 + SongItem Parcelable | `content://.../playback/state` 可查询，广播可接收 |
| **M2: Music App 系统组件** | AppWidgetProvider + RemoteViews + 布局 | 系统桌面可添加、显示、点击跳转 |
| **M3: Launcher 原生组件类型** | NokiaWidgetItem 新增 TYPE_MUSIC_PLAYER | 常量、图标、类型名、默认标签 |
| **M4: Launcher 渲染与刷新** | createMusicPlayerWidgetRow + ContentObserver | 组件区显示播放态、实时刷新、焦点稳定 |
| **M5: 交互联调** | SELECT 启动、返回焦点恢复、两机型验收 | 端到端可用 |

---

## 9. 备选方案对比（决策记录）

| 方案 | 优点 | 缺点 | 决策 |
|------|------|------|------|
| **仅系统 AppWidget + AppWidgetHost** | 标准协议、复用 Music App 现有 RemoteViews | Launcher 需引入 AppWidgetHost、焦点导航难融合、RemoteViews 不支持点阵字体、尺寸适配麻烦 | ❌ 放弃 |
| **仅原生组件 + ContentProvider** | 完美融合 Launcher 焦点系统、点阵字体、统一样式、无跨进程 View 复杂度 | 第三方桌面用不了 | ✅ 核心方案 |
| **双轨制（选定）** | 两全其美：Launcher 用原生，第三方桌面用系统组件 | 维护两套显示逻辑 | ✅ 采纳 |

---

## 10. 风险与对策

| 风险 | 影响 | 对策 |
|------|------|------|
| ContentProvider 跨进程查询延迟 | 组件行刷新卡顿 | Provider 仅读内存 StateFlow，无 I/O；Launcher 用 ContentObserver 异步刷新 |
| 歌词解析耗时 | UI 线程阻塞 | 解析在 Music App 侧完成，Provider 仅返回当前行文本 |
| 多设备分辨率文本溢出 | 显示异常 | 单行 `ellipsize="marquee"` + `maxLines=1`，歌词行同理 |
| Music App 进程被杀 | Provider 不可用 | Provider 运行在 Music App 进程，进程死则无播放态，显示 "未播放" 即可 |

---

*文档版本：v1.0*  
*作者：AI Assistant*  
*日期：2025-08-26*