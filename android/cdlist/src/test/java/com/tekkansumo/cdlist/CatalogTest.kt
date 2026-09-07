package com.tekkansumo.cdlist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分類たどり。URL の形を決め打ちしていないので、この作り物のような
 * 「案内リンクに混じってサブジャンルが並ぶ」普通のページなら潜れる。
 */
class CatalogTest {

    private val base = "https://shopping.bookoff.co.jp/genre/dvd"

    private val html = """
        <!doctype html><html><body>
        <header>
          <a href="/">トップ</a><a href="/help/">ヘルプ</a><a href="/mypage/">マイページ</a>
          <a href="/cart/">カート</a>
        </header>
        <nav class="side">
          <a href="/genre/dvd/hoga">邦画</a>
          <a href="/genre/dvd/yoga">洋画</a>
          <a href="/genre/dvd/anime">アニメ</a>
          <a href="/genre/dvd/tokusatsu">特撮</a>
          <a href="/genre/dvd/horror">ホラー</a>
          <a href="/genre/dvd/music">音楽</a>
          <a href="/genre/dvd/kids">キッズ</a>
        </nav>
        <ul class="items">
          <li><a href="/used/0012345678"><img src="a.jpg"></a>
              <a href="/used/0012345678">七人の侍</a>
              <div>中古DVD</div><div>￥1,100(税込)</div><button>カートに入れる</button></li>
          <li><a href="/used/0012345679"><img src="b.jpg"></a>
              <a href="/used/0012345679">羅生門</a>
              <div>￥770</div><div>売り切れ</div></li>
        </ul>
        <div class="pager"><a href="/genre/dvd/hoga?p=1">1</a>
          <a href="/genre/dvd/hoga?p=2">次へ</a></div>
        <footer><a href="/company/">会社概要</a><a href="/privacy/">個人情報</a></footer>
        </body></html>
    """.trimIndent()

    @Test
    fun `並んでいるサブジャンルを拾う`() {
        val g = Catalog.genres(html, base)
        assertEquals(7, g.size)
        assertEquals(listOf("邦画", "洋画", "アニメ"), g.take(3).map { it.label })
        assertEquals("https://shopping.bookoff.co.jp/genre/dvd/hoga", g[0].url)
    }

    @Test
    fun `ヘッダやフッタの案内は分類に混ぜない`() {
        val labels = Catalog.genres(html, base).map { it.label }
        assertFalse(labels.any { it in setOf("ヘルプ", "カート", "マイページ", "会社概要") })
    }

    @Test
    fun `ページ送りは分類として拾わない`() {
        val labels = Catalog.genres(html, base).map { it.label }
        assertFalse(labels.any { it == "1" || it == "次へ" })
    }

    @Test
    fun `同じページから商品も取れる`() {
        val items = Bookoff.parse(html)
        assertEquals(2, items.size)
        assertEquals("七人の侍", items[0].title)
        assertEquals(1100, items[0].price)
        assertTrue(items[1].soldOut)
    }

    @Test
    fun `次のページを見つける`() {
        assertEquals(
            "https://shopping.bookoff.co.jp/genre/dvd/hoga?p=2",
            Catalog.nextPage(html, base)
        )
        assertNull(Catalog.nextPage("<a href='/x'>1</a>", base))
    }

    @Test
    fun `分類が並んでいないページでは分類を出さない`() {
        val one = "<a href='/genre/a'>ひとつ</a><a href='/help/'>ヘルプ</a>"
        assertTrue(Catalog.genres(one, base).isEmpty())
    }

    @Test
    fun `BOOKOFF 以外の URL は開かない`() {
        assertTrue(Catalog.isBookoff("https://shopping.bookoff.co.jp/genre/1"))
        assertFalse(Catalog.isBookoff("https://example.com/"))
        assertFalse(Catalog.isBookoff("file:///etc/passwd"))
        assertEquals(false, Catalog.explore("https://example.com/").getBoolean("ok"))
    }
}
