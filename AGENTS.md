# KeydroidX Music (原键音乐) - 智能体协作指南 (AGENTS.md)

本项目是基于 **KeydroidX 按键机生态** 构建的独立轻量级音乐播放器，专为物理九键/全键盘 Android 按键机量身定制。

---

## 一、 项目定位与生态架构关系

在 KeydroidX 生态中，各个项目分工明确、高度解耦：

- **KeydroidX Launcher (桌面端 / 中枢)**：
  - 负责待机桌面、通知栏与按键机全局设置。
  - 通过 `NokiaKeyProvider` 向外提供物理按键映射、主题、字体及缩放配置。
- **keydroidx-core (通用 SDK 核心库)**：
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

## 三、 按键交互语义

- **音乐库列表**：
  - `UP` / `DOWN`：上下移动光标；
  - `SELECT` (确定)：播放选中歌曲并进入详情页；
  - `SOFT_LEFT` (左软键)：唤出选项菜单；
  - `SOFT_RIGHT` (右软键)：退出应用。
- **正在播放详情页**：
  - `SELECT` (确定)：播放 / 暂停 切换；
  - `LEFT` / `RIGHT`：上一曲 / 下一曲；
  - `UP` / `DOWN`：音量增减；
  - `SOFT_LEFT` (左软键)：切换播放模式（列表循环/单曲循环/随机）；
  - `SOFT_RIGHT` (右软键)：返回列表（后台继续播放）。
