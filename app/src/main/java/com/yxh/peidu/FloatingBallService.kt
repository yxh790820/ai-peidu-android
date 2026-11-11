package com.yxh.peidu

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.hypot

/**
 * 悬浮球：一个可拖动的小圆点，点一下 → 截屏 → 拉起陪读小窗进入框选。
 * 需要「显示在其他应用上层」权限。
 */
class FloatingBallService : Service() {

    companion object {
        const val TAG = "peidu"
        const val CH_ID = "peidu_ball"

        @Volatile
        var running = false
            private set

        @Volatile
        private var inst: FloatingBallService? = null

        /** 框选/提问时把球藏起来：直接挪出屏幕（改 alpha / GONE 对部分 overlay 不生效） */
        fun setBallVisible(v: Boolean) {
            val s = inst
            if (s == null) {
                Log.w(TAG, "setBallVisible($v): 服务实例为空")
                return
            }
            s.ball?.post {
                try {
                    if (v) {
                        s.lp.x = s.savedX
                        s.lp.y = s.savedY
                    } else {
                        s.savedX = s.lp.x
                        s.savedY = s.lp.y
                        s.lp.x = -(s.lp.width + 40)
                    }
                    s.wm.updateViewLayout(s.ball, s.lp)
                    Log.i(TAG, "setBallVisible($v) -> x=${s.lp.x}")
                } catch (e: Throwable) {
                    Log.w(TAG, "切球可见性失败: ${e.message}")
                }
            }
        }

        fun start(ctx: android.content.Context) {
            val i = Intent(ctx, FloatingBallService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: android.content.Context) {
            ctx.stopService(Intent(ctx, FloatingBallService::class.java))
        }
    }

    private lateinit var wm: WindowManager
    private var ball: View? = null
    private lateinit var lp: WindowManager.LayoutParams
    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0
    private var moved = false
    private var savedX = 0
    private var savedY = 0

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        running = true
        inst = this
        startForegroundNt()
        addBall()
        // 屏幕旋转 / 分辨率变化时把球拉回可视范围，否则会跑到屏幕外点不到
        registerComponentCallbacks(object : android.content.ComponentCallbacks {
            override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
                clampBall()
            }
            override fun onLowMemory() {}
        })
    }

    private fun clampBall() {
        val v = ball ?: return
        val dm = resources.displayMetrics
        lp.x = lp.x.coerceIn(0, (dm.widthPixels - lp.width).coerceAtLeast(0))
        lp.y = lp.y.coerceIn(0, (dm.heightPixels - lp.height).coerceAtLeast(0))
        try { wm.updateViewLayout(v, lp) } catch (e: Throwable) {}
    }

    private fun startForegroundNt() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CH_ID, "悬浮球", NotificationManager.IMPORTANCE_MIN).apply {
                    setShowBadge(false)
                }
            )
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n: Notification = if (Build.VERSION.SDK_INT >= 26)
            Notification.Builder(this, CH_ID)
                .setContentTitle("陪读悬浮球")
                .setContentText("点屏幕上的小球即可截屏提问")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentIntent(pi)
                .setOngoing(true)
                .build()
        else
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("陪读悬浮球")
                .setContentText("点屏幕上的小球即可截屏提问")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentIntent(pi)
                .setOngoing(true)
                .build()
        startForeground(1001, n)
    }

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()

    private fun addBall() {
        val size = dp(46f)
        val v = TextView(this).apply {
            text = "读"
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xE62F6FEB.toInt())
                setStroke(dp(1.5f), 0x55FFFFFF)
            }
            alpha = 0.92f
        }

        lp = WindowManager.LayoutParams(
            size, size,
            if (Build.VERSION.SDK_INT >= 26)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = resources.displayMetrics.widthPixels - size - dp(8f)
            y = resources.displayMetrics.heightPixels / 2
        }

        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = lp.x; startY = lp.y
                    moved = false
                    v.alpha = 1f
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (abs(dx) > dp(6f) || abs(dy) > dp(6f)) moved = true
                    if (moved) {
                        val dm = resources.displayMetrics
                        lp.x = (startX + dx).toInt().coerceIn(0, dm.widthPixels - size)
                        lp.y = (startY + dy).toInt().coerceIn(0, dm.heightPixels - size)
                        try { wm.updateViewLayout(v, lp) } catch (ex: Throwable) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        v.alpha = 0.92f
                        onBallClick()
                    } else {
                        snapToEdge()
                    }
                    true
                }
                else -> false
            }
        }

        ball = v
        try {
            wm.addView(v, lp)
            Log.i(TAG, "悬浮球已添加")
        } catch (e: Throwable) {
            Log.e(TAG, "悬浮球添加失败: ${e.message}")
            stopSelf()
        }
    }

    /** 松手后贴到最近的一边，顺便半透明一点，别太碍事 */
    private fun snapToEdge() {
        val v = ball ?: return
        val dm = resources.displayMetrics
        val size = lp.width
        lp.x = if (lp.x + size / 2 < dm.widthPixels / 2) dp(2f) else dm.widthPixels - size - dp(2f)
        try { wm.updateViewLayout(v, lp) } catch (e: Throwable) {}
        v.alpha = 0.72f
    }

    private fun onBallClick() {
        Log.i(TAG, "点了悬浮球")
        if (!PeiduScreenService.isAlive) {
            // 无障碍掉了：别静默失败，弹通知引导恢复
            Notify.accessibilityLost(this)
            Log.w(TAG, "点球但无障碍服务没在运行")
            return
        }
        PeiduScreenService.capture { bmp, err ->
            if (bmp == null) {
                Log.w(TAG, "截屏失败: $err")
                Notify.toast(this, "截屏失败：$err")
                return@capture
            }
            ShotHolder.put(bmp)
            setBallVisible(false)
            val i = Intent(this, MainActivity::class.java).apply {
                action = MainActivity.ACTION_CAPTURE
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(i)
        }
    }

    override fun onDestroy() {
        running = false
        // 只有自己还是当前实例时才清空，避免旧实例把新实例的引用打掉（重启竞态）
        if (inst === this) inst = null
        ball?.let { try { wm.removeView(it) } catch (e: Throwable) {} }
        ball = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

/** 截屏结果在进程内传递（避免 Intent 1MB 限制） */
object ShotHolder {
    @Volatile
    private var bmp: android.graphics.Bitmap? = null

    fun put(b: android.graphics.Bitmap) {
        bmp?.recycle()
        bmp = b
    }

    fun take(): android.graphics.Bitmap? {
        val b = bmp
        bmp = null
        return b
    }

    fun peek(): android.graphics.Bitmap? = bmp
}
