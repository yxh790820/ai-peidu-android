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
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
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

    private var menuView: View? = null

    private fun onBallClick() {
        Log.i(TAG, "点了悬浮球：弹菜单")
        showBallMenu()
    }

    /**
     * 球旁弹菜单：截图 / 主页 / 切档位 / 退出。
     * 用自绘的全屏 overlay 而不是 PopupWindow —— overlay 窗口上 PopupWindow 的
     * showAtLocation 坐标会被系统忽略（永远跑到屏幕角上），自己加一层才贴得住球。
     */
    private fun showBallMenu() {
        dismissMenu()
        val dm = resources.displayMetrics
        val sz = dp(46f)
        val gap = dp(10f)
        val pad = dp(10f)
        val items = 4
        val mw = sz + pad * 2
        val mh = sz * items + gap * (items - 1) + pad * 2

        val root = FrameLayout(this).apply {
            // 全屏透明，点空白处收起菜单
            isClickable = true
            setOnClickListener { dismissMenu() }
        }

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(22f).toFloat()
                setColor(0xF21C2130.toInt())
                setStroke(dp(1f), 0x33FFFFFF)
            }
            // 面板自身吞掉点击，避免点在面板空白处就收起
            isClickable = true
        }

        fun addItem(emoji: String, color: Int, onClick: () -> Unit) {
            val t = TextView(this).apply {
                text = emoji
                setTextColor(Color.WHITE)
                textSize = 21f
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                }
                setOnClickListener {
                    dismissMenu()
                    onClick()
                }
            }
            val ip = LinearLayout.LayoutParams(sz, sz)
            if (col.childCount > 0) ip.topMargin = gap
            col.addView(t, ip)
        }

        addItem("📷", 0xE62F6FEB.toInt()) { doCapture() }
        addItem("🏠", 0xE62A9E63.toInt()) { openMain() }
        addItem("🔄", 0xE68A5A2B.toInt()) { openPromptPicker() }
        addItem("✕", 0xE6C0392B.toInt()) { exitPeidu() }

        // 贴着球：球在右半边 → 菜单在左；反之在右。垂直方向对齐球心，越界夹回屏幕内
        val onRight = lp.x + lp.width / 2 > dm.widthPixels / 2
        val mx = if (onRight) lp.x - mw - dp(8f) else lp.x + lp.width + dp(8f)
        val my = (lp.y + lp.height / 2 - mh / 2)
            .coerceIn(dp(8f), (dm.heightPixels - mh - dp(8f)).coerceAtLeast(dp(8f)))

        root.addView(col, FrameLayout.LayoutParams(mw, mh).apply {
            leftMargin = mx.coerceIn(0, (dm.widthPixels - mw).coerceAtLeast(0))
            topMargin = my
        })

        val wlp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= 26)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        try {
            wm.addView(root, wlp)
            menuView = root
        } catch (e: Throwable) {
            Log.w(TAG, "菜单弹出失败: ${e.message}")
            menuView = null
        }
    }

    private fun dismissMenu() {
        menuView?.let { try { wm.removeView(it) } catch (e: Throwable) {} }
        menuView = null
    }

    /** 退出陪读：收掉球和通知，再把界面关掉（ball_on 保持 1，下次打开 App 球还会回来） */
    private fun exitPeidu() {
        Log.i(TAG, "菜单：退出陪读")
        val i = Intent(this, MainActivity::class.java).apply {
            action = MainActivity.ACTION_EXIT
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
        }
        try { startActivity(i) } catch (e: Throwable) {}
        stopSelf()
    }

    private fun doCapture() {
        dismissMenu()
        // 菜单是独立窗口，dismiss 之后要等它真正从屏幕上撤掉，否则会被截进图里
        ball?.postDelayed({ realCapture() }, 150)
    }

    private fun realCapture() {
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

    private fun openMain() {
        Log.i(TAG, "菜单：打开主界面")
        val i = Intent(this, MainActivity::class.java).apply {
            action = MainActivity.ACTION_HOME
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        try { startActivity(i) } catch (e: Throwable) { Notify.toast(this, "打开失败：${e.message}") }
    }

    private fun openPromptPicker() {
        val i = Intent(this, MainActivity::class.java).apply {
            action = MainActivity.ACTION_PROMPT
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        try { startActivity(i) } catch (e: Throwable) { Notify.toast(this, "打开失败：${e.message}") }
    }

    override fun onDestroy() {
        running = false
        dismissMenu()
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
