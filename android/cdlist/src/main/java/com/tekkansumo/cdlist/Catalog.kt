package com.tekkansumo.cdlist

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URI

/** 分類ひとつ。表示名と、そこへ潜る URL。 */
data class Cat(val label: String, val url: String)

/**
 * 分類をたどる。
 *
 * 分類の URL の形はこちらで決め打ちしない。同じ階層に並ぶ兄弟リンクが
 * いちばん多いところが分類の一覧である、という当て方をする。サイトの
 * 作りが変わっても、リンクが並んでいる限り辿れる。
 */
object Catalog {

    private val HOST = Regex("(^|\\.)bookoff\\.co\\.jp$", RegexOption.IGNORE_CASE)

    /** 分類として拾わないパス。商品・店舗・案内ページなど。 */
    private val NOT_GENRE = Regex(
        "/(used|goods|shop|help|guide|info|login|logout|mypage|cart|favorite" +
                "|inquiry|privacy|policy|terms|company|sitemap|news|campaign)(/|$)",
        RegexOption.IGNORE_CASE
    )

    /** ページ送り。分類と同じ階層に並ぶので分類から外す。 */
    private val PAGER = Regex(
        "^(\\d+|次|次へ|次のページ|前|前へ|最初|最後|先頭|末尾" +
                "|next|prev|previous|first|last|›|‹|»|«|>|<)$",
        RegexOption.IGNORE_CASE
    )
    private val NEXT = Regex("^(次|次へ|次のページ|next|›|»|>)$", RegexOption.IGNORE_CASE)
    private val SPACES = Regex("\\s+")

    fun isBookoff(url: String): Boolean {
        val u = try {
            URI(url)
        } catch (e: Exception) {
            return false
        }
        if (u.scheme != "http" && u.scheme != "https") return false
        return HOST.containsMatchIn(u.host ?: "")
    }

    private data class L(val path: String, val text: String, val href: String)

    private fun links(html: String, baseUrl: String): List<L> {
        val doc = Jsoup.parse(html, baseUrl)
        val out = ArrayList<L>()
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
            val t = SPACES.replace(a.text(), " ").trim()
            if (t.isEmpty()) continue
            out.add(L(u.path ?: "", t, href.substringBefore("#")))
        }
        return out
    }

    fun genres(html: String, baseUrl: String): List<Cat> {
        val groups = LinkedHashMap<String, ArrayList<Cat>>()
        val seen = HashSet<String>()
        for (l in links(html, baseUrl)) {
            if (l.path.isEmpty() || l.path == "/") continue
            if (NOT_GENRE.containsMatchIn(l.path)) continue
            if (l.text.length > 30) continue        // 説明文のようなリンクは分類ではない
            if (PAGER.matches(l.text)) continue
            if (!seen.add(l.href)) continue
            val segs = l.path.split("/").filter { it.isNotEmpty() }
            val prefix = when {
                segs.size > 1 -> segs.dropLast(1).joinToString("/")
                segs.size == 1 -> segs[0]
                else -> ""
            }
            groups.getOrPut(prefix) { ArrayList() }.add(Cat(l.text, l.href))
        }
        val best = groups.values.maxByOrNull { it.size } ?: return emptyList()
        return if (best.size >= 3) best else emptyList()
    }

    fun nextPage(html: String, baseUrl: String): String? =
        links(html, baseUrl).firstOrNull { NEXT.matches(it.text) }?.href

    /** 分類ページを 1 枚開いて、下位の分類と商品と次ページを返す。 */
    fun explore(url: String): JSONObject {
        if (!isBookoff(url)) {
            return JSONObject().put("ok", false)
                .put("msg", "BOOKOFF のページを指定してください")
        }
        val res = Http.get(url)
        if (res.error != null) return JSONObject().put("ok", false).put("msg", res.error)
        if (res.code != 200) {
            return JSONObject().put("ok", false).put("msg", "HTTP ${res.code}")
        }

        val cats = JSONArray()
        for (c in genres(res.body, res.url)) {
            cats.put(JSONObject().put("label", c.label).put("url", c.url))
        }
        val items = JSONArray()
        for (f in Bookoff.parse(res.body)) {
            items.put(JSONObject().apply {
                put("pid", f.pid)
                put("new", f.isNew)
                put("title", f.title)
                put("price", f.price ?: JSONObject.NULL)
                put("soldout", f.soldOut)
                put("url", f.url)
            })
        }
        return JSONObject()
            .put("ok", true)
            .put("url", res.url)
            .put("links", cats)
            .put("items", items)
            .put("next", nextPage(res.body, res.url) ?: JSONObject.NULL)
    }
}
