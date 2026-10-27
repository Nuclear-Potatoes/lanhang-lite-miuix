package com.lanhang.miuix.schedule

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import com.lanhang.miuix.R
import androidx.core.content.ContextCompat
import com.google.android.material.R as MaterialR

/**
 * 课表配色令牌，全部由应用主题（Miuix 语义色 / Material 属性）派生，
 * 保证课表与 Compose 页面在浅色、深色下都是同一套观感。
 */
class ScheduleStyle(
    val onBackground: Int,
    val onBackgroundVariant: Int,
    val outline: Int,
    val divider: Int,
    val cardFill: Int,
    val chipFill: Int,
    val accent: Int,
    val accentFill: Int,
    val onAccentFill: Int
) {

    /** 圆角矩形；fill 可带透明度（自定义背景下的半透明卡片） */
    fun rounded(
        density: Float,
        radiusDp: Float,
        fill: Int,
        strokeDp: Float = 0f,
        strokeColor: Int = Color.TRANSPARENT
    ): GradientDrawable = GradientDrawable().apply {
        cornerRadius = radiusDp * density
        setColor(fill)
        if (strokeDp > 0f) setStroke((strokeDp * density).toInt().coerceAtLeast(1), strokeColor)
    }

    /**
     * 课程色块上的文字。
     *
     * 统一用白字：以前按对比度在白/深之间自动选，导致同一屏里有的课名是黑的、有的是白的，
     * 看着像没统一过。现在一律白字，靠 [courseColor] 把过亮的色相压深来保证可读性
     * （见 [MIN_WHITE_CONTRAST]），而不是换文字颜色。
     */
    fun onCourse(@Suppress("UNUSED_PARAMETER") color: Int): Int = LIGHT_TEXT

    /** 天数摘要里的「N 门」小胶囊 */
    fun badgeFill(selected: Boolean): Int = if (selected) {
        withAlpha(onAccentFill, 0.18f)
    } else {
        withAlpha(onBackground, 0.10f)
    }

    /**
     * 课程块底色（Miuix 化，2026-10-24）：不用满色块，改成「同色相低透明度染色」，
     * 与 Miuix / HyperOS 日历的染色卡片是同一套观感；深色底自动提高透明度以保持可见。
     */
    fun courseFill(color: Int): Int =
        if (cardIsLight) pastel(color, 0.62f, 0.90f) else pastel(color, 0.40f, 0.26f)

    /**
     * 染底块上的文字与左侧色条：浅底用同色相高饱和深色，深底用提亮的同色，
     * 保证「块内文字」和「浅色底」始终是同一色相的两档明度（参考截图的做法）。
     */
    fun onCourseTint(color: Int): Int =
        if (cardIsLight) pastel(color, 0.72f, 0.38f) else pastel(color, 0.55f, 0.80f)

    /** 取原色色相，换成指定饱和/明度的 HSL 色（同色系浅底 + 深字） */
    private fun pastel(color: Int, s: Float, l: Float): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        return androidx.core.graphics.ColorUtils.HSLToColor(floatArrayOf(hsv[0], s, l))
    }

    /** 当前卡片底是浅色吗（决定染色深浅与文字取色方向） */
    private val cardIsLight: Boolean
        get() {
            val c = cardFill
            val r = Color.red(c) / 255f
            val g = Color.green(c) / 255f
            val b = Color.blue(c) / 255f
            return (0.299f * r + 0.587f * g + 0.114f * b) > 0.5f
        }

    companion object {
        val DARK_TEXT = 0xFF12181D.toInt()
        val LIGHT_TEXT = 0xFFFFFFFF.toInt()

        private const val FALLBACK_PRIMARY = 0xFF3482FF.toInt()

        /**
         * 课程配色：HSL 黄金角分布，同课同色、不同课色相差约 137°。
         *
         * 色块上一律白字，所以偏亮的色相（黄/绿/橙）会按原色相与饱和度压深，
         * 直到与白字的对比度达到 [MIN_WHITE_CONTRAST]；蓝/红这类本来就深的颜色不动。
         */
        fun courseColor(courseName: String): Int {
            // 第二十八轮：色相统一由 [CourseColors] 分配（全学期均分，互不重复，且与小部件同色）
            val hue = CourseColors.hueOf(courseName)
            return deepenForWhiteText(Color.HSVToColor(floatArrayOf(hue, 0.72f, 0.82f)))
        }

        /**
         * 白字版课程块的最低对比度。WCAG AA 对大字号要求 3:1，课名是 10sp 加粗，
         * 取 3.2 略留余量；实测 3.2 时黄/绿只比原来深一档，肉眼仍是同一个色。
         */
        private const val MIN_WHITE_CONTRAST = 3.2

        /** 保持色相/饱和度，仅降低明度直到白字可读；再深就会失真，所以下限卡在 0.34 */
        private fun deepenForWhiteText(color: Int): Int {
            if (contrast(color, LIGHT_TEXT) >= MIN_WHITE_CONTRAST) return color
            val hsv = FloatArray(3)
            Color.colorToHSV(color, hsv)
            var value = hsv[2]
            var current = color
            repeat(40) {
                value = (value - 0.015f).coerceAtLeast(0.34f)
                current = Color.HSVToColor(floatArrayOf(hsv[0], hsv[1], value))
                if (contrast(current, LIGHT_TEXT) >= MIN_WHITE_CONTRAST || value <= 0.34f) {
                    return current
                }
            }
            return current
        }

        fun fromTheme(context: Context): ScheduleStyle {
            val night = isNight(context)
            // 2026-10-27 第二十一轮：语义色一律读我们自己的资源（values / values-night 各一套）。
            // 原来读 Material 属性 + 写死 DARK_TEXT 兜底，只要宿主主题没定义那几个属性，
            // 深色下拿到就是近黑 —— 课表标题、页码、按钮全部消失在黑底里。
            val onBackground = res(context, R.color.text_primary)
            val variant = res(context, R.color.text_secondary)
            val accent = accentOf(context, night)
            return ScheduleStyle(
                onBackground = onBackground,
                onBackgroundVariant = variant,
                outline = res(context, R.color.divider),
                divider = withAlpha(onBackground, if (night) 0.16f else 0.10f),
                cardFill = res(context, R.color.card_bg),
                chipFill = res(context, R.color.card_bg_pressed),
                accent = accent,
                // 用户 2026-10-25：当天必须「蓝色文字 + 蓝色底」。
                // 2026-10-27 补：深色下 26% 半透明铺在纯黑上等于一块近黑，看不出「今天」，
                // 所以深色改成同色相的**实心深蓝** + 提亮的同色文字。
                accentFill = if (night) solidAccent(accent) else withAlpha(accent, 0.14f),
                onAccentFill = if (night) lift(accent, 0.55f) else accent,
            )
        }

        /** 强调色：跟随主题主色，深色模式往白里提一档（HyperOS 深色标准） */
        private fun accentOf(context: Context, night: Boolean): Int {
            val raw = themeColor(context, MaterialR.attr.colorPrimary, FALLBACK_PRIMARY)
            return if (night) lift(raw, 0.42f) else raw
        }

        /** 朝白插值（色相不变、明度上去） */
        private fun lift(color: Int, amount: Float): Int {
            val r = Color.red(color) + ((255 - Color.red(color)) * amount).toInt()
            val g = Color.green(color) + ((255 - Color.green(color)) * amount).toInt()
            val b = Color.blue(color) + ((255 - Color.blue(color)) * amount).toInt()
            return Color.rgb(r, g, b)
        }

        /** 同色相的实心深底：深色模式下「今天」那格必须是一块看得见的蓝 */
        private fun solidAccent(accent: Int): Int {
            val hsv = FloatArray(3)
            Color.colorToHSV(accent, hsv)
            return androidx.core.graphics.ColorUtils.HSLToColor(floatArrayOf(hsv[0], 0.58f, 0.34f))
        }

        private fun res(context: Context, id: Int): Int = ContextCompat.getColor(context, id)

        private fun themeColor(context: Context, attr: Int, fallback: Int): Int {
            val typed = context.obtainStyledAttributes(intArrayOf(attr))
            return try {
                typed.getColor(0, fallback)
            } finally {
                typed.recycle()
            }
        }

        private fun isNight(context: Context): Boolean =
            (context.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES

        /** 相对亮度（WCAG 2.x，sRGB 转线性） */
        fun relLuminance(color: Int): Double {
            fun channel(value: Int): Double {
                val s = value / 255.0
                return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
            }
            return 0.2126 * channel(Color.red(color)) +
                0.7152 * channel(Color.green(color)) +
                0.0722 * channel(Color.blue(color))
        }

        fun contrast(foreground: Int, background: Int): Double {
            val a = relLuminance(foreground)
            val b = relLuminance(background)
            val lighter = if (a > b) a else b
            val darker = if (a > b) b else a
            return (lighter + 0.05) / (darker + 0.05)
        }

        fun withAlpha(color: Int, alpha: Float): Int {
            val a = (alpha.coerceIn(0f, 1f) * 255f).toInt()
            return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
        }

    }
}
