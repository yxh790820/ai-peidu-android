package com.yxh.peidu

import android.util.Log
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * 维基检索：查局域网里的 Kiwix（默认 192.168.2.99:8060）。
 *
 * 跟电脑版同一套逻辑，两个关键点：
 *  - Kiwix 全文检索不给标题加权，搜「数学」条目本身排到几十名开外，
 *    所以先走 /suggest 的标题索引，把同名/前缀条目提到前面；
 *  - 全文结果和正文都是 HTML（不是 JSON），只能正则 + 去标签。
 */
object WikiClient {

    private const val TAG = "peidu"
    private const val DEFAULT_BASE = "http://192.168.2.99:8060"
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(14, TimeUnit.SECONDS)
        /* 很多手机网络/模拟器 IPv6 是假的，连 2001:… 会一直超时；强制优先 IPv4 */
        .dns(object : Dns {
            override fun lookup(hostname: String): List<java.net.InetAddress> {
                val all = Dns.SYSTEM.lookup(hostname)
                val v4 = all.filter { it is Inet4Address }
                return if (v4.isNotEmpty()) v4 else all
            }
        })
        .build()

    /** 多源查询：sourcesJson = [{name,type,base,on}]，按顺序试，第一个有结果的返回 */
    fun lookup(sourcesJson: String, queryIn: String, lang: String = "zho"): String {
        val q = queryIn.trim()
        if (q.isEmpty()) return ""
        val arr = try { JSONArray(sourcesJson) } catch (e: Throwable) { JSONArray() }
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val base = o.optString("base").trim()
            if (base.isEmpty()) continue
            val name = o.optString("name", "维基")
            val type = o.optString("type", "kiwix")
            val r = try {
                if (type == "mediawiki") lookupMw(base, q) else lookupKiwix(base, q, lang)
            } catch (e: Throwable) {
                Log.w(TAG, "wiki 源 " + name + " 失败", e)
                ""
            }
            if (r.isNotBlank()) return "【" + name + "】\n" + r
        }
        return ""
    }

    /** 单个 Kiwix 源 */
    private fun lookupKiwix(baseIn: String, queryIn: String, lang: String = "zho"): String {
        val base = (if (baseIn.isBlank()) DEFAULT_BASE else baseIn).trim().trimEnd('/')
        val q = queryIn.trim()
        return try {
            val books = books(base, lang)
            Log.i(TAG, "wiki books=$books")
            val hits = LinkedHashMap<String, Pair<String, String>>()   // href -> (title, snippet)

            // 1) 标题索引优先
            for (b in books) {
                try {
                    val txt = get("$base/suggest?content=${enc(b)}&term=${enc(q)}") ?: continue
                    val arr = JSONArray(txt)
                    for (i in 0 until minOf(arr.length(), 8)) {
                        val o = arr.optJSONObject(i) ?: continue
                        if (o.optString("kind") != "path") continue
                        val path = o.optString("path")
                        if (path.isEmpty()) continue
                        val name = o.optString("value", path)
                        if (!name.contains(q) && !path.contains(q)) continue
                        hits["/content/$b/$path"] = name to ""
                    }
                } catch (e: Throwable) { /* 这本书没有标题索引，跳过 */ }
            }

            // 2) 全文检索
            try {
                val html = get("$base/search?pattern=${enc(q)}&books.filter.lang=$lang&pageLength=8") ?: ""
                val m = Pattern.compile(
                    "<li>\\s*<a href=\"([^\"]+)\"[^>]*>\\s*(.*?)\\s*</a>\\s*<cite>(.*?)</cite>",
                    Pattern.DOTALL
                ).matcher(html)
                while (m.find()) {
                    val href = m.group(1) ?: continue
                    if (hits.containsKey(href)) continue
                    hits[href] = stripHtml(m.group(2) ?: "") to stripHtml(m.group(3) ?: "")
                }
            } catch (e: Throwable) { }

            Log.i(TAG, "wiki hits=${hits.size} first=${hits.keys.take(2)}")
            if (hits.isEmpty()) return ""

            // 3) 取前两条正文
            val sb = StringBuilder()
            var n = 0
            for ((href, pair) in hits) {
                if (n >= 2) break
                val body = try { article(base, href) } catch (e: Throwable) { "" }
                if (body.isBlank()) continue
                sb.append("《").append(pair.first).append("》\n").append(body).append("\n\n")
                n++
            }
            sb.toString().trim().take(3200)
        } catch (e: Throwable) {
            Log.w(TAG, "wiki lookup 异常", e)
            ""
        }
    }

    /** 官方维基（MediaWiki Action API）：list=search 找条目 + TextExtracts 取纯文本首段 */
    private fun lookupMw(baseIn: String, q: String): String {
        val base = mwHost(baseIn)
        val sUrl = base + "/w/api.php?action=query&format=json&formatversion=2&variant=zh-cn&list=search&srsearch=" + enc(q) + "&srlimit=3"
        Log.i(TAG, "mw 开始 " + sUrl)
        val raw = get(sUrl)
        Log.i(TAG, "mw 搜索返回=" + (raw?.length ?: -1))
        val sj = JSONObject(raw ?: return "")
        val hits = sj.optJSONObject("query")?.optJSONArray("search") ?: return ""
        val titles = ArrayList<String>()
        for (i in 0 until minOf(hits.length(), 3)) {
            val t = hits.optJSONObject(i)?.optString("title") ?: continue
            if (t.isNotBlank()) titles.add(t)
        }
        Log.i(TAG, "mw 搜到 " + titles.size + " 条")
        if (titles.isEmpty()) return ""
        val sb = StringBuilder()
        var n = 0
        for (t in titles) {
            if (n >= 2) break
            val eUrl = base + "/w/api.php?action=query&format=json&formatversion=2&variant=zh-cn&prop=extracts&explaintext=1&exlimit=1&redirects=1&titles=" + enc(t)
            val ej = JSONObject(get(eUrl) ?: continue)
            val pages = ej.optJSONObject("query")?.optJSONArray("pages") ?: continue
            for (i in 0 until pages.length()) {
                val p = pages.optJSONObject(i) ?: continue
                val ex = p.optString("extract")
                if (ex.isBlank()) continue
                sb.append("《").append(p.optString("title", t)).append("》\n")
                sb.append(ex.trim()).append("\n\n")
                n++
                break
            }
        }
        val out = sb.toString().trim()
        Log.i(TAG, "mw 正文长度=" + out.length)
        return out.take(3200)
    }

    /** 把 https://zh.wikipedia.org/wiki/xxx 或 …/w/api.php 归一到 https://zh.wikipedia.org */
    private fun mwHost(b: String): String {
        var s = b.trim()
        if (!s.startsWith("http")) s = "https://" + s
        s = s.substringBefore("/w/api.php").substringBefore("/api.php").substringBefore("/wiki/")
        return s.trimEnd('/')
    }

    /** 列出某语言的所有 ZIM 书（取前 3 本，够用且快） */
    private fun books(base: String, lang: String): List<String> {
        val xml = get("$base/catalog/v2/entries?count=-1") ?: return emptyList()
        val out = ArrayList<String>()
        val m = Pattern.compile("<entry>(.*?)</entry>", Pattern.DOTALL).matcher(xml)
        while (m.find()) {
            val e = m.group(1) ?: continue
            val lm = Pattern.compile("<language>([^<]*)</language>").matcher(e)
            if (!lm.find() || (lm.group(1) ?: "").trim() != lang) continue
            val lk = Pattern.compile("<link type=\"text/html\" href=\"/content/([^\"]+)\"").matcher(e)
            if (lk.find()) {
                val raw = lk.group(1) ?: continue
                out.add(try { java.net.URLDecoder.decode(raw, "UTF-8") } catch (e: Throwable) { raw })
            }
        }
        return out.take(3)
    }

    /** 只抓 <p> 段落：整页 350KB 上跑全文正则会把栈打爆，只取正文段落又快又稳 */
    private fun article(base: String, href: String): String {
        if (href.contains("_categories_partials") || href.contains("/Category:")) return ""
        val html = get(base + href) ?: return ""
        val out = StringBuilder()
        try {
            val m = Pattern.compile("<p(?:\\s[^>]*)?>(.*?)</p>", Pattern.DOTALL).matcher(html)
            while (m.find() && out.length < 2000) {
                val t = stripInline(m.group(1) ?: "")
                if (t.length > 12) out.append(t).append("\n")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "wiki 取段落失败", e)
        }
        val r = out.toString().replace(Regex("\\[\\d+]"), "").trim()
        Log.i(TAG, "wiki article len=" + r.length + " " + href.take(40))
        return r.take(1400)
    }

    /** 小片段去标签（只用于 <p> 内容和搜索摘要，不会碰上巨型正则） */
    private fun stripInline(t: String): String {
        var s = t.replace(Regex("<[^>]{0,400}>"), " ")
        s = s.replace("&nbsp;", " ").replace("&#160;", " ").replace("&amp;", "&")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace("&#39;", "'").replace("&ndash;", "–").replace("&mdash;", "—")
        s = s.replace(Regex("\\[\\d+]"), "")
        s = s.replace(Regex("[ \t\u00a0]+"), " ")
        return s.trim()
    }

    private fun stripHtml(t: String): String {
        var s = t.replace(Regex("(?is)<(script|style|math)[^>]*>.*?</\\1>"), " ")
        s = s.replace(Regex("(?is)<(br|/p|/div|/li|/h\\d|/tr)[^>]*>"), "\n")
        s = s.replace(Regex("(?s)<[^>]+>"), "")
        s = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
            .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
            .replace("&#160;", " ")
        s = s.replace(Regex("\\{\\\\displaystyle[^}]*}"), " ")
        s = s.replace(Regex("[ \\t\\u00a0]+"), " ")
        s = s.replace(Regex("\\n\\s*\\n+"), "\n")
        return s.trim()
    }

    private fun get(url: String): String? = try {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Peidu/1.5 (android)")
            .build()
        client.newCall(req).execute().use { r ->
            Log.i(TAG, "wiki GET ${r.code} ${url.take(100)}")
            if (!r.isSuccessful) null else r.body?.string()
        }
    } catch (e: Throwable) {
        Log.w(TAG, "wiki GET 失败 " + url.take(120), e)
        null
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}
