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

    private lateinit var web: WebView
    private val io = Executors.newCachedThreadPool()
    private var pendingText: String? = null
    private var pendingImage: String? = null
    private var pageReady = false
    private var floatMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        floatMode = isFloatIntent(intent)

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
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
                flushPending()
            }
        }
        web.addJavascriptInterface(Bridge(), "PeiduBridge")
        web.loadUrl("https://appassets.androidplatform.net/assets/index.html")

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (isFloatIntent(intent) && !floatMode) {
            floatMode = true
            applyWindowMode()
            js("window.__setFloat && window.__setFloat(true)")
        }
        handleIntent(intent)
    }

    private fun isFloatIntent(i: Intent?): Boolean {
        val a = i?.action ?: return false
        return a == Intent.ACTION_PROCESS_TEXT || a == Intent.ACTION_SEND
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
