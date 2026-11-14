package com.lanhang.miuix.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.Camera
import android.os.Bundle
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer

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
 */
class ScanQrActivity : Activity(), SurfaceHolder.Callback, Camera.PreviewCallback {

    companion object {
        const val EXTRA_RESULT = "qr_result"

        /** 结果码：扫码成功。 */
        const val RESULT_SCANNED = 1001

        /** 结果码：用户主动放弃（按返回键 / 点关闭）。 */
        const val RESULT_CANCELLED = 1002

        fun intent(context: Context): Intent = Intent(context, ScanQrActivity::class.java)
    }

    private var camera: Camera? = null
    private var previewing = false
    private val reader = MultiFormatReader()
    private var surfaceView: SurfaceView? = null

    // 预览帧的宽高。Camera1 的 onPreviewFrame 每秒来十几帧，
    // 每帧都去 cam.parameters 取一次会明显掉帧，这里在开预览时缓存下来。
    private var previewW = 0
    private var previewH = 0
    private var hint: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            // 正常路径不会走到这里（OaWebViewActivity 那边已经先申请过），
            // 万一没有就直接放弃，让网页收到「取消」，不要卡一个黑屏。
            setResult(RESULT_CANCELLED)
            finish()
            return
        }
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

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val surface = SurfaceView(this).apply {
            holder.addCallback(this@ScanQrActivity)
        }
        surfaceView = surface
        root.addView(
            surface,
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
        }
        hint = tip
        root.addView(
            tip,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = Gravity.BOTTOM; bottomMargin = 160 },
        )

        val close = TextView(this).apply {
            text = "关闭"
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(48, 32, 48, 32)
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
            ).apply { gravity = Gravity.TOP or Gravity.END },
        )

        setContentView(root)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        startPreview(holder)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (previewing) {
            camera?.stopPreview()
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
            stopPreview()
            setResult(
                RESULT_SCANNED,
                Intent().putExtra(EXTRA_RESULT, result.text.orEmpty()),
            )
            finish()
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
}
