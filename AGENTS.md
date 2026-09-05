# KeydroidX Music (原键音乐)

本项目是基于 **KeydroidX 按键机生态** 构建的独立轻量级音乐播放器，专为物理九键/全键盘 Android 按键机量身定制。

本应用采用“功能逻辑外调，原生 UI 驱动”策略：底层核心音乐播放与请求逻辑移植自 `fork_Ncrust` 项目，UI 层完全重构以符合 KeydroidX 生态的物理按键交互与点阵设计规范。

---

## 一、项目定位与生态架构

### 1.1 生态架构关系
在 KeydroidX 生态中，各个项目分工明确、高度解耦：

- **KeydroidX Launcher (桌面端 / 中枢)**：
  - 路径：`../keydroidx-launcher`
  - 负责待机桌面、通知栏与按键机全局设置。
  - 通过 `NokiaKeyProvider` 向外提供物理按键映射、主题、字体及缩放配置。
- **keydroidx-core (通用 SDK 核心库)**：
  - 路径：`../keydroidx-core`
  - 生态客户端通用基础库（包含 Model, Client, UI, Dialog）。
  - 负责三级降级按键解析、跨进程配置热同步与复古 UI 规范。
- **keydroidx-music (本项目 - 独立应用)**：
  - 纯物理按键驱动的轻量音乐播放器。
  - 依赖 `keydroidx-core` 并继承 `NokiaBaseActivity`，开箱即用获得生态能力。

### 1.2 核心机制与协同工作流
1. **零二次配置**：
   - 用户在 KeydroidX 桌面配好物理按键或切换主题/字体后，音乐播放器通过 ContentProvider 即时自动换肤并生效，无需用户进入音乐 App 重新配键。
2. **三级平滑降级**：
   - 优先同步桌面配置；若设备未安装桌面，则平滑降级为本应用本地独立配置；若无本地配置则回退为 Android 系统标准键值。
3. **开箱即用的复古 UI**：
   - 列表页与播放详情页统一采用 240dp 基准规范，自动应用生态点阵字体（ArkPixel / FusionPixel）与 MaterialIcons 矢量图标。
   - 弹窗统一采用 SDK 内置的 `NokiaOptionsDialog`（选项菜单）与 `NokiaConfirmDialog`（确认弹窗）。

### 1.3 项目路径
- **本应用**：`D:\project\keydroidx_ecosystem\keydroidx-music`
- **参考库/项目 (Ncrust)**：`D:\project\fork_Ncrust`

---

## 二、应用架构与模块划分

### 2.1 宿主 Activity + Fragment 页面体系
- **宿主 `MainActivity`**（继承 `keydroidx-core` 的 `NokiaBaseActivity`）：
  - 纯轻量宿主，管理顶栏 Tab 指示器与 `supportFragmentManager`（`add`/`show`/`hide` 保活模式，切 Tab 零重载）。
  - 通过 `getCurrentPage()` 自动将软键与标题栏代理给当前 Fragment，并将按键事件（`onAction`）精准分发到当前页面。
- **四大主 Tab 页面（`ui/fragment/`）**：
  - `MineTabFragment : NokiaPageFragment`（我的音乐库：用户信息、喜欢/历史/本地入口、自建与收藏歌单列表、`NokiaOptionsDialog` 与 `NokiaConfirmDialog` 选项）
  - `DiscoverTabFragment : NokiaPageFragment`（发现音乐：私人 FM、每日推荐、今日推荐歌单动态拉取）
  - `ChartTabFragment : NokiaListPageFragment`（云音乐排行榜：10 大官方榜单单列循环列表，继承基类自动滚动与焦点高亮）
  - `SearchTabFragment : NokiaPageFragment`（歌曲搜索：`NokiaInputDialog` 拼音搜索弹窗、热门搜索词、云端搜索结果列表与一键播放）
- **二级与独立页面**：
  - `ui/PlaylistDetailActivity`（歌单歌曲列表，接入 `NokiaListFocusHelper` 管理歌曲焦点与自动平滑滚动，懒加载分页）
  - `ui/LocalMusicActivity`（本地音乐扫描与播放，接入 `NokiaListFocusHelper`）
  - `ui/MusicPlayerActivity`（黑胶唱机详情页，含全屏歌词、音量浮层、独立返回栈与通知栏入口）
  - `ui/WebLoginActivity`（网易云 WebView 登录）

### 2.2 播放状态总线与服务层
- `player/PlaybackStateManager.kt` 为全局单例（Kotlin StateFlow），UI 订阅它渲染播放态。
- Activity/Fragment 通过给 `PlaybackService` 发 Intent action（`ACTION_PLAY_INDEX` / `PLAY_PAUSE` / `NEXT` / `PREV` / `TOGGLE_MODE` / `SEEK`）下发控制。
- `PlaybackService` 是 Media3 `MediaSessionService` + ExoPlayer，内置 VIP 歌曲（fee=1）自动跳过、取链失败自动跳下一首逻辑。

### 2.3 网络层与协议封装
- `network/RetrofitClient.kt` 封装网易云两套协议：
  1. **Retrofit 普通 GET/POST**（`NcmApi`：搜索/歌词/详情等公开接口）；
  2. **eapi 加密接口**（`eapiPost` + `network/crypto/EapiCrypto`：歌单/取链/账号等，走 interface3 与 music.163 双主机回退）。
- `player/SongUrlFetcher` 按音质 5 级降级取链。
- `network/PlaylistApi` 提供歌单/用户资料/红心等业务接口（JSON 手解析）。

### 2.4 本地与缓存层
- `auth/`：Cookie 持久化、用户资料缓存。
- `cache/`：Content/PlaylistSong 内存缓存。
- `library/`：收藏与最近播放（SharedPreferences + Gson）。
- `lyric/LrcParser`：LRC 歌词解析。
- `warmup/AppWarmup`：应用预热。

---

## 三、核心继承体系与架构契约

SDK 已经将 240dp 基准视口缩放、按键解析分发、点阵字体整树应用、主题动态联动和焦点防出界滚动固化在基类中。**宿主应用严禁直接继承 Android 原生 `Activity` 或 `Fragment`。**

```
┌───────────────────────────────────────────────────────────┐
│               NokiaBaseActivity (宿主窗口骨架)             │
│  - 240dp 视口自适应居中容器                                 │
│  - 经典顶栏（电量/信号/时钟）+ 底部软键栏（左/中/右）        │
│  - 物理按键 DOWN/UP 严密拦截与分发                         │
│  - 实现 NokiaPageHost 契约                                │
└─────────────────────────────┬─────────────────────────────┘
                              │ 承载并分发按键
                              ▼
┌───────────────────────────────────────────────────────────┐
│                  NokiaPage (声明式契约)                    │
│      getPageTitle() / getSoftLeftText() / getSoftRightText │
└─────────────────────────────┬─────────────────────────────┘
                              │ 实现
┌─────────────────────────────┴─────────────────────────────┐
│                 NokiaPageFragment (模板基类)               │
│  - 固化 getLayoutRes() 布局加载                            │
│  - 视图创建后自动注入点阵字体与主题                        │
│  - 页面切入自动触发 host.refreshPageBar()                  │
└──────┬──────────────────────┬──────────────────────┬──────┘
       │ 派生                 │ 派生                 │ 派生
       ▼                      ▼                      ▼
┌──────────────────┐   ┌──────────────────┐   ┌──────────────────┐
│NokiaListPageFrag │   │NokiaScrollPageFr │   │ 业务自定义页面    │
│(单列列表黄金基类)│   │ (长文本滚动页)   │   │ (播放器/九宫格等)│
└──────────────────┘   └──────────────────┘   └──────────────────┘
```

### 3.1 窗口骨架：`NokiaBaseActivity`
所有业务 Activity 必须直接继承 `io.github.cctyl.nokia.keycore.ui.NokiaBaseActivity`。

#### 核心职责：
- **布局装配**：子类实现 `getLayoutResId()` 提供内容区布局。
- **自动初始化**：在 `onCreate` 中自动注册 `NokiaClient` 配置监听器（按键/主题/字体实时同步），并递归将点阵字体应用到整棵 View 树。
- **按键中枢**：重写 `dispatchKeyEvent`，将底层硬件 KeyCode 解析为 `NokiaKeyAction`，并自动分发给前台的 `NokiaPage`。

```java
public class MyMainActivity extends NokiaBaseActivity {
    @Override
    protected int getLayoutResId() {
        return R.layout.activity_main; // 仅提供中间内容区布局
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 无需手动加载字体和顶栏/底栏，基类已自动就绪
    }
}
```

### 3.2 页面基类：`NokiaPageFragment` 三大模板
中间内容区的所有页面一律使用 Fragment 构建，并按场景继承以下三种基类之一：

| 页面场景 | 继承基类 | 职责与规范 |
| :--- | :--- | :--- |
| **单列列表页**<br>（设置、歌单、文件列表、历史等） | **`NokiaListPageFragment`** | **最常用基类**。<br>① 自动处理 UP/DOWN 首尾循环导航；<br>② 自动应用当前生态主题高亮背景（`createSelectionDrawable`）；<br>③ 自动调用防出界滚动算法；<br>④ 子类只需在 `onPageCreated` 绑定 `itemViews` 与 `listScroll`，并覆写 `onItemClicked`。 |
| **长文本 / 说明 / 表单**<br>（小说阅读、版本说明、详情） | **`NokiaScrollPageFragment`** | 自动查找布局中的 `ScrollView`，上下键自动触发平滑步进滚动（默认 48dp 步长）。 |
| **自定义普通页面**<br>（音乐播放器主页、表盘、九宫格） | **`NokiaPageFragment`** | 模板基类。固化 `getLayoutRes()` 布局膨胀、整树点阵字体渲染与生命周期守卫，子类自定义复杂焦点与视图。 |

#### `NokiaListPageFragment` 标准开发范例：
```java
public class SettingListFragment extends NokiaListPageFragment {

    @Override
    protected int getLayoutRes() {
        return R.layout.fragment_setting_list;
    }

    @Override
    public CharSequence getPageTitle() {
        return "系统设置";
    }

    @Override
    public CharSequence getSoftLeftText() {
        return "选择";
    }

    @Override
    public CharSequence getSoftCenterText() {
        return "进入";
    }

    @Override
    public CharSequence getSoftRightText() {
        return "返回";
    }

    @Override
    protected void onPageCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        // 1. 绑定 ScrollView 与条目 View 数组
        this.listScroll = view.findViewById(R.id.scroll_container);
        this.itemViews = new View[] {
            view.findViewById(R.id.item_network),
            view.findViewById(R.id.item_display),
            view.findViewById(R.id.item_sound),
            view.findViewById(R.id.item_about)
        };

        // 2. 初始化焦点至第 0 项（自动高亮并滚入可视区域）
        setFocusIndex(0);
    }

    @Override
    protected void onItemClicked(int index, @NonNull View itemView) {
        // 响应确定键（SELECT / DPAD_CENTER）或触屏点击
        switch (index) {
            case 0: openNetwork(); break;
            case 1: openDisplay(); break;
            case 2: openSound(); break;
            case 3: openAbout(); break;
        }
    }
}
```

### 3.3 页面描述契约：`NokiaPage` 接口
`NokiaPageFragment` 默认实现了 `NokiaPage`。页面**严禁直接 `findViewById` 去修改 Activity 的顶栏或底部软键文字**，必须采用**声明式 Getter**：

```java
public interface NokiaPage extends NokiaFocusHost {
    CharSequence getPageTitle();      // 页面中间标题（返回 null 表示不显示）
    CharSequence getSoftLeftText();   // 左软键文案（返回 null 表示隐藏）
    CharSequence getSoftCenterText(); // 中软键/OK键文案（返回 null 表示隐藏）
    CharSequence getSoftRightText();  // 右软键文案（返回 null 表示隐藏）
}
```

#### 动态刷新规则：
当页面内部状态变化（例如：切歌、换选、多选模式切换、输入框聚焦等），只需调用：
```java
notifyHostRefresh(); // 触发宿主 Activity 重新调用 getter 刷新软键栏
```

---

## 四、物理按键与交互分发规范

按键机没有触屏（或触屏为辅助），按键分发的稳定性直接决定应用的可用性。

### 4.1 按键语义转换：禁止写死 KeyCode
- ❌ **严禁**：在业务逻辑中写死 KeyCode（例如 `if (keyCode == 23)` 或 `if (keyCode == KeyEvent.KEYCODE_MENU)`）。
- ✅ **强制**：所有按键必须通过 `NokiaKeyBinding.resolveAction(event)` 统一解析为语义动作（`NokiaKeyAction`）：

```java
int action = keyBinding.resolveAction(event);
switch (action) {
    case NokiaKeyAction.UP:         // 向上移动焦点 / 滚动
    case NokiaKeyAction.DOWN:       // 向下移动焦点 / 滚动
    case NokiaKeyAction.LEFT:       // 向左 / 上一曲 / 步退
    case NokiaKeyAction.RIGHT:      // 向右 / 下一曲 / 步进
    case NokiaKeyAction.SELECT:     // 确定 / 进入 / 播放
    case NokiaKeyAction.SOFT_LEFT:  // 左软键（菜单 / 选项）
    case NokiaKeyAction.SOFT_RIGHT: // 右软键（返回 / 取消）
}
```

### 4.2 DOWN / UP 完整配对：防点击合成误触
- ⚠️ **踩坑原因**：Android 输入管道中，如果某层仅消费了 `ACTION_DOWN` 并返回 `true`，但放过了 `ACTION_UP`，系统会在 UP 时对当前处于 pressed 状态的控件自动合成 `performClick()`，造成“按一次确定键触发两次操作 / 刚打开新页面就被自动误触点击”的严重 Bug。
- ✅ **规则**：凡是在 Activity / Fragment 层消费了 `ACTION_DOWN`，**必须同步拦截消费对应的 `ACTION_UP` 与 `REPEAT`**。`NokiaBaseActivity` 已内置该机制。

### 4.3 首键防吞规范：Touch Mode 焦点陷阱
- ⚠️ **现象**：新打开页面或从弹窗返回后，**按第 1 次方向键/确定键无效，按第 2 次才起作用**。
- 🔍 **根因**：当前 Window 处于触摸模式（Touch Mode）且没有 View 持有焦点（`findFocus() == null`）。Android 底层将首个方向键用于“退出触摸模式并寻焦”，直接吞掉事件，不会派发给 Activity。
- ✅ **宿主开发守则**：
  1. 页面内的所有列表项根 View 必须在 XML 中声明：
     ```xml
     android:focusable="true"
     android:focusableInTouchMode="true"
     ```
  2. 页面最外层如果有包裹的 `ScrollView`，必须显式声明禁止获焦：
     ```xml
     android:focusable="false"
     android:focusableInTouchMode="false"
     ```
  3. 页面初次加载或切换后，必须主动为首个可聚焦项执行 `view.requestFocus()`（`NokiaListPageFragment` 与 `NokiaListFocusHelper` 已内置此逻辑）。

### 4.4 列表循环导航与平滑滚动算法
1. **首尾循环导航**：
   - 在第 0 项按 `UP` 键，焦点必须平滑跳转至末尾项；
   - 在末尾项按 `DOWN` 键，焦点必须平滑跳转至第 0 项。
2. **防出界平滑滚动**：
   - ❌ **严禁**使用 `child.getTop()` 作为滚动位置（在复杂嵌套布局中 `getTop()` 仅是相对直接父容器的偏移，会导致光标滚出可视区域之外）。
   - ✅ **强制**使用 `NokiaListFocusHelper.smoothScrollToVisible(scroll, target)`，它通过递归累加祖先绝对偏移量，确保条目始终完整露在视口中央。

### 4.5 核心按键语义摘要
- **音乐库 / 列表页**：
  - `UP` / `DOWN`：上下移动条目高亮光标；
  - `SELECT` (确定)：播放选中歌曲并进入详情页；
  - `SOFT_LEFT` (左软键)：唤出标准选项菜单 (`NokiaOptionsDialog`)；
  - `SOFT_RIGHT` (右软键)：退出应用或返回上一级。
- **正在播放详情页**：
  - `SELECT` (确定)：播放 / 暂停 切换；
  - `LEFT` / `RIGHT`：上一曲 / 下一曲；
  - `UP` / `DOWN`：调节媒体音量；
  - `*` (星号键)：快速进入/退出全屏歌词浏览；
  - `#` (井号键)：切换播放模式（列表循环/单曲循环/随机播放）；
  - `SOFT_LEFT` (左软键)：呼出播放选项菜单；
  - `SOFT_RIGHT` (右软键)：返回列表主页（后台保持播放）。

---

## 五、视觉与 UI 设计规范

### 5.1 字体规范：点阵字体全局渲染
- ❌ **严禁**：使用系统默认无衬线字体（Roboto、Droid Sans、思源黑体等）。
- ✅ **强制**：所有界面文字必须通过 `NokiaFontManager` 渲染为复古点阵字体（内置 `ArkPixel-12px` 与 `FusionPixel-12px`）。
- ⚠️ **动态 View 规则**：在代码中动态 `new TextView` 或通过 Adapter 动态 inflate 添加到容器后，必须手动调用一次：
  ```java
  NokiaFontManager.applyToViewTree(newView);
  ```

#### 5.1.1 语义字号规范（6 级标准 Token）
- ❌ **严禁**：在 XML 中硬编码裸写数字字号（如 `android:textSize="14sp"`）或使用非整数 sp。
- ❌ **严禁**：在 ≤13sp 的点阵字体上滥用 `android:textStyle="bold"`（算法加粗会导致像素粘连模糊）。
- ✅ **强制**：统一引用生态 `@dimen/nokia_font_*` 语义 Token：

| 语义 Token | 基准字号 (1.0x) | 适用场景 |
| :--- | :---: | :--- |
| **`@dimen/nokia_font_display`** | **16sp** | 待机大时钟、关于页应用名、品牌大标题 |
| **`@dimen/nokia_font_title`** | **13sp** | 顶栏页面标题、弹窗标题栏、播放器歌曲大名、OK 中键 |
| **`@dimen/nokia_font_body`** | **12sp** | **核心正文**：单列设置列表项、反馈表单主项、歌曲/歌单名 |
| **`@dimen/nokia_font_small_title`** | **11sp** | 左右软键文本、表单提交按钮、分组小标题、选项弹窗行 |
| **`@dimen/nokia_font_caption`** | **9sp** | 副标题（歌手/专辑）、输入框占位符/提示、底部说明、九宫格应用名 |
| **`@dimen/nokia_font_micro`** | **7sp** | 极小角标（未读数/下载进度等徽标） |

### 5.2 配色规范：动态生态主题跟随
- ❌ **严禁**：在布局 XML 或 Java 代码中硬编码具体颜色值（如 `#FF0000` / `#2196F3` / `#000000`）。
- ✅ **强制**：所有前景色、背景色、高亮色必须从 `NokiaTheme.ThemeDef` 或 `NokiaTheme.getCurrentTheme()` 中获取：

| 主题色字段 | 语义与用途 |
| :--- | :--- |
| `primaryColor` | 主色（顶栏与软键栏渐变起点） |
| `darkColor` | 深色底（窗口内容区背景、渐变终点） |
| `focusColor` | 列表选中行的高亮背景色 |
| `textColor` | 主文本颜色（一般为纯白或高亮浅色） |
| `subTextColor` | 副文本颜色（灰色/次级信息） |
| `cardBgColor` | 卡片背景色（弹窗主容器底色） |

- **选中高亮标准实现**：
  ```java
  // 列表行选中背景（4dp 圆角矩形）
  Drawable selectDrawable = NokiaTheme.createSelectionDrawable(context, 4f);
  itemView.setBackground(selectDrawable);
  ```

### 5.3 图标规范：统一 MaterialIcons 字符图标
- ❌ **严禁**：在项目中引入零散的 PNG 图片或独立的 XML Vector 图标（会导致不同分辨率下留白失真、边缘模糊）。
- ✅ **强制**：统一使用 `NokiaIcons` 获取 Google Material Icons（内置 2500+ 图标，矢量光栅化居中，边缘极其锐利）：

```java
// 获取指定图标、指定尺寸（DP）、指定颜色的 Drawable
Drawable icon = NokiaIcons.createDrawable(context, NokiaIcons.ICON_SETTINGS, 20, Color.WHITE);
imageView.setImageDrawable(icon);
```

#### 常用尺寸标准：
- **桌面/小组件单行图标**：`20dp`
- **列表行/菜单项图标**：`22dp`
- **弹窗选项图标**：`18dp`
- **快捷开关图标**：`18dp`

### 5.4 软键栏规范：静态标签与 INVISIBLE 占位
1. **软键栏禁止高亮**：
   - 底部左/中/右文字只是物理按键的静态标签，**绝对禁止**为软键加 `bg_nokia_selected` 选中背景，**绝对禁止**用左右方向键在软键间切换高亮。
2. **隐藏必须用 `View.INVISIBLE`**：
   - 软键栏为三栏等宽布局（`0dp + weight=1`）。
   - 当某个软键为空时，底层通过 `View.INVISIBLE` 隐藏，**严禁使用 `View.GONE`**（`GONE` 会丢失占位宽度，导致三栏塌陷，中间标题偏向一侧）。
3. **中间标题字号动态自适应**：
   - ≤4 字：`12sp`
   - 5~6 字：`11sp`
   - ≥7 字：`10sp`
   - 单行截断，中间省略（`ellipsize="middle"`）。

### 5.5 屏幕与分辨率适配
- 主要适配 **240×320** 与 **320×480** 两种分辨率；次要兜底 16:9 及以上长屏（小米设备）。
- UI 采用 240dp 设计基准 + 运行时缩放，验证时需在 240×320（`4a24ecf`）与 320×480（tcpip）两台真机上截图确认无横向溢出、无裁切错位。

---

## 六、标准复古弹窗体系

宿主应用**严禁使用 Android 原生 `AlertDialog`、`PopupWindow` 或系统原生 `Toast`**。必须使用 SDK 提供的诺基亚复古弹窗组件：

```
┌───────────────────────────────────────────────────────────┐
│              SDK 标准弹窗体系 (Gravity.BOTTOM)            │
│  - 底部弹出、透明遮罩、紧凑复古卡片底色                    │
│  - 自动绑定当前生态主题渐变顶栏与软键                     │
│  - 内置 NokiaDialogFocus.forceNonTouchMode 首键防吞修复   │
└───────────────────────────────────────────────────────────┘
```

### 6.1 选项菜单：`NokiaOptionsDialog`
用于替代原生弹出菜单/上下文菜单：
```java
new NokiaOptionsDialog(context, "歌曲选项")
    .addItem(1, "播放", NokiaIcons.createDrawable(context, NokiaIcons.ICON_PLAY, 18, Color.WHITE))
    .addItem(2, "添加到歌单", NokiaIcons.createDrawable(context, NokiaIcons.ICON_ADD, 18, Color.WHITE))
    .addItem(3, "删除歌曲", NokiaIcons.createDrawable(context, NokiaIcons.ICON_DELETE, 18, Color.WHITE))
    .setOnOptionSelectedListener((index, item) -> {
        switch (item.getId()) {
            case 1: play(); break;
            case 2: addToPlaylist(); break;
            case 3: delete(); break;
        }
    })
    .show();
```

### 6.2 确认提示框：`NokiaConfirmDialog`
用于替代原生确认/提示对话框：
```java
new NokiaConfirmDialog(context, "删除确认", "确定要删除该条记录吗？")
    .setPositiveButton("删除", () -> doDelete())
    .setNegativeButton("取消", null)
    .show();
```
> **注意**：`NokiaConfirmDialog` 采用“先 dismiss 再回调”机制，杜绝弹窗叠加造成的窗口 Token 泄漏。

### 6.3 文本输入框：`NokiaInputDialog`
用于快速单行文本输入（如重命名、新建歌单、搜索输入）：
```java
new NokiaInputDialog(context, "新建歌单", "", "请输入歌单名称")
    .setOnInputConfirmListener(text -> createPlaylist(text))
    .show();
```

---

## 七、跨进程通信与 AndroidManifest 配置

为了实现与 KeydroidX Launcher 桌面中枢的数据互联（用户在桌面改键、换主题、切字体，宿主 App 瞬间无感热重载），宿主 App 的 `AndroidManifest.xml` 必须声明包可见性与 Provider 权限：

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.example.myapp">

    <!-- 适配 Android 11+ (API 30+) 包可见性声明，否则无法跨进程查询桌面 Provider -->
    <queries>
        <package android:name="io.github.cctyl.nokia" />
        <package android:name="io.github.cctyl.nokia.debug" />
        <provider android:authorities="io.github.cctyl.nokia.keyprovider" />
        <provider android:authorities="io.github.cctyl.nokia.debug.keyprovider" />
    </queries>

    <application
        android:theme="@style/Theme.AppCompat.NoActionBar" ... >
        <!-- 宿主页面声明 -->
    </application>
</manifest>
```

---

## 八、统一日志规范（强制使用 NokiaLog）

为了配合生态统一日志收集与意见反馈（`NokiaFeedbackActivity`），**本项目禁止直接使用原生 `android.util.Log` 或引入第三方日志库，必须强制统一使用 `keydroidx-core` 提供的 `NokiaLog`**。

### 8.1 接入与使用规范
1. **Application 初始化**：
   ```kotlin
   NokiaLog.setTag("KeydroidX-Music")
   NokiaLog.init(this) // 自动读取详细日志开关决定落盘级别
   NokiaLog.installCrashHandler(this) // 崩溃自动抓取瞬时落盘
   ```
2. **业务代码打印（零成本桥接）**：
   - 在 Kotlin 文件头部添加别名导入，现有 `Log.d/i/w/e` 代码无需改动即可自动享受分级过滤与落盘：
     ```kotlin
     import io.github.cctyl.keydroidx.music.util.NLog as Log
     ```
   - 或直接调用：`NokiaLog.d("Tag", "msg")`、`NokiaLog.e("Tag", "msg", throwable)`。
3. **日志分级与持久化开关**：
   - **详细日志关闭（默认/Release）**：仅记录 `ERROR` 与 `FATAL` 崩溃堆栈，零文件 I/O 损耗。
   - **详细日志开启（Debug/排查）**：记录所有 `DEBUG`、`INFO`、`WARN` 业务日志。
   - 主界面/设置页选项菜单必须提供「详细日志：开/关」切换项（调用 `NokiaLog.setDetailedLogEnabled`）。
4. **日志落盘约定**：
   - 统一输出至 `/sdcard/Android/data/<包名>/log/yyyyMMdd.log`，按天自动轮转，保留 7 天。

---

## 九、构建与运行指南

### 8.1 环境与前置要求
- **JDK 17**：`gradle.properties` 中 `org.gradle.java.home` 硬编码了本机环境路径（换机器需修改）与 Android SDK。
- **本地依赖**：依赖同级 `../keydroidx-core`，`settings.gradle` 通过 `includeBuild` + `dependencySubstitution` 将 `io.github.cctyl.nokia:nokia-key-core` 替换为本地项目。

### 8.2 构建与测试命令
- **Debug 构建**：
  ```bash
  gradlew.bat assembleDebug
  # 或直接运行 build_debug.bat，输出 app/build/outputs/apk/debug/app-debug.apk
  ```
- **一键构建 + 安装 + 启动**：
  ```bash
  build_install_debug.bat [serial]
  # 内部调用 install_debug.py，无参时并行安装到所有 adb 在线设备，随后启动 MainActivity
  ```
- **Release 构建**：
  ```bash
  gradlew.bat assembleRelease -x lint
  # 或运行 build_release.bat（Release 必须跳过 lint，否则构建失败）
  # 签名统一使用 app/test.jks（debug/release 共用，别名 key0）
  ```
- **单元测试**：
  ```bash
  gradlew.bat testDebugUnitTest
  # 已配置 junit4 + org.json，PlaylistApi、LrcParser 等纯解析逻辑可写 JVM 单测
  ```

### 9.3 测试设备说明
- `4a24ecf`：240×320（Android 4.4，可直装）
- `tcpip 连接设备`：320×480（可直装）
- `jz5dauzlu8euw4e6`：小米 16:9 长屏（**不支持 adb 直装**，需 push 到 `/sdcard/Download` 手动安装）

---

## 十、开发自查清单

- [ ] **继承合规**：所有 Activity 继承自 `NokiaBaseActivity`，所有内容 Fragment 继承自 `NokiaListPageFragment` / `NokiaScrollPageFragment` / `NokiaPageFragment`。
- [ ] **日志合规**：全工程禁止直接使用原生 `android.util.Log`，强制使用 `NokiaLog`（或 `import ...NLog as Log`），确保日志可分级过滤与落盘。
- [ ] **软键声明**：软键文字与标题全部通过覆写 `getPageTitle()` / `getSoftLeftText()` / `getSoftRightText()` 实现，无直接 `findViewById` 操作软键栏代码。
- [ ] **软键无高亮**：底部软键栏无任何背景高亮设置、无左右方向键焦点切换代码。
- [ ] **字体合规**：所有文字均为点阵字体，动态添加的 View 已调用 `NokiaFontManager.applyToViewTree(view)`。
- [ ] **配色合规**：无任何硬编码颜色，所有色值与高亮背景均来自 `NokiaTheme`。
- [ ] **图标合规**：无零散 PNG / XML 图标，全部使用 `NokiaIcons` 生成。
- [ ] **按键语义**：物理按键全走 `NokiaKeyBinding.resolveAction(event)`，无硬编码 KeyCode。
- [ ] **按键配对**：所有自定义消费 DOWN 事件之处均已拦截 UP 事件。
- [ ] **首键防吞**：条目声明了 `focusableInTouchMode="true"`，外层 ScrollView 声明了 `focusable="false"`，页面进入后第 1 次按方向键立即响应。
- [ ] **弹窗合规**：无原生 `AlertDialog` / `Toast`，全部使用 `NokiaOptionsDialog` / `NokiaConfirmDialog` / `NokiaInputDialog`。
- [ ] **包可见性**：`AndroidManifest.xml` 中已包含 `<queries>` 桌面 Provider 声明。

---

## 十一、参考文档与设计资产

- **UI 布局与按键状态机详细规范**：👉 **[UI_DESIGN_SPEC.md](./UI_DESIGN_SPEC.md)**
- **交互原型单文件（浏览器可用键盘验证）**：`nokia_music_ui_mockup.html`
- **页面视觉描述文档**：
  - `ui_desc_01_我的音乐库.md`
  - `ui_desc_02_发现音乐.md`
  - `ui_desc_03_云音乐排行榜.md`
  - `ui_desc_04_歌曲搜索.md`
  > *注：HTML 原型与页面描述可能含 mock / 示意数值，以代码实际行为为准。*
