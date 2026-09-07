package com.tekkansumo.cdlist

import org.jsoup.Jsoup
import java.net.URI

/**
 * 商品ページから在庫のある実店舗を読む。
 *
 * 在庫店舗チェッカー（bookoff_web.py / android:app）と同じ見方。実店舗の
 * ページは /shop/shopNNNN で、そこへのリンクが在庫のある店として並ぶ。
 */
object Shops {

    private val HOST = Regex("(^|\\.)bookoff\\.co\\.jp$", RegexOption.IGNORE_CASE)
    private val PATH = Regex("/shop/shop\\d+", RegexOption.IGNORE_CASE)
    private val SPACES = Regex("\\s+")

    fun parse(html: String, baseUrl: String): List<String> {
        if (html.isBlank()) return emptyList()
        val doc = Jsoup.parse(html, baseUrl)
        val out = ArrayList<String>()
        val seen = HashSet<String>()
        for (a in doc.select("a[href]")) {
            val href = a.absUrl("href")
            if (href.isEmpty()) continue
            val u = try {
                URI(href)
            } catch (e: Exception) {
                continue
            }
            if (u.scheme != "http" && u.scheme != "https") continue
            if (!HOST.containsMatchIn(u.host ?: "")) continue
            val path = u.path ?: ""
            if (!PATH.containsMatchIn(path)) continue   // 「店舗検索」など実店舗でないリンク
            val t = SPACES.replace(a.text(), " ").trim()
            if (t.isEmpty() || t == "店舗検索") continue
            if (!seen.add("${u.host}$path")) continue
            out.add(t)
        }
        return out
    }

    /** 店舗名の一部で絞る。空なら在庫のある店をすべて返す。 */
    fun filter(shops: List<String>, keyword: String): List<String> {
        val key = Match.key(keyword)
        if (key.isEmpty()) return shops
        return shops.filter { Match.key(it).contains(key) }
    }
}
