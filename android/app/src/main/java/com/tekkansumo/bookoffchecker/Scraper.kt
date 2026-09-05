package com.tekkansumo.bookoffchecker

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit

data class Shop(val name: String, val url: String)

/** 商品ページの取得と店舗リンクの抽出。Python 版の fetch_stores / parse_shops 相当。 */
object Scraper {

    private const val UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"

    private val SHOP_PATH = Regex("/shop/shop\\d+", RegexOption.IGNORE_CASE)

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun url(pid: String) = "https://shopping.bookoff.co.jp/used/$pid"

    fun fetch(pid: String): Response {
        val req = Request.Builder()
            .url(url(pid))
            .header("User-Agent", UA)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "ja,en-US;q=0.9,en;q=0.8")
            .build()
        return client.newCall(req).execute()
    }

    /** 商品名（h1）と在庫店舗リンクを返す。相対 URL も絶対化する。 */
    fun parse(baseUrl: String, html: String): Pair<String?, List<Shop>> {
        val doc = Jsoup.parse(html, baseUrl)
        val name = doc.selectFirst("h1")?.text()?.trim()?.ifEmpty { null }

        val shops = ArrayList<Shop>()
        val seen = HashSet<String>()
        for (a in doc.select("a[href]")) {
            val abs = a.absUrl("href")
            if (abs.isEmpty()) continue
            val u = abs.toHttpUrlOrNull() ?: continue
            val host = u.host
            if (host != "bookoff.co.jp" && !host.endsWith(".bookoff.co.jp")) continue
            if (!SHOP_PATH.containsMatchIn(u.encodedPath)) continue
            val text = a.text().trim()
            if (text.isEmpty() || text == "店舗検索") continue
            val key = host + u.encodedPath
            if (!seen.add(key)) continue
            shops.add(Shop(text, abs))
        }
        return Pair(name, shops)
    }
}
