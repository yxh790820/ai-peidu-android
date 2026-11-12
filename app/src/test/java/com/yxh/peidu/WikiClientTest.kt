package com.yxh.peidu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * 维基检索的回归测试。
 *
 * 简繁表与英文回退走本地桩服务器，任何机器可跑、不依赖 NAS；
 * 连真库的用例要局域网 Kiwix 开着，没开时自动跳过（assumeTrue），不会把构建搞红。
 *
 * 桩服务器为什么必要：中文维基正文里普遍夹着英文词（「霍尔逻辑（Hoare logic）」），
 * 真库几乎任何英文词都能被中文全文检索兜住，**没法用真库稳定触发英文回退**。
 */
class WikiClientTest {

    private val base = "http://192.168.2.99:8060"
    private val sources = """[{"id":"kiwix","name":"本地 Kiwix","type":"kiwix","base":"$base","on":true}]"""

    private fun nasUp(): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("192.168.2.99", 8060), 800); true }
    } catch (e: Throwable) {
        false
    }

    @Test
    fun `简转繁`() {
        assertEquals("三國演義", ZhConv.toTraditional("三国演义"))
        assertEquals("軟件自由", ZhConv.toTraditional("软件自由"))
        assertEquals("易經", ZhConv.toTraditional("易经"))
    }

    @Test
    fun `繁转简`() {
        assertEquals("蜀道难", ZhConv.toSimplified("蜀道難"))
        assertEquals("红楼梦", ZhConv.toSimplified("紅樓夢"))
        assertEquals("刘备", ZhConv.toSimplified("劉備"))
    }

    @Test
    fun `变体列表`() {
        val v = ZhConv.variants("三国演义")
        assertEquals("三国演义", v[0])
        assertTrue("缺繁体变体: " + v, v.contains("三國演義"))
        /* 纯英文不该被翻译出第二个变体 */
        assertEquals(1, ZhConv.variants("Hoare logic").size)
        assertEquals(1, ZhConv.variants("上海").size)
    }

    @Test
    fun `截断切在句末`() {
        /* 硬截会在句子中间断开，模型就会说「资料到此截断」——这是用户反馈的「维基结果经常中断」 */
        val text = "第一句话说明背景。" + "第二句话继续解释这个概念，内容比较长。".repeat(80) + "最后一句。"
        val cut = WikiClient.cutClean(text, 200)
        assertTrue("太长: " + cut.length, cut.length <= 200)
        val last = cut.last()
        assertTrue("切在字中间了: …" + cut.takeLast(12),
                   !(last.isLetterOrDigit() || last.code in 0x4E00..0x9FFF))
        /* 短文本原样返回 */
        assertEquals("短句。", WikiClient.cutClean("短句。", 100))
        /* 整段没有句号时按字数切，不能把内容切没 */
        assertEquals(60, WikiClient.cutClean("没".repeat(300), 60).length)
    }

    @Test
    fun `小写拉丁词能命中大写条目`() {
        assumeTrue("NAS 上的 Kiwix 没开，跳过", nasUp())
        /* 用户实测反馈：打「china」说查不到。三处原因（都已修）：
           ① /content 路径区分大小写，China 要补首字母大写写法；
           ② 拉丁词先去英文库，别被中文库的全文结果先抢走；
           ③ stripHtml 里的 `}` 未转义在安卓（ICU 正则）会抛异常，直接把整条全文检索废掉。 */
        val r = WikiClient.lookup(sources, "china")
        assertTrue("没查到内容: " + r.take(120), r.length > 200)
        assertTrue("拿到的不像 China 条目: " + r.take(120), r.contains("China"))
        assertTrue("英文库结果缺标注: " + r.take(120), r.contains("英文条目"))
    }

    @Test
    fun `小写多词拉丁词走标题索引`() {
        assumeTrue("NAS 上的 Kiwix 没开，跳过", nasUp())
        /* 带空格的词直取必 404，只能靠标题索引：/suggest?term=hoare logic 回的 "Hoare logic"
           大写，过滤和排序要 ignoreCase 才留得下来。 */
        val r = WikiClient.lookup(sources, "hoare logic")
        assertTrue("标题索引没命中: " + r.take(120), r.length > 200)
        assertTrue("条目名不对: " + r.take(120), r.contains("Hoare", true))
    }

    @Test
    fun `资料带节选说明且末尾切在句末`() {
        assumeTrue("NAS 上的 Kiwix 没开，跳过", nasUp())
        val r = WikiClient.lookup(sources, "中国")
        assertTrue("缺节选说明: " + r.take(120), r.contains("节选"))
        val t = r.trimEnd()
        assertTrue("末尾不是句末（会被模型说成资料截断）: …" + t.takeLast(20),
                   t.endsWith("。") || t.endsWith("！") || t.endsWith("？"))
    }

    @Test
    fun `本地源连不上要立刻跳到下一个源`() {
        /* 用户实测：本地 Kiwix 和官方维基都勾上，NAS 一关就说「没查到」——因为死源每个请求都要等满 8 秒，
           四五次就把 JS 的 25 秒兜底耗光，后面的官方维基根本轮不到。这里用不可达地址 + 桩服务器验证：
           死源要被 2 秒探活快速跳过，第二个源正常出结果。 */
        val srv = stubKiwix()
        try {
            val src = """[
              {"id":"dead","name":"本地 Kiwix","type":"kiwix","base":"http://10.255.255.1:8060","on":true},
              {"id":"ok","name":"备用","type":"kiwix","base":"${srv.url}","on":true}
            ]"""
            val t0 = System.currentTimeMillis()
            val r = WikiClient.lookup(src, "Hoare logic")
            val ms = System.currentTimeMillis() - t0
            assertTrue("没落到第二个源: " + r.take(120), r.contains("Hoare"))
            assertTrue("死源拖太久: " + ms + "ms", ms < 6000)
        } finally {
            srv.stop()
        }
    }

    @Test
    fun `短词要先拿到同名条目`() {
        assumeTrue("NAS 上的 Kiwix 没开，跳过", nasUp())
        /* 用户实测（电脑版）：搜「日本」取到的是《BS日本》《CS日本》。原因是 Kiwix 的 /suggest
           索引里根本没有《日本》（只有 .日本 / BS日本 / Amazon日本…），全文前 8 条又全是
           Category: 页面。这里守住：直取得优先，《日本》必须排在第一个。 */
        val r = WikiClient.lookup(sources, "日本")
        val first = Regex("《([^》]+)》").find(r)?.groupValues?.get(1) ?: ""
        assertEquals("日本", first)
    }

    @Test
    fun `本地库能取到条目`() {
        assumeTrue("NAS 上的 Kiwix 没开，跳过", nasUp())
        val r = WikiClient.lookup(sources, "易经")
        assertTrue("结果太短: " + r.take(80), r.length > 200)
        assertTrue("没有条目名: " + r.take(80), r.contains("《"))
    }

    @Test
    fun `全文检索能兜住没有同名条目的词`() {
        assumeTrue("NAS 上的 Kiwix 没开，跳过", nasUp())
        /* 这个词条里没有同名条目、标题索引也给不出候选，只能靠全文检索兜：
           direct 404 + suggest 空 + search 命中 300 条（《牛顿望远镜》等）。
           NAS 上那本坏书没清掉时 /search?books.filter.lang=zho 会 500，这条测试就会红。 */
        val r = WikiClient.lookup(sources, "牛顿 光学 望远镜")
        assertTrue("全文检索没兜住: " + r.take(80), r.length > 200)
        assertTrue("没拿到条目名: " + r.take(80), r.contains("《"))
    }

    /** 极简 HTTP 桩（不用 com.sun.net.httpserver：单测编译在 android.jar 下，看不到它） */
    private class StubServer(private val route: (String) -> String?) {
        private val ss = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val url: String get() = "http://127.0.0.1:" + ss.localPort

        fun start() {
            Thread {
                while (!ss.isClosed) {
                    val s = try { ss.accept() } catch (e: Throwable) { break }
                    Thread { handle(s) }.apply { isDaemon = true }.start()
                }
            }.apply { isDaemon = true }.start()
        }

        private fun handle(sock: Socket) {
            try {
                sock.use {
                    val input = sock.getInputStream().bufferedReader()
                    val line = input.readLine() ?: return
                    val target = line.split(" ").getOrNull(1) ?: "/"
                    while (true) {
                        val h = input.readLine() ?: break
                        if (h.isEmpty()) break
                    }
                    val body = route(target)
                    val out = sock.getOutputStream()
                    if (body == null) {
                        out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    } else {
                        val b = body.toByteArray(Charsets.UTF_8)
                        out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n" +
                                "Content-Length: " + b.size + "\r\nConnection: close\r\n\r\n").toByteArray())
                        out.write(b)
                    }
                    out.flush()
                }
            } catch (e: Throwable) {
                /* 单测桩，连接异常直接丢掉 */
            }
        }

        fun stop() { try { ss.close() } catch (e: Throwable) { } }
    }

    /** 中文库一本什么都查不到的书 + 英文库一本有内容的书 */
    private fun stubKiwix(): StubServer {
        val catalog = """<?xml version="1.0" encoding="UTF-8"?>
<feed xmlns="http://www.w3.org/2005/Atom">
  <entry><title>维基百科</title><language>zho</language>
    <link type="text/html" href="/content/zh_book"/></entry>
  <entry><title>Wikipedia</title><language>eng</language>
    <link type="text/html" href="/content/en_book"/></entry>
</feed>"""
        val para = "<p>Hoare logic is a formal system for reasoning about the correctness of computer " +
                "programs, introduced by Tony Hoare in 1969 and taught in every verification course.</p>"
        val srv = StubServer { target ->
            val path = target.substringBefore("?")
            val query = target.substringAfter("?", "")
            when {
                path == "/catalog/v2/entries" -> catalog
                path == "/suggest" && query.contains("en_book") ->
                    """[{"value":"Hoare logic","kind":"path","path":"Hoare logic"}]"""
                path == "/suggest" -> "[]"
                path == "/search" -> "<html><body>Results 0 of 0</body></html>"
                path.startsWith("/content/en_book/") -> "<html><body>" + para.repeat(4) + "</body></html>"
                else -> null
            }
        }
        srv.start()
        return srv
    }

    @Test
    fun `中文库没有时回退英文库`() {
        val srv = stubKiwix()
        try {
            val src = """[{"id":"stub","name":"本地 Kiwix","type":"kiwix","base":"${srv.url}","on":true}]"""
            val r = WikiClient.lookup(src, "Hoare logic")
            assertTrue("没回退到英文库: " + r.take(120), r.contains("Hoare"))
            assertTrue("缺英文标注: " + r.take(120), r.contains("英文条目"))
        } finally {
            srv.stop()
        }
    }
}
