package com.lanhang.miuix.moments

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.ViewCompat
import com.lanhang.miuix.R
import kotlin.math.abs

/**
 * 私信里点视频后的全屏预览。
 *
 * 2026-11-14 第七十四轮（用户：「私信中发送视频或图片时，应该添加一个预览功能直接预览
 * 发送的图片与视频，具体动效参考工作圈中打开图片的效果」）。
 *
 * 图文的两条路径此前不一致：图片点开走 [ImagePreviewActivity]（工作圈那套：黑底、
 * 下拉关闭、关闭时缩回原位），视频却是 `ACTION_VIEW` 丢给系统播放器 —— 会跳出 App，
 * 且没有任何转场。这里补上视频侧的同款预览：
 *
 *  - 同样是全屏黑底 + [DragDismissLayout] 下拉关闭，拖动时画面跟手缩小、背景变透明；
 *  - 进入时 0.92 → 1.0 的缩放淡入，退出时按当前拖动状态缩回去再 finish；
 *  - 视频用 [VideoView]，`setOnPreparedListener` 里起播并按屏幕比例摆正（不拉伸）；
 *  - 底部保留「用其他应用打开」，编码不被系统识别时仍有出路。
 *
 * 不引入任何第三方库，也不用 XML 布局（这里只有三层 View，代码里搭更清楚）。
 */
class VideoPreviewActivity : AppCompatActivity(), DragDismissLayout.Listener {

    companion object {
        private const val EXTRA_URI = "extra_uri"
        private const val EXTRA_TITLE = "extra_title"

        /** 下拉多少距离算「要关掉」 */
        private const val DISMISS_DP = 110f

        fun start(context: Context, uri: String, title: String? = null) {
            if (uri.isBlank()) return
            val intent = Intent(context, VideoPreviewActivity::class.java)
                .putExtra(EXTRA_URI, uri)
                .putExtra(EXTRA_TITLE, title)
            if (context !is android.app.Activity) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            (context as? android.app.Activity)?.overridePendingTransition(0, 0)
        }
    }

    private lateinit var root: DragDismissLayout
    private lateinit var video: VideoView
    private lateinit var stage: FrameLayout
    private val ease = PathInterpolator(0.4f, 0f, 0.2f, 1f)

    private var dismissing = false
    private var dragDy = 0f
    private var videoUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val raw = intent.getStringExtra(EXTRA_URI).orEmpty()
        videoUri = runCatching { Uri.parse(raw) }.getOrNull()

        val density = resources.displayMetrics.density
        root = DragDismissLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isClickable = true
            isFocusable = true
        }
        root.listener = this

        stage = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        video = VideoView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ).apply { gravity = Gravity.CENTER }
        }
        stage.addView(video)

        // 右上角关闭：和图片预览一样「退出整个预览」
        val close = pill("✕", primary = false).apply {
            layoutParams = FrameLayout.LayoutParams(
                (40 * density).toInt(),
                (40 * density).toInt(),
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = (40 * density).toInt()
                rightMargin = (16 * density).toInt()
            }
            setOnClickListener { closeSmoothly(0f, 0f) }
        }
        stage.addView(close)

        // 底部兜底：系统播放器能放的编码比 VideoView 多
        val openOther = pill(getString(R.string.im_video_open_other), primary = true).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = (52 * density).toInt()
            }
            setOnClickListener { openInOtherApp() }
        }
        stage.addView(openOther)

        root.addView(stage)
        setContentView(root)

        // 背景由透明渐显到纯黑（和图片预览的黑底保持一致，只是要有过渡）
        root.setBackgroundColor(Color.BLACK)
        root.alpha = 0f
        stage.scaleX = 0.92f
        stage.scaleY = 0.92f
        root.animate().alpha(1f).setDuration(200).setInterpolator(ease).start()
        stage.animate().scaleX(1f).scaleY(1f).setDuration(240).setInterpolator(ease).start()

        // 贴边显示（刘海屏也别让画面钻到摄像头下面）
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        video.setVideoURI(videoUri)
        video.setOnPreparedListener { mp ->
            mp.isLooping = false
            // 按视频真实比例摆正，避免竖屏视频被横向拉扁
            val vw = mp.videoWidth
            val vh = mp.videoHeight
            if (vw > 0 && vh > 0) {
                val boxW = root.width
                val boxH = root.height
                if (boxW > 0 && boxH > 0) {
                    val scale = minOf(boxW.toFloat() / vw, boxH.toFloat() / vh)
                    val lp = video.layoutParams as FrameLayout.LayoutParams
                    lp.width = (vw * scale).toInt()
                    lp.height = (vh * scale).toInt()
                    video.layoutParams = lp
                }
            }
            runCatching { video.start() }
        }
        video.setOnErrorListener { _, _, _ ->
            // 播不了就直接放行给系统播放器，别让用户对着黑屏
            openInOtherApp()
            true
        }

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = closeSmoothly(0f, 0f)
        })
    }

    override fun onPause() {
        super.onPause()
        runCatching { if (video.isPlaying) video.pause() }
    }

    override fun onDestroy() {
        runCatching { video.stopPlayback() }
        super.onDestroy()
    }

    private fun pill(text: String, primary: Boolean): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        gravity = Gravity.CENTER
        setPadding(dp(14), dp(9), dp(14), dp(9))
        setTextColor(if (primary) Color.WHITE else Color.WHITE)
        val bg = GradientDrawable()
        bg.cornerRadius = dp(19).toFloat()
        bg.setColor(
            if (primary) Color.parseColor("#CC1F6FEB") else Color.parseColor("#66000000")
        )
        background = bg
        isClickable = true
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun openInOtherApp() {
        val u = videoUri ?: return
        runCatching {
            val it = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(u, "video/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(it)
        }.onFailure {
            android.widget.Toast.makeText(this, R.string.im_video_play_failed, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /* ---------------- DragDismissLayout.Listener ---------------- */

    override fun canDrag(): Boolean = !dismissing

    override fun onDragStart() {
        runCatching { video.pause() }
    }

    override fun onDrag(dy: Float) {
        dragDy = dy
        val h = root.height.coerceAtLeast(1)
        // 往下拖越大，画面越小越透明 —— 和图片预览的手感一致
        val progress = (abs(dy) / h).coerceIn(0f, 1f)
        val s = 1f - progress * 0.28f
        stage.scaleX = s
        stage.scaleY = s
        stage.translationY = dy
        root.setBackgroundColor(Color.argb((255 * (1f - progress * 0.85f)).toInt().coerceIn(0, 255), 0, 0, 0))
    }

    override fun onDragEnd(dy: Float, velocityY: Float) {
        val limit = dp(DISMISS_DP.toInt()).toFloat()
        val commit = abs(dy) > limit || abs(velocityY) > 1200f
        if (commit) {
            closeSmoothly(dy, velocityY)
        } else {
            stage.animate().translationY(0f).scaleX(1f).scaleY(1f)
                .setDuration(160).setInterpolator(ease).start()
            root.setBackgroundColor(Color.BLACK)
            runCatching { video.start() }
        }
    }

    /** 顺着当前拖动状态缩回去再关，避免「先弹回原样再消失」的跳变 */
    private fun closeSmoothly(dyHint: Float = 0f, velocityY: Float = 0f) {
        if (dismissing) return
        dismissing = true
        runCatching { video.pause() }
        val h = root.height.coerceAtLeast(1)
        val target = if (dyHint > 0f) dyHint + h * 0.25f else h * 0.6f
        stage.animate()
            .translationY(target)
            .scaleX(0.86f)
            .scaleY(0.86f)
            .setDuration(if (velocityY > 800f) 150 else 200)
            .setInterpolator(ease)
            .start()
        root.animate().alpha(0f).setDuration(200).setInterpolator(ease)
            .withEndAction { finish() }
            .start()
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
