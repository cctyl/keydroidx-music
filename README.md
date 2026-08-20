# KeydroidX Music (按键机音乐播放器)

专为 **KeydroidX（原键）按键机生态** 打造的轻量级、纯物理按键驱动音乐播放器。

---

## 核心特性
- **物理按键优先**：完全支持 DPAD 上下左右、确定、左右软键控制，无需触屏。
- **生态无缝同步**：基于 `nokia-key-core` SDK，自动与 KeydroidX 桌面（`nokia_desktop`）同步按键映射，支持热重载。
- **三级降级保护**：即使未安装生态桌面，亦可独立运行并内置独立按键向导。
- **复古 240dp 风格**：高对比度、低功耗、适配小屏按键机硬件。

---

## 架构与工程结构
```text
keydroidx-music/
├── app/
│   ├── src/main/java/io/github/cctyl/keydroidx/music/
│   │   ├── adapter/
│   │   │   └── MusicAdapter.java         # 支持物理光标选择的列表适配器
│   │   ├── model/
│   │   │   └── MusicItem.java            # 歌曲条目模型
│   │   └── ui/
│   │       ├── MainActivity.java         # 音乐主列表（继承 NokiaBaseActivity）
│   │       └── MusicPlayerActivity.java  # 播放详情面板
│   ├── src/main/res/                     # 布局与主题资源
│   └── build.gradle                      # 依赖 nokia-key-core
├── build.gradle
└── settings.gradle                       # includeBuild 依赖同级 keydroidx-core
```

---

## 编译与运行
```bash
# Debug 编译
./gradlew assembleDebug

# Release 签名打包
./gradlew assembleRelease
```
