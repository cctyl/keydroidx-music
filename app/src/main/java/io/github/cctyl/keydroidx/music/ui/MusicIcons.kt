package io.github.cctyl.keydroidx.music.ui

/**
 * 音乐 App 侧图标码点补充。
 *
 * 用途：`keydroidx-core` 的 [io.github.cctyl.nokia.keycore.ui.NokiaIcons] 是生态共享字典，
 * 不允许为了单个宿主 App 随意追加。这里只补充音乐业务必需、而 SDK 字典中缺失的
 * Material Icons 码点，仍然用同一套 `MaterialIcons-Regular.ttf` 渲染，
 * 因此视觉与 SDK 图标完全一致（矢量、锐利、可任意染色）。
 *
 * ⚠️ 若目标设备的字体文件缺少某个字形，会渲染成空白方框，
 *    此时应把对应常量改指向 SDK 中确定存在的近似图标（见注释）。
 */
object MusicIcons {

    /** 评论气泡（Material `chat_bubble` 实心）。缺字形时回退 `NokiaIcons.ICON_SUBTITLES`。 */
    const val COMMENT = "\uE0CA"

    /** 点赞（Material `thumb_up`）。缺字形时回退 `NokiaIcons.ICON_FAVORITE`。 */
    const val THUMB_UP = "\uE8DC"
}
