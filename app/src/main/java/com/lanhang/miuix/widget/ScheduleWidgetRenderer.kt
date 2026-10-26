package com.lanhang.miuix.widget

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
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
    private val todayDateFormatter = DateTimeFormatter.ofPattern("M/d")

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
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.textSize = 15f * density
        paint.color = palette.onSurface
        canvas.drawText(title, width / 2f, height / 2f - 8f * density, paint)
        paint.typeface = Typeface.DEFAULT
        paint.textSize = 11f * density
        paint.color = palette.onSurfaceVariant
        canvas.drawText(
            context.getString(com.lanhang.miuix.R.string.widget_empty_hint),
            width / 2f,
            height / 2f + 14f * density,
            paint
        )
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

    /**
     * 课程卡配色：同课同色、不同课色相差约 137°。
     *
     * 卡片上一律白字（与课表页统一），所以浅色主题原本的淡彩（L=0.82）配白字只有 ~2:1，
     * 会糊成一片 —— 由 [deepenForWhiteText] 按原色相压暗到白字可读；深色主题本来够深，不动。
     *
     * @param alpha 卡片实际绘制时的透明度（4×4 网格色块是半透明的）
     * @param behind 半透明卡片的叠底颜色
     */
    private fun courseColorFor(
        name: String,
        isDark: Boolean,
        alpha: Int = 255,
        behind: Int = Color.WHITE
    ): Int {
        val hue = ((name.hashCode() * 137.508f % 360f) + 360f) % 360f
        val base = if (isDark) {
            ColorUtils.HSLToColor(floatArrayOf(hue, 0.40f, 0.28f))
        } else {
            ColorUtils.HSLToColor(floatArrayOf(hue, 0.55f, 0.82f))
        }
        return deepenForWhiteText(base, alpha, behind)
    }

    /**
     * 白字版卡片的最低对比度（与 `ScheduleStyle.MIN_WHITE_CONTRAST` 同一档）。
     * 课名是 10~14sp 加粗，WCAG AA 对大字号要求 3:1，取 3.2 留一点余量。
     */
    private const val MIN_CARD_WHITE_CONTRAST = 3.2

    /**
     * 保持色相/饱和度，仅降低明度直到白字可读；下限 0.24 防止压成黑色。
     * 半透明卡片按叠底后的实际颜色算对比度，否则白字会算得比看起来亮。
     */
    private fun deepenForWhiteText(color: Int, alpha: Int = 255, behind: Int = Color.WHITE): Int {
        fun composited(c: Int): Int = ColorUtils.blendARGB(behind, c, alpha / 255f)

        if (ColorUtils.calculateContrast(Color.WHITE, composited(color)) >= MIN_CARD_WHITE_CONTRAST) {
            return color
        }
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(color, hsl)
        var lightness = hsl[2]
        var current = color
        repeat(40) {
            lightness = (lightness - 0.015f).coerceAtLeast(0.24f)
            current = ColorUtils.HSLToColor(floatArrayOf(hsl[0], hsl[1], lightness))
            if (ColorUtils.calculateContrast(Color.WHITE, composited(current)) >=
                MIN_CARD_WHITE_CONTRAST || lightness <= 0.24f
            ) {
                return current
            }
        }
        return current
    }

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

        private val outerPad = dp(9f)
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
            paint.textAlign = Paint.Align.LEFT
            paint.alpha = 255
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            paint.textSize = dp(15f)
            paint.color = palette.onSurface
            canvas.drawText(ellipsize(title, width * 0.62f), outerPad, centerY + paint.textSize * 0.35f, paint)
            paint.textAlign = Paint.Align.RIGHT
            paint.textSize = dp(14f)
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
            val cardColor = courseColorFor(course.name, palette.isDark)
            paint.color = cardColor
            canvas.drawRoundRect(
                RectF(outerPad, top, width - outerPad, top + rowH),
                minOf(dp(12f), rowH / 2f),
                minOf(dp(12f), rowH / 2f),
                paint
            )
            // 卡片统一白字：「够不够白」由 cardColor 的压深保证（见 courseColorFor）
            val nameColor = Color.WHITE
            val metaColor = 0xE6FFFFFF.toInt()

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
        private val outerPad = dp(6f)
        private val compactHeader = width < dp(320f)
        /** 星期名随界面语言（跟随 values / values-en） */
        private val dayLabels = context.resources.getStringArray(com.lanhang.miuix.R.array.schedule_day_names)
        private val compactDayLabels =
            context.resources.getStringArray(com.lanhang.miuix.R.array.schedule_day_names_compact)
        private val headH = if (compactHeader) dp(36f) else dp(56f)
        private val timeW = if (width < dp(260f)) dp(36f) else dp(40f)
        private val gapH = dp(1.5f)
        private val gapW = dp(2.5f)
        private val bodyTop = outerPad + headH
        private val bodyBottom = height - outerPad
        private val bodyH = (bodyBottom - bodyTop).coerceAtLeast(1f)
        private val slotH = ((bodyH - gapH * 12f) / 11f).coerceAtLeast(dp(3f))
        private val dayW = ((width - outerPad * 2f - timeW - gapW * 8f) / 7f)
            .coerceAtLeast(1f)

        fun draw() {
            drawHeaders()
            drawTodayColumn()
            drawTimeColumn()
            val layouts = courseLayouts()
            drawCourses(layouts)
            if (snapshot.days.all { it.courses.isEmpty() }) {
                paint.textAlign = Paint.Align.CENTER
                paint.typeface = Typeface.DEFAULT
                paint.textSize = dp(12f)
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

        private fun drawHeaders() {
            paint.style = Paint.Style.FILL
            paint.alpha = 255
            paint.color = palette.header
            val radius = dp(14f)
            canvas.drawRoundRect(
                RectF(outerPad, outerPad, outerPad + timeW, outerPad + headH),
                radius,
                radius,
                paint
            )
            for (index in 0..6) {
                val x = outerPad + timeW + gapW + index * (dayW + gapW)
                val day = snapshot.days.getOrNull(index)
                val isToday = day?.date == today
                paint.color = if (isToday) palette.today else palette.header
                canvas.drawRoundRect(
                    RectF(x, outerPad, x + dayW, outerPad + headH),
                    radius,
                    radius,
                    paint
                )
                paint.textAlign = Paint.Align.CENTER
                paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                paint.textSize = if (compactHeader) {
                    (headH * 0.42f).coerceIn(dp(9f), dp(13f))
                } else {
                    (headH * 0.24f).coerceIn(dp(9f), dp(13f))
                }
                paint.color = if (isToday) palette.primary else palette.onSurface
                canvas.drawText(
                    if (compactHeader) compactDayLabels[index] else dayLabels[index],
                    x + dayW / 2f,
                    if (compactHeader) outerPad + headH * 0.60f else outerPad + headH * 0.4f,
                    paint
                )
                if (compactHeader) continue
                paint.typeface = Typeface.DEFAULT
                paint.textSize = (headH * 0.18f).coerceIn(dp(7f), dp(10f))
                paint.color = if (isToday) palette.primary else palette.onSurfaceVariant
                val date = day?.date?.format(dateFormatter).orEmpty()
                canvas.drawText(date, x + dayW / 2f, outerPad + headH * 0.72f, paint)
            }
        }

        private fun drawTodayColumn() {
            val todayIndex = snapshot.days.indexOfFirst { it.date == today }
            if (todayIndex < 0) return
            val x = outerPad + timeW + gapW + todayIndex * (dayW + gapW)
            paint.color = palette.today
            paint.alpha = 45
            canvas.drawRect(
                RectF(x, bodyTop, x + dayW, bodyBottom),
                paint
            )
            paint.alpha = 255
        }

        private fun drawTimeColumn() {
            paint.textAlign = Paint.Align.CENTER
            val centerX = outerPad + timeW / 2f
            for (index in 0 until 11) {
                val centerY = rowTop(index) + slotH / 2f
                paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                paint.textSize = (slotH * 0.4f).coerceIn(dp(7f), dp(13f))
                paint.color = palette.onSurface
                canvas.drawText(
                    (index + 1).toString(),
                    centerX,
                    centerY + paint.textSize * 0.34f,
                    paint
                )
                if (slotH > dp(18f)) {
                    paint.typeface = Typeface.DEFAULT
                    paint.textSize = (slotH * 0.2f).coerceIn(dp(4f), dp(7f))
                    paint.color = palette.onSurfaceVariant
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
                val baseColor = courseColor(layout.course.name)
                val radius = minOf(dp(10f), minOf(layout.width, layout.height) / 2f)
                paint.style = Paint.Style.FILL
                paint.color = baseColor
                paint.alpha = blockAlpha()
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
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = dp(0.5f)
                paint.color = baseColor
                paint.alpha = 80
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
                paint.style = Paint.Style.FILL
                paint.strokeWidth = 0f
                paint.alpha = 255
                val textColor = Color.WHITE
                // 字号**按块自适应**：4×4 里同一周既有整列宽的块，也有并排分成两半的窄块，
                // 取全网格最小值会把整周的字一起压到下限（并行块只有半列宽）。
                val nameSize = fitNameSize(layout, NAME_MAX_SIZE_DP * density)
                drawCourseName(layout, nameSize, textColor)
                drawCourseRoom(layout, nameSize, textColor)
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
            paint.alpha = 255
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

        /**
         * 4×4 网格的色块是半透明画的（alpha 见 [drawCourses]），叠在卡片底色上，
         * 白字的对比度必须按叠底后的颜色算，所以这里把 alpha/behind 一起传进去。
         */
        private fun courseColor(name: String): Int =
            courseColorFor(name, palette.isDark, blockAlpha(), palette.background)

        private fun blockAlpha(): Int = if (palette.isDark) 220 else 205

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

    private data class Palette(
        val background: Int,
        val header: Int,
        val today: Int,
        val primary: Int,
        val onSurface: Int,
        val onSurfaceVariant: Int,
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
            Palette(
                background = 0xFF0D1B24.toInt(),
                header = 0xFF07141C.toInt(),
                today = 0xFF123B52.toInt(),
                primary = 0xFF8ED8F8.toInt(),
                onSurface = 0xFFE8F4FA.toInt(),
                onSurfaceVariant = 0xFF9FB6C5.toInt(),
                isDark = true
            )
        } else {
            Palette(
                background = 0xFFEAF2F8.toInt(),
                header = 0xFFF8FBFE.toInt(),
                today = 0xFFD4E9F7.toInt(),
                primary = 0xFF084168.toInt(),
                onSurface = 0xFF14232D.toInt(),
                onSurfaceVariant = 0xFF5E7482.toInt(),
                isDark = false
            )
        }
    }
}
