package com.tekkansumo.bookoffchecker

import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** 検索一覧の 1 商品。diff は「定価より N 円おトク」の N（表記が無ければ null）。 */
data class Product(
    val pid: String,
    val url: String,
    val title: String,
    val artist: String,
    val category: String,
    val price: Int?,
    val diff: Int?,
    val diffPct: Int?,
    val onlineStock: Boolean
)

data class SearchPage(val items: List<Product>, val next: String?)

/** 入荷店舗モーダルの 1 店舗。 */
data class StoreHit(val name: String, val address: String, val url: String?, val pref: String?)

/**
 * 商品ページの入荷店舗。declared は見出し「商品が入荷した店舗：N店」の N、
 * modalFound はモーダル自体があったか（無ければ JS 描画を疑う）。
 */
data class StoreInfo(val declared: Int?, val stores: List<StoreHit>, val modalFound: Boolean)

/**
 * ブックオフ公式オンラインストアの HTML 解析。Android に依存しないので JVM でも試せる。
 *
 * 検索一覧（サーバ描画）:
 *   div.productItem > a.productItem__link[href=/used/<id>]
 *     p.productItem__title / p.productItem__author / li.productItem__genreItem--category
 *     p.productItem__price  "¥495円 定価より308円（38%）おトク"
 *     span.productItem__stock--alert  オンライン在庫あり
 *   a.pagination__next  次ページ（最終ページは span）
 *
 * 商品ページ:
 *   #modalStoreInformation
 *     p.modalStoreInformation__heading  "商品が入荷した店舗：32店"
 *     ul.modalStoreInformation__list > li.pref_<1-47>.city_<n>
 *       a.modalStoreInformation__link  "BOOKOFF 〇〇店 <small class=…__address>住所</small>"
 */
object ArtistParser {

    const val BASE = "https://shopping.bookoff.co.jp"

    val PREFS = listOf(
        "北海道", "青森県", "岩手県", "宮城県", "秋田県", "山形県", "福島県",
        "茨城県", "栃木県", "群馬県", "埼玉県", "千葉県", "東京都", "神奈川県",
        "新潟県", "富山県", "石川県", "福井県", "山梨県", "長野県",
        "岐阜県", "静岡県", "愛知県", "三重県",
        "滋賀県", "京都府", "大阪府", "兵庫県", "奈良県", "和歌山県",
        "鳥取県", "島根県", "岡山県", "広島県", "山口県",
        "徳島県", "香川県", "愛媛県", "高知県",
        "福岡県", "佐賀県", "長崎県", "熊本県", "大分県", "宮崎県", "鹿児島県", "沖縄県"
    )

    private val USED_ID = Regex("/used/(\\d{8,12})")
    private val YEN = Regex("[¥￥]\\s*([\\d,]+)")
    private val NUM = Regex("([\\d,]+)\\s*円")
    private val DIFF = Regex("定価より\\s*([\\d,]+)\\s*円")
    private val PCT = Regex("[（(]\\s*(\\d+)\\s*[%％]")
    private val HEADING = Regex("入荷した店舗\\s*[：:]\\s*([\\d,]+)\\s*店")
    private val PREF_CLASS = Regex("^pref_(\\d{1,2})$")

    private fun int(s: String?) = s?.replace(",", "")?.toIntOrNull()

    private fun clean(s: String?) = (s ?: "").replace(' ', ' ')
        .replace(Regex("\\s+"), " ").trim()

    // ───────────────────────── 検索一覧 ─────────────────────────

    fun parseSearch(html: String, pageUrl: String): SearchPage {
        val doc = Jsoup.parse(html, pageUrl)
        val out = ArrayList<Product>()
        val seen = HashSet<String>()

        var cards: List<Element> = doc.select(".productItem")
        if (cards.isEmpty()) cards = doc.select(".js-hoverItem")
        for (card in cards) {
            val p = parseCard(card) ?: continue
            if (seen.add(p.pid)) out.add(p)
        }

        val nextA = doc.selectFirst("a.pagination__next[href]")
        val next = nextA?.absUrl("href")?.ifEmpty { null }
        return SearchPage(out, next)
    }

    private fun parseCard(card: Element): Product? {
        var pid: String? = null
        var href: String? = null
        for (a in card.select("a[href]")) {
            val m = USED_ID.find(a.attr("href")) ?: continue
            pid = m.groupValues[1]
            href = a.absUrl("href").ifEmpty { BASE + "/used/" + pid }
            break
        }
        if (pid == null) return null   // 新品のみの商品などは対象外

        val title = clean(card.selectFirst(".productItem__title")?.text())
            .ifEmpty { clean(card.selectFirst("img[alt]")?.attr("alt")) }
        val artist = clean(card.selectFirst(".productItem__author")?.text())
        val category = clean(card.selectFirst(".productItem__genreItem--category")?.text())

        val priceEl = card.selectFirst(".productItem__price")
        val priceText = clean(priceEl?.text())
        // 「おトク」表記は <small> の中。本体価格はその外側の最初の数字
        val small = clean(priceEl?.selectFirst("small")?.text())
        val main = if (small.isNotEmpty()) priceText.replace(small, "") else priceText
        val price = int(YEN.find(main)?.groupValues?.get(1))
            ?: int(NUM.find(main)?.groupValues?.get(1))
        val diff = int(DIFF.find(priceText)?.groupValues?.get(1))
        val pct = int(PCT.find(small.ifEmpty { priceText })?.groupValues?.get(1))
        val stock = card.selectFirst(".productItem__stock--alert") != null

        return Product(pid, href!!, title.ifEmpty { pid }, artist, category, price, diff, pct, stock)
    }

    // ───────────────────────── 商品ページ ─────────────────────────

    fun parseStores(html: String, pageUrl: String): StoreInfo {
        val doc = Jsoup.parse(html, pageUrl)
        // 同じ id のモーダルがテンプレートとして複数入っていることがあるので最初の 1 つ
        val modal = doc.selectFirst("#modalStoreInformation")
        val scope: Element = modal ?: doc

        val head = clean(scope.selectFirst(".modalStoreInformation__heading")?.text())
        val declared = int(HEADING.find(head)?.groupValues?.get(1))
            ?: if (modal == null) int(HEADING.find(clean(doc.text()))?.groupValues?.get(1)) else null

        val stores = ArrayList<StoreHit>()
        val seen = HashSet<String>()
        for (li in scope.select(".modalStoreInformation__list li")) {
            val hit = parseStoreLi(li) ?: continue
            if (seen.add(hit.name + "|" + hit.address)) stores.add(hit)
        }
        return StoreInfo(declared, stores, modal != null)
    }

    private fun parseStoreLi(li: Element): StoreHit? {
        val a = li.selectFirst("a")
        val box = (a ?: li).clone()
        val addrEl = box.selectFirst(".modalStoreInformation__address, small")
        val address = clean(addrEl?.text())
        addrEl?.remove()
        val name = clean(box.text())
        if (name.isEmpty()) return null

        // href="/" のダミーリンクは店舗ページではないので捨てる
        val abs = a?.absUrl("href") ?: ""
        val url = abs.takeIf {
            it.isNotEmpty() && !it.trimEnd('/').equals(BASE, true) && !it.endsWith("#")
        }

        var pref: String? = null
        for (c in li.classNames()) {
            val n = PREF_CLASS.find(c)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            pref = PREFS.getOrNull(n - 1)
        }
        if (pref == null) pref = PREFS.firstOrNull { address.startsWith(it) || name.contains(it) }
        return StoreHit(name, address, url, pref)
    }

    // ───────────────────────── 名前の照合 ─────────────────────────

    /** 全角英数・空白・記号ゆれを吸収して比較用にする。 */
    fun norm(s: String): String {
        val sb = StringBuilder()
        for (ch in s) {
            var c = ch
            if (c in '！'..'～') c = (c.code - 0xFEE0).toChar()
            if (c == '　' || c.isWhitespace()) continue
            // 長音「ー」はカナの一部なので落とさない
            if (c in "・･'’‘`\"“”.,，、。-‐−_~〜") continue
            sb.append(c.lowercaseChar())
        }
        return sb.toString()
    }

    fun artistMatches(artist: String, title: String, query: String): Boolean {
        val q = norm(query)
        if (q.isEmpty()) return true
        return norm(artist).contains(q) || norm(title).contains(q)
    }
}
