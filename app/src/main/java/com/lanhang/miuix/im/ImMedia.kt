package com.lanhang.miuix.im

import android.content.Context
import android.content.Intent
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.Locale

/**
 * 私信里「图片 / 视频 / 文件 / 语音 / 位置」这五种消息的本地支撑代码。
 *
 * 全部用系统能力实现（`ContentResolver` / `MediaMetadataRetriever` / `MediaRecorder` /
 * `MediaPlayer` / `LocationManager` / `Geocoder`），**没有新增任何第三方依赖**。
 * 上传、下载分别由 IMLib 的 `sendMediaMessage` 和服务端地址完成，这里只负责
 * 「把用户选的 content:// 变成 SDK 能读的本地文件」以及「把远端地址变成本地可播放/可打开的文件」。
 */
object ImMedia {

    /** 图片 / 视频 / 文件都先落到这个目录，SDK 上传完再随系统清理 */
    private fun cacheDir(context: Context): File =
        File(context.cacheDir, "im").apply { mkdirs() }

    /** 把 content:// 复制成缓存文件（Photo Picker / SAF 给的临时授权进程一被杀就失效） */
    fun copyToCache(context: Context, uri: Uri, fileName: String? = null): File? = runCatching {
        val name = fileName ?: queryName(context, uri) ?: ("im_" + System.currentTimeMillis())
        val out = File(cacheDir(context), name.replace('/', '_'))
        context.contentResolver.openInputStream(uri)?.use { input ->
            out.outputStream().use { output -> input.copyTo(output) }
        } ?: return null
        out
    }.getOrNull()

    /** 选中的文件名（SAF 提供） */
    fun queryName(context: Context, uri: Uri): String? {
        if (uri.scheme == "file") return uri.lastPathSegment
        return runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        }.getOrNull() ?: uri.lastPathSegment
    }

    /** 选中的文件大小 */
    fun querySize(context: Context, uri: Uri): Long = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) cursor.getLong(index) else 0L
        } ?: 0L
    }.getOrDefault(0L)

    /** 视频时长（秒）与封面（抽第一帧存 PNG，SightMessage 需要一张缩略图） */
    fun videoInfo(context: Context, uri: Uri): Pair<File?, Int> {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val ms = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            val cover = frame?.let { bitmap ->
                val file = File(cacheDir(context), "thumb_${System.currentTimeMillis()}.jpg")
                file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, it) }
                file
            }
            cover to ((ms / 1000).toInt().coerceAtLeast(1))
        } catch (e: Exception) {
            null to 0
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** 文件大小的人类可读形式 */
    fun formatSize(bytes: Long): String = when {
        bytes <= 0 -> ""
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(Locale.US, "%.0f KB", bytes / 1024f)
        bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1024f / 1024f)
        else -> String.format(Locale.US, "%.2f GB", bytes / 1024f / 1024f / 1024f)
    }

    /**
     * 打开收到的文件：远端先下载到缓存，再交给系统播放器/阅读器。
     *
     * 用 `FileProvider` 出 content://，不然 Android 7 起直接 `file://` 会抛
     * `FileUriExposedException`。下载在 IO 线程，结果回调回主线程。
     */
    fun openFile(context: Context, uri: String, name: String, onError: (String) -> Unit) {
        val app = context.applicationContext
        Thread {
            val file = runCatching {
                if (uri.startsWith("http", true)) {
                    val target = File(cacheDir(app), name.ifBlank { "file_${System.currentTimeMillis()}" })
                    if (!target.exists() || target.length() == 0L) {
                        val response = OkHttpClient().newCall(Request.Builder().url(uri).build()).execute()
                        response.body?.byteStream()?.use { input ->
                            target.outputStream().use { output -> input.copyTo(output) }
                        }
                        response.close()
                    }
                    target
                } else {
                    File(Uri.parse(uri).path ?: return@runCatching null)
                }
            }.getOrNull()
            if (file == null || !file.exists()) {
                onError("文件下载失败")
                return@Thread
            }
            runCatching {
                val shared = FileProvider.getUriForFile(app, app.packageName + ".fileprovider", file)
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(shared, mimeOf(file.name))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                app.startActivity(intent)
            }.onFailure { onError("没有能打开这个文件的应用") }
        }.start()
    }

    /** 打开地图看位置（有高德/百度/Google 就交给它们，没有就落到浏览器） */
    fun openLocation(context: Context, lat: Double, lng: Double, poi: String) {
        val label = Uri.encode(poi.ifBlank { "$lat,$lng" })
        val candidates = listOf(
            "androidamap://viewMap?sourceApplication=lanhang&poiname=$label&lat=$lat&lon=$lng&dev=0",
            "baidumap://map/marker?location=$lat,$lng&title=$label&src=lanhang",
            "geo:$lat,$lng?q=$lat,$lng($label)",
        )
        for (url in candidates) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(intent) }.isSuccess) return
        }
    }

    /**
     * 当前位置：优先 GPS / 网络的上次已知点，取不到就返回 null（不编造坐标）。
     * 地名用系统 `Geocoder` 反查，没有网络时退回「纬度, 经度」。
     */
    fun currentLocation(context: Context): Pair<Location, String>? {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        if (!LocationManager::class.java.isInstance(manager)) return null
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        val best = providers.mapNotNull { provider ->
            runCatching {
                if (manager.isProviderEnabled(provider)) manager.getLastKnownLocation(provider) else null
            }.getOrNull()
        }.maxByOrNull { it.time } ?: return null
        val poi = runCatching {
            @Suppress("DEPRECATION")
            Geocoder(context, Locale.CHINA)
                .getFromLocation(best.latitude, best.longitude, 1)
                ?.firstOrNull()
                ?.let { listOfNotNull(it.locality, it.subLocality, it.thoroughfare, it.featureName).distinct().joinToString("") }
        }.getOrNull()
        return best to poi.orEmpty().ifBlank {
            String.format(Locale.US, "%.5f, %.5f", best.latitude, best.longitude)
        }
    }

    private fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase(Locale.US)) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "mp4" -> "video/mp4"
        "mp3" -> "audio/mpeg"
        "m4a", "aac" -> "audio/mp4"
        "pdf" -> "application/pdf"
        "doc", "docx" -> "application/msword"
        "xls", "xlsx" -> "application/vnd.ms-excel"
        "ppt", "pptx" -> "application/vnd.ms-powerpoint"
        "zip" -> "application/zip"
        "txt" -> "text/plain"
        else -> "*/*"
    }

    // ---------------------------------------------------------------- 语音录制 / 播放

    /** 录音（按住说话）：输出 m4a/AAC，16k 单声道，够聊天用 */
    class VoiceRecorder(private val context: Context) {

        private var recorder: MediaRecorder? = null
        private var file: File? = null
        private var startedAt = 0L

        fun start(): Boolean = runCatching {
            val target = File(cacheDir(context), "voice_${System.currentTimeMillis()}.m4a")
            @Suppress("DEPRECATION")
            val r = MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(16000)
            r.setAudioChannels(1)
            r.setAudioEncodingBitRate(32000)
            r.setOutputFile(target.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            file = target
            startedAt = System.currentTimeMillis()
            true
        }.getOrDefault(false)

        /** 停止并返回 (文件, 秒数)；不足 1 秒按 1 秒算，太短（<1s）视为取消 */
        fun stop(): Pair<File, Int>? {
            val r = recorder ?: return null
            recorder = null
            val seconds = ((System.currentTimeMillis() - startedAt) / 1000).toInt()
            val ok = runCatching { r.stop() }.isSuccess
            runCatching { r.release() }
            val target = file ?: return null
            file = null
            if (!ok || seconds < 1) {
                target.delete()
                return null
            }
            return target to seconds.coerceAtMost(60)
        }

        fun cancel() {
            val r = recorder ?: return
            recorder = null
            runCatching { r.stop() }
            runCatching { r.release() }
            file?.delete()
            file = null
        }
    }

    /** 语音播放：同一时间只播一条，正在播的 key 是 Compose 状态（气泡上的波纹/停止图标读它） */
    object Player {
        var playingKey by mutableStateOf<String?>(null)
            private set

        private var player: MediaPlayer? = null

        fun toggle(context: Context, key: String, uri: String) {
            if (playingKey == key) {
                stop()
                return
            }
            stop()
            val player = MediaPlayer()
            runCatching {
                if (uri.startsWith("http", true)) {
                    player.setDataSource(uri)
                } else {
                    player.setDataSource(context, Uri.parse(uri))
                }
                player.setOnCompletionListener { stop() }
                player.setOnErrorListener { _, _, _ -> stop(); true }
                player.prepare()
                player.start()
                this.player = player
                playingKey = key
            }.onFailure {
                runCatching { player.release() }
                playingKey = null
            }
        }

        fun stop() {
            playingKey = null
            val p = player ?: return
            player = null
            runCatching { if (p.isPlaying) p.stop() }
            runCatching { p.release() }
        }
    }
}
