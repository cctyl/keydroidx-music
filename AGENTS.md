# KeydroidX Music (原键音乐) 
本项目是基于 **KeydroidX 按键机生态** 构建的独立轻量级音乐播放器，专为物理九键/全键盘 Android 按键机量身定制。

本应用采用“功能逻辑外调，原生UI驱动”策略：底层核心音乐播放与请求逻辑移植自 `fork_Ncrust` 项目，UI 层完全重构以符合 KeydroidX 生态的物理按键交互与点阵设计规范。

---

##  项目定位与生态架构关系

在 KeydroidX 生态中，各个项目分工明确、高度解耦：

- **KeydroidX Launcher (桌面端 / 中枢)**：
  - ../keydroidx-launcher
  - 负责待机桌面、通知栏与按键机全局设置。
  - 通过 `NokiaKeyProvider` 向外提供物理按键映射、主题、字体及缩放配置。
- **keydroidx-core (通用 SDK 核心库)**：
  - ../keydroidx-core
  - 生态客户端通用基础库（包含 Model, Client, UI, Dialog）。
  - 负责三级降级按键解析、跨进程配置热同步与复古 UI 规范。
- **keydroidx-music (本项目 - 独立应用)**：
  - 纯物理按键驱动的轻量音乐播放器。
  - 依赖 `keydroidx-core` 并继承 `NokiaBaseActivity`，开箱即用获得生态能力。

---

##  核心机制与协同工作流

1. **零二次配置**：
   - 用户在 KeydroidX 桌面配好物理按键或切换主题/字体后，音乐播放器通过 ContentProvider 即时自动换肤并生效，无需用户进入音乐 App 重新配键。
2. **三级平滑降级**：
   - 优先同步桌面配置；若设备未安装桌面，则平滑降级为本应用本地独立配置；若无本地配置则回退为 Android 系统标准键值。
3. **开箱即用的复古 UI**：
   - 列表页与播放详情页统一采用 240dp 基准规范，自动应用生态点阵字体（ArkPixel / FusionPixel）与 MaterialIcons 矢量图标。
   - 弹窗统一采用 SDK 内置的 `NokiaOptionsDialog`（选项菜单）与 `NokiaConfirmDialog`（确认弹窗）。

---

##  项目路径
- 本应用: `D:\project\keydroidx_ecosystem\keydroidx-music`
- 参考库/项目 (Ncrust): `D:\project\fork_Ncrust`

##  应用架构（本仓库代码速览）

- **纯 Activity 架构，无 Fragment**：所有界面继承 `keydroidx-core` 的 `NokiaBaseActivity`。基类骨架自带「顶栏 + 内容区 + 三段式软键栏」，按键经 `NokiaKeyBinding.resolveAction` 解析为语义动作后回调 `onAction(action)`；子类只需覆写 `onAction` 处理动作，并通过 `setSoftKeys(左, 中, 右)`、`setTitleText`、`setStatusBarVisible` 等装配 UI。顶栏/软键栏主题、点阵字体、电池/信号由基类自动应用。
- **播放状态总线**：`player/PlaybackStateManager.kt` 为全局单例（Kotlin StateFlow），UI 订阅它渲染播放态；Activity 通过给 `PlaybackService` 发 Intent action（`ACTION_PLAY_INDEX` / `PLAY_PAUSE` / `NEXT` / `PREV` / `TOGGLE_MODE` / `SEEK`）下发控制。`PlaybackService` 是 Media3 `MediaSessionService` + ExoPlayer，已内置 VIP 歌曲（fee=1）自动跳过、取链失败自动跳下一首。
- **网络层**：`network/RetrofitClient.kt` 封装网易云两套协议 —— ① Retrofit 普通 GET/POST（`NcmApi`：搜索/歌词/详情等公开接口）；② eapi 加密接口（`eapiPost` + `network/crypto/EapiCrypto`：歌单/取链/账号等，走 interface3 与 music.163 双主机回退）。`player/SongUrlFetcher` 按音质 5 级降级取链。`network/PlaylistApi` 提供歌单/用户资料/红心等业务接口（JSON 手解析）。
- **数据/本地层**：`auth/`（Cookie 持久化、用户资料缓存）、`cache/`（Content/PlaylistSong 内存缓存）、`library/`（收藏与最近播放，SharedPreferences + Gson）、`lyric/LrcParser`（LRC 解析）、`warmup/AppWarmup`。
- **UI 页面**：`ui/MainActivity`（四 Tab：我的/发现/榜单/搜索）、`ui/PlaylistDetailActivity`（歌单歌曲列表，懒加载分页）、`ui/MusicPlayerActivity`（黑胶唱机详情页，含全屏歌词、音量浮层）、`ui/WebLoginActivity`（网易云 WebView 登录）。

##  构建与运行

- 前置：**JDK 17**（`gradle.properties` 中 `org.gradle.java.home` 硬编码了本机 `D:\soft\temurin-jdk17\...`，换机器需改）与 Android SDK。依赖同级 `../keydroidx-core`：`settings.gradle` 通过 `includeBuild` + `dependencySubstitution` 将 `io.github.cctyl.nokia:nokia-key-core` 替换为本地项目。
- Debug 构建：`gradlew.bat assembleDebug`（或直接运行 `build_debug.bat`）。输出 `app/build/outputs/apk/debug/app-debug.apk`。
- 一键构建 + 安装 + 启动：`build_install_debug.bat [serial]`。内部调用 `install_debug.py`，无参时并行安装到所有 adb 在线设备（单台失败不影响其他），随后启动 MainActivity。
- Release 构建：`gradlew.bat assembleRelease -x lint`（或 `build_release.bat`；**release 必须跳过 lint**，否则构建失败）。签名统一使用 `app/test.jks`（debug/release 共用，别名 key0，密码见 `gradle.properties`）。
- 单元测试：`gradlew.bat testDebugUnitTest`。当前 `app/src/test` 为空；已配置 junit4 + org.json，`PlaylistApi`、`LrcParser` 等纯解析逻辑可写 JVM 单测。
- 测试设备（`adb devices`）：`4a24ecf` = 240×320（Android 4.4，可直装）；tcpip 连接设备 = 320×480（可直装）；`jz5dauzlu8euw4e6` = 小米 16:9 长屏（**不支持 adb 直装**，需 push 到 /sdcard/Download 手动安装）。

## 开发核心约束 (NOKIA_DEVELOPMENT_RULES.md)
在进行任何 UI 实现或按键映射时，必须严格遵循以下原则：

- **按键解耦**：严禁硬编码 `keyCode`，必须复用 `keydroidx-core` 中的 `NokiaKeyBinding` 来解析物理按键交互。
- **UI 规范**：
  - 必须使用内置矢量字体 `MaterialIcons`，禁止新增 PNG/XML 图标。
  - 严禁硬编码主题颜色，必须通过 `NokiaTheme` 系列工具生成适配主题的 drawable。
- **生命周期安全**：在异步回调或 Fragment 更新中，必须守护 Context，防止出现 `IllegalStateException` 导致的黑屏或崩溃。
- **日志与提交**：在应该加日志的地方尽可能多加日志输出，方便排查问题；未经允许不得私自提交 git。
- 按键机开发约束文档： ./NOKIA_DEVELOPMENT_RULES.md ，必须遵守，若与其他文档有冲突，应该向我询问
  - ⚠️ **适用边界**：该文档为 KeydroidX Launcher（包名 `ru.playsoftware.j2meloader`）编写，其中 Fragment 基类体系（`NokiaPageFragment`/`NokiaListPageFragment`/`NokiaScrollPageFragment`）、`NokiaDesktopActivity`、网格行数预算、240dp 根宽规范等在本项目**不存在**（本项目无 Fragment，全部为 Activity，但是后续可以参考）。本项目适用的部分（MaterialIcons 图标、`NokiaKeyBinding` 按键解耦、软键栏禁止高亮、Android 4.4 兼容、生命周期守卫）大多已由 `NokiaBaseActivity` 内置，业务代码只需遵守「覆写 `onAction` + 声明式装配 UI + 不硬编码 keyCode / 颜色 / 图标」。

## 按键交互与 UI 设计标准
详细的 UI 布局规范、全套按键交互状态机以及页面设计详见专项文档：
👉 **[UI_DESIGN_SPEC.md](./UI_DESIGN_SPEC.md)**
👉 交互原型单文件：`nokia_music_ui_mockup.html`

核心按键语义摘要：
- **音乐库/列表页**：
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


## 分辨率适配
主要适配 **240×320** 与 **320×480** 两种分辨率；次要兜底 16:9 及以上长屏（小米设备）。UI 采用 240dp 设计基准 + 运行时缩放，验证时需在 240×320（4a24ecf）与 320×480（tcpip）两台真机上截图确认无横向溢出、无裁切错位。

## 页面描述与设计文档
- UI 布局 / 按键状态机规范：`UI_DESIGN_SPEC.md`；交互原型（浏览器打开可用键盘操作验证）：`nokia_music_ui_mockup.html`
- 各页面视觉描述：`ui_desc_01_我的音乐库.md` ~ `ui_desc_04_歌曲搜索.md`
- HTML 原型与页面描述可能含 mock / 示意数值，以代码实际行为为准