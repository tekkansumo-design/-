package com.tekkansumo.cdprice

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit

data class Res(val url: String, val code: Int, val body: String, val error: String?)

object Http {

    const val UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .followRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    fun get(url: String): Res {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", UA)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "ja,en-US;q=0.9,en;q=0.8")
                .build()
            client.newCall(req).execute().use { r ->
                val finalUrl = r.request.url.toString()
                Res(finalUrl, r.code, r.body?.string() ?: "", null)
            }
        } catch (e: Exception) {
            Res(url, 0, "", e.javaClass.simpleName + ": " + (e.message ?: ""))
        }
    }

    /**
     * トップページの検索フォームから検索 URL の形を割り出す。
     * 候補テンプレートが全滅したときの最後の手段。
     */
    fun discoverTemplate(site: Site): String? {
        val res = get(site.home)
        if (res.body.isEmpty()) return null
        val doc = Jsoup.parse(res.body, res.url)

        for (form in doc.select("form")) {
            val method = form.attr("method").ifBlank { "get" }
            if (!method.equals("get", true)) continue

            val inputs = form.select("input")
            val q = inputs.firstOrNull { i ->
                val type = i.attr("type").lowercase()
                val name = i.attr("name").lowercase()
                if (name.isBlank()) return@firstOrNull false
                if (type in setOf("hidden", "checkbox", "radio", "submit", "image", "button")) return@firstOrNull false
                type == "search" || Regex("keyword|search|word|query|^q$|^k$").containsMatchIn(name)
            } ?: continue

            val action = form.absUrl("action").ifBlank { res.url }
            val base = action.toHttpUrlOrNull() ?: continue
            val b = base.newBuilder()
            // 既定値のある hidden はカテゴリ指定などのことが多いので引き継ぐ
            for (h in inputs) {
                if (!h.attr("type").equals("hidden", true)) continue
                val n = h.attr("name")
                if (n.isBlank() || n == q.attr("name")) continue
                b.setQueryParameter(n, h.attr("value"))
            }
            b.setQueryParameter(q.attr("name"), "__CDQ__")
            return b.build().toString().replace("__CDQ__", "{q}")
        }
        return null
    }
}
