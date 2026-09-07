package com.tekkansumo.cdprice

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode

data class Hit(
    val site: String,
    val title: String,
    val price: Int,
    val url: String,
    val image: String?,
    val used: Boolean
)

/**
 * 検索結果ページから商品を取り出す。
 *
 * サイトごとに CSS セレクタを書くとリニューアルのたびに全滅するので、
 * どの通販サイトでも成り立つ形だけに頼っている。
 *
 *   価格らしき文字列を持つ「いちばん内側の要素」を全部拾う
 *     → そこから親を辿って、同一ホストの商品リンクを含む最初の箱を商品カードとみなす
 *     → カードの中から題名・画像・最安の金額を取る
 *
 * 価格と商品リンクが対になっていない要素（ヘッダやおすすめ枠）は自然に落ちる。
 */
object Extract {

    private val SKIP = setOf("script", "style", "noscript", "template", "svg", "head", "iframe")

    /** ¥1,234 と 1,234円 の両方。全角数字は事前に半角へ寄せる。 */
    private val PRICE = Regex("""(?:[¥￥]\s*([0-9][0-9,]{0,8})|([0-9][0-9,]{0,8})\s*円)""")

    /** 商品価格ではない金額。これを含む行の数字は使わない。 */
    private val NOT_PRICE = Regex("ポイント|ﾎﾟｲﾝﾄ|還元|送料|手数料|クーポン|割引|OFF|オフ|以上|pt\\b", RegexOption.IGNORE_CASE)

    /** 商品ページではないリンク。 */
    private val NOT_ITEM = Regex("/(search|category|genre|ranking|cart|login|mypage|help|guide|news)(/|\\?|$)", RegexOption.IGNORE_CASE)

    private const val MIN_PRICE = 30
    private const val MAX_PRICE = 300_000

    /** 全角英数を半角へ。価格の桁が全角で書かれていても拾えるようにする。 */
    fun halfWidth(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            sb.append(
                when {
                    c in '０'..'９' -> c - 0xFEE0   // ０-９
                    c in 'Ａ'..'Ｚ' -> c - 0xFEE0   // Ａ-Ｚ
                    c in 'ａ'..'ｚ' -> c - 0xFEE0   // ａ-ｚ
                    c == '　' -> ' '
                    c == '，' -> ','
                    else -> c
                }
            )
        }
        return sb.toString()
    }

    private fun clean(s: String) = s.replace(Regex("\\s+"), " ").trim()

    // ── 価格を持つ最小要素を集める ─────────────────────────────
    private class Scan {
        val hits = ArrayList<Element>()

        /** 戻り値は (この要素配下のテキスト, 子孫で既に価格要素を確定したか)。 */
        fun walk(el: Element): Pair<String, Boolean> {
            val sb = StringBuilder()
            var below = false
            for (n in el.childNodes()) {
                when (n) {
                    is TextNode -> {
                        sb.append(n.wholeText).append(' ')
                    }
                    is Element -> {
                        if (n.tagName() in SKIP) continue
                        val (t, b) = walk(n)
                        sb.append(t).append(' ')
                        if (b) below = true
                    }
                }
            }
            val text = halfWidth(sb.toString())
            // 子孫で確定済みならこの要素は「最小」ではない。
            // 長すぎる箱は、離れた数字がたまたま並んだだけのことが多いので採らない。
            if (!below && text.length <= 140 && PRICE.containsMatchIn(text)) {
                hits.add(el)
                return Pair(text, true)
            }
            return Pair(text, below)
        }
    }

    // ── 補助 ────────────────────────────────────────────
    private fun absOf(a: Element): String? {
        val abs = a.absUrl("href")
        if (abs.isEmpty() || !abs.startsWith("http")) return null
        return abs
    }

    private fun allowed(site: Site, url: String): Boolean {
        val h = url.toHttpUrlOrNull()?.host ?: return false
        return site.hostAllowed(h)
    }

    /** 追跡パラメータを落とし、同じ商品が二重に出ないようにする。 */
    private fun key(url: String): String {
        val u = url.toHttpUrlOrNull() ?: return url
        return u.host + u.encodedPath.trimEnd('/')
    }

    private fun chain(e: Element): ArrayList<Element> {
        val list = ArrayList<Element>()
        var cur: Element? = e
        while (cur != null) {
            list.add(cur)
            cur = cur.parent()
        }
        return list
    }

    /**
     * 価格要素といちばん近い（＝共通の親がいちばん深い）リンクを選ぶ。
     * 同じ近さに複数あるときは、文字を持っているほうを採る。
     * サムネ用の空リンクと題名のリンクが並んでいることが多く、
     * 前者を選ぶと題名が画像の alt 頼りになってしまう。
     */
    private fun nearest(priceEl: Element, anchors: List<Element>): Element? {
        val depth = HashMap<Element, Int>()
        for ((i, e) in chain(priceEl).withIndex()) depth[e] = i

        var best: Element? = null
        var bestScore = Int.MAX_VALUE
        var bestLen = -1
        for (a in anchors) {
            var d = 0
            var cur: Element? = a
            while (cur != null) {
                val up = depth[cur]
                if (up != null) {
                    val score = up + d
                    val len = clean(a.text()).length
                    if (score < bestScore || (score == bestScore && len > bestLen)) {
                        bestScore = score
                        bestLen = len
                        best = a
                    }
                    break
                }
                cur = cur.parent()
                d++
            }
        }
        return best
    }

    private fun usable(s: String): Boolean =
        s.length >= 3 && !Regex("^[\\d,¥￥円\\s]+$").matches(s)

    private fun titleOf(a: Element, card: Element, href: String): String {
        // 同じ商品を指す別のリンク（題名側）に文字があればそれがいちばん確か
        val sameHref = card.select("a[href]")
            .filter { absOf(it) == href }
            .map { clean(it.text()) }
            .filter { usable(it) }
            .maxByOrNull { it.length }

        val cands = listOf(
            sameHref ?: "",
            clean(a.text()),
            clean(a.attr("aria-label")),
            clean(a.attr("title")),
            clean(a.selectFirst("img")?.attr("alt") ?: ""),
            clean(card.selectFirst("img[alt]")?.attr("alt") ?: "")
        )
        for (t in cands) if (usable(t)) return t.take(200)

        // どれも駄目なら箱の文字から金額を除いて代用する
        val t = clean(PRICE.replace(card.text(), " "))
        return if (usable(t)) t.take(200) else ""
    }

    private fun imageOf(card: Element): String? {
        for (img in card.select("img")) {
            for (attr in listOf("src", "data-src", "data-original", "data-lazy-src")) {
                val v = img.absUrl(attr)
                if (v.startsWith("http") && !v.endsWith(".svg")) return v
            }
            val srcset = img.attr("srcset")
            if (srcset.isNotBlank()) {
                val first = srcset.split(",").firstOrNull()?.trim()?.split(" ")?.firstOrNull()
                if (!first.isNullOrBlank()) {
                    val abs = card.baseUri().toHttpUrlOrNull()?.resolve(first)?.toString()
                    if (abs != null) return abs
                }
            }
        }
        return null
    }

    /** カード内の金額のうち、商品価格として妥当な最小値。 */
    private fun priceOf(card: Element, priceEls: List<Element>): Int? {
        var best: Int? = null
        for (pe in priceEls) {
            val text = halfWidth(pe.text())
            if (NOT_PRICE.containsMatchIn(text)) continue
            for (m in PRICE.findAll(text)) {
                val raw = (m.groupValues[1].ifEmpty { m.groupValues[2] }).replace(",", "")
                val v = raw.toIntOrNull() ?: continue
                if (v < MIN_PRICE || v > MAX_PRICE) continue
                if (best == null || v < best!!) best = v
            }
        }
        return best
    }

    // ── 本体 ────────────────────────────────────────────
    fun extract(site: Site, baseUrl: String, html: String, limit: Int = 150): List<Hit> {
        val doc = Jsoup.parse(html, baseUrl)
        doc.select(SKIP.joinToString(",")).remove()
        val body = doc.body() ?: return emptyList()

        val scan = Scan()
        scan.walk(body)
        if (scan.hits.isEmpty()) return emptyList()
        val priceElSet = HashSet<Element>(scan.hits)

        val found = LinkedHashMap<String, Hit>()
        for (pe in scan.hits) {
            // 価格から親を辿り、同一ホストのリンクを含む最初の箱を商品カードとみなす
            var node: Element? = pe
            var card: Element? = null
            var anchors: List<Element> = emptyList()
            var up = 0
            while (node != null && up <= 12) {
                val list = node.select("a[href]").filter { a ->
                    val abs = absOf(a) ?: return@filter false
                    allowed(site, abs) && !NOT_ITEM.containsMatchIn(abs)
                }
                if (list.isNotEmpty()) {
                    card = node
                    anchors = list
                    break
                }
                node = node.parent()
                up++
            }
            if (card == null) continue

            val a = nearest(pe, anchors) ?: continue
            val url = absOf(a) ?: continue
            val k = key(url)

            val inCard = card.select("*").filter { it in priceElSet }
            val price = priceOf(card, if (inCard.isEmpty()) listOf(pe) else inCard) ?: continue

            val title = titleOf(a, card, url)
            if (title.isEmpty()) continue

            val cardText = card.text()
            val used = site.usedOnly ||
                url.contains("/used/") ||
                site.usedHints.any { cardText.contains(it) }

            val hit = Hit(site.id, title, price, url, imageOf(card), used)
            val prev = found[k]
            // 同じ商品が複数の箱で拾えたときは安いほうを残す
            if (prev == null || hit.price < prev.price) found[k] = hit
            if (found.size >= limit * 3) break
        }

        var list = found.values.toList()

        // 商品ページらしい URL に十分な数が集まっているときだけ、それで絞る。
        // パターンの当てが外れているときに全滅させないための保険。
        val path = site.itemPath
        if (path != null) {
            val matched = list.filter { path.containsMatchIn(it.url) }
            if (matched.size >= 3) list = matched
        }

        return list.sortedBy { it.price }.take(limit)
    }
}
