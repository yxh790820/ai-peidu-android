package com.yxh.peidu

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 极简 OpenAI 兼容流式客户端。
 * payload: {base, key, model, messages:[{role,content}], params:{...}}
 * 回调：window.__onDelta / __onThink / __onDone / __onError
 */
object LlmClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var call: okhttp3.Call? = null

    fun cancel() {
        call?.cancel()
    }

    fun run(act: MainActivity, payload: String) {
        val sb = StringBuilder()
        val thinkAll = StringBuilder()
        try {
            val p = JSONObject(payload)
            val base = p.optString("base").trim().trimEnd('/')
            val key = p.optString("key")
            val model = p.optString("model")
            val messages = p.optJSONArray("messages") ?: org.json.JSONArray()

            val body = JSONObject()
            body.put("model", model)
            body.put("messages", messages)
            body.put("stream", true)
            val params = p.optJSONObject("params")
            if (params != null) {
                for (k in params.keys()) body.put(k, params.get(k))
            }

            if (base.isEmpty()) {
                err(act, "服务地址没填（设置里填 base，比如 https://api.deepseek.com/v1）")
                return
            }
            // 与电脑版 peidu.py 一致：base 不以 /v1 结尾就自动补，否则本地 18080 会 400
            val url = if (base.endsWith("/v1")) "$base/chat/completions" else "$base/v1/chat/completions"

            val req = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $key")
                .addHeader("Accept", "text/event-stream")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val c = client.newCall(req)
            call = c
            c.execute().use { resp ->
                val rb = resp.body
                if (!resp.isSuccessful) {
                    val txt = rb?.string()?.take(400) ?: ""
                    err(act, "HTTP ${resp.code}  $txt")
                    return
                }
                if (rb == null) {
                    err(act, "响应为空")
                    return
                }
                val src = rb.source()
                while (!src.exhausted()) {
                    val line = src.readUtf8Line() ?: break
                    if (line.isEmpty() || !line.startsWith("data:")) continue
                    val data = line.substring(5).trim()
                    if (data == "[DONE]") break
                    val obj = try {
                        JSONObject(data)
                    } catch (e: Exception) {
                        continue
                    }
                    val choices = obj.optJSONArray("choices") ?: continue
                    if (choices.length() == 0) continue
                    val delta = choices.getJSONObject(0).optJSONObject("delta") ?: continue
                    val think = delta.optString("reasoning_content", "")
                    if (think.isNotEmpty()) {
                        thinkAll.append(think)
                        act.js("window.__onThink && window.__onThink(${JSONObject.quote(think)})")
                    }
                    val text = delta.optString("content", "")
                    if (text.isNotEmpty()) {
                        sb.append(text)
                        act.js("window.__onDelta && window.__onDelta(${JSONObject.quote(text)})")
                    }
                }
                if (sb.isEmpty()) {
                    if (thinkAll.isEmpty()) {
                        err(act, "服务没返回内容（检查模型名 / 参数）")
                    } else {
                        err(act, "只收到思考内容、没有正文。本地模型可能需要更长输出上限，或在附加参数里关掉 thinking。")
                    }
                } else {
                    act.js("window.__onDone && window.__onDone(${JSONObject.quote(sb.toString())})")
                }
            }
        } catch (e: Exception) {
            if (call?.isCanceled() == true) {
                act.js("window.__onError && window.__onError(${JSONObject.quote("已停止")})")
            } else {
                err(act, e.javaClass.simpleName + ": " + (e.message ?: ""))
            }
        } finally {
            call = null
        }
    }

    private fun err(act: MainActivity, msg: String) {
        act.js("window.__onError && window.__onError(${JSONObject.quote(msg)})")
    }
}
