package com.tekkansumo.cdprice

import java.net.URLEncoder

/**
 * 検索先の定義。
 *
 * 各サイトの HTML 構造は予告なく変わるうえ、検索 URL の形も一定ではない。
 * そのため「この CSS セレクタ」と決め打ちにはせず、
 *
 *   1. 検索 URL は候補を順に試し、商品が取れたものを覚える（[Prefs.learned]）
 *   2. 候補が全滅したらトップページの検索フォームから URL を組み立てる
 *   3. それでも駄目なら診断画面から利用者が URL を直接指定できる（[Prefs.override]）
 *
 * という三段構えにしてある。商品の取り出し自体は [Extract] の汎用処理が担う。
 */
data class Site(
    val id: String,
    val label: String,
    /** 商品リンクとして認めるホスト。サブドメインも許す。 */
    val hosts: List<String>,
    /** 検索フォームを探しにいくトップページ。 */
    val home: String,
    /** {q} に URL エンコード済みキーワードが入る。上から順に試す。 */
    val templates: List<String>,
    /** 商品ページらしい URL パス。合致が十分あるときだけ絞り込みに使う。 */
    val itemPath: Regex?,
    /** JavaScript で描画されるためヘッドレス WebView が要る。 */
    val needsJs: Boolean = false,
    /** 中古しか扱わないサイト。中古判定を省ける。 */
    val usedOnly: Boolean = false,
    /** この文字列を含む商品は中古とみなす。 */
    val usedHints: List<String> = listOf("中古", "USED", "used")
) {
    fun hostAllowed(host: String): Boolean =
        hosts.any { host == it || host.endsWith(".$it") }
}

object Sites {

    val BOOKOFF = Site(
        id = "bookoff",
        label = "ブックオフ",
        hosts = listOf("bookoff.co.jp"),
        home = "https://shopping.bookoff.co.jp/",
        templates = listOf(
            "https://shopping.bookoff.co.jp/search/keyword/{q}",
            "https://shopping.bookoff.co.jp/search/keyword/{q}/",
            "https://shopping.bookoff.co.jp/search/?keyword={q}",
            "https://shopping.bookoff.co.jp/search?q={q}"
        ),
        itemPath = Regex("/(used|new|goods)/[0-9]"),
        usedHints = listOf("中古", "USED", "used")
    )

    val NETOFF = Site(
        id = "netoff",
        label = "ネットオフ",
        hosts = listOf("netoff.co.jp"),
        home = "https://www.netoff.co.jp/",
        templates = listOf(
            "https://www.netoff.co.jp/cd/search?searchWord={q}",
            "https://www.netoff.co.jp/search/list?searchWord={q}",
            "https://www.netoff.co.jp/cd/list?searchWord={q}",
            "https://www.netoff.co.jp/search?keyword={q}"
        ),
        itemPath = Regex("/(detail|item|goods)/"),
        usedOnly = true
    )

    val SURUGAYA = Site(
        id = "surugaya",
        label = "駿河屋",
        hosts = listOf("suruga-ya.jp"),
        home = "https://www.suruga-ya.jp/",
        templates = listOf(
            "https://www.suruga-ya.jp/search?category=&search_word={q}",
            "https://www.suruga-ya.jp/search?search_word={q}",
            "https://www.suruga-ya.jp/products-list?search_word={q}"
        ),
        itemPath = Regex("/product/detail/"),
        usedHints = listOf("中古")
    )

    val MERCARI = Site(
        id = "mercari",
        label = "メルカリ",
        hosts = listOf("mercari.com"),
        home = "https://jp.mercari.com/",
        templates = listOf(
            "https://jp.mercari.com/search?keyword={q}&status=on_sale&sort=price&order=asc",
            "https://jp.mercari.com/search?keyword={q}"
        ),
        itemPath = Regex("/(item|shops/product)/"),
        needsJs = true,
        usedOnly = true
    )

    val HMV = Site(
        id = "hmv",
        label = "HMV",
        hosts = listOf("hmv.co.jp"),
        home = "https://www.hmv.co.jp/",
        templates = listOf(
            "https://www.hmv.co.jp/search/keyword_{q}/target_ALL/",
            "https://www.hmv.co.jp/search/keyword_{q}/",
            "https://www.hmv.co.jp/search/?keyword={q}"
        ),
        itemPath = Regex("/product/detail/|/artist_|/item_"),
        usedHints = listOf("中古", "USED", "used", "マーケットプレイス")
    )

    val ALL = listOf(BOOKOFF, NETOFF, SURUGAYA, MERCARI, HMV)

    fun byId(id: String): Site? = ALL.firstOrNull { it.id == id }
}

/**
 * テンプレートの {q} にキーワードを埋める。
 * パスに埋め込む形の URL もあるので、空白は + ではなく %20 にする。
 */
fun fillTemplate(template: String, word: String): String {
    val enc = URLEncoder.encode(word, "UTF-8").replace("+", "%20")
    return template.replace("{q}", enc)
}
