package com.lanhang.miuix.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.hardware.Camera
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.lanhang.miuix.R
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import kotlin.math.max
import kotlin.math.min

/**
 * 2026-11-14 第七十七轮：给「赣青二课」补一个真正能用的扫码页。
 *
 * 为什么需要它：二课首页右上角的扫码按钮走的是 `window.CP2.showScanQr({needResult:1})`，
 * `window.CP2` 是青新闻 App 注入的一套桥对象（sdkDebug / showScanQr / selectMedia /
 * getLocation / wxSign / upload …）。在我们的 WebView 里这个对象虽然存在（站点自己
 * 铺了一份同名 stub），但 `showScanQr` 返回的 Promise 永远不会 resolve ——
 * 所以用户点下去就是「没有任何反应」，既不报错也不跳页。
 * 现在由 [ScanBridge] 把这个方法接管过来，落到这里做真正的相机扫码，
 * 扫到结果后按站点约定的结构 `{code: 1, data: {qrCode: ...}}` 回调给网页。
 *
 * 解码用项目里已有的 `com.google.zxing:core`，不额外引三方 UI 库；
 * 预览用最朴素的 `android.hardware.Camera`（API 1 那套，虽然被标记废弃，
 * 但只有它的 `setPreviewCallback` 能拿到 NV21 原始帧，改动面最小）。
 *
 * 第七十八轮（2026-11-15）按用户反馈补三件事：
 * 1. 预览不再拉伸 —— 原来 SurfaceView 是 `MATCH_PARENT`，摄像头给的是 640×480，
 *    被硬拉成整屏 1220×2656，画面完全变形。改成按预览比例「居中裁剪铺满」
 *    （等价于 ImageView 的 centerCrop），多出来的部分裁掉，比例始终正确。
 * 2. 补取景框 —— 原来只有底部一行文字，用户不知道该把二维码对准哪里。
 *    现在四角亮框 + 四周压暗，中间是可直接扫描的方形区域。
 * 3. 补手电筒开关 —— 光线不足时用 `FLASH_MODE_TORCH` 常亮补光，再次点击熄灭。
 *
 * 第九十五轮（2026-11-20）按用户要求再补一件事：**从相册选图片扫码**。
 * 有些二维码在屏幕上（截图、聊天里收到的图、别人发给你的海报），相机拍不准也拍不清，
 * 直接在相册里挑原图识别最省事。左下角新增一个相册按钮（图标照 Material Icons
 * `photo_library` outlined，见 `res/drawable/ic_album.xml`），走系统相册
 * （`ACTION_GET_CONTENT`，不需要读相册权限），选中后把图解码出来交给 zxing。
 * 大图按 `inSampleSize` 缩放后再解（防 OOM），并按 EXIF 方向旋正（手机拍的照片常带 90/180/270，
 * 不旋正会扫不到）。识别成功走和相机扫描完全一样的回传路径。
 */
class ScanQrActivity : Activity(), SurfaceHolder.Callback, Camera.PreviewCallback {

    companion object {
        const val EXTRA_RESULT = "qr_result"

        /** 结果码：扫码成功。 */
        const val RESULT_SCANNED = 1001

        /** 结果码：用户主动放弃（按返回键 / 点关闭）。 */
        const val RESULT_CANCELLED = 1002

        /** 第九十三轮：本页自己申请相机运行时权限的请求码。 */
        private const val REQ_CAMERA = 7101

        /** 第九十五轮：从相册选图识别的请求码。 */
        private const val REQ_PICK_IMAGE = 7102

        /**
         * 第九十五轮：相册图解码后最长边的上限（像素）。
         *
         * 1600 是经验值：够 zxing 认出二维码，又把内存压在 `1600×1600×4B ≈ 10MB` 以内；
         * 再往上加，识别率并不会明显变好，反而让低端机在 OOM 边缘晃。
         */
        private const val MAX_IMAGE_SIDE = 1600

        fun intent(context: Context): Intent = Intent(context, ScanQrActivity::class.java)
    }

    private var camera: Camera? = null
    private var previewing = false
    private val reader = MultiFormatReader()
    private var surfaceView: CoverSurfaceView? = null

    // 预览帧的宽高。Camera1 的 onPreviewFrame 每秒来十几帧，
    // 每帧都去 cam.parameters 取一次会明显掉帧，这里在开预览时缓存下来。
    private var previewW = 0
    private var previewH = 0
    private var hint: TextView? = null
    private var torchButton: ImageView? = null

    /** 手电筒当前是否点亮。 */
    private var torchOn = false

    /** 设备是否支持常亮补光；null 表示还没问过摄像头。 */
    private var torchSupported: Boolean? = null

    /** 相册按钮（第九十五轮）。 */
    private var albumButton: ImageView? = null

    private fun dp(value: Float): Int = max(1, (value * resources.displayMetrics.density).toInt())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            // 第九十三轮修正：这里以前直接 finish，注释写着「上游已经先申请过」——
            // 但全工程根本没有第二处申请 CAMERA（清单只声明、OaWebViewActivity 只代网页 grant），
            // 于是第一次点扫码就是「立刻返回、网页收到 code=0、用户看到毫无反应」。
            // 现在自己申请，拿到权限再建界面；被拒绝才明确提示并回取消。
            android.util.Log.i("ScanQr", "相机权限未授予，自己申请")
            runCatching {
                requestPermissions(arrayOf(android.Manifest.permission.CAMERA), REQ_CAMERA)
            }.onFailure {
                android.util.Log.w("ScanQr", "权限申请发起失败", it)
                toastAndFinish("无法申请相机权限，请在系统设置里允许「蓝航」使用相机")
            }
            return
        }
        buildUi()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_CAMERA) return
        val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        android.util.Log.i("ScanQr", "相机权限结果 granted=" + granted)
        if (granted) {
            buildUi()
        } else {
            toastAndFinish("需要相机权限才能扫码，请在系统设置里允许「蓝航」使用相机")
        }
    }

    private fun toastAndFinish(message: String) {
        runCatching {
            android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
        }
        setResult(RESULT_CANCELLED)
        finish()
    }

    /** 真正搭界面 + 开预览。只有拿到相机权限后才会走到这里。 */
    private fun buildUi() {
        reader.setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(
                    BarcodeFormat.QR_CODE,
                    BarcodeFormat.CODE_128,
                    BarcodeFormat.CODE_39,
                    BarcodeFormat.EAN_13,
                ),
                DecodeHintType.TRY_HARDER to true,
            ),
        )

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            clipChildren = true
        }

        // 预览层：自己按比例量尺寸，父容器居中，超出的部分被 root 裁掉。
        val surface = CoverSurfaceView(this).apply {
            holder.addCallback(this@ScanQrActivity)
        }
        surfaceView = surface
        root.addView(
            surface,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER,
            ),
        )

        // 取景蒙层：四周压暗、中间透出取景方框、四角亮线。
        root.addView(
            ScanOverlayView(this),
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        val tip = TextView(this).apply {
            text = "把二维码放进框内"
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            setShadowLayer(6f, 0f, 1f, 0x99000000.toInt())
        }
        hint = tip
        root.addView(
            tip,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = Gravity.BOTTOM; bottomMargin = dp(88f) },
        )

        // 手电筒：圆形按钮放矢量图标，点亮时变成浅色实心，一眼能看出状态。
        val torch = ImageView(this).apply {
            setImageResource(R.drawable.ic_torch)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(17f), dp(17f), dp(17f), dp(17f))
            isClickable = true
            isFocusable = true
            contentDescription = "手电筒"
            setOnClickListener { toggleTorch() }
            applyTorchLook(false)
        }
        torchButton = torch
        val torchSize = dp(64f)
        root.addView(
            torch,
            FrameLayout.LayoutParams(torchSize, torchSize).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                rightMargin = dp(28f)
                bottomMargin = dp(20f)
            },
        )

        // 相册：左下角一个同样大小的圆钮。二维码在屏幕上（截图、别人发的海报）时，
        // 相机怎么对都对不准，直接挑相册里的原图识别最省事。
        val album = ImageView(this).apply {
            setImageResource(R.drawable.ic_album)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(17f), dp(17f), dp(17f), dp(17f))
            isClickable = true
            isFocusable = true
            contentDescription = "从相册选图"
            setOnClickListener { pickImage() }
            applyRoundLook(false, R.drawable.ic_album)
        }
        albumButton = album
        val albumSize = dp(64f)
        root.addView(
            album,
            FrameLayout.LayoutParams(albumSize, albumSize).apply {
                gravity = Gravity.BOTTOM or Gravity.START
                leftMargin = dp(28f)
                bottomMargin = dp(20f)
            },
        )

        val close = TextView(this).apply {
            text = "关闭"
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(dp(16f), dp(10f), dp(16f), dp(10f))
            setShadowLayer(6f, 0f, 1f, 0x99000000.toInt())
            setOnClickListener {
                setResult(RESULT_CANCELLED)
                finish()
            }
        }
        root.addView(
            close,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = Gravity.TOP or Gravity.END; topMargin = dp(8f); rightMargin = dp(8f) },
        )

        setContentView(root)
    }

    /** 按钮外观：开启时白底深色图标（表示「正在补光」），关闭时半透明黑底白色图标。 */
    private fun ImageView.applyTorchLook(on: Boolean) {
        applyRoundLook(on, R.drawable.ic_torch)
    }

    /**
     * 圆钮外观，手电筒与相册共用，保证两个按钮长得一样。
     *
     * [active] 为 true 时白底深色图标（表示「已开启」），false 时半透明黑底白图标。
     * 先 `setImageResource` 再 `setColorFilter`：换图后旧的滤镜会被清掉，
     * 顺序反了这个按钮就会变成彩色原图。
     */
    private fun ImageView.applyRoundLook(active: Boolean, icon: Int) {
        setImageResource(icon)
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (active) 0xF2FFFFFF.toInt() else 0x66000000)
            setStroke(dp(1f), if (active) 0x00000000 else 0x40FFFFFF)
        }
        setColorFilter(if (active) 0xFF1A1A1A.toInt() else Color.WHITE)
    }

    /**
     * 切换常亮补光。
     *
     * 先问一遍设备支持哪些闪光模式：模拟器和不带补光灯的前摄都会返回空列表，
     * 这时直接给用户一句提示，不要静默失败。设置失败（比如被别的应用占用）
     * 会把状态回滚，保证按钮显示和实际硬件状态一致。
     */
    /**
     * 打开系统相册挑一张图。
     *
     * 用 `ACTION_GET_CONTENT` 而不是读 `MediaStore`：前者由系统相册自己做，
     * 不需要申请 `READ_MEDIA_IMAGES`，用户选哪张就给哪张的临时读权限，权限面最小。
     */
    private fun pickImage() {
        // 去相册途中先把预览停掉：相机一直开着白占资源，返回时也容易拿到已失效的 Surface。
        stopPreview()
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        runCatching {
            startActivityForResult(intent, REQ_PICK_IMAGE)
        }.onFailure {
            // 同 onActivityResult：先恢复预览，再写提示，否则文案会被 startPreview 重置。
            startPreviewIfSurfaceReady()
            if (camera != null) hint?.text = "没有找到可用的相册"
        }
    }

    @Deprecated("旧的 startActivityForResult 回调，与 Camera1 时代保持一致")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK_IMAGE) return
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) {
            // 用户在相册里按了返回：恢复取景，不当作取消整个扫码。
            startPreviewIfSurfaceReady()
            return
        }
        val text = runCatching { decodeImage(uri) }.getOrNull()
        if (text.isNullOrEmpty()) {
            android.util.Log.i("ScanQr", "相册图片没识别到码 uri=$uri")
            // 顺序不能反：startPreview 会自己把提示重置成「把二维码放进框内」，
            // 先设文案再起预览就白设了。相机没起来时也不要盖掉它的失败原因。
            startPreviewIfSurfaceReady()
            if (camera != null) hint?.text = "这张图里没找到二维码"
            return
        }
        android.util.Log.i("ScanQr", "相册识别成功 len=${text.length} text=$text")
        deliver(text)
    }

    /**
     * 从相册 URI 里解出二维码文本，解不出返回 null。
     *
     * 三步都不能省：① 先只读图头算 `inSampleSize`，四千万像素的原图直接
     * `decodeStream` 会 OOM；② 按 EXIF 方向旋正，手机拍的照片常带 90/180/270，
     * 不旋正 zxing 扫不到；③ 转成 `RGBLuminanceSource` 交给同一个 [reader]。
     */
    private fun decodeImage(uri: Uri): String? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching {
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > MAX_IMAGE_SIDE || bounds.outHeight / sample > MAX_IMAGE_SIDE) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = runCatching {
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        }.getOrNull() ?: return null
        val upright = rotateByExif(decoded, uri)
        if (upright !== decoded) decoded.recycle()
        val w = upright.width
        val h = upright.height
        if (w <= 0 || h <= 0) {
            upright.recycle()
            return null
        }
        val pixels = IntArray(w * h)
        upright.getPixels(pixels, 0, w, 0, 0, w, h)
        upright.recycle()
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(w, h, pixels)))
        val result = runCatching { reader.decodeWithState(bitmap) }.getOrNull()
        reader.reset()
        return result?.text?.takeIf { it.isNotBlank() }
    }

    /** 按 EXIF 方向把图旋正；方向未知或本来就是正的，原样返回。 */
    private fun rotateByExif(source: Bitmap, uri: Uri): Bitmap {
        val orientation = runCatching {
            contentResolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return source
        }
        return runCatching {
            Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        }.getOrDefault(source)
    }

    /** 相机与相册共用出口：识别成功就带着结果关页。 */
    private fun deliver(text: String) {
        stopPreview()
        setResult(RESULT_SCANNED, Intent().putExtra(EXTRA_RESULT, text))
        finish()
    }

    /** 表面仍然有效就重启预览（从相册回来时用，否则取景框是黑的）。 */
    private fun startPreviewIfSurfaceReady() {
        val holder = surfaceView?.holder ?: return
        if (holder.surface?.isValid == true) startPreview(holder)
    }

    private fun toggleTorch() {
        val cam = camera
        if (cam == null) {
            hint?.text = "摄像头还没准备好"
            return
        }
        val supported = torchSupported ?: runCatching {
            cam.parameters.supportedFlashModes.orEmpty()
                .contains(Camera.Parameters.FLASH_MODE_TORCH)
        }.getOrDefault(false).also { torchSupported = it }
        if (!supported) {
            hint?.text = "这个摄像头不支持手电筒"
            return
        }
        val want = !torchOn
        val ok = runCatching {
            val params = cam.parameters
            params.focusMode = params.focusMode
            params.flashMode = if (want) {
                Camera.Parameters.FLASH_MODE_TORCH
            } else {
                Camera.Parameters.FLASH_MODE_OFF
            }
            cam.parameters = params
            true
        }.getOrDefault(false)
        torchOn = ok && want
        torchButton?.applyTorchLook(torchOn)
        hint?.text = if (torchOn) "手电筒已打开" else "把二维码放进框内"
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        startPreview(holder)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (previewing) {
            runCatching { camera?.stopPreview() }
            previewing = false
        }
        startPreview(holder)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        stopPreview()
    }

    private fun startPreview(holder: SurfaceHolder) {
        if (previewing) return
        // 模拟器与部分机型上 Camera.open() 会因为摄像头被占用而返回 null，
        // 这里先用无参重载，失败再显式指定 0 号摄像头试一次。
        val cam = runCatching { Camera.open() }.getOrNull()
            ?: runCatching { Camera.open(0) }.getOrNull()
        if (cam == null) {
            android.util.Log.e("ScanQr", "Camera.open 两次都失败，没有可用摄像头")
            hint?.text = "摄像头打不开"
            return
        }
        camera = cam
        torchSupported = null
        torchOn = false
        torchButton?.applyTorchLook(false)

        // 每一项设置单独兜住：模拟器的虚拟摄像头不支持连续对焦、也未必接受指定预览格式，
        // 任何一项抛异常都不该把整个预览拖垮（之前就是一锅端，全部失败只看得到「初始化失败」）。
        runCatching {
            val params = cam.parameters
            val sizes = params.supportedPreviewSizes.orEmpty()
            val target = sizes
                .filter { it.width <= 1280 && it.height <= 1280 }
                .maxByOrNull { it.width * it.height }
                ?: sizes.minByOrNull { it.width * it.height }
            if (target != null) {
                params.setPreviewSize(target.width, target.height)
            }
            // 只有设备明确支持连续对焦时才设，否则 setParameters 会抛 IllegalArgumentException。
            val modes = runCatching { params.supportedFocusModes }.getOrNull().orEmpty()
            if (modes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                params.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE
            }
            cam.parameters = params
            previewW = params.previewSize?.width ?: 0
            previewH = params.previewSize?.height ?: 0
        }.onFailure { android.util.Log.w("ScanQr", "摄像头参数设置失败，用默认值继续", it) }

        // 预览格式不强求 NV21：默认的 YV12 与 NV21 的 Y 平面完全一样，
        // zxing 只读 Y 平面，所以设不上也不影响解码。
        runCatching {
            val params = cam.parameters
            params.previewFormat = android.graphics.ImageFormat.NV21
            cam.parameters = params
        }.onFailure { android.util.Log.w("ScanQr", "设置 NV21 失败，沿用默认格式", it) }

        // 真正的开流这部分失败就是失败，必须报出来。
        runCatching {
            cam.setDisplayOrientation(90)
            cam.setPreviewDisplay(holder)
            cam.setPreviewCallback(this)
            cam.startPreview()
            previewing = true
            hint?.text = "把二维码放进框内"
            // 预览跑起来之后才知道真实分辨率，这里把比例交给 SurfaceView 重新量一次，
            // 它按「居中裁剪铺满」算出正确尺寸，画面才不会像之前那样被拉扁。
            if (previewW > 0 && previewH > 0) {
                surfaceView?.let {
                    it.ratio = previewH.toFloat() / previewW.toFloat()
                    it.requestLayout()
                }
            } else {
                android.util.Log.w("ScanQr", "拿不到预览尺寸，按整屏铺开，画面可能变形")
            }
        }.onFailure {
            android.util.Log.e("ScanQr", "启动预览失败", it)
            hint?.text = "摄像头初始化失败"
            stopPreview()
        }
    }

    private fun stopPreview() {
        val cam = camera ?: return
        runCatching {
            cam.setPreviewCallback(null)
            cam.stopPreview()
        }
        runCatching { cam.release() }
        camera = null
        previewing = false
        torchOn = false
        torchSupported = null
        torchButton?.applyTorchLook(false)
    }

    override fun onPreviewFrame(data: ByteArray?, cam: Camera?) {
        if (data == null || cam == null) return
        // 用开预览时缓存下来的尺寸，不再每帧问一次摄像头（那样每秒十几次 setParameters 级的开销）。
        val w = if (previewW > 0) previewW else (cam.parameters.previewSize?.width ?: return)
        val h = if (previewH > 0) previewH else (cam.parameters.previewSize?.height ?: return)
        // NV21 的 Y 平面在最前面，正好是 zxing 要的灰度图；预览被旋转了 90°，
        // 这里反过来把宽高对调，条码才是正的（否则竖着扫不到）。
        val source = runCatching {
            PlanarYUVLuminanceSource(data, w, h, 0, 0, w, h, false)
        }.getOrNull() ?: return
        val rotated = runCatching { source.rotateCounterClockwise() }.getOrNull() ?: source
        val bitmap = BinaryBitmap(HybridBinarizer(rotated))
        val result = runCatching { reader.decodeWithState(bitmap) }.getOrNull()
        reader.reset()
        if (result != null) {
            // 扫到了：先停预览再回调，避免同一条码连着触发好几次。
            deliver(result.text.orEmpty())
        }
    }

    @Deprecated("Camera1 的旧接口，保持签名一致")
    override fun onBackPressed() {
        setResult(RESULT_CANCELLED)
        finish()
    }

    override fun onPause() {
        stopPreview()
        super.onPause()
    }

    /**
     * 第九十五轮：从相册返回时要把预览重新拉起来。
     *
     * [onPause] 里已经把相机释放了，而 `surfaceCreated` 只会在 Surface 重建时回调一次，
     * 相册盖上来再退回去通常不会重建 Surface —— 不在这里补一刀，用户看到的就是黑屏。
     */
    override fun onResume() {
        super.onResume()
        if (camera == null) startPreviewIfSurfaceReady()
    }

    /**
     * 按摄像头真实比例铺满屏幕的预览层。
     *
     * 摄像头给的是 4:3（如 640×480），屏幕是 1220×2656。直接把 SurfaceView 设成
     * MATCH_PARENT 会让画面横向被拉伸一半以上，人眼立刻能看出「变形」。
     * 这里按 [ratio]（显示后的 宽/高）放大到刚好盖住父容器，溢出部分由父容器裁剪，
     * 效果与 ImageView 的 centerCrop 一致。
     */
    private class CoverSurfaceView(context: Context) : SurfaceView(context) {

        /** 显示内容的 宽/高 比；0 表示还没拿到摄像头参数。 */
        var ratio = 0f

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            val h = MeasureSpec.getSize(heightMeasureSpec)
            if (ratio <= 0f || w <= 0 || h <= 0) {
                setMeasuredDimension(w, h)
                return
            }
            val widthByHeight = (h * ratio).toInt()
            if (widthByHeight >= w) {
                setMeasuredDimension(widthByHeight, h)
            } else {
                setMeasuredDimension(w, (w / ratio).toInt())
            }
        }
    }

    /**
     * 取景蒙层：四周压暗、中间挖一个方形扫描区、四角画亮线，并在中间画一条扫描线。
     * 纯 Canvas 绘制，不引任何图片资源。
     */
    private class ScanOverlayView(context: Context) : View(context) {

        private val hole = RectF()
        private val maskPaint = Paint().apply { color = 0xA6000000.toInt() }
        private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = context.resources.displayMetrics.density * 3.5f
            strokeCap = Paint.Cap.ROUND
        }
        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x66FFFFFF
            style = Paint.Style.STROKE
            strokeWidth = context.resources.displayMetrics.density * 2f
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            val side = min(w, h) * 0.66f
            val cx = w / 2f
            // 稍微上移，给底部提示与手电筒让出空间，视觉重心更稳。
            val cy = h / 2f - h * 0.06f
            hole.set(cx - side / 2f, cy - side / 2f, cx + side / 2f, cy + side / 2f)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (hole.isEmpty) return
            val w = width.toFloat()
            val h = height.toFloat()

            // 四周压暗：分四块画，避免用 CLEAR 图层带来的合成开销。
            canvas.drawRect(0f, 0f, w, hole.top, maskPaint)
            canvas.drawRect(0f, hole.bottom, w, h, maskPaint)
            canvas.drawRect(0f, hole.top, hole.left, hole.bottom, maskPaint)
            canvas.drawRect(hole.right, hole.top, w, hole.bottom, maskPaint)

            // 四角亮线：每角两条，长度取边长的 17%。
            val len = hole.width() * 0.17f
            // 左上
            canvas.drawLine(hole.left, hole.top, hole.left + len, hole.top, framePaint)
            canvas.drawLine(hole.left, hole.top, hole.left, hole.top + len, framePaint)
            // 右上
            canvas.drawLine(hole.right - len, hole.top, hole.right, hole.top, framePaint)
            canvas.drawLine(hole.right, hole.top, hole.right, hole.top + len, framePaint)
            // 左下
            canvas.drawLine(hole.left, hole.bottom - len, hole.left, hole.bottom, framePaint)
            canvas.drawLine(hole.left, hole.bottom, hole.left + len, hole.bottom, framePaint)
            // 右下
            canvas.drawLine(hole.right, hole.bottom - len, hole.right, hole.bottom, framePaint)
            canvas.drawLine(hole.right - len, hole.bottom, hole.right, hole.bottom, framePaint)

            // 中间横向扫描线，帮助用户判断「对准这里」。
            val midY = hole.centerY()
            canvas.drawLine(hole.left + len * 0.5f, midY, hole.right - len * 0.5f, midY, linePaint)
        }
    }
}