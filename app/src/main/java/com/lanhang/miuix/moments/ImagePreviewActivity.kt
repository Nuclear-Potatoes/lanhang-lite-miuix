package com.lanhang.miuix.moments

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import coil.load
import com.lanhang.miuix.R
import com.lanhang.miuix.databinding.ActivityImagePreviewBinding
import com.lanhang.miuix.util.LocaleHelper
import kotlin.math.abs

/**
 * 工作圈图片全屏预览：左右滑动多图，双指/双击缩放。
 *
 * 2026-10-24 八次改版：关闭动效要「衔接流畅」，三条路径统一走 [closeSmoothly]：
 *  · 单击图片**显示区域之外**（黑底）→ 原地缩小淡出（此前是瞬切 finish + 系统 fade）
 *  · 上滑手势 → 跟手位移/缩放/背景渐隐，抬手超过阈值按速度方向飞出，不够则回弹
 *  · 系统返回键 → 与单击同一套淡出
 * 打开时也补了一个 alpha 渐显，避免「啪」地出现。
 */
class ImagePreviewActivity : AppCompatActivity() {
    override fun attachBaseContext(newBase: Context) {
        // 语言切换见 LocaleHelper：用 createConfigurationContext 包 base context，
        // 切语言时由设置页显式 recreate()，不走系统的 per-app locale（会黑屏）
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    private lateinit var binding: ActivityImagePreviewBinding
    private val urls by lazy { intent.getStringArrayListExtra(EXTRA_URLS).orEmpty() }
    private var closing = false

    /** 点开这张图时它在工作圈里的位置（窗口坐标）；null = 没有来源信息（如发表页预览） */
    private val sourceRect: RectF? by lazy {
        val r = intent.getIntArrayExtra(EXTRA_RECT) ?: return@lazy null
        if (r.size != 4) null else RectF(
            r[0].toFloat(), r[1].toFloat(), r[2].toFloat(), r[3].toFloat()
        )
    }

    /** 打开时点的是第几张（换了图之后就不能再缩回原缩略图） */
    private val originIndex by lazy { intent.getIntExtra(EXTRA_INDEX, 0) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 2026-10-25：改成「半透明窗口」——
        //  · 打开时图片从缩略图原位放大，背景黑色慢慢加深，不再是整窗淡入（用户说那层像一片异常阴影）
        //  · 关闭时图片缩回缩略图原位，背景同时散去，下面就是工作圈列表
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityImagePreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setBackdropAlpha(0f)

        binding.root.let { v ->
            ViewCompat.setOnApplyWindowInsetsListener(v) { _, insets ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                val lp = binding.tvIndex.layoutParams as ViewGroup.MarginLayoutParams
                lp.topMargin = 16 * resources.displayMetrics.density.toInt() + bars.top
                binding.tvIndex.layoutParams = lp
                insets
            }
        }

        binding.pager.adapter = PreviewAdapter(urls) { closeSmoothly() }
        binding.pager.setCurrentItem(
            intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, urls.size - 1), false
        )
        updateIndex(binding.pager.currentItem)
        binding.pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) = updateIndex(position)
        })

        binding.root.listener = dragListener
        // 返回键也走同一套退出动效，不要直接 finish
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = closeSmoothly()
        })

        // 首帧布局完才有 ViewPager2 的页面 View，等它出来再做「从缩略图放大」
        binding.pager.post { playOpenAnimation(0) }
    }

    /**
     * 打开动画：图片从缩略图所在矩形（[sourceRect]）放大到居中显示，背景黑幕同时推到不透明。
     * 拿不到来源位置（或当前页还没绑定）时退回「只渐显背景」。
     */
    private fun playOpenAnimation(attempt: Int) {
        val v = currentPageView()
        if (v == null || v.width == 0 || !v.hasDrawable()) {
            if (attempt < 40) {
                binding.pager.postDelayed({ playOpenAnimation(attempt + 1) }, 16L)
            } else {
                animateBackdropTo(1f, SHRINK_MS)
            }
            return
        }
        // 2026-10-25：打开也做「从缩略图原位放大」（用户要求与关闭对称）。
        // 之前冻结在半缩放，根因是系统窗口 OPEN 过渡和自绘缩放叠加；窗口过渡已在
        // start() 里 overridePendingTransition(0, 0) 关掉，这里可以放心自己缩。
        v.reset()
        val rect = sourceRect?.takeIf { binding.pager.currentItem == originIndex }
        val fitted = RectF()
        if (rect != null && rect.width() > 0f && rect.height() > 0f &&
            v.fittedRect(fitted) && fitted.width() > 0f && rect.width() < fitted.width()
        ) {
            val startScale = (rect.width() / fitted.width()).coerceIn(0.05f, 1f)
            v.pivotX = v.width / 2f
            v.pivotY = v.height / 2f
            v.scaleX = startScale
            v.scaleY = startScale
            v.translationX = rect.centerX() - v.width / 2f
            v.translationY = rect.centerY() - v.height / 2f
            // 先摆好「缩略图原位」这一帧，等它真的画出来之后再启动缩放动画。
            // 直接在首帧前 start() 在真机上会停在半缩放（动画帧没被投递），必须先出帧后动画。
            binding.root.post {
                v.animate()
                    .scaleX(1f).scaleY(1f)
                    .translationX(0f).translationY(0f)
                    .setDuration(SHRINK_MS)
                    .setInterpolator(EASE_OUT)
                    .start()
                binding.tvIndex.animate().alpha(1f).setDuration(OPEN_MS).start()
                // 兜底：动画万一被系统/同步信号打断，也要落回「全屏 + 纯黑背景」，
                // 绝不能停在半缩放（2026-10-25 复现过）,
                binding.root.postDelayed({
                    if (!closing) {
                        v.scaleX = 1f
                        v.scaleY = 1f
                        v.translationX = 0f
                        v.translationY = 0f
                        setBackdropAlpha(1f)
                        binding.tvIndex.alpha = 1f
                    }
                }, SHRINK_MS + 260L)
            }
        } else {
            v.scaleX = 1f
            v.scaleY = 1f
            v.translationX = 0f
            v.translationY = 0f
        }
        animateBackdropTo(1f, OPEN_MS)
        binding.tvIndex.alpha = 0f
        binding.tvIndex.animate().alpha(1f).setDuration(OPEN_MS).start()
    }

    private fun updateIndex(position: Int) {
        binding.tvIndex.text = if (urls.size > 1) "${position + 1} / ${urls.size}" else ""
    }

    private fun currentPageView(): ZoomImageView? {
        val rv = binding.pager.getChildAt(0) as? RecyclerView ?: return null
        val holder = rv.findViewHolderForAdapterPosition(binding.pager.currentItem) as? PreviewAdapter.VH
        return holder?.iv
    }

    // ---------------- 手势：上滑关闭（跟手 + 回弹/飞出） ----------------

    private val dragListener = object : DragDismissLayout.Listener {
        override fun canDrag(): Boolean = currentPageView()?.isZoomed != true

        override fun onDragStart() {
            cancelAnimations()
        }

        override fun onDrag(dy: Float) {
            val v = currentPageView() ?: return
            val h = binding.root.height.toFloat().coerceAtLeast(1f)
            val p = (abs(dy) / (h * DRAG_FULL_RANGE)).coerceIn(0f, 1f)
            v.translationY = dy
            val s = 1f - DRAG_SCALE_DROP * p
            v.scaleX = s
            v.scaleY = s
            v.alpha = 1f - p * 0.25f
            setBackdropAlpha(1f - 0.85f * p)
            binding.tvIndex.alpha = 1f - p
        }

        override fun onDragEnd(dy: Float, velocityY: Float) {
            val v = currentPageView() ?: return
            val h = binding.root.height.toFloat().coerceAtLeast(1f)
            val commit = abs(dy) > h * DRAG_DISMISS_FRACTION || abs(velocityY) > DRAG_FLING_VELOCITY
            if (commit) closeSmoothly(dyHint = dy, velocityY = velocityY) else springBack(v)
        }
    }

    /** 拖动不够：弹回原位（同一条弹簧曲线，和 Miuix 导航手感一致） */
    private fun springBack(v: ZoomImageView) {
        v.animate()
            .translationY(0f)
            .scaleX(1f).scaleY(1f)
            .alpha(1f)
            .setDuration(BACK_MS)
            .setInterpolator(EASE_OUT)
            .start()
        binding.tvIndex.animate().alpha(1f).setDuration(BACK_MS).setInterpolator(EASE_OUT).start()
        animateBackdropTo(1f, BACK_MS)
    }

    // ---------------- 退出：三条路径共用 ----------------

    /**
     * 平滑退出。2026-10-25 用户反馈两个问题，一起在这里解决：
     *  1. 「单击图片关闭时缩放动画不自然」——根因是旧曲线 SpringEase(320ms) 在 320ms 时只走到
     *     约 0.90（临界阻尼弹簧永远逼近 1 而不等于 1），图片还没缩到缩略图窗口就 finish 了，
     *     最后一下是「跳」过去的。现在换成能在给定时长内真正到达终点的缓出曲线。
     *  2. 「上滑关闭没有缩放回原位置」——以前甩出路径是直接飞出屏幕 + 淡出。现在只要拿得到
     *     来源缩略图矩形，上滑和单击**共用同一段缩回动画** [animateIntoThumbnail]；
     *     只有没有来源矩形时（例如发表页预览）才退回飞出/淡出。
     */
    private fun closeSmoothly(dyHint: Float = 0f, velocityY: Float = 0f) {
        if (closing) return
        closing = true
        val v = currentPageView()
        val rect = sourceRect?.takeIf { binding.pager.currentItem == originIndex }
        val fitted = RectF()
        val canShrink = v != null && rect != null &&
            v.fittedRect(fitted) && fitted.width() > 0f &&
            rect.width() > 0f && rect.width() < fitted.width()
        if (v != null && canShrink && rect != null) {
            animateIntoThumbnail(v, rect, fitted)
            return
        }
        // —— 兜底：没有缩略图来源，按方向飞出 / 原地淡出 ——
        val h = binding.root.height.toFloat().coerceAtLeast(1f)
        val targetY = when {
            velocityY < -DRAG_FLING_VELOCITY -> -h
            velocityY > DRAG_FLING_VELOCITY -> h
            dyHint < -1f -> -h
            dyHint > 1f -> h
            else -> 0f
        }
        val duration = if (targetY != 0f) FLING_CLOSE_MS else TAP_CLOSE_MS
        if (v != null) {
            v.animate().cancel()
            if (targetY == 0f) {
                v.animate()
                    .scaleX(0.86f).scaleY(0.86f)
                    .alpha(0f)
                    .setDuration(duration)
                    .setInterpolator(EASE_OUT)
                    .start()
            } else {
                v.animate()
                    .translationY(targetY)
                    .scaleX(0.85f).scaleY(0.85f)
                    .alpha(0f)
                    .setDuration(duration)
                    .setInterpolator(EASE_OUT)
                    .start()
            }
        }
        binding.tvIndex.animate().alpha(0f).setDuration(duration / 2).start()
        fadeBackdropAfterHold(duration)
        binding.root.postDelayed({ finishWithoutTransition() }, duration)
        binding.root.postDelayed({ finishWithoutTransition() }, duration + 160L)
    }

    /**
     * 把当前图片缩回它在工作圈里的缩略图矩形（窗口坐标），黑幕随进度散去。
     * 终点用的是**缩略图矩形的中心 + 缩略图宽度对应的缩放比**，所以最后一帧正好和列表里的
     * 缩略图重合，不会出现「缩到一半就消失」。
     */
    private fun animateIntoThumbnail(v: ZoomImageView, rect: RectF, fitted: RectF) {
        v.animate().cancel()
        val endScale = (rect.width() / fitted.width()).coerceIn(0.05f, 1f)
        v.pivotX = v.width / 2f
        v.pivotY = v.height / 2f
        // 上滑途中图片被降过透明度、缩过一点，收回时要恢复实心
        val backdropFrom = ((binding.root.background as? ColorDrawable)?.alpha ?: 255) / 255f
        v.animate()
            .scaleX(endScale).scaleY(endScale)
            .translationX(rect.centerX() - v.width / 2f)
            .translationY(rect.centerY() - v.height / 2f)
            .alpha(1f)
            .setDuration(SHRINK_MS)
            .setInterpolator(EASE_OUT)
            .setUpdateListener { a ->
                // 黑幕：前 15% 先稳住，之后随图片缩小线性散去，最后一帧完全透明
                val p = ((a.animatedFraction - 0.15f) / 0.85f).coerceIn(0f, 1f)
                setBackdropAlpha(backdropFrom * (1f - p))
            }
            .withEndAction { finishWithoutTransition() }
            .start()
        binding.tvIndex.animate().alpha(0f).setDuration(SHRINK_MS / 2).start()
        // 兜底：动画监听万一没回调，也别把用户卡在黑屏
        binding.root.postDelayed({ finishWithoutTransition() }, SHRINK_MS + 160L)
    }

    /**
     * 退场用：前 40% 时长保持不透明，之后才把黑幕淡到 0。
     * 这样图片缩回原位的整段过程都有黑底衬着，不会「闪一下」。
     */
    private fun fadeBackdropAfterHold(duration: Long) {
        val anim = android.animation.ValueAnimator.ofFloat(0f, 1f)
        anim.duration = duration
        anim.interpolator = android.view.animation.LinearInterpolator()
        anim.addUpdateListener { a ->
            val p = a.animatedFraction
            val k = ((p - 0.40f) / 0.60f).coerceIn(0f, 1f)
            setBackdropAlpha(1f - k)
        }
        anim.start()
    }

    private fun finishWithoutTransition() {
        if (isFinishing || isDestroyed) return
        finish()
        overridePendingTransition(0, 0)
    }

    private fun cancelAnimations() {
        binding.root.animate().cancel()
        binding.tvIndex.animate().cancel()
        currentPageView()?.animate()?.cancel()
    }

    private fun setBackdropAlpha(alpha: Float) {
        val bg = binding.root.background
        if (bg is ColorDrawable) {
            bg.alpha = (255 * alpha.coerceIn(0f, 1f)).toInt()
        } else {
            binding.root.setBackgroundColor(android.graphics.Color.BLACK)
            (binding.root.background as? ColorDrawable)?.alpha = (255 * alpha.coerceIn(0f, 1f)).toInt()
        }
    }

    private fun animateBackdropTo(target: Float, duration: Long) {
        val start = ((binding.root.background as? ColorDrawable)?.alpha ?: 255) / 255f
        android.animation.ValueAnimator.ofFloat(start, target).apply {
            this.duration = duration
            interpolator = EASE_OUT
            addUpdateListener { setBackdropAlpha(it.animatedValue as Float) }
            start()
        }
    }

    /**
     * 缓出曲线：给定时长内**必定落到 1.0**（旧弹簧曲线永远只到 ~0.9，末帧会跳一下）。
     * 前段快、后段慢，正是「缩回原位」想要的手感。
     */
    private val EASE_OUT: android.view.animation.Interpolator =
        android.view.animation.PathInterpolator(0.26f, 0f, 0.20f, 1f).let { p ->
            object : android.view.animation.Interpolator {
                override fun getInterpolation(input: Float): Float = p.getInterpolation(input)
            }
        }

    private inner class PreviewAdapter(
        private val items: List<String>,
        private val onClose: () -> Unit
    ) : RecyclerView.Adapter<PreviewAdapter.VH>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val iv = ZoomImageView(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                // 单击（图片显示区域之内或之外都算）→ 平滑关闭
                onTap = onClose
            }
            return VH(iv)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.iv.load(items[position]) {
                crossfade(150)
            }
        }

        inner class VH(val iv: ZoomImageView) : RecyclerView.ViewHolder(iv)
    }

    companion object {
        private const val EXTRA_URLS = "urls"
        private const val EXTRA_INDEX = "index"
        private const val EXTRA_RECT = "source_rect"

        /** 打开渐显 */
        private const val OPEN_MS = 220L
        /** 缩回缩略图的时长（打开也用它，保持对称） */
        private const val SHRINK_MS = 300L
        /** 拖动到「算作关闭」的位移比例阈值 */
        private const val DRAG_DISMISS_FRACTION = 0.16f
        /** 拖动/甩动的速度阈值（px/s） */
        private const val DRAG_FLING_VELOCITY = 700f
        /** 拖动多少距离算「满进度」（缩放/背景渐隐的比例基准） */
        private const val DRAG_FULL_RANGE = 0.55f
        /** 满进度时缩到多小 */
        private const val DRAG_SCALE_DROP = 0.18f
        /** 回弹时长 */
        private const val BACK_MS = 320L
        /** 单击关闭时长 */
        private const val TAP_CLOSE_MS = 300L
        /** 甩出关闭时长 */
        private const val FLING_CLOSE_MS = 260L

        fun start(context: Context, urls: List<String>, index: Int, sourceRect: Rect? = null) {
            if (urls.isEmpty()) return
            context.startActivity(
                Intent(context, ImagePreviewActivity::class.java).apply {
                    putStringArrayListExtra(EXTRA_URLS, ArrayList(urls))
                    putExtra(EXTRA_INDEX, index)
                    if (sourceRect != null) {
                        putExtra(
                            EXTRA_RECT,
                            intArrayOf(sourceRect.left, sourceRect.top, sourceRect.right, sourceRect.bottom)
                        )
                    }
                }
            )
            // 打开/关闭全部由共享元素动画承担，禁止系统再叠一层窗口过渡
            (context as? android.app.Activity)?.overridePendingTransition(0, 0)
        }
    }
}
