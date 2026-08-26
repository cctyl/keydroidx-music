package io.github.cctyl.keydroidx.music.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import io.github.cctyl.nokia.keycore.NokiaClient

/**
 * 音乐 App 专属主题色表。
 *
 * 与桌面的关系：桌面（KeydroidX Launcher）只通过 NokiaClient 告知「当前是哪套主题」，
 * 具体每一套主题在音乐 App 内的配色由本表定义，取值与交互原型
 * docs/nokia_music_ui_mockup.html 中 themes 表完全一致。
 *
 * 品牌色（不随主题变化）：#38BDF8 天蓝高亮、#22D3EE 正在播放、#FDE047 中软键。
 */
object MusicTheme {

    /** 单套主题的完整配色（字段名与 HTML 原型 CSS 变量一一对应） */
    data class Palette(
        val id: String,
        val name: String,
        val primary: Int,    // --theme-primary 标题栏/软键栏渐变起始
        val dark: Int,       // --theme-dark   渐变结束/深底
        val accent: Int,     // --theme-accent
        val focus: Int,      // --theme-focus  焦点行背景
        val text: Int,       // --theme-text
        val subtext: Int,    // --theme-subtext
        val cardBg: Int,     // --theme-cardbg 卡片底
        val bodyBg: Int,     // --theme-bodybg 页面底
        val border: Int,     // --theme-border 描边
        val dashed: Int      // --theme-dashed 虚线分隔
    )

    // ── 品牌 fixed 色 ──────────────────────────────
    const val BRAND_ACCENT = 0xFF38BDF8.toInt()   // 列表图标 / 搜索框描边等高亮
    const val BRAND_PLAYING = 0xFF22D3EE.toInt()  // 正在播放指示
    const val BRAND_SOFTKEY_CENTER = 0xFFFDE047.toInt() // 中软键文字
    val SUBTEXT_FOCUSED = 0xFFE0F2FE.toInt()      // 焦点态副文字（浅蓝白）

    private val CLASSIC_BLUE = Palette(
        "classic_blue", "经典深蓝",
        Color.parseColor("#1a3a6b"), Color.parseColor("#0d1b3e"),
        Color.parseColor("#0055AA"), Color.parseColor("#0055AA"),
        Color.WHITE, Color.parseColor("#B0B0B0"),
        Color.parseColor("#141824"), Color.parseColor("#090c13"),
        Color.parseColor("#233454"), Color.parseColor("#2D426B")
    )

    private val THEMES = linkedMapOf(
        CLASSIC_BLUE.id to CLASSIC_BLUE,
        "obsidian_black" to Palette(
            "obsidian_black", "曜石纯黑",
            Color.parseColor("#2D2D2D"), Color.parseColor("#141414"),
            Color.parseColor("#4A4A4A"), Color.parseColor("#383838"),
            Color.WHITE, Color.parseColor("#A0A0A0"),
            Color.parseColor("#181818"), Color.parseColor("#0c0c0c"),
            Color.parseColor("#3a3a3a"), Color.parseColor("#333333")
        ),
        "cyan_sea" to Palette(
            "cyan_sea", "青海浩渺",
            Color.parseColor("#005A70"), Color.parseColor("#002A35"),
            Color.parseColor("#00838F"), Color.parseColor("#00838F"),
            Color.WHITE, Color.parseColor("#80DEEA"),
            Color.parseColor("#0c1d24"), Color.parseColor("#041117"),
            Color.parseColor("#184552"), Color.parseColor("#124250")
        ),
        "emerald_green" to Palette(
            "emerald_green", "翡翠幽绿",
            Color.parseColor("#1B4D2E"), Color.parseColor("#0C2616"),
            Color.parseColor("#2E7D32"), Color.parseColor("#2E7D32"),
            Color.WHITE, Color.parseColor("#A5D6A7"),
            Color.parseColor("#112217"), Color.parseColor("#07130b"),
            Color.parseColor("#1e472a"), Color.parseColor("#183e24")
        ),
        "wine_purple" to Palette(
            "wine_purple", "典雅酒红",
            Color.parseColor("#4A154B"), Color.parseColor("#250826"),
            Color.parseColor("#6A1B9A"), Color.parseColor("#6A1B9A"),
            Color.WHITE, Color.parseColor("#CE93D8"),
            Color.parseColor("#221124"), Color.parseColor("#120613"),
            Color.parseColor("#4b1e4e"), Color.parseColor("#3d1440")
        ),
        "amber_gold" to Palette(
            "amber_gold", "琥珀暖金",
            Color.parseColor("#5C4300"), Color.parseColor("#2E2000"),
            Color.parseColor("#E65100"), Color.parseColor("#D97706"),
            Color.WHITE, Color.parseColor("#FFE082"),
            Color.parseColor("#241d0f"), Color.parseColor("#140f06"),
            Color.parseColor("#543f17"), Color.parseColor("#42310f")
        )
    )

    /** 当前生效主题（桌面同步；未装桌面时回退本地配置；最终兜底经典深蓝） */
    fun current(context: Context): Palette {
        val id = try {
            NokiaClient.get(context).currentThemeId
        } catch (e: Exception) {
            CLASSIC_BLUE.id
        }
        return THEMES[id] ?: CLASSIC_BLUE
    }

    /** 焦点行背景（圆角矩形），运行时按当前主题生成 */
    fun createFocusDrawable(context: Context, radiusDp: Float): GradientDrawable {
        val density = context.resources.displayMetrics.density
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(current(context).focus)
            cornerRadius = radiusDp * density
        }
    }

    /** 卡片背景（卡片底色 + 描边），取值对应 HTML --theme-cardbg / --theme-border */
    fun createCardDrawable(context: Context, radiusDp: Float): GradientDrawable {
        val p = current(context)
        val density = context.resources.displayMetrics.density
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(p.cardBg)
            setStroke(dp(1, density), p.border)
            cornerRadius = radiusDp * density
        }
    }

    private fun dp(v: Int, density: Float) = (v * density + 0.5f).toInt()

    // ── XML 静态色 → 当前主题色 运行时重映射 ──────────────────
    // 布局里 @color/music_* 引用的是经典深蓝的静态值；主题切换后需要
    // 把这些颜色重刷为当前主题对应色。key = 静态色值，value = 取当前主题色。
    private val COLOR_REMAP: Map<Int, (Palette) -> Int> = mapOf(
        0xFFB0B0B0.toInt() to { p -> p.subtext },   // music_subtext / music_item_subtext
        0xFF2D426B.toInt() to { p -> p.dashed },    // music_divider / music_progress_bg
        0xFF0055AA.toInt() to { p -> p.focus },     // music_focus_bg / music_primary / music_item_selected
        0xFF090C13.toInt() to { p -> p.bodyBg },    // music_dark_bg / music_bg
        0xFF1A3A6B.toInt() to { p -> p.primary }    // music_tab_bottom_line / music_titlebar_top
    )

    private fun remap(color: Int, p: Palette): Int? = COLOR_REMAP[color]?.invoke(p)

    /**
     * 递归遍历视图树，把静态经典蓝配色替换为当前主题色。
     * 在 onInitViews 与 onThemeChanged 后调用一次即可。
     */
    fun applyToViewTree(root: android.view.View, palette: Palette = current(root.context)) {
        if (root is android.widget.TextView) {
            remap(root.currentTextColor, palette)?.let { root.setTextColor(it) }
            remap(root.hintTextColors.defaultColor, palette)?.let { root.setHintTextColor(it) }
        }
        root.background?.let { bg ->
            if (bg is android.graphics.drawable.ColorDrawable &&
                bg.color != Color.TRANSPARENT
            ) {
                remap(bg.color, palette)?.let { root.background = android.graphics.drawable.ColorDrawable(it) }
            }
        }
        if (root is android.view.ViewGroup) {
            for (i in 0 until root.childCount) applyToViewTree(root.getChildAt(i), palette)
        }
    }
}
