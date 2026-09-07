package com.tekkansumo.cdlist

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor
import java.net.URLEncoder

/** 検索結果に出てきた商品 1 件。 */
data class Found(
    val pid: String,
    val isNew: Boolean,
    val title: String,
    val price: Int?,
    val soldOut: Boolean
) {
    val url: String get() = Bookoff.ORIGIN + (if (isNew) "/goods/" else "/used/") + pid
}

/**
 * BOOKOFF オンラインの検索結果を読む。
 *
 * 見た目の class 名は変わりやすいので手がかりにしない。商品ページが
 * /used/<番号>（新品は /goods/<番号>）である点だけを頼りに、そのリンクから
 * 次のリンクまでを 1 商品の区切りとみなす。
 */
object Bookoff {

    const val ORIGIN = "https://shopping.bookoff.co.jp"

    /** 検索 URL の形。上から試して、商品が取れた形を覚える。 */
    val TEMPLATES = listOf(
        "$ORIGIN/search/keyword/{kw}",
        "$ORIGIN/search/?keyword={kw}",
        "$ORIGIN/search?keyword={kw}",
        "$ORIGIN/search/?q={kw}"
    )

    @Volatile
    var good: String? = null

    private val PROD = Regex("/(used|goods)/(\\d{6,})")
    private val PRICE = Regex("[¥￥]\\s*([0-9][0-9,]*)|([0-9][0-9,]*)\\s*円")
    private val SOLD = Regex("売り?切れ|品切れ|在庫切れ|在庫がありません|SOLD\\s*OUT",
        RegexOption.IGNORE_CASE)
    private val INSTOCK = Regex("カートに入れる|在庫あり|購入手続き|残り\\s*\\d+")
    private val NOHIT = Regex(
        "該当(する|の)(商品|検索結果)(は)?(ありません|見つかりません)" +
                "|検索結果は?\\s*0\\s*件|0\\s*件でした"
    )
    private val SPACES = Regex("\\s+")

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    fun urlFor(template: String, keyword: String): String =
        template.replace("{kw}", enc(keyword))

    /** 「該当する商品はありません」の類が出ているか。ページ自体は開けている。 */
    fun noHit(html: String): Boolean = NOHIT.containsMatchIn(html)

    fun priceOf(text: String): Int? {
        val m = PRICE.find(text) ?: return null
        val raw = m.groupValues[1].ifEmpty { m.groupValues[2] }.replace(",", "")
        val v = raw.toIntOrNull() ?: return null
        return if (v in 1..9999999) v else null
    }

    private class Blk(val kind: String, val pid: String) {
        val text = StringBuilder()
        val anchor = StringBuilder()
    }

    fun parse(html: String): List<Found> {
        if (html.isBlank()) return emptyList()
        val doc = Jsoup.parse(html, ORIGIN)
        val blocks = ArrayList<Blk>()
        var cur: Blk? = null

        NodeTraversor.traverse(object : NodeVisitor {
            override fun head(node: Node, depth: Int) {
                if (node is Element && node.normalName() == "a") {
                    val m = PROD.find(node.attr("href")) ?: return
                    val pid = m.groupValues[2]
                    var c = cur
                    // 画像リンクと題名リンクで同じ商品が続けて 2 回出る
                    if (c == null || c.pid != pid) {
                        c = Blk(m.groupValues[1], pid)
                        blocks.add(c)
                        cur = c
                    }
                    val t = node.text().trim()
                    // リンクの文字はそれ自体が商品名。周りの「中古CD」等と混ぜない
                    if (t.isNotEmpty() && c.anchor.isEmpty()) c.anchor.append(t)
                } else if (node is TextNode) {
                    val c = cur ?: return
                    if (c.text.length < 800) c.text.append(node.text()).append(' ')
                }
            }

            override fun tail(node: Node, depth: Int) {}
        }, doc.body())

        val out = ArrayList<Found>()
        val seen = HashSet<String>()
        for (b in blocks) {
            if (!seen.add(b.pid)) continue      // 同じ商品が複数枠に出ることがある
            val text = SPACES.replace(b.text.toString(), " ").trim()
            var title = SPACES.replace(b.anchor.toString(), " ").trim()
            if (title.isEmpty()) {
                // 画像だけのリンクなら周りの文字から、価格や状態表示の手前まで拾う
                var cut = text.length
                for (rx in listOf(PRICE, SOLD, INSTOCK)) {
                    val m = rx.find(text)
                    if (m != null && m.range.first < cut) cut = m.range.first
                }
                title = text.substring(0, cut).trim().ifEmpty { text }
            }
            if (title.length > 120) title = title.substring(0, 120)
            out.add(Found(b.pid, b.kind == "goods", title, priceOf(text),
                SOLD.containsMatchIn(text)))
        }
        return out
    }

    /**
     * どの URL の形が生きているか、商品リンクを拾えているかをそのまま出す。
     * サイトの作りが変わって在庫が取れなくなったとき、ここを見れば分かる。
     */
    fun diagnose(keyword: String): String {
        val sb = StringBuilder("キーワード: ").append(keyword).append("\n\n")
        val known = good
        val tries = (if (known != null) listOf(known) else emptyList()) +
                TEMPLATES.filter { it != known }
        for (tpl in tries) {
            val url = urlFor(tpl, keyword)
            val res = Http.get(url)
            if (res.error != null) {
                sb.append("[NG] ").append(url).append("\n     ").append(res.error).append('\n')
                continue
            }
            val items = if (res.code == 200) parse(res.body) else emptyList()
            sb.append('[').append(res.code).append("] ").append(url).append('\n')
                .append("     ").append(res.body.length).append(" 文字 / 商品リンク ")
                .append(items.size).append(" 件 / 該当なし表記 ")
                .append(if (noHit(res.body)) "あり" else "なし").append('\n')
            for (f in items.take(20)) {
                sb.append("     - ").append(f.pid)
                    .append(if (f.isNew) " 新品 " else " 中古 ")
                    .append(f.price ?: 0).append("円 ")
                    .append(if (f.soldOut) "品切" else "在庫").append(" | ")
                    .append(f.title.take(60)).append('\n')
            }
            if (items.isNotEmpty()) {
                sb.append("\n--- 上のページの生 HTML ---\n").append(res.body)
                break
            }
        }
        return sb.toString()
    }

    /**
     * 作品に当たる商品を選ぶ。戻り値は (選んだ商品, 一致した件数)。
     * 一致が無ければ null。無関係な商品の値段を出すより「該当なし」が正しい。
     */
    fun pickBest(items: List<Found>, artist: String, title: String): Pair<Found?, Int> {
        val ak = Match.key(artist)
        val tk = Match.key(title)
        var scored = items.mapNotNull { f ->
            val s = Match.score(f.title, ak, tk)
            if (s >= 3) Pair(s, f) else null
        }
        if (scored.isEmpty()) return Pair(null, 0)
        // アーティスト名まで一致したものがあれば、それ以外は同名の別物とみなす
        if (scored.any { it.first >= 5 }) scored = scored.filter { it.first >= 5 }
        // 在庫あり優先 → 一致度が高い順 → 安い順
        val best = scored.sortedWith(
            compareBy(
                { if (it.second.soldOut) 1 else 0 },
                { -it.first },
                { it.second.price ?: Int.MAX_VALUE }
            )
        ).first()
        return Pair(best.second, scored.size)
    }
}
