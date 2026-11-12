package com.yxh.peidu

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import androidx.webkit.WebSettingsCompat
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    companion object {
        /** 点悬浮球截完屏后拉起本页，进入框选 */
        const val ACTION_CAPTURE = "com.yxh.peidu.CAPTURE"
        /** 从悬浮球菜单「切档位」进来，打开档位选择浮层 */
        const val ACTION_PROMPT = "com.yxh.peidu.PROMPT"
        /** 从悬浮球菜单「主页」进来，强制全屏形态 */
        const val ACTION_HOME = "com.yxh.peidu.HOME"
        /** 从悬浮球菜单「退出」进来，关掉界面 */
        const val ACTION_EXIT = "com.yxh.peidu.EXIT"
        private const val REQ_PICK_DIR = 1001
    }

    private lateinit var web: WebView
    private val io = Executors.newCachedThreadPool()
    private var pendingText: String? = null
    private var pendingImage: String? = null
    private var pageReady = false
    private var floatMode = false
    private var pendingCapture = false
    private var pendingPromptPicker = false
    private var pendingHome = false
    private var pendingPaste = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        floatMode = isFloatIntent(intent)

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .addPathHandler("/shot/", ShotPathHandler())
            .build()

        web = WebView(this)
        web.setBackgroundColor(Color.TRANSPARENT)
        web.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        setContentView(web)
        applyWindowMode()
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = false
        }
        try {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(web.settings, true)
        } catch (e: Throwable) {
            // 老 WebView 不支持就算了，页面自己还有手动主题
        }
        web.webViewClient = object : WebViewClientCompat() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? = loader.shouldInterceptRequest(request.url)

            override fun onPageFinished(view: WebView, url: String) {
                pageReady = true
                pushSystemDark()
                js("window.__setFloat && window.__setFloat($floatMode)")
                if (pendingCapture) {
                    pendingCapture = false
                    js("window.__onCapture && window.__onCapture()")
                }
                flushPending()
            }
        }
        web.addJavascriptInterface(Bridge(), "PeiduBridge")
        web.loadUrl("https://appassets.androidplatform.net/assets/index.html")

        // 默认就带悬浮球；想关去 ⚙ 里点「关闭」（关掉后记住，下次不再自动起）
        if (getSharedPreferences("peidu", MODE_PRIVATE).getString("ball_on", "1") == "1" &&
            android.provider.Settings.canDrawOverlays(this)
        ) {
            FloatingBallService.start(this)
        }

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (isFloatIntent(intent) && !floatMode) {
            floatMode = true
            applyWindowMode()
            js("window.__setFloat && window.__setFloat(true)")
        } else if (!isFloatIntent(intent) && floatMode) {
            // 从悬浮球菜单「主页」进来：退回全屏形态
            floatMode = false
            applyWindowMode()
            js("window.__setFloat && window.__setFloat(false)")
        }
        handleIntent(intent)
    }

    private fun isFloatIntent(i: Intent?): Boolean {
        val a = i?.action ?: return false
        return a == Intent.ACTION_PROCESS_TEXT || a == Intent.ACTION_SEND || a == ACTION_CAPTURE || a == ACTION_PROMPT || a == ACTION_HOME
    }

    /** 把内存里那张截屏以 JPEG 形式喂给 WebView（不落地、不经过 JS 字符串） */
    private class ShotPathHandler : WebViewAssetLoader.PathHandler {
        override fun handle(path: String): WebResourceResponse? {
            val bmp = ShotHolder.peek() ?: return null
            return try {
                val bos = java.io.ByteArrayOutputStream()
                bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, bos)
                WebResourceResponse(
                    "image/jpeg", null,
                    java.io.ByteArrayInputStream(bos.toByteArray())
                )
            } catch (e: Throwable) {
                null
            }
        }
    }

    /** 选中文字/分享唤出时：窗口铺满但透明，卡片由网页画在底部 70%，上面露出的原 App 可见可点 */
    private fun applyWindowMode() {
        val lp = window.attributes
        lp.width = ViewGroup.LayoutParams.MATCH_PARENT
        lp.height = ViewGroup.LayoutParams.MATCH_PARENT
        lp.gravity = Gravity.TOP or Gravity.START
        lp.dimAmount = 0f
        window.attributes = lp
        window.setGravity(lp.gravity)
        try {
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        } catch (e: Throwable) {
        }
    }

    /** 点窗口外面（下面的读书 App）→ 退回后台，不销毁，对话保留 */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (floatMode) {
            moveTaskToBack(true)
            return
        }
        super.onBackPressed()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        pushSystemDark()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK_DIR && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            try {
                NoteStore.saveDir(this, uri)
                js("window.__onNoteDir && window.__onNoteDir(${JSONObject.quote(NoteStore.dirName(this))})")
                Notify.toast(this, "笔记目录已设置")
            } catch (e: Throwable) {
                Notify.toast(this, "设置目录失败：" + (e.message ?: ""))
            }
        }
    }

    private fun pushSystemDark() {
        val mode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val dark = mode == Configuration.UI_MODE_NIGHT_YES
        js("window.__setSystemDark && window.__setSystemDark($dark)")
    }

    override fun onDestroy() {
        super.onDestroy()
        LlmClient.cancel()
        io.shutdownNow()
    }

    private fun handleIntent(i: Intent?) {
        if (i == null) return
        when (i.action) {
            ACTION_CAPTURE -> {
                if (pageReady) {
                    js("window.__onCapture && window.__onCapture()")
                } else {
                    pendingCapture = true
                }
            }
            ACTION_PROMPT -> {
                if (pageReady) {
                    js("window.__openPromptPicker && window.__openPromptPicker()")
                } else {
                    pendingPromptPicker = true
                }
            }
            ACTION_HOME -> {
                // 菜单「主页」：半屏浮窗打开。
                // 剪贴板不能在这里读 —— Android 10 起只有真正位于前台的 App 才允许读，
                // onNewIntent 这一刻 App 往往还没拿到焦点，读到的是 null（模拟器不明显，真机必现）。
                // 先记标记，等 onWindowFocusChanged 有了焦点再读。
                floatMode = true
                applyWindowMode()
                pendingPaste = true
                if (pageReady) {
                    js("window.__setFloat && window.__setFloat(true)")
                } else {
                    pendingHome = true
                }
                // 如果本来就在前台（用户就在陪读界面点球），不会再触发 onWindowFocusChanged，这里直接读
                if (hasWindowFocus() && ::web.isInitialized) {
                    pendingPaste = false
                    web.postDelayed({ pasteClipboard(0) }, 300)
                }
            }
            ACTION_EXIT -> {
                // 菜单「退出」：把界面从最近任务里也清掉
                finishAndRemoveTask()
            }
            Intent.ACTION_PROCESS_TEXT -> {
                val t = i.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
                if (!t.isNullOrBlank()) {
                    pendingText = t
                    flushPending()
                }
            }
            Intent.ACTION_SEND -> {
                val t = i.getStringExtra(Intent.EXTRA_TEXT)
                if (!t.isNullOrBlank()) pendingText = t
                @Suppress("DEPRECATION")
                val u: Uri? = i.getParcelableExtra(Intent.EXTRA_STREAM)
                if (u != null) {
                    io.execute {
                        val data = uriToDataUrl(u)
                        if (data != null) {
                            pendingImage = data
                            flushPending()
                        }
                    }
                } else {
                    flushPending()
                }
            }
        }
    }

    private fun uriToDataUrl(uri: Uri): String? {
        return try {
            val mime = contentResolver.getType(uri) ?: "image/jpeg"
            val stream = contentResolver.openInputStream(uri)
            val bytes = stream?.use { it.readBytes() } ?: return null
            "data:$mime;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            null
        }
    }

    private fun flushPending() {
        if (!pageReady) return
        val t = pendingText
        if (t != null) {
            pendingText = null
            js("window.__onIntent && window.__onIntent(${JSONObject.quote(t)})")
        }
        val img = pendingImage
        if (img != null) {
            pendingImage = null
            js("window.__onImage && window.__onImage(${JSONObject.quote(img)})")
        }
        if (pendingPromptPicker) {
            pendingPromptPicker = false
            js("window.__openPromptPicker && window.__openPromptPicker()")
        }
        if (pendingHome) {
            pendingHome = false
            js("window.__setFloat && window.__setFloat(true)")
        }
    }

    /** 窗口拿到焦点后再读剪贴板（Android 10+ 只有前台 App 能读） */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && pendingPaste) {
            pendingPaste = false
            if (::web.isInitialized) {
                // 系统把 App 认成前台可能滞后一点，先等一下再读
                web.postDelayed({ pasteClipboard(0) }, 350)
            }
        }
    }

    private fun pasteClipboard(attempt: Int) {
        if (!::web.isInitialized) return
        if (!pageReady) {
            if (attempt < 4) web.postDelayed({ pasteClipboard(attempt + 1) }, 300)
            return
        }
        val clip = readClipboard()
        android.util.Log.i("peidu", "剪贴板读取: ${clip?.length ?: 0} 字")
        if (!clip.isNullOrBlank()) {
            js("window.__onClipboard && window.__onClipboard(${JSONObject.quote(clip)})")
            return
        }
        // 还是空：可能系统还没把 App 算作前台，再试两次
        if (attempt < 2) web.postDelayed({ pasteClipboard(attempt + 1) }, 400)
    }

    /** 读剪贴板文字：只在用户主动点「主页」时调用，不在后台偷读 */
    private fun readClipboard(): String? {
        return try {
            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val c = cm.primaryClip ?: return null
            if (c.itemCount == 0) return null
            val item = c.getItemAt(0)
            // 先直接取 text（最快最稳），拿不到再退回 coerceToText（URI 之类）
            val t = item.text?.toString() ?: item.coerceToText(this)?.toString()
            t?.trim()?.takeIf { it.isNotEmpty() }
        } catch (e: Throwable) {
            android.util.Log.w("peidu", "读剪贴板失败: ${e.message}")
            null
        }
    }

    fun js(code: String) {
        runOnUiThread {
            if (::web.isInitialized) web.evaluateJavascript(code, null)
        }
    }

    inner class Bridge {

        @JavascriptInterface
        fun chat(payload: String) {
            io.execute { LlmClient.run(this@MainActivity, payload) }
        }

        @JavascriptInterface
        fun stop() {
            LlmClient.cancel()
        }

        @JavascriptInterface
        fun hide() {
            runOnUiThread { moveTaskToBack(true) }
        }

        /** 开/关悬浮球，返回状态：ok / need_overlay / need_a11y */
        @JavascriptInterface
        fun startBall(): String {
            if (android.os.Build.VERSION.SDK_INT >= 23 &&
                !android.provider.Settings.canDrawOverlays(this@MainActivity)
            ) {
                runOnUiThread {
                    try {
                        startActivity(
                            Intent(
                                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                android.net.Uri.parse("package:$packageName")
                            )
                        )
                    } catch (e: Throwable) {}
                }
                return "need_overlay"
            }
            FloatingBallService.start(this@MainActivity)
            getSharedPreferences("peidu", MODE_PRIVATE).edit().putString("ball_on", "1").apply()
            return if (PeiduScreenService.isAlive) "ok" else "need_a11y"
        }

        @JavascriptInterface
        fun stopBall() {
            FloatingBallService.stop(this@MainActivity)
            getSharedPreferences("peidu", MODE_PRIVATE).edit().putString("ball_on", "0").apply()
        }

        /** 权限状态，给设置面板显示用 */
        @JavascriptInterface
        fun ballState(): String {
            val overlay = android.os.Build.VERSION.SDK_INT < 23 ||
                android.provider.Settings.canDrawOverlays(this@MainActivity)
            val want = getSharedPreferences("peidu", MODE_PRIVATE).getString("ball_on", "1") == "1"
            return "{\"overlay\":$overlay,\"a11y\":${PeiduScreenService.isAlive},\"ball\":${FloatingBallService.running},\"want\":$want}"
        }

        @JavascriptInterface
        fun openA11ySettings() {
            runOnUiThread {
                try {
                    startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                } catch (e: Throwable) {}
            }
        }

        /** 框选完成（或取消）后释放那张全屏截图，并把悬浮球放回来 */
        @JavascriptInterface
        fun clearShot() {
            ShotHolder.take()?.recycle()
            FloatingBallService.setBallVisible(true)
        }

        // ---------- 存笔记 ----------

        @JavascriptInterface
        fun noteDirName(): String = NoteStore.dirName(this@MainActivity)

        @JavascriptInterface
        fun pickNoteDir() {
            runOnUiThread {
                try {
                    val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                        addFlags(
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
                        )
                    }
                    startActivityForResult(i, REQ_PICK_DIR)
                } catch (e: Throwable) {
                    Notify.toast(this@MainActivity, "打不开目录选择器：" + (e.message ?: ""))
                }
            }
        }

        @JavascriptInterface
        fun saveNote(payload: String): String = NoteStore.save(this@MainActivity, payload)

        // ---------- 维基 ----------

        /** 查局域网 Kiwix，返回拼好的资料正文（查不到返回空串） */
        @JavascriptInterface
        fun wikiLookup(base: String, query: String): String = WikiClient.lookup(base, query)

        /** 异步版：立刻返回，结果通过 window.__onWiki(cbId, text) 回调，别让 JS 线程等网络 */
        @JavascriptInterface
        fun wikiLookupAsync(sourcesJson: String, query: String, cbId: String) {
            Thread {
                val r = try { WikiClient.lookup(sourcesJson, query) } catch (e: Throwable) { "" }
                runOnUiThread {
                    try {
                        js(
                            "window.__onWiki && window.__onWiki(" +
                                JSONObject.quote(cbId) + "," + JSONObject.quote(r) + ")"
                        )
                    } catch (e: Throwable) { }
                }
            }.start()
        }

        /** 直接截一张（设置面板里的「截屏试试」，也用来在悬浮球不好使时兜底） */
        @JavascriptInterface
        fun captureNow(): String {
            if (!PeiduScreenService.isAlive) return "no_a11y"
            PeiduScreenService.capture { bmp, err ->
                if (bmp == null) {
                    Notify.toast(this@MainActivity, "截屏失败：$err")
                    return@capture
                }
                ShotHolder.put(bmp)
                js("window.__onCapture && window.__onCapture()")
            }
            return "ok"
        }

        @JavascriptInterface
        fun load(key: String): String =
            getSharedPreferences("peidu", Context.MODE_PRIVATE).getString(key, "") ?: ""

        @JavascriptInterface
        fun save(key: String, value: String) {
            getSharedPreferences("peidu", Context.MODE_PRIVATE)
                .edit().putString(key, value).apply()
        }

        @JavascriptInterface
        fun toast(msg: String) {
            runOnUiThread {
                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
            }
        }

        @JavascriptInterface
        fun copy(text: String) {
            runOnUiThread {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("peidu", text))
                Toast.makeText(this@MainActivity, "已复制", Toast.LENGTH_SHORT).show()
            }
        }

        @JavascriptInterface
        fun saveNote(fileName: String, content: String): String = try {
            val dir = File(getExternalFilesDir(null), "笔记")
            dir.mkdirs()
            val f = File(dir, fileName)
            f.writeText(content, Charsets.UTF_8)
            f.absolutePath
        } catch (e: Exception) {
            "ERROR: " + e.message
        }
    }
}
