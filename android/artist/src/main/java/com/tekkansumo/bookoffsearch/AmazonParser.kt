package com.tekkansumo.bookoffsearch

import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * Amazon.co.jp の HTML 解析。Android に依存しないので JVM でも試せる。
 *
 * 検索（/s?k=<JAN>）:
 *   div[data-component-type=s-search-result][data-asin=<ASIN>]
 *
 * 全出品（/gp/aod/ajax?asin=<ASIN>&pc=dp&pageno=N）:
 *   #aod-pinned-offer / div#aod-offer
 *     #aod-offer-heading  "中古品 - 非常に良い" など
 *     .a-price .a-offscreen  "￥1,280"
 *
 * 商品ページ（/dp/<ASIN>）は全出品が取れなかったときの予備。
 * 「中古品の出品：3 ￥1,280」のような表記から拾う。
 */
object AmazonParser {

    const val BASE = "https://www.amazon.co.jp"

    private val ASIN = Regex("^[A-Z0-9]{10}$")
    private val DP = Regex("/(?:dp|gp/product)/([A-Z0-9]{10})")
    private val JPY = Regex("[￥¥]\\s*([\\d,]+)")
    private val USED_NEAR = Regex("中古[^￥¥]{0,40}[￥¥]\\s*([\\d,]+)")

    fun searchUrl(jan: String) = "$BASE/s?k=$jan"
    fun productUrl(asin: String) = "$BASE/dp/$asin"

    /** 中古の出品だけに絞った全出品。フィルタが効かなくても中古判定は解析側でする。 */
    fun aodUrl(asin: String, page: Int, usedOnly: Boolean): String {
        val f = if (usedOnly)
            "&filters=%7B%22all%22%3Atrue%2C%22usedLikeNew%22%3Atrue%2C%22usedVeryGood%22%3Atrue" +
                    "%2C%22usedGood%22%3Atrue%2C%22usedAcceptable%22%3Atrue%7D"
        else ""
        return "$BASE/gp/aod/ajax/ref=auto_load_aod?asin=$asin&pc=dp&qty=1&pageno=$page$f"
    }

    fun jpy(s: String?): Int? = s?.let { JPY.find(it)?.groupValues?.get(1) ?: it.filter { c -> c.isDigit() } }
        ?.replace(",", "")?.toIntOrNull()?.takeIf { it > 0 }

    /** ロボット確認ページか。 */
    fun isBlocked(html: String): Boolean =
        html.contains("validateCaptcha") ||
                (html.length < 30000 && html.contains("captcha", ignoreCase = true))

    /** 検索結果の最初の商品の ASIN。広告（スポンサー）は後回し。 */
    fun firstAsin(html: String): String? {
        val doc = Jsoup.parse(html, BASE)
        val cards = doc.select("div[data-component-type=s-search-result][data-asin]")
            .filter { ASIN.matches(it.attr("data-asin")) }
        val organic = cards.firstOrNull { !isSponsored(it) }
        (organic ?: cards.firstOrNull())?.let { return it.attr("data-asin") }

        for (e in doc.select("[data-asin]")) {
            val a = e.attr("data-asin")
            if (ASIN.matches(a)) return a
        }
        return DP.find(html)?.groupValues?.get(1)
    }

    private fun isSponsored(card: Element): Boolean =
        card.hasClass("AdHolder") || card.selectFirst(".puis-sponsored-label-text, .s-sponsored-label-text") != null

    /** 全出品の HTML から中古の最安値。中古が 1 件も無ければ null。 */
    fun usedMinFromAod(html: String): Int? {
        val doc = Jsoup.parse(html, BASE)
        var min: Int? = null
        for (offer in doc.select("#aod-pinned-offer, div#aod-offer")) {
            val heading = offer.selectFirst("#aod-offer-heading")?.text()
                ?: offer.selectFirst("h5")?.text() ?: ""
            if (!heading.contains("中古")) continue
            val price = priceOf(offer) ?: continue
            if (min == null || price < min) min = price
        }
        return min
    }

    /** 全出品の次ページがあるか。 */
    fun aodHasNext(html: String): Boolean {
        val doc = Jsoup.parse(html, BASE)
        return doc.selectFirst("ul.a-pagination li.a-last:not(.a-disabled)") != null
    }

    fun aodOfferCount(html: String): Int = Jsoup.parse(html, BASE).select("#aod-pinned-offer, div#aod-offer").size

    private fun priceOf(e: Element): Int? {
        for (sel in listOf(".a-price .a-offscreen", "span.a-price-whole", ".a-color-price")) {
            val p = jpy(e.selectFirst(sel)?.text())
            if (p != null) return p
        }
        return null
    }

    /** 商品ページの「中古品 ￥…」表記から。 */
    fun usedMinFromProduct(html: String): Int? {
        val doc = Jsoup.parse(html, BASE)
        for (sel in listOf("#usedBuySection .a-color-price", "#usedBuySection .a-offscreen",
            "#olpLinkWidget_feature_div", "#buybox-see-all-buying-choices", "#moreBuyingChoices_feature_div")) {
            val e = doc.selectFirst(sel) ?: continue
            val t = e.text()
            if (sel.startsWith("#usedBuySection")) jpy(t)?.let { return it }
            USED_NEAR.find(t)?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull()?.let { return it }
        }
        return USED_NEAR.find(doc.text())?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull()
    }
}
