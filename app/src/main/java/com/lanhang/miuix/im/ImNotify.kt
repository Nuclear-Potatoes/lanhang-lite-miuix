package com.lanhang.miuix.im

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.lanhang.miuix.R
import com.lanhang.miuix.ui.MainActivity

/**
 * 私信的**普通通知**（系统通知），2026-10-26 第二轮按用户要求从 MiPush 改过来。
 *
 * 口径（别写成「已开启推送」）：
 * - 这是本地通知，不是推送通道。**App 进程活着（前台或后台）时**，新私信会弹一条系统通知；
 *   进程被系统杀掉/被用户清理之后什么都收不到——那需要厂商推送或常驻服务，本项目没有。
 * - 通知点开直接进对应的会话页（[MainActivity] 读 `EXTRA_IM_PEER`）。
 * - Android 13+ 要运行时授权 `POST_NOTIFICATIONS`，没授权就静默不弹（不反复骚扰）。
 */
object ImNotify {

    const val CHANNEL_ID = "im_message"

    const val EXTRA_IM_PEER = "com.lanhang.miuix.extra.IM_PEER"
    const val EXTRA_IM_TITLE = "com.lanhang.miuix.extra.IM_TITLE"

    /** App 是否在前台（MainActivity 的 onResume/onPause 维护） */
    @Volatile
    var appForeground: Boolean = false

    /** 当前正在看的会话；在这个会话里收到消息就不弹通知（不然边聊边响） */
    @Volatile
    var activePeerId: String? = null

    /** App 启动时调一次即可；重复调用无副作用 */
    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.im_notify_channel),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.im_notify_channel_desc)
            enableVibration(true)
        }
        nm.createNotificationChannel(channel)
    }

    /** Android 13+ 是否已授权通知（低版本恒为 true） */
    fun hasPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 弹一条「谁发来了什么」。同一会话共用一个通知 id，后来的消息覆盖前一条
     * （和微信一致：一个人只留一个通知位）。
     *
     * 调用方负责决定要不要弹（前台正在看这个会话时不要弹）。
     */
    fun showMessage(
        context: Context,
        peerId: String,
        title: String,
        body: String,
    ) {
        if (peerId.isBlank()) return
        if (!hasPermission(context)) return
        ensureChannel(context)

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_IM_PEER, peerId)
            putExtra(EXTRA_IM_TITLE, title)
        }
        val requestCode = peerId.hashCode()
        val pending = PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_im_notify)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .setContentIntent(pending)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(requestCode, notification)
        }
    }

    /** 进入某个会话时清掉它的通知（会话里的消息已经被看过了） */
    fun cancel(context: Context, peerId: String) {
        if (peerId.isBlank()) return
        runCatching { NotificationManagerCompat.from(context).cancel(peerId.hashCode()) }
    }
}
