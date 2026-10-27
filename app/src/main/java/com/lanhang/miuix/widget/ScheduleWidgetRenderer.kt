package com.lanhang.miuix.widget

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.graphics.ColorUtils
import com.lanhang.miuix.schedule.PeriodClock
import com.lanhang.miuix.session.Session
import com.lanhang.miuix.util.LocaleHelper
import com.lanhang.miuix.widget.ScheduleWidgetData.CourseBlock
import com.lanhang.miuix.widget.ScheduleWidgetData.WeekSnapshot
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.ceil
import kotlin.math.roundToInt

object ScheduleWidgetRenderer {

    /** 节次时间文本（仅 4×4 网格的时间列用）；时间表的唯一来源是 [PeriodClock] */
    private val periodTimes = Array(PeriodClock.count) { PeriodClock.rangeText(it + 1, it + 1) }
    private val dateFormatter = DateTimeFormatter.ofPattern("M-d")
    /** 规范里的日期写法是「无前导零 + 短横」（`8-16`），两个小部件统一用这个 */
    private val todayDateFormatter = DateTimeFormatter.ofPattern("M-d")

    fun render(
        appContext: Context,
        snapshot: WeekSnapshot?,
        widthDp: Int,
        heightDp: Int
    ): Bitmap {
        // 小部件文案跟着应用语言走（桌面进程默认给的是系统语言），见 LocaleHelper
        val context = LocaleHelper.wrap(appContext)
        val density = context.resources.displayMetrics.density
        val width = (widthDp * density).roundToInt().coerceAtLeast(1)
        val height = (heightDp * density).roundToInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val palette = palette(context)
        val radius = 18f * density
        paint.color = palette.background
        canvas.drawRoundRect(
            RectF(0f, 0f, width.toFloat(), height.toFloat()),
            radius,
            radius,
            paint
        )
        if (snapshot == null) {
            drawEmpty(canvas, paint, context, width, height, density, palette)
            return bitmap
        }
        CanvasRenderer(canvas, paint, context, density, width, height, palette, snapshot).draw()
        return bitmap
    }

    private fun drawEmpty(
        canvas: Canvas,
        paint: Paint,
        context: Context,
        width: Int,
        height: Int,
        density: Float,
        palette: Palette
    ) {
        val title = if (Session.isLoggedIn) {
            context.getString(com.lanhang.miuix.R.string.widget_empty_sync)
        } else {
            context.getString(com.lanhang.miuix.R.string.widget_empty_login)
        }
        val centerX = width / 2f
        val centerY = height / 2f
        // 规范 3「无内容场景」：标题（Demibold）+ 说明（40% 前景）+ 一颗行动胶囊。
        // 胶囊只是**看上去**可点：整张小部件都是同一个「打开课表」的 PendingIntent，
        // 点它和点别处效果一致，不是画出来的假按钮。
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.textSize = 14f * density
        paint.color = palette.onSurface
        paint.alpha = 255
        canvas.drawText(
            fitText(paint, title, width - 24f * density),
            centerX,
            centerY - 13f * density,
            paint
        )
        paint.typeface = Typeface.DEFAULT
        paint.textSize = 10.5f * density
        paint.color = palette.onSurfaceVariant
        canvas.drawText(
            context.getString(com.lanhang.miuix.R.string.widget_empty_hint),
            centerX,
            centerY + 5f * density,
            paint
        )
        if (height >= 96f * density) {
            drawActionPill(
                canvas, paint, palette, centerX, centerY + 30f * density, density,
                context.getString(com.lanhang.miuix.R.string.widget_empty_action)
            )
        }
    }

    /** 小部件没有 TextView，只能自己截断：放不下就用省略号（规范 4「多语言适配」） */
    private fun fitText(paint: Paint, text: String, maxWidth: Float): String {
        if (maxWidth <= 0f) return ""
        if (paint.measureText(text) <= maxWidth) return text
        var result = text
        while (result.isNotEmpty() && paint.measureText("$result…") > maxWidth) {
            result = result.dropLast(1)
        }
        return "$result…"
    }

    /** 空态行动胶囊：主色 12%（深色 20%）底 + 主色文字 = Miuix 的「浅色按钮」 */
    private fun drawActionPill(
        canvas: Canvas,
        paint: Paint,
        palette: Palette,
        centerX: Float,
        centerY: Float,
        density: Float,
        label: String
    ) {
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.textSize = 11f * density
        val pillW = paint.measureText(label) + 26f * density
        val pillH = 26f * density
        val radius = pillH / 2f
        paint.color = ColorUtils.setAlphaComponent(palette.primary, if (palette.isDark) 0x40 else 0x24)
        canvas.drawRoundRect(
            RectF(centerX - pillW / 2f, centerY - pillH / 2f, centerX + pillW / 2f, centerY + pillH / 2f),
            radius,
            radius,
            paint
        )
        paint.color = palette.primary
        paint.alpha = 255
        canvas.drawText(label, centerX, centerY + paint.textSize * 0.35f, paint)
        paint.textAlign = Paint.Align.LEFT
    }

    /** 2×2 底栏（胶囊 ◀ ⟳ ▶）占用的高度阈值：低于该高度完全不显示底栏 */
    const val FOOTER_MIN_HEIGHT_DP = 130

    /** 底栏高度：位图要在内容区里给它留出这么高，布局里的底栏也正好这么高 */
    const val FOOTER_HEIGHT_DP = 22

    /**
     * 今日课程最多同时显示的节数。
     *
     * 规则：上完的课立刻从小部件里消失，后面的课依次顶上来，所以任何时刻最多只有
     * 这么几节「还没上完」的课在列（不是按当天总节数截断）。
     */
    const val MAX_TODAY_ROWS = 3

    /* ---------- 4×4 周视图：课程名排版（竖排、最多两列、整组居中） ---------- */

    /** 课程名字号上限（块够高够宽时用这个） */
    private const val NAME_MAX_SIZE_DP = 13f

    /** 课程名字号下限：再小就糊成一团，宁可用「…」截断 */
    private const val NAME_MIN_SIZE_DP = 7f

    /**
     * 竖排行距倍数（相对字号）：一个字一行的字距。
     *
     * 课程名是竖排的（用户要求「竖着两排」）——横排会把色块左右撑满，
     * 竖排只占两个字宽，块内两侧都留白。
     *
     * 1.14 而不是 1.0：汉字字形高约 1.0em，但基线以下还有 descent（约 0.22em），
     * 行距太紧最后一行会压到底部的教室行上。
     */
    private const val NAME_ROW_SPACING = 1.14f

    /** 竖排列间距（相对字号）：两列之间留这么多空，0.12 字宽既不挤也不散 */
    private const val NAME_COL_GAP = 0.12f

    /** 课程名最多排几列：固定两列，再多就不是「竖着两排」了 */
    private const val NAME_MAX_COLS = 2

    /** 课程名左右内边距（窄列里必须小，否则字数的余量全被 pad 吃掉） */
    private const val NAME_PAD_H_DP = 2.5f

    /** 课程名上下内边距 */
    private const val NAME_PAD_V_DP = 2f

    /** 当前主题是否为深色（胶囊/按钮底色、图标颜色与此保持一致） */
    fun isDark(context: Context): Boolean = palette(context).isDark

    /**
     * 底栏胶囊文案：今天「第 N 周」，翻走后「明天 / 昨天 / +N 天」。
     *
     * 胶囊现在是布局里的 TextView（位图不再画画它），所以文案由这里出、由
     * [ScheduleWidgetUpdater] 绑上去；放不下时交给 TextView 自己 ellipsize。
     *
     * @param compact 窄尺寸（vivo 等桌面的 2×2 实测只有 110~130dp）用无空格短排版，
     *   同样的宽度能多显示一两个字，避免退化成「…」
     */
    fun footerLabel(appContext: Context, week: Int, target: LocalDate, compact: Boolean): String {
        val context = LocaleHelper.wrap(appContext)
        val offset = ChronoUnit.DAYS.between(LocalDate.now(), target)
        return when (offset) {
            0L -> context.getString(
                if (compact) {
                    com.lanhang.miuix.R.string.widget_week_compact
                } else {
                    com.lanhang.miuix.R.string.widget_week_format
                },
                week
            )
            1L -> context.getString(com.lanhang.miuix.R.string.widget_today_tomorrow)
            -1L -> context.getString(com.lanhang.miuix.R.string.widget_today_yesterday)
            else -> context.getString(
                com.lanhang.miuix.R.string.widget_day_offset_format,
                if (offset > 0) "+$offset" else "$offset"
            )
        }
    }

    /**
     * 「课表 · 小」2×2 紧凑档：今日课程列表。
     *
     * 排课规则（与桌面表现一致）：
     * - **上完即走**：今天已经下课的课不再出现，后面的课依次顶上来；始终最多 [MAX_TODAY_ROWS] 节
     * - **正在上课**：该行右侧显示「剩 N 分」（距下课），由 [ScheduleWidgetUpdater] 每分钟重绘一次
     * - 翻到别的日期（昨天/明天）时不做时间过滤，只按节次顺序取前几节
     * - 底部 ◀ ⟳ ▶：翻转前后一天 + 回到今天；复位按钮在「已翻走」时才高亮可点
     *
     * @param targetDate 要展示的日期（翻天导航）；null = 今天
     * @param now 渲染时刻（默认取系统时间；抽出参数便于测试与预览）
     */
    fun renderToday(
        appContext: Context,
        snapshot: WeekSnapshot?,
        widthDp: Int,
        heightDp: Int,
        targetDate: LocalDate? = null,
        now: LocalDateTime = LocalDateTime.now()
    ): Bitmap {
        // 同 render：小部件文案跟着应用语言走
        val context = LocaleHelper.wrap(appContext)
        val density = context.resources.displayMetrics.density
        val width = (widthDp * density).roundToInt().coerceAtLeast(1)
        val height = (heightDp * density).roundToInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val palette = palette(context)
        val radius = 18f * density
        paint.color = palette.background
        canvas.drawRoundRect(
            RectF(0f, 0f, width.toFloat(), height.toFloat()),
            radius,
            radius,
            paint
        )
        if (snapshot == null) {
            drawEmpty(canvas, paint, context, width, height, density, palette)
            return bitmap
        }
        TodayRenderer(
            canvas, paint, context, density, width, height, palette, snapshot,
            targetDate ?: now.toLocalDate(), now
        ).draw()
        return bitmap
    }

    /* ---------- 课程配色：淡彩底 + 同色系深字（Miuix 化，取代原来的「饱和色 + 白字」） ---------- */

    /** 课程色相：同课同色，不同课相差约 137°（黄金角），相邻课程的色相互相岔开。 */
    private fun courseHue(name: String): Float =
        (((name.hashCode() * 137.508f) % 360f) + 360f) % 360f

    /**
     * 色块底：卡底只借一点课程色相的染色（浅色 15% / 深色 26%）。
     *
     * 这样整张小部件仍是「白卡 + 一个主色」的 Miuix 观感，不会变成七彩色盘；
     * 区分度交给文字色（见 [courseInk]），一眼仍能认出「这是哪门课」。
     */
    private fun courseTint(name: String, palette: Palette): Int {
        val dyed = ColorUtils.HSLToColor(
            floatArrayOf(courseHue(name), 0.62f, if (palette.isDark) 0.62f else 0.52f)
        )
        return ColorUtils.blendARGB(palette.background, dyed, if (palette.isDark) 0.26f else 0.15f)
    }

    /** 色块文字：同色相的深（浅色主题）/ 亮（深色主题）版本，与底色对比度 ≥ 4.5 */
    private fun courseInk(name: String, palette: Palette): Int {
        val hue = courseHue(name)
        val tint = courseTint(name, palette)
        val saturation = if (palette.isDark) 0.66f else 0.58f
        val step = if (palette.isDark) 0.02f else -0.02f
        val low = if (palette.isDark) 0.55f else 0.18f
        val high = if (palette.isDark) 0.94f else 0.46f
        var lightness = if (palette.isDark) 0.78f else 0.34f
        var color = ColorUtils.HSLToColor(floatArrayOf(hue, saturation, lightness))
        repeat(24) {
            if (ColorUtils.calculateContrast(color, tint) >= MIN_TINT_CONTRAST) return color
            lightness = (lightness + step).coerceIn(low, high)
            color = ColorUtils.HSLToColor(floatArrayOf(hue, saturation, lightness))
        }
        return color
    }

    /** 淡彩底上文字的对比度下限（WCAG AA 正文 4.5:1） */
    private const val MIN_TINT_CONTRAST = 4.5

    private class TodayRenderer(
        private val canvas: Canvas,
        private val paint: Paint,
        private val context: Context,
        private val density: Float,
        private val width: Int,
        private val height: Int,
        private val palette: Palette,
        private val snapshot: WeekSnapshot,
        private val target: LocalDate,
        private val now: LocalDateTime
    ) {
        private val today = now.toLocalDate()

        /** 星期名随界面语言（跟随 values / values-en） */
        private val dayLabels = context.resources.getStringArray(com.lanhang.miuix.R.array.schedule_day_names)
        private val compactDayLabels =
            context.resources.getStringArray(com.lanhang.miuix.R.array.schedule_day_names_compact)

        /** 相对今天的偏移天数：0=今天，>0=未来，<0=过去（翻天按钮改的就是它） */
        private val offsetDays = ChronoUnit.DAYS.between(today, target)

        /** 今天才按钟点过滤（上完的课消失）；翻到别的日期不过滤 */
        private val filterByClock = offsetDays == 0L

        /** 当前时刻的「当天第几分钟」，用于判断上课中 / 已下课 */
        private val nowMinute = PeriodClock.minutesOf(now.toLocalTime())

        /** 与 4×4 一致：内容安全区 10dp（底栏布局的 paddingStart/End 也是 10dp） */
        private val outerPad = dp(10f)
        private val headerH = dp(24f)
        private val headerGap = dp(5f)
        private val cardGap = dp(4f)
        private val minRowH = dp(22f)

        fun draw() {
            drawHeader()
            val contentTop = outerPad + headerH + headerGap
            // 底栏（胶囊 + ◀ ⟳ ▶）是布局里的真实 View，位图只负责给它留出高度
            val footerH = if (height >= dp(FOOTER_MIN_HEIGHT_DP.toFloat())) {
                dp(FOOTER_HEIGHT_DP.toFloat())
            } else {
                0f
            }
            val contentH = (height - outerPad - footerH - contentTop).coerceAtLeast(0f)
            val all = snapshot.days.firstOrNull { it.date == target }?.courses.orEmpty()
            // 已经下课的课从这里开始就不存在了 → 后面的课自然顶上来
            val courses = if (filterByClock) all.filter { isPending(it) } else all
            when {
                courses.isNotEmpty() -> drawRows(courses, contentTop, contentH)
                // 今天本来有课、但全上完了：给一个「已上完」的空态，而不是「今天没有课程」
                filterByClock && all.isNotEmpty() -> drawDayDone(contentTop, contentH)
                else -> drawNoCourse(contentTop, contentH)
            }
        }

        /** 这节课还没下课（下课时间严格晚于现在） */
        private fun isPending(course: CourseBlock): Boolean =
            PeriodClock.endMinute(course.end) > nowMinute

        /** 正在上的那节课（同一时刻最多一节） */
        private fun ongoingOf(courses: List<CourseBlock>): CourseBlock? {
            if (!filterByClock) return null
            return courses.firstOrNull {
                PeriodClock.startMinute(it.start) <= nowMinute && nowMinute < PeriodClock.endMinute(it.end)
            }
        }

        /** 距下课还剩几分钟（向上取整，最后一分钟显示「剩 1 分」而不是 0） */
        private fun minutesLeft(course: CourseBlock): Int {
            val endSecond = PeriodClock.endMinute(course.end) * 60
            val nowSecond = now.toLocalTime().toSecondOfDay()
            return ceil((endSecond - nowSecond) / 60.0).toInt().coerceAtLeast(1)
        }

        private fun drawHeader() {
            val centerY = outerPad + headerH / 2f
            val weekday = dayLabels[(target.dayOfWeek.value - 1).coerceIn(0, 6)]
            val prefix = when (offsetDays) {
                0L -> context.getString(com.lanhang.miuix.R.string.widget_today_prefix)
                1L -> context.getString(com.lanhang.miuix.R.string.widget_tomorrow_prefix)
                -1L -> context.getString(com.lanhang.miuix.R.string.widget_yesterday_prefix)
                else -> ""
            }
            val title = "$prefix$weekday"
            val dateText = target.format(todayDateFormatter)
            // 规范 2.3：标题行属于「次」层级（40% 前景、Medium），
            // 不跟主信息（课程名 Demibold）抢层级；强调色只留给「正在上」那节课
            paint.textAlign = Paint.Align.LEFT
            paint.alpha = 255
            paint.typeface = Typeface.DEFAULT
            paint.textSize = dp(11.5f)
            paint.color = palette.onSurfaceVariant
            canvas.drawText(ellipsize(title, width * 0.62f), outerPad, centerY + paint.textSize * 0.35f, paint)
            paint.textAlign = Paint.Align.RIGHT
            paint.textSize = dp(11f)
            paint.color = palette.onSurfaceVariant
            canvas.drawText(dateText, width - outerPad, centerY + paint.textSize * 0.35f, paint)
            paint.textAlign = Paint.Align.LEFT
        }

        /**
         * 列出「还没上完」的课：最多 [MAX_TODAY_ROWS] 节，按当前可用高度决定实际行数。
         * 超出部分不再画「+N」胶囊（2×2 里没有多余高度），后面的课会在前面的课下课后自动补位。
         */
        private fun drawRows(courses: List<CourseBlock>, contentTop: Float, contentH: Float) {
            if (contentH <= 0f) return
            val capacity = ((contentH + cardGap) / (minRowH + cardGap)).toInt().coerceAtLeast(1)
            val shown = minOf(MAX_TODAY_ROWS, capacity, courses.size)
            val rowH = ((contentH - cardGap * (shown - 1)) / shown).coerceAtLeast(1f)
            val ongoing = ongoingOf(courses)
            for (index in 0 until shown) {
                val course = courses[index]
                val top = contentTop + index * (rowH + cardGap)
                // 只有「正在上」的课才在右侧显示距下课倒计时
                val left = if (course === ongoing) minutesLeft(course) else null
                drawRow(course, top, rowH, left)
            }
        }

        private fun drawRow(course: CourseBlock, top: Float, rowH: Float, countdown: Int?) {
            paint.style = Paint.Style.FILL
            paint.alpha = 255
            // 「正在上」用主色实心块把注意力拉过来；其余课程是淡彩底 + 同色系深字
            val inProgress = countdown != null
            val cardColor = if (inProgress) palette.primary else courseTint(course.name, palette)
            val nameColor = if (inProgress) palette.onPrimary else courseInk(course.name, palette)
            // 次要信息（时间 · 教室 / 倒计时）压到同色 ~70%，对应规范的「次」层级
            val metaColor = ColorUtils.setAlphaComponent(nameColor, if (inProgress) 0xDE else 0xC4)
            paint.color = cardColor
            val rowRadius = minOf(dp(14f), rowH / 2f)
            canvas.drawRoundRect(
                RectF(outerPad, top, width - outerPad, top + rowH),
                rowRadius,
                rowRadius,
                paint
            )

            val textLeft = outerPad + dp(9f)
            val textRight = width - outerPad - dp(9f)
            val textW = (textRight - textLeft).coerceAtLeast(1f)
            val room = course.room
            val twoLine = rowH >= dp(26f)
            val nameSize =
                if (twoLine) minOf(dp(13f), rowH * 0.38f)
                else (rowH * 0.58f).coerceIn(dp(10f), dp(14f))
            val metaSize = (nameSize * 0.72f).coerceIn(dp(8f), dp(10f))

            // 右侧状态：「剩 N 分」（上课中）优先，其次教室；两行版把教室放进第二行
            val status = when {
                countdown != null -> context.getString(com.lanhang.miuix.R.string.widget_today_countdown, countdown)
                twoLine -> null
                else -> room.ifBlank { null }
            }
            var statusW = 0f
            if (!status.isNullOrEmpty()) {
                paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                paint.textSize = metaSize
                statusW = minOf(paint.measureText(status), textW * 0.5f)
            }
            val nameAvail = (textW - if (statusW > 0f) statusW + dp(6f) else 0f)
                .coerceAtLeast(dp(10f))

            val blockH = nameSize + if (twoLine) dp(2f) + metaSize else 0f
            val blockTop = top + (rowH - blockH) / 2f
            val nameBaseline = blockTop + nameSize * 0.82f

            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            paint.textSize = nameSize
            paint.color = nameColor
            paint.alpha = 255
            paint.textAlign = Paint.Align.LEFT
            canvas.drawText(ellipsize(course.name, nameAvail), textLeft, nameBaseline, paint)

            if (!status.isNullOrEmpty()) {
                val statusBaseline = if (twoLine) {
                    nameBaseline - (nameSize - metaSize) * 0.12f
                } else {
                    top + rowH / 2f + metaSize * 0.35f
                }
                paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                paint.textSize = metaSize
                paint.color = metaColor
                paint.textAlign = Paint.Align.RIGHT
                canvas.drawText(
                    ellipsize(status, statusW),
                    textRight,
                    statusBaseline,
                    paint
                )
                paint.textAlign = Paint.Align.LEFT
            }

            if (twoLine) {
                val metaTop = blockTop + nameSize + dp(2f)
                val meta = timeRange(course) + (if (room.isNotBlank()) " · $room" else "")
                paint.typeface = Typeface.DEFAULT
                paint.textSize = metaSize
                paint.color = metaColor
                canvas.drawText(
                    ellipsize(meta, textW),
                    textLeft,
                    metaTop + metaSize * 0.82f,
                    paint
                )
            }
        }

        /** 今天本来有课、但都已下课：和「今天没有课程」区分开，避免误以为课表丢了 */
        private fun drawDayDone(contentTop: Float, contentH: Float) {
            val centerY = contentTop + contentH / 2f
            paint.textAlign = Paint.Align.CENTER
            paint.alpha = 255
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            paint.textSize = dp(13f)
            paint.color = palette.onSurface
            canvas.drawText(
                context.getString(com.lanhang.miuix.R.string.widget_today_done),
                width / 2f,
                centerY - dp(2f),
                paint
            )
            paint.typeface = Typeface.DEFAULT
            paint.textSize = dp(10f)
            paint.color = palette.onSurfaceVariant
            canvas.drawText(
                context.getString(
                    com.lanhang.miuix.R.string.widget_today_done_hint,
                    snapshot.week
                ),
                width / 2f,
                centerY + dp(14f),
                paint
            )
            paint.textAlign = Paint.Align.LEFT
        }

        private fun drawNoCourse(contentTop: Float, contentH: Float) {
            val centerY = contentTop + contentH / 2f
            paint.textAlign = Paint.Align.CENTER
            paint.alpha = 255
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            paint.textSize = dp(13f)
            paint.color = palette.onSurface
            canvas.drawText(
                context.getString(
                    if (offsetDays == 0L) com.lanhang.miuix.R.string.widget_today_empty
                    else com.lanhang.miuix.R.string.widget_day_empty
                ),
                width / 2f,
                centerY - dp(4f),
                paint
            )
            paint.typeface = Typeface.DEFAULT
            paint.textSize = dp(10f)
            paint.color = palette.onSurfaceVariant
            canvas.drawText(
                context.getString(com.lanhang.miuix.R.string.widget_empty_hint),
                width / 2f,
                centerY + dp(14f),
                paint
            )
            if (contentH >= dp(76f)) {
                drawActionPill(
                    canvas, paint, palette, width / 2f, centerY + dp(40f), density,
                    context.getString(com.lanhang.miuix.R.string.widget_empty_action)
                )
            }
        }

        /** 节次时间区间，如「08:00-11:40」；单节为整段「08:00-08:45」 */
        private fun timeRange(course: CourseBlock): String =
            PeriodClock.rangeText(course.start.coerceIn(1, 11), course.end.coerceIn(1, 11))

        private fun ellipsize(text: String, maxWidth: Float): String {
            if (maxWidth <= 0f) return ""
            if (paint.measureText(text) <= maxWidth) return text
            var result = text
            while (result.isNotEmpty() && paint.measureText("$result…") > maxWidth) {
                result = result.dropLast(1)
            }
            return "$result…"
        }

        private fun dp(value: Float): Float = value * density
    }

    private class CanvasRenderer(
        private val canvas: Canvas,
        private val paint: Paint,
        private val context: Context,
        private val density: Float,
        private val width: Int,
        private val height: Int,
        private val palette: Palette,
        private val snapshot: WeekSnapshot
    ) {
        private val today = LocalDate.now()

        /**
         * 内容安全区内缩 10dp：规范要求 1080p 下四周至少留 42px（≈14dp），
         * 桌面已按圆角裁过卡片，10dp 是「一眼不贴边、4×4 又不浪费格子」的折中。
         * **必须与 `widget_schedule_week.xml` 底栏的 paddingStart/End 一致**，否则胶囊会和内容错位。
         */
        private val outerPad = dp(10f)
        private val compactHeader = width < dp(320f)
        /** 星期名随界面语言（跟随 values / values-en） */
        private val dayLabels = context.resources.getStringArray(com.lanhang.miuix.R.array.schedule_day_names)
        private val compactDayLabels =
            context.resources.getStringArray(com.lanhang.miuix.R.array.schedule_day_names_compact)

        /** 顶部标题行「第 N 周 … 日期区间」：卡片够高才画，矮了就整行不出（省给格子） */
        private val titleH = if (height >= dp(240f)) dp(17f) else 0f
        private val titleGap = if (titleH > 0f) dp(4f) else 0f
        private val headH = if (compactHeader) dp(30f) else dp(38f)
        private val timeW = if (width < dp(260f)) dp(32f) else dp(36f)
        private val gapH = dp(2f)
        private val gapW = dp(3f)
        private val headerTop = outerPad + titleH + titleGap
        private val bodyTop = headerTop + headH + dp(2f)
        private val bodyBottom = height - outerPad
        private val bodyH = (bodyBottom - bodyTop).coerceAtLeast(1f)
        private val slotH = ((bodyH - gapH * 12f) / 11f).coerceAtLeast(dp(3f))
        private val dayW = ((width - outerPad * 2f - timeW - gapW * 8f) / 7f)
            .coerceAtLeast(1f)

        fun draw() {
            drawTitle()
            drawHeaders()
            drawTodayColumn()
            drawGridLines()
            drawTimeColumn()
            drawCourses(courseLayouts())
            if (snapshot.days.all { it.courses.isEmpty() }) {
                paint.textAlign = Paint.Align.CENTER
                paint.typeface = Typeface.DEFAULT
                paint.textSize = dp(11f)
                paint.color = palette.onSurfaceVariant
                paint.alpha = 255
                canvas.drawText(
                    context.getString(com.lanhang.miuix.R.string.widget_no_course_week),
                    width / 2f,
                    bodyTop + bodyH / 2f,
                    paint
                )
            }
        }

        /**
         * 顶部标题行：左「第 N 周」（主信息），右「10-27 – 11-02」（40% 前景）。
         * 日期用「无前导零 + 短横」的写法，和规范里的 `8-16` 一致；区间用 en dash，不夹文字，
         * 中英文界面都不用再拆字符串。
         */
        private fun drawTitle() {
            if (titleH <= 0f) return
            paint.textAlign = Paint.Align.LEFT
            paint.typeface = Typeface.DEFAULT_BOLD
            paint.alpha = 255
            paint.textSize = dp(12f)
            paint.color = palette.onSurface
            canvas.drawText(
                context.getString(com.lanhang.miuix.R.string.widget_week_format, snapshot.week),
                outerPad,
                outerPad + titleH * 0.74f,
                paint
            )
            val first = snapshot.days.firstOrNull()?.date
            val last = snapshot.days.lastOrNull()?.date
            if (first != null && last != null) {
                paint.textAlign = Paint.Align.RIGHT
                paint.typeface = Typeface.DEFAULT
                paint.textSize = dp(10.5f)
                paint.color = palette.onSurfaceVariant
                canvas.drawText(
                    "${first.format(dateFormatter)} – ${last.format(dateFormatter)}",
                    width - outerPad,
                    outerPad + titleH * 0.74f,
                    paint
                )
                paint.textAlign = Paint.Align.LEFT
            }
        }

        /**
         * 星期表头：**扁平**——只有「今天」带一颗主色淡化胶囊，其余星期纯文字（Miuix 不做色块堆叠）。
         */
        private fun drawHeaders() {
            paint.style = Paint.Style.FILL
            paint.alpha = 255
            val radius = dp(10f)
            for (index in 0..6) {
                val x = outerPad + timeW + gapW + index * (dayW + gapW)
                val day = snapshot.days.getOrNull(index)
                val isToday = day?.date == today
                if (isToday) {
                    paint.color = palette.today
                    canvas.drawRoundRect(
                        RectF(x, headerTop, x + dayW, headerTop + headH),
                        radius,
                        radius,
                        paint
                    )
                }
                paint.textAlign = Paint.Align.CENTER
                paint.typeface = if (isToday) {
                    Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                } else {
                    Typeface.DEFAULT
                }
                paint.textSize = if (compactHeader) {
                    (headH * 0.46f).coerceIn(dp(9f), dp(12f))
                } else {
                    (headH * 0.32f).coerceIn(dp(9f), dp(12f))
                }
                paint.color = if (isToday) palette.primary else palette.onSurface
                canvas.drawText(
                    if (compactHeader) compactDayLabels[index] else dayLabels[index],
                    x + dayW / 2f,
                    if (compactHeader) headerTop + headH * 0.64f else headerTop + headH * 0.44f,
                    paint
                )
                if (compactHeader) continue
                paint.typeface = Typeface.DEFAULT
                paint.textSize = (headH * 0.24f).coerceIn(dp(8f), dp(10f))
                paint.color = if (isToday) palette.primary else palette.onSurfaceVariant
                val date = day?.date?.format(dateFormatter).orEmpty()
                canvas.drawText(date, x + dayW / 2f, headerTop + headH * 0.82f, paint)
            }
        }

        private fun drawTodayColumn() {
            val todayIndex = snapshot.days.indexOfFirst { it.date == today }
            if (todayIndex < 0) return
            val x = outerPad + timeW + gapW + todayIndex * (dayW + gapW)
            paint.style = Paint.Style.FILL
            paint.color = palette.today
            paint.alpha = 255
            val radius = dp(10f)
            canvas.drawRoundRect(RectF(x, bodyTop, x + dayW, bodyBottom), radius, radius, paint)
        }

        /** 节次分割线：前景 4%（浅）/ 10%（深）的发丝线，只画星期区，不压时间列 */
        private fun drawGridLines() {
            val left = outerPad + timeW + gapW
            val right = width - outerPad - gapW
            paint.style = Paint.Style.FILL
            paint.color = palette.gridLine
            paint.alpha = 255
            val thickness = (density * 0.5f).coerceAtLeast(1f)
            for (index in 1 until 11) {
                val y = rowTop(index) - gapH / 2f
                canvas.drawRect(RectF(left, y, right, y + thickness), paint)
            }
        }

        private fun drawTimeColumn() {
            paint.textAlign = Paint.Align.CENTER
            val centerX = outerPad + timeW / 2f
            for (index in 0 until 11) {
                val centerY = rowTop(index) + slotH / 2f
                paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                paint.textSize = (slotH * 0.4f).coerceIn(dp(7f), dp(13f))
                // 节次是参照物，属于「次」层级：40% 前景，不跟课程名抢层级
                paint.color = palette.onSurfaceVariant
                canvas.drawText(
                    (index + 1).toString(),
                    centerX,
                    centerY + paint.textSize * 0.34f,
                    paint
                )
                if (slotH > dp(18f)) {
                    paint.typeface = Typeface.DEFAULT
                    paint.textSize = (slotH * 0.2f).coerceIn(dp(4f), dp(7f))
                    paint.color = ColorUtils.setAlphaComponent(palette.onSurfaceVariant, 0x99)
                    canvas.drawText(
                        periodTimes[index],
                        centerX,
                        centerY + paint.textSize * 1.9f,
                        paint
                    )
                }
            }
        }

        private fun courseLayouts(): List<CourseLayout> {
            val result = mutableListOf<CourseLayout>()
            for (dayIndex in 0..6) {
                val day = snapshot.days.getOrNull(dayIndex) ?: continue
                val lanes = layoutLanes(day.courses)
                val colX = outerPad + timeW + gapW + dayIndex * (dayW + gapW)
                for (lane in lanes) {
                    val start = (lane.course.start - 1).coerceIn(0, 10)
                    val end = (lane.course.end - 1).coerceIn(start, 10)
                    val span = end - start + 1
                    val laneGap = if (lane.laneCount > 1) dp(1f) else 0f
                    val laneW = (dayW - laneGap * (lane.laneCount - 1)) / lane.laneCount
                    val left = colX + lane.lane * (laneW + laneGap)
                    val top = rowTop(start)
                    val cardH = slotH * span + gapH * (span - 1)
                    result.add(
                        CourseLayout(
                            course = lane.course,
                            left = left,
                            top = top,
                            width = laneW,
                            height = cardH
                        )
                    )
                }
            }
            return result
        }

        private fun drawCourses(layouts: List<CourseLayout>) {
            if (layouts.isEmpty()) return
            for (layout in layouts) {
                val tint = courseTint(layout.course.name, palette)
                val ink = courseInk(layout.course.name, palette)
                val radius = minOf(dp(12f), minOf(layout.width, layout.height) / 2f)
                paint.style = Paint.Style.FILL
                paint.color = tint
                paint.alpha = 255
                canvas.drawRoundRect(
                    RectF(
                        layout.left,
                        layout.top,
                        layout.left + layout.width,
                        layout.top + layout.height
                    ),
                    radius,
                    radius,
                    paint
                )
                val textColor = ink
                // 字号**按块自适应**：4×4 里同一周既有整列宽的块，也有并排分成两半的窄块，
                // 取全网格最小值会把整周的字一起压到下限（并行块只有半列宽）。
                val nameSize = fitNameSize(layout, NAME_MAX_SIZE_DP * density)
                drawCourseName(layout, nameSize, textColor)
                // 教室行压到同色 68%，与课程名（主信息）拉开层级
                drawCourseRoom(layout, nameSize, ColorUtils.setAlphaComponent(textColor, 0xAD))
            }
        }

        /**
         * 该块能**完整放下**课程名的最大字号。
         *
         * 课程名是**竖排**（一个字一行），一列排不下就折到右边的第二列，
         * 最多 [NAME_MAX_COLS] 列，整组在色块里水平 + 垂直居中。
         * 判定：① 列数不超过两列 ② 每列行数 × 行距不超可用高度 ③ 两列总宽不超块宽。
         */
        private fun fitNameSize(layout: CourseLayout, start: Float): Float {
            val count = nameCharCount(layout.course.name)
            if (count == 0) return start
            val availW = nameMaxWidth(layout)
            val availH = nameAvailHeight(layout)
            var size = start
            while (size > NAME_MIN_SIZE_DP * density) {
                if (fitsColumns(count, size, availW, availH)) return size
                size -= 0.5f * density
            }
            return NAME_MIN_SIZE_DP * density
        }

        /** 该字号下能否排进最多 [NAME_MAX_COLS] 列，且两列总宽、每列高度都够 */
        private fun fitsColumns(count: Int, size: Float, availW: Float, availH: Float): Boolean {
            val rows = rowsPerColumn(size, availH)
            val cols = ceilDiv(count, rows)
            if (cols > NAME_MAX_COLS) return false
            return groupWidth(cols, size) <= availW + 0.01f
        }

        /** 一列最多放几个字（按可用高度与竖排行距算） */
        private fun rowsPerColumn(size: Float, availH: Float): Int =
            (availH / (size * NAME_ROW_SPACING)).toInt().coerceAtLeast(1)

        /** 整组文字的宽度：列宽 = 一个字宽，列间留 [NAME_COL_GAP] 个字的间隙 */
        private fun groupWidth(cols: Int, size: Float): Float {
            if (cols <= 1) return size
            return cols * size + (cols - 1) * size * NAME_COL_GAP
        }

        private fun ceilDiv(a: Int, b: Int): Int = (a + b - 1) / b

        /** 课程名逐字拆开（竖排一格一个字），空格丢弃 */
        private fun nameChars(name: String): List<String> =
            name.filterNot { it == '\n' || it == ' ' || it == '\u3000' }.map { it.toString() }

        private fun nameCharCount(name: String): Int =
            name.count { it != '\n' && it != ' ' && it != '\u3000' }

        /** 课程名可用的横向空间（扣掉左右内边距） */
        private fun nameMaxWidth(layout: CourseLayout): Float =
            (layout.width - NAME_PAD_H_DP * density * 2f).coerceAtLeast(1f)

        /** 课程名可用的纵向空间（扣掉上下内边距与底部的教室行） */
        private fun nameAvailHeight(layout: CourseLayout): Float =
            (layout.height - NAME_PAD_V_DP * density * 2f - roomReserve(layout)).coerceAtLeast(1f)

        /**
         * 底部教室行要预留的高度。
         *
         * 不能只留 7dp：教室字号约 7dp、基线距底约 5.6dp，字形顶部实际到距底 ~12dp，
         * 预留不足会让最后一行课程名和教室叠在一起（vivo 200% 字体缩放下更明显）。
         * 块太矮（单节）时按比例缩，最多占 32%。
         */
        private fun roomReserve(layout: CourseLayout): Float {
            if (layout.course.room.isBlank() || layout.height < dp(14f)) return 0f
            return minOf(dp(12f), layout.height * 0.32f)
        }

        /**
         * 课程名：**竖排、最多两列、整组在色块正中间**。
         *
         * 一个字一行往下排，一列排满就折到右边第二列（左列从「第一个字」开始，
         * 右列承接剩下的）——这样只占两个字的宽度，块里左右都留白，
         * 不会像横排那样把整块宽度撑满。两列整体水平居中、整体垂直居中。
         */
        private fun drawCourseName(layout: CourseLayout, nameSize: Float, textColor: Int) {
            val chars = nameChars(layout.course.name)
            if (chars.isEmpty()) return
            val availH = nameAvailHeight(layout)
            val rowH = nameSize * NAME_ROW_SPACING
            val rowsByHeight = rowsPerColumn(nameSize, availH)
            var shown = chars.size
            var cols = ceilDiv(chars.size, rowsByHeight)
            if (cols > NAME_MAX_COLS) {
                // 字号已到下限还排不下：固定在两列，多余的截断
                cols = NAME_MAX_COLS
                shown = minOf(chars.size, cols * rowsByHeight)
            }
            // 字数装得下一列就只排一列（短课名不该被硬拆成两列）
            cols = minOf(cols, ceilDiv(shown, rowsByHeight))
            val rows = ceilDiv(shown, cols)
            val truncated = shown < chars.size
            val drawable = if (truncated) (shown - 1).coerceAtLeast(1) else shown
            val totalW = groupWidth(cols, nameSize)
            val totalH = rows * rowH
            val groupLeft = layout.left + (layout.width - totalW) / 2f
            val groupTop = layout.top + NAME_PAD_V_DP * density + (availH - totalH) / 2f
            val colStep = nameSize * (1f + NAME_COL_GAP)
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            paint.textAlign = Paint.Align.CENTER
            paint.color = textColor
            paint.alpha = 255
            paint.textSize = nameSize
            for (index in 0 until shown) {
                val col = index / rows
                val row = index % rows
                val text = if (truncated && index >= drawable) "…" else chars[index]
                val centerX = groupLeft + col * colStep + nameSize / 2f
                // 基线用 0.78em：字形上方留 0.78、下方 descent 约 0.22，正好填满一行
                val baseline = groupTop + row * rowH + nameSize * 0.78f
                canvas.drawText(text, centerX, baseline, paint)
            }
        }

        private fun drawCourseRoom(layout: CourseLayout, nameSize: Float, textColor: Int) {
            val room = layout.course.room
            if (room.isBlank() || layout.height < dp(14f)) return
            val pad = dp(4f)
            val roomSize = minOf(dp(7f), nameSize * 0.66f).coerceAtLeast(dp(4f))
            paint.typeface = Typeface.DEFAULT
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = roomSize
            paint.color = textColor
            val maxWidth = (layout.width - pad * 2f).coerceAtLeast(1f)
            val text = ellipsize(room, maxWidth)
            canvas.drawText(
                text,
                layout.left + layout.width / 2f,
                layout.top + layout.height - pad - roomSize * 0.25f,
                paint
            )
        }

        private fun ellipsize(text: String, maxWidth: Float): String {
            if (paint.measureText(text) <= maxWidth) return text
            var result = text
            while (result.isNotEmpty() && paint.measureText("$result…") > maxWidth) {
                result = result.dropLast(1)
            }
            return "$result…"
        }

        private fun layoutLanes(courses: List<CourseBlock>): List<LaneCourse> {
            if (courses.isEmpty()) return emptyList()
            val sorted = courses.sortedWith(compareBy({ it.start }, { it.end }))
            val result = mutableListOf<LaneCourse>()
            var index = 0
            while (index < sorted.size) {
                val cluster = mutableListOf<CourseBlock>()
                var clusterEnd = -1
                var next = index
                while (next < sorted.size &&
                    (cluster.isEmpty() || sorted[next].start <= clusterEnd)
                ) {
                    cluster.add(sorted[next])
                    if (sorted[next].end > clusterEnd) clusterEnd = sorted[next].end
                    next++
                }
                val laneEnds = mutableListOf<Int>()
                val assignments = mutableListOf<Pair<CourseBlock, Int>>()
                for (course in cluster) {
                    var lane = laneEnds.indexOfFirst { it < course.start }
                    if (lane < 0) {
                        lane = laneEnds.size
                        laneEnds.add(course.end)
                    } else {
                        laneEnds[lane] = course.end
                    }
                    assignments.add(course to lane)
                }
                val laneCount = laneEnds.size.coerceAtLeast(1)
                for ((course, lane) in assignments) {
                    result.add(LaneCourse(course, lane, laneCount))
                }
                index = next
            }
            return result
        }

        private fun rowTop(index: Int): Float {
            return bodyTop + gapH + index * (slotH + gapH)
        }

        private fun dp(value: Float): Float = value * density
    }

    private data class CourseLayout(
        val course: CourseBlock,
        val left: Float,
        val top: Float,
        val width: Float,
        val height: Float
    )

    private data class LaneCourse(
        val course: CourseBlock,
        val lane: Int,
        val laneCount: Int
    )

    /**
     * 小部件语义色：**与 Miuix 主题同一套**（`res/values/colors.xml` /
     * `res/values-night/colors.xml`），并落在 HyperOS 小组件设计规范的三个层级上：
     *
     * - `background` 卡底 = Miuix `card_bg`（浅 #FFFFFF / 深 #242424）
     * - `header`     次级容器 = Miuix `secondary_container`（浅 #F0F0F0 / 深 #2D2D2D）
     * - `today`      今日高亮 = 卡底混 10%（深色 20%）主色，不做第三套颜色
     * - `primary`    强调色 = Miuix `primary`（浅 #3482FF / 深 #4C8DFF），只给「今天 / 正在上」
     * - `onSurface`  主信息；`onSurfaceVariant` = 前景 40%（规范里的「次」层级）
     * - `gridLine`   分割线（前景 4% / 10%）
     *
     * 为什么不直接 `ContextCompat.getColor`：主题档位由 `Session.themeMode` 决定，
     * 可能和「桌面进程 inflate 小部件时的资源 night 配置」不是同一个档位，
     * 所以这里按同一个档位把色值一次算全，保证位图和布局两套配色不会错配。
     */
    private data class Palette(
        val background: Int,
        val header: Int,
        val today: Int,
        val primary: Int,
        val onPrimary: Int,
        val onSurface: Int,
        val onSurfaceVariant: Int,
        val gridLine: Int,
        val isDark: Boolean
    )

    private fun palette(context: Context): Palette {
        val night = when (Session.themeMode) {
            "dark" -> true
            "light" -> false
            else -> (context.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        }
        return if (night) {
            val card = 0xFF242424.toInt()
            val accent = 0xFF4C8DFF.toInt()
            Palette(
                background = card,
                header = 0xFF2D2D2D.toInt(),
                today = ColorUtils.blendARGB(card, accent, 0.20f),
                primary = accent,
                onPrimary = 0xFFFFFFFF.toInt(),
                onSurface = 0xFFE6FFFFFF.toInt(),
                onSurfaceVariant = 0xFF8E8E93.toInt(),
                gridLine = 0x1AFFFFFF.toInt(),
                isDark = true
            )
        } else {
            val card = 0xFFFFFFFF.toInt()
            val accent = 0xFF3482FF.toInt()
            Palette(
                background = card,
                header = 0xFFF0F0F0.toInt(),
                today = ColorUtils.blendARGB(card, accent, 0.10f),
                primary = accent,
                onPrimary = 0xFFFFFFFF.toInt(),
                onSurface = 0xFF000000.toInt(),
                onSurfaceVariant = 0xFF999999.toInt(),
                gridLine = 0x0F000000.toInt(),
                isDark = false
            )
        }
    }
}
