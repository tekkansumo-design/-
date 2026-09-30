package com.tekkansumo.ebaylister.core

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.roundToLong

/** 落札相場。ebay_lister.py の「落札相場」節の移植。 */
class Prices(private val http: OkHttpClient, private val ebay: Ebay) {

    fun soldSearchUrl(c: JSONObject, q: String) = ebay.webHost(c) + "/sch/i.html?" +
        Ebay.query(mapOf("_nkw" to q, "LH_Sold" to "1", "LH_Complete" to "1", "_ipg" to "120"))

    private fun scrape(c: JSONObject, q: String): List<PriceItem> {
        val req = Request.Builder().url(soldSearchUrl(c, q) + "&rt=nc")
            .header("User-Agent", UA).header("Accept", "text/html,application/xhtml+xml")
            .header("Accept-Language", "en-US,en;q=0.9").build()
        http.newCall(req).execute().use { r ->
            if (r.code != 200) throw AppError("Sold 検索ページの取得に失敗しました (${r.code})")
            val html = r.body?.string().orEmpty()
            val items = parseSoldPage(html)
            val low = html.lowercase()
            if (items.isEmpty() && ("captcha" in low || "splashui" in low))
                throw AppError("eBay にロボット判定されました。時間をおくか、下のリンクから直接確認してください")
            return items
        }
    }

    fun research(c: JSONObject, q: String): JSONObject {
        val market = Conf.market(c)
        val res = JSONObject().put("query", q).put("currency", market.currency)
            .put("usd_jpy", c.optDouble("usd_jpy", 0.0))
            .put("links", JSONObject().put("sold", soldSearchUrl(c, q)).put("terapeak",
                "https://www.ebay.com/sh/research?" + Ebay.query(mapOf(
                    "marketplace" to c.optString("marketplace_id").replace("EBAY_", "EBAY-"),
                    "keywords" to q, "tabName" to "SOLD"))))

        var sold = emptyList<PriceItem>()
        var source: String? = null
        var soldErr: String? = null
        try {
            sold = ebay.searchSoldApi(c, q)
            source = "Marketplace Insights API"
        } catch (e: AppError) {
            val apiErr = e.message
            if (c.optBoolean("scrape_sold", true)) {
                try {
                    sold = scrape(c, q)
                    source = "eBay Sold 検索ページ"
                } catch (e2: Exception) {
                    soldErr = "${e2.message}（Insights API: $apiErr）"
                }
            } else {
                soldErr = "Insights API が使えません: $apiErr"
            }
        } catch (e: java.io.IOException) {
            soldErr = "通信エラー: ${e.message}"
        }
        val soldStats = stats(sold)
        res.put("sold", JSONObject().put("source", source ?: JSONObject.NULL)
            .put("items", JSONArray(sold.map { it.toJson() })).put("error", soldErr ?: JSONObject.NULL)
            .put("stats", soldStats ?: JSONObject.NULL))

        var active = emptyList<PriceItem>()
        var activeErr: String? = null
        try {
            active = ebay.searchActive(c, q)
        } catch (e: Exception) {
            activeErr = e.message
        }
        val activeStats = stats(active)
        res.put("active", JSONObject().put("items", JSONArray(active.map { it.toJson() }))
            .put("error", activeErr ?: JSONObject.NULL).put("stats", activeStats ?: JSONObject.NULL))

        // 推奨価格: 落札の中央値。落札が取れなければ出品中の下位 25% あたり
        if (soldStats != null) {
            res.put("suggested", soldStats.getDouble("median"))
            res.put("suggested_basis", "落札 ${soldStats.getInt("count")} 件の中央値")
        } else if (activeStats != null) {
            res.put("suggested", activeStats.optDouble("p25", activeStats.getDouble("median")))
            res.put("suggested_basis", "出品中 ${activeStats.getInt("count")} 件の下位 25%（落札データなし）")
        }
        return res
    }

    companion object {
        const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

        private fun r2(x: Double) = (x * 100).roundToLong() / 100.0

        /** Python の statistics と同じ計算（quantiles は既定の exclusive 法）。 */
        fun stats(items: List<PriceItem>): JSONObject? {
            val p = items.map { it.price }.sorted()
            if (p.isEmpty()) return null
            val n = p.size
            val median = if (n % 2 == 1) p[n / 2] else (p[n / 2 - 1] + p[n / 2]) / 2
            val st = JSONObject().put("count", n).put("min", p.first()).put("max", p.last())
                .put("mean", r2(p.sum() / n)).put("median", r2(median))
            if (n >= 4) {
                val m = n + 1
                fun q(i: Int): Double {
                    val j = i * m / 4
                    val delta = i * m - j * 4
                    return (p[j - 1] * (4 - delta) + p[j] * delta) / 4
                }
                st.put("p25", r2(q(1))).put("p75", r2(q(3)))
            }
            return st
        }

        private fun strip(s: String) = unescape(s.replace(Regex("<[^>]+>"), " "))
            .replace(Regex("\\s+"), " ").trim()

        private fun unescape(s: String) = s.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&#x27;", "'").replace("&amp;", "&")

        private val PRICE_RE = Regex("(?:US\\s*)?(?:\\$|£|€|AU\\s*\\$|C\\s*\\$)\\s*([\\d,]+(?:\\.\\d{1,2})?)" +
            "|([\\d.,]+)\\s*€|EUR\\s*([\\d.,]+)")
        private val SOLD_DATE_RE = Regex("(?:Sold|Verkauft)\\s+([A-Z][a-z]{2}\\s+\\d{1,2},?\\s+\\d{4}|\\d{1,2}\\.?\\s+\\w+\\.?\\s+\\d{4})")
        private val ITEM_URL_RE = Regex("href=\"(https?://[^\"]*?/itm/(?:[^\"/]*/)?(\\d{9,15})[^\"]*)\"")
        private val SPLIT_RE = Regex("<li\\b(?=[^>]*class=\"[^\"]*\\bs-(?:item|card)\\b)")
        private val TITLE_RE = Regex("(?s)class=\"[^\"]*s-(?:item|card)__title[^\"]*\"[^>]*>(.*?)</(?:div|h3)>")
        private val H3_RE = Regex("(?s)<h3[^>]*>(.*?)</h3>")
        private val PRICE_SPAN_RE = Regex("(?s)class=\"[^\"]*s-(?:item|card)__price[^\"]*\"[^>]*>(.*?)</span>")

        fun price(text: String): Double? {
            val m = PRICE_RE.find(text) ?: return null
            val g = m.groupValues
            val raw = if (g[1].isNotEmpty()) g[1].replace(",", "")
            else (g[2].ifEmpty { g[3] }).replace(".", "").replace(",", ".")
            return raw.toDoubleOrNull()
        }

        /** eBay の Sold 検索結果ページから抜く。旧（li.s-item）と新（li.s-card）の両方を見る。 */
        fun parseSoldPage(html: String): List<PriceItem> {
            val out = ArrayList<PriceItem>()
            val seen = HashSet<String>()
            val chunks = html.split(SPLIT_RE)
            for (raw in chunks.drop(1)) {
                val ch = raw.take(20000)
                val m = ITEM_URL_RE.find(ch) ?: continue
                if (m.groupValues[2] in seen) continue
                val tm = TITLE_RE.find(ch) ?: H3_RE.find(ch)
                var title = if (tm != null) strip(tm.groupValues[1]) else ""
                title = title.replace(Regex("^(New Listing|Neues Angebot)\\s*"), "")
                    .replace(Regex("\\s*Opens in a new window or tab$"), "")
                if (title.isEmpty() || title.lowercase().startsWith("shop on ebay")) continue
                val pm = PRICE_SPAN_RE.find(ch) ?: continue
                val pr = price(strip(pm.groupValues[1])) ?: continue
                var date = ""
                SOLD_DATE_RE.find(strip(ch))?.let { dm ->
                    val s = dm.groupValues[1]
                    for (fmt in listOf("MMM d, yyyy", "MMM d yyyy")) {
                        try {
                            val f = SimpleDateFormat(fmt, Locale.US).apply { isLenient = false }
                            date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(f.parse(s)!!)
                            break
                        } catch (e: Exception) { /* 次の書式 */ }
                    }
                    if (date.isEmpty()) date = s
                }
                seen.add(m.groupValues[2])
                out.add(PriceItem(title, pr, "", date, "", unescape(m.groupValues[1]).substringBefore("?")))
            }
            return out
        }
    }
}
