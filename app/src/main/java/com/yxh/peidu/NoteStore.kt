package com.yxh.peidu

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Base64
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 存笔记：往用户在 SAF 里选的目录追加当天 $date.md，截图写进 attachments/。
 * 格式与电脑版一致（## HH:MM 陪读 · 服务 · 档位 + ![[图]] + **问：** + 回答）。
 */
object NoteStore {

    private const val TAG = "peidu"
    private const val PREFS = "peidu"
    private const val KEY_URI = "note_tree_uri"
    private const val KEY_NAME = "note_tree_name"
    private val io = Executors.newSingleThreadExecutor()

    fun hasDir(ctx: Context): Boolean = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(KEY_URI, null) != null

    fun saveDir(ctx: Context, uri: Uri) {
        ctx.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_URI, uri.toString())
            .putString(KEY_NAME, prettyName(uri))
            .apply()
    }

    /** 把 content:// 的 tree uri 变成人能看懂的完整路径（主存储/Download/…/陪读） */
    fun prettyName(uri: Uri): String {
        val raw = try {
            DocumentsContract.getTreeDocumentId(uri)
        } catch (e: Throwable) {
            uri.lastPathSegment ?: ""
        }
        return raw.replace("%3A", ":").replace("%2F", "/")
            .split("/", ":")
            .filter { it.isNotBlank() }
            .map { if (it == "primary") "主存储" else it }
            .joinToString("/")
    }

    /** 每次都从保存的 URI 现算，老的只有短名的配置也能显示成全路径 */
    fun dirName(ctx: Context): String {
        val uriStr = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_URI, null) ?: return ""
        return try { prettyName(Uri.parse(uriStr)) } catch (e: Throwable) { "" }
    }

    /**
     * payload: {text, question, answer, service, prompt, image(dataURL 或空)}
     * 返回 "OK:说明" 或 "ERR:原因"
     */
    fun save(ctx: Context, payload: String): String {
        return try {
            val p = JSONObject(payload)
            val uriStr = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_URI, null)
                ?: return "ERR:还没选笔记目录"
            val tree = DocumentFile.fromTreeUri(ctx, Uri.parse(uriStr))
                ?: return "ERR:目录打不开了，重新选一次"
            if (!tree.canWrite()) return "ERR:这个目录没有写权限，重新选一次"

            val now = Date()
            val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now)
            val hhmm = SimpleDateFormat("HH:mm", Locale.US).format(now)

            val sb = StringBuilder()
            sb.append("\n## ").append(hhmm).append(" 陪读 · ")
                .append(p.optString("service", "deepseek")).append(" · ")
                .append(p.optString("prompt", "陪读")).append("\n\n")

            val quote = p.optString("text", "").trim()
            if (quote.isNotEmpty()) {
                quote.split("\n").forEach { sb.append("> ").append(it).append("\n") }
                sb.append("\n")
            }

            // 图：写进 attachments/，正文用 Obsidian 内嵌语法
            val dataUrl = p.optString("image", "")
            if (dataUrl.startsWith("data:image")) {
                val name = try {
                    writeImage(ctx, tree, dataUrl, date, hhmm)
                } catch (e: Throwable) {
                    Log.w(TAG, "存图失败: ${e.message}")
                    null
                }
                if (name != null) {
                    sb.append("![[").append(name).append("]]\n\n")
                } else {
                    sb.append("> （截图没能存下来）\n\n")
                }
            }

            val q = p.optString("question", "").trim()
            sb.append("**问：** ").append(if (q.isEmpty()) "这段讲的什么？" else q).append("\n\n")
            sb.append(p.optString("answer", "").trim()).append("\n")

            val mdName = "$date.md"
            val doc = findOrCreateMd(ctx, tree, mdName) ?: return "ERR:建不了 $mdName"
            val old = try {
                ctx.contentResolver.openInputStream(doc.uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            } catch (e: Throwable) {
                ""
            }
            ctx.contentResolver.openOutputStream(doc.uri, "wt")?.use { out ->
                out.write((old + sb.toString()).toByteArray(Charsets.UTF_8))
            } ?: return "ERR:写不进去"

            "OK:$mdName"
        } catch (e: Throwable) {
            Log.w(TAG, "存笔记失败", e)
            "ERR:" + (e.message ?: e.javaClass.simpleName)
        }
    }

    private fun findOrCreateMd(ctx: Context, tree: DocumentFile, name: String): DocumentFile? {
        tree.findFile(name)?.let { if (it.isFile) return it }
        tree.findFile("$name.txt")?.let { if (it.isFile) return it }
        return try {
            val uri = DocumentsContract.createDocument(
                ctx.contentResolver, tree.uri, "text/markdown", name
            ) ?: return null
            val doc = DocumentFile.fromSingleUri(ctx, uri)
            if (doc != null && doc.name != name) {
                // 某些 provider 会按 MIME 自动加 .txt，强制改回 .md
                try {
                    DocumentsContract.renameDocument(ctx.contentResolver, uri, name)
                } catch (e: Throwable) {
                    Log.w(TAG, "改名失败: ${e.message}")
                }
                tree.findFile(name) ?: doc
            } else {
                doc
            }
        } catch (e: Throwable) {
            Log.w(TAG, "创建 $name 失败: ${e.message}")
            null
        }
    }

    private fun writeImage(
        ctx: Context,
        tree: DocumentFile,
        dataUrl: String,
        date: String,
        hhmm: String
    ): String? {
        val comma = dataUrl.indexOf(',')
        if (comma < 0) return null
        val bytes = Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT)
        val dir = tree.findFile("attachments")?.takeIf { it.isDirectory }
            ?: tree.createDirectory("attachments")
            ?: return null
        val base = "${date.replace("-", "")}_${hhmm.replace(":", "")}.jpg"
        dir.findFile(base)?.delete()
        val f = dir.createFile("image/jpeg", base)
        ctx.contentResolver.openOutputStream(f!!.uri)?.use { it.write(bytes) }
        return f.name ?: base
    }
}
