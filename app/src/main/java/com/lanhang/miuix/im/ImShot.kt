package com.lanhang.miuix.im

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.RenderNode
import android.graphics.HardwareRenderer
import android.media.Image
import android.media.ImageReader
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import android.widget.FrameLayout
import android.view.View
import android.view.ViewGroup
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.FileProvider
import androidx.core.view.drawToBitmap
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.findViewTreeViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.findViewTreeSavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File

/**
 * 2026-11-15 第八十九轮（用户：「截图应该只截取选中的消息，而不是一整个消息页面，参考 QQ」）：
 * QQ 多选截图的完整链路 = 点起止消息选范围 → 完成 → **只把范围内的消息**渲染成一张长图
 * → 底部弹层「发送给好友 / 保存到手机」。
 *
 * 长图渲染为什么这么绕（三次踩坑的最终结论，别走回头路）：
 * 1. 离屏挂 ComposeView 手动 measure/layout 后 `View.drawToBitmap()`（软件画布）→
 *    Compose 的 GraphicsLayer 是硬件 RenderNode，画进软件画布直接抛 IAE；
 * 2. 对正在显示的聊天 ComposeView `drawToBitmap()` → 同样的 IAE，且只能截到可见区；
 * 3. `PixelCopy` 只能拷窗口表面 —— 窗口表面只有屏幕那么大，**超出一屏的长图截不到**。
 *
 * 最终方案（QQ 也是自己渲染的思路）：把内容挂到窗口上等组合完成，然后
 * `RenderNode.beginRecording()` 拿**硬件画布**让 ComposeView 画进去，再用
 * `HardwareRenderer` 把这棵节点渲染到 `ImageReader` 的 Surface 上，读回 RGBA 像素。
 * 高度不受一屏限制，图片真正按内容高度生成。API 29 起可用（minSdk 26，低版本
 * 由调用方降级成 PixelCopy 截可见区）。
 */
object ImShot {

    /** 内容高度上限：GPU 纹理一般 4096~16384，超了让用户少选一点 */
    const val MAX_IMAGE_HEIGHT = 12000

    /**
     * 把 [content] 渲染成一张 [widthPx] 宽的长图。必须在主线程调用；
     * [owner] 用来给临时 ComposeView 继承 lifecycle / savedState（传当前界面的任意已挂载 View）。
     * 失败（组合没完成 / 内容超高 / 渲染出错）返回 null。
     */
    suspend fun renderLongImage(
        activity: Activity,
        widthPx: Int,
        owner: View,
        content: @Composable () -> Unit,
    ): Bitmap? {
        if (Build.VERSION.SDK_INT < 29) return null
        if (widthPx <= 0) return null
        val decor = activity.window.decorView as? ViewGroup ?: return null
        val holder = FrameLayout(activity)
        val cv = ComposeView(activity).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setViewTreeLifecycleOwner(owner.findViewTreeLifecycleOwner())
            setViewTreeViewModelStoreOwner(owner.findViewTreeViewModelStoreOwner())
            setViewTreeSavedStateRegistryOwner(owner.findViewTreeSavedStateRegistryOwner())
            setContent { MiuixTheme { content() } }
        }
        holder.addView(
            cv,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        // 0×0 的容器挂在窗口上只为让 composition 跑起来；默认 clipChildren 会把它裁得不可见
        decor.addView(holder, ViewGroup.LayoutParams(0, 0))
        try {
            repeat(4) { withFrameNanos { } }
            cv.measure(
                View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            cv.layout(0, 0, cv.measuredWidth, cv.measuredHeight)
            val w = cv.measuredWidth
            val h = cv.measuredHeight
            if (w <= 0 || h <= 0 || h > MAX_IMAGE_HEIGHT) return null
            return renderViewToBitmap(cv, w, h)
        } finally {
            decor.removeView(holder)
        }
    }

    /** 低版本 / 渲染失败的降级：对已显示的 [host] 做可见区截图（软件画布，可能失败，尽力而为） */
    suspend fun captureVisible(context: Context, host: View, fileName: String): Boolean {
        if (host.width <= 0 || host.height <= 0) return false
        return withContext(Dispatchers.IO) {
            runCatching { saveToGallery(context, host.drawToBitmap(), fileName) }.getOrDefault(false)
        }
    }

    /** 长图存进 cacheDir/shot，用于预览与分享（FileProvider 的 cache-path 已覆盖 cacheDir 根） */
    fun saveShareFile(context: Context, bitmap: Bitmap, fileName: String): File? {
        return runCatching {
            val dir = File(context.cacheDir, "shot").apply { mkdirs() }
            val f = File(dir, "$fileName.png")
            f.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            f
        }.getOrNull()
    }

    fun shareUri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)

    /** 保存进系统相册（Pictures/LanHang），Android 10+ 免存储权限 */
    fun saveToGallery(context: Context, bitmap: Bitmap, fileName: String): Boolean {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$fileName.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/LanHang")
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return false
        val ok = runCatching {
            resolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            } ?: return false
            true
        }.getOrDefault(false)
        if (!ok) runCatching { resolver.delete(uri, null, null) }
        return ok
    }

    /** 分享长图（QQ 底部弹层的「发送给好友」走的就是系统分享） */
    fun shareIntent(context: Context, file: File): Intent {
        val uri = shareUri(context, file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "分享长图")
    }

    // ------------------------------------------------------------------ 硬件渲染

    @RequiresApi(29)
    private fun renderViewToBitmap(view: View, w: Int, h: Int): Bitmap? {
        val node = RenderNode("imshot")
        node.setPosition(0, 0, w, h)
        val canvas = node.beginRecording(w, h)
        try {
            view.draw(canvas)
        } finally {
            node.endRecording()
        }
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        val renderer = HardwareRenderer().apply {
            setSurface(reader.surface)
            setContentRoot(node)
        }
        try {
            renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
            var image: Image? = null
            val deadline = System.currentTimeMillis() + 4000
            while (image == null && System.currentTimeMillis() < deadline) {
                image = reader.acquireLatestImage()
                if (image == null) SystemClock.sleep(30)
            }
            image ?: return null
            val plane = image.planes[0]
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            if (plane.rowStride == w * 4) {
                bitmap.copyPixelsFromBuffer(plane.buffer)
            } else {
                // ImageReader 的行 Stride 可能带对齐填充，逐行拷
                val bb = plane.buffer
                val row = ByteArray(w * 4)
                val pixels = java.nio.ByteBuffer.allocateDirect(w * h * 4)
                for (y in 0 until h) {
                    bb.position(y * plane.rowStride)
                    bb.get(row, 0, w * 4)
                    pixels.put(row)
                }
                pixels.flip()
                bitmap.copyPixelsFromBuffer(pixels)
            }
            return bitmap
        } catch (e: Exception) {
            android.util.Log.e("ImShot", "硬件渲染长图失败", e)
            return null
        } finally {
            runCatching { renderer.setSurface(null) }
            runCatching { reader.close() }
        }
    }
}
