# KeydroidX Music (原键音乐) 
本项目是基于 **KeydroidX 按键机生态** 构建的独立轻量级音乐播放器，专为物理九键/全键盘 Android 按键机量身定制。

本应用采用“功能逻辑外调，原生UI驱动”策略：底层核心音乐播放与请求逻辑移植自 `fork_Ncrust` 项目，UI 层完全重构以符合 KeydroidX 生态的物理按键交互与点阵设计规范。

---

## 一、 项目定位与生态架构关系

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

## 二、 核心机制与协同工作流

1. **零二次配置**：
   - 用户在 KeydroidX 桌面配好物理按键或切换主题/字体后，音乐播放器通过 ContentProvider 即时自动换肤并生效，无需用户进入音乐 App 重新配键。
2. **三级平滑降级**：
   - 优先同步桌面配置；若设备未安装桌面，则平滑降级为本应用本地独立配置；若无本地配置则回退为 Android 系统标准键值。
3. **开箱即用的复古 UI**：
   - 列表页与播放详情页统一采用 240dp 基准规范，自动应用生态点阵字体（ArkPixel / FusionPixel）与 MaterialIcons 矢量图标。
   - 弹窗统一采用 SDK 内置的 `NokiaOptionsDialog`（选项菜单）与 `NokiaConfirmDialog`（确认弹窗）。

---

## 四、 项目路径
- 本应用: `D:\project\keydroidx_ecosystem\keydroidx-music`
- 参考库/项目 (Ncrust): `D:\project\fork_Ncrust`

## 五、 移植分阶段计划
我们会采取“先逻辑后UI”的原则，分模块进行代码迁移与适配：

1. **移植网络接口层 (Network/API)**
   - 迁移 `network` 相关代码，重构 API 调用逻辑以适配生态规范。
2. **移植播放核心与状态管理 (Player)**
   - 迁移 `PlaybackService` 及相关状态流，确保后台播放逻辑独立稳定。
3. **本地库与缓存逻辑 (Library/Cache)**
   - 迁移歌单、播放队列及本地缓存策略。
4. **UI 重构与按键适配**
   - 基于上述逻辑模块，重新设计符合 240dp 规范并适配物理按键的界面。

## 六、 开发核心约束 (NOKIA_DEVELOPMENT_RULES.md)
在进行任何 UI 实现或按键映射时，必须严格遵循以下原则：

- **按键解耦**：严禁硬编码 `keyCode`，必须复用 `keydroidx-core` 中的 `NokiaKeyBinding` 来解析物理按键交互。
- **UI 规范**：
  - 必须使用内置矢量字体 `MaterialIcons`，禁止新增 PNG/XML 图标。
  - 严禁硬编码主题颜色，必须通过 `NokiaTheme` 系列工具生成适配主题的 drawable。
- **生命周期安全**：在异步回调或 Fragment 更新中，必须守护 Context，防止出现 `IllegalStateException` 导致的黑屏或崩溃。

## 三、 按键交互与 UI 设计标准
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
