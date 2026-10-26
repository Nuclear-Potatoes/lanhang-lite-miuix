package com.lanhang.miuix.moments

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import com.google.gson.JsonObject
import com.lanhang.miuix.R
import com.lanhang.miuix.net.ApiClient
import com.lanhang.miuix.net.RceApi
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Uploads a selected photo to the same media service used by the official app. */
object MomentImageUploader {
    private const val FALLBACK_MAX_BYTES = 2L * 1024 * 1024
    private const val MIN_MAX_BYTES = 64L * 1024
    private const val HARD_MAX_BYTES = 10L * 1024 * 1024
    // 朋友圈图片没必要留 2048：1600 长边在手机屏幕上看不出差别，体积小一半以上，
    // 更容易过服务器限制（用户 2026-10-25：发图提示「图片大小超过服务器限制」）。
    private const val MAX_DIMENSION = 1600
    private const val MIN_DIMENSION = 320
    private const val MIN_QUALITY = 40
    private val uploadHttp = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    fun upload(context: Context, uri: Uri): MediaItem {
        val config = RceApi.configurationAll().features?.mediaServer
        val token = RceApi.getImageUploadToken()
        val fileName = "moment_${System.currentTimeMillis()}_${(1000..9999).random()}.jpg"
        val maxBytes = resolveMaxBytes(config?.maxFileSize)
        val imageFile = File(context.cacheDir, fileName)
        val sourceFile = File(context.cacheDir, "source_$fileName")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                sourceFile.outputStream().use { output -> input.copyTo(output) }
            } ?: error(context.getString(R.string.error_image_read))
            compress(context, sourceFile, imageFile, maxBytes)

            val uploadUrl = config?.uploadUrls?.firstOrNull { it.isNotBlank() }
                ?: token.server?.let { "http://$it" }
                ?: error(context.getString(R.string.error_image_upload_url))
            val downloadBase = config?.downloadUrls?.firstOrNull { it.isNotBlank() }
                ?: token.domain?.let { "http://$it" }
                ?: error(context.getString(R.string.error_image_access_url))

            val form = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "token",
                    token.token ?: error(context.getString(R.string.error_image_token_empty))
                )
                .addFormDataPart("key", fileName)
                .addFormDataPart("file", fileName, imageFile.asRequestBody("image/jpeg".toMediaType()))
                .build()
            val request = Request.Builder().url(uploadUrl).post(form).build()
            uploadHttp.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    error(
                        context.getString(
                            R.string.error_image_upload_failed,
                            response.code,
                            body.take(160)
                        )
                    )
                }
                val uploadResult = runCatching {
                    ApiClient.gson.fromJson(body, JsonObject::class.java)?.getAsJsonObject("rc_url")
                }.getOrNull()
                val uploadedUrl = uploadResult?.get("path")?.asString
                    ?.takeIf { it.isNotBlank() }
                    ?.let { path ->
                        if (uploadResult.get("type")?.asInt == 1) path
                        else "${downloadBase.trimEnd('/')}/${path.trimStart('/')}"
                    }
                    ?: "${downloadBase.trimEnd('/')}/$fileName"
                return MediaItem(type = 0, url = uploadedUrl, thumbnail = uploadedUrl, digest = "")
            }
        } finally {
            sourceFile.delete()
            imageFile.delete()
        }
    }

    /**
     * 服务器下发的 `max_file_size` 单位并不统一：融云媒体的配置里有的是 KB、有的是字节。
     * 以前直接当字节用，遇到「2048」这种 KB 值就变成 2KB 上限，压缩到 320px 也进不去，
     * 于是每次都报「图片超过服务器大小限制」（用户 2026-10-25 反馈的真实原因）。
     * 规则：<=0 用兜底值；< 100000 视为 KB；否则视为字节；最后夹到 [64KB, 10MB]。
     */
    private fun resolveMaxBytes(raw: Long?): Long {
        val value = raw ?: 0L
        val bytes = when {
            value <= 0L -> FALLBACK_MAX_BYTES
            value < 100_000L -> value * 1024L
            else -> value
        }
        return bytes.coerceIn(MIN_MAX_BYTES, HARD_MAX_BYTES)
    }

    private fun compress(context: Context, source: File, destination: File, maxBytes: Long) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) {
            context.getString(R.string.error_image_invalid)
        }
        val sample = generateSequence(1) { it * 2 }
            .takeWhile { maxOf(bounds.outWidth, bounds.outHeight) / it > MAX_DIMENSION }
            .lastOrNull() ?: 1
        val bitmap = BitmapFactory.decodeFile(
            source.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        ) ?: error(context.getString(R.string.error_image_decode))

        val orientation = ExifInterface(source.absolutePath)
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        val rotation = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        val oriented = if (rotation == 0f) bitmap else {
            val matrix = Matrix().apply { postRotate(rotation) }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also { bitmap.recycle() }
        }

        var current = oriented
        try {
            while (true) {
                // 质量阶梯放宽到 40（以前下限 56，压不到限制就直接报错）：
                // 先降质量（肉眼看不出）、再缩尺寸，绝大多数图第一轮就能过。
                var quality = 88
                var bytes: ByteArray
                do {
                    val output = ByteArrayOutputStream()
                    current.compress(Bitmap.CompressFormat.JPEG, quality, output)
                    bytes = output.toByteArray()
                    quality -= 8
                } while (bytes.size > maxBytes && quality >= MIN_QUALITY)
                if (bytes.size <= maxBytes) {
                    destination.writeBytes(bytes)
                    return
                }
                val nextWidth = (current.width * 0.82f).toInt()
                val nextHeight = (current.height * 0.82f).toInt()
                if (minOf(nextWidth, nextHeight) < MIN_DIMENSION) {
                    error(context.getString(R.string.error_image_too_large))
                }
                val smaller = Bitmap.createScaledBitmap(current, nextWidth, nextHeight, true)
                current.recycle()
                current = smaller
            }
        } finally {
            current.recycle()
        }
    }
}
