package com.tekkansumo.cdlist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 検索結果の読み取り。相手のページを実際に叩かずに確かめられるよう、
 * 作り物の HTML を相手にする。class 名に頼らない作りなので、この形が
 * 変わっても /used/<番号> さえ残っていれば拾える。
 */
class BookoffTest {

    private val html = """
        <!doctype html><html><head><title>検索結果</title>
        <script>var x="/used/9999999999";</script></head><body>
        <header><a href="/">BOOKOFF</a><a href="/search/">検索</a></header>
        <ul class="productList">
         <li class="item">
           <a href="/used/0016309421"><img src="/img/a.jpg" alt=""></a>
           <div class="ttl"><a href="/used/0016309421">宇多田ヒカル／First Love</a></div>
           <div class="cond">中古CD</div>
           <div class="price">￥550<span class="tax">(税込)</span></div>
           <div class="btn"><button>カートに入れる</button></div>
         </li>
         <li class="item">
           <a href="/used/0016309422"><img src="/img/b.jpg" alt=""></a>
           <div class="ttl"><a href="/used/0016309422">宇多田ヒカル／First Love (初回限定盤)</a></div>
           <div class="price">1,320 円</div>
           <div class="soldout">売り切れ</div>
         </li>
         <li class="item">
           <a href="/goods/0026309430"><img src="/img/c.jpg"></a>
           <div class="ttl"><a href="/goods/0026309430">宇多田ヒカル／Distance</a></div>
           <div class="price">￥2,200</div><button>カートに入れる</button>
         </li>
         <li class="item">
           <a href="/used/0016309999"><img src="/img/d.jpg"></a>
           <div class="ttl"><a href="/used/0016309999">First Love 楽譜集</a></div>
           <div class="price">￥300</div>
         </li>
        </ul>
        <footer><a href="/help">ヘルプ</a></footer></body></html>
    """.trimIndent()

    @Test
    fun `商品を4件拾い、script の中の番号は拾わない`() {
        val items = Bookoff.parse(html)
        assertEquals(4, items.size)
        assertFalse(items.any { it.pid == "9999999999" })
    }

    @Test
    fun `題名はリンクの文字を使い、周りの「中古CD」を混ぜない`() {
        val f = Bookoff.parse(html).first()
        assertEquals("0016309421", f.pid)
        assertEquals("宇多田ヒカル／First Love", f.title)
        assertEquals("https://shopping.bookoff.co.jp/used/0016309421", f.url)
    }

    @Test
    fun `値段は円表記でも￥表記でも読む`() {
        val items = Bookoff.parse(html)
        assertEquals(550, items[0].price)
        assertEquals(1320, items[1].price)
        assertEquals(2200, items[2].price)
    }

    @Test
    fun `売り切れと新品を見分ける`() {
        val items = Bookoff.parse(html)
        assertFalse(items[0].soldOut)
        assertTrue(items[1].soldOut)
        assertFalse(items[0].isNew)
        assertTrue(items[2].isNew)
    }

    @Test
    fun `在庫のあるものを先に選び、同名の別物は落とす`() {
        val items = Bookoff.parse(html)
        val (best, hits) = Bookoff.pickBest(items, "宇多田ヒカル", "First Love")
        assertEquals("0016309421", best?.pid)
        assertEquals(2, hits)          // 「First Love 楽譜集」は数に入れない
    }

    @Test
    fun `関わりのない作品は拾わない`() {
        val items = Bookoff.parse(html)
        assertNull(Bookoff.pickBest(items, "宇多田ヒカル", "ULTRA BLUE").first)
    }

    @Test
    fun `該当なしのページは商品0件で、その旨を見分けられる`() {
        val none = "<html><body><p>該当する商品はありませんでした。</p></body></html>"
        assertEquals(0, Bookoff.parse(none).size)
        assertTrue(Bookoff.noHit(none))
        assertFalse(Bookoff.noHit(html))
    }

    @Test
    fun `全角半角と記号の違いは無視して突き合わせる`() {
        assertEquals(Match.key("first love"), Match.key("ＦＩＲＳＴ　ＬＯＶＥ！"))
        assertTrue(Match.score("宇多田ヒカル／First Love", Match.key("宇多田ヒカル"),
            Match.key("First Love")) >= 5)
    }

    @Test
    fun `検索URLは日本語をそのまま繋げず組み立てる`() {
        assertEquals(
            "https://shopping.bookoff.co.jp/search/keyword/%E5%85%89",
            Bookoff.urlFor(Bookoff.TEMPLATES[0], "光")
        )
    }
}
