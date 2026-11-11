package com.yxh.peidu

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast

/** 小工具：Toast（任意线程）与「无障碍掉了」的恢复通知 */
object Notify {

    private const val CH_LOST = "peidu_lost"

    fun toast(ctx: Context, msg: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
        }
    }

    /** 无障碍服务被系统关掉时：给一条能一键恢复的通知 */
    fun accessibilityLost(ctx: Context) {
        toast(ctx, "截屏服务被系统关了，去通知栏点一下恢复")
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(
                    NotificationChannel(CH_LOST, "权限提醒", NotificationManager.IMPORTANCE_DEFAULT)
                )
            }
            val pi = PendingIntent.getActivity(
                ctx, 11,
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val n = if (Build.VERSION.SDK_INT >= 26)
                android.app.Notification.Builder(ctx, CH_LOST)
            else
                @Suppress("DEPRECATION") android.app.Notification.Builder(ctx)
            n.setContentTitle("陪读截屏服务被系统关闭")
                .setContentText("点这里打开无障碍设置，把「陪读」重新打开")
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentIntent(pi)
                .setAutoCancel(true)
            nm.notify(1002, n.build())
        } catch (e: Throwable) {
            // 没通知权限就只剩 Toast
        }
    }
}
