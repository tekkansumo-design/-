package com.tekkansumo.cdprice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 抽出が成り立つかを、通販サイトでよくある並び方の疑似 HTML で確かめる。
 *
 * 実サイトの HTML は予告なく変わるので「この CSS セレクタ」は試験できない。
 * 代わりに、どのサイトにも出てくる構造の型
 *   ・サーバ描画のグリッド
 *   ・¥ と数字が別要素に割れている作り
 *   ・定価と中古価格の併記
 *   ・送料やポイントの数字が紛れている
 * を再現して、そこから正しい商品と金額が取れることを押さえる。
 */
class ExtractTest {

    private val bookoffHtml = """
    <html><body>
    <header><p>1,650円以上のご注文で送料無料</p><a href="/search/keyword/x">検索</a></header>
    <ul class="productList">
      <li class="item">
        <a href="/used/0012345678"><img src="/img/a.jpg" alt="First Love"></a>
        <p class="ttl"><a href="/used/0012345678">First Love／宇多田ヒカル</a></p>
        <p class="state">中古</p>
        <p class="price">￥550<span class="tax">(税込)</span></p>
        <p class="pt">5ポイント進呈</p>
      </li>
      <li class="item">
        <a href="/used/0087654321"><img src="/img/b.jpg" alt="DEEP RIVER"></a>
        <p class="ttl"><a href="/used/0087654321">DEEP RIVER／宇多田ヒカル</a></p>
        <p class="state">中古</p>
        <p class="price">￥1,100<span class="tax">(税込)</span></p>
      </li>
      <li class="item">
        <a href="/new/0099999999"><img src="/img/c.jpg" alt="BADモード"></a>
        <p class="ttl"><a href="/new/0099999999">BADモード／宇多田ヒカル</a></p>
        <p class="state">新品</p>
        <p class="price">￥3,300</p>
      </li>
    </ul>
    <aside><h2>おすすめ</h2><ul><li><a href="/used/0011112222">なにか</a></li></ul></aside>
    </body></html>
    """

    private val mercariHtml = """
    <html><body><div id="root"><ul>
      <li data-testid="item-cell">
        <a href="/item/m11111111111" aria-label="宇多田ヒカル First Love CD">
          <figure><img src="https://static.mercdn.net/1.jpg" alt="宇多田ヒカル First Love CD"></figure>
          <div><span>¥</span><span>480</span></div>
        </a>
      </li>
      <li data-testid="item-cell">
        <a href="/item/m22222222222" aria-label="宇多田ヒカル DEEP RIVER 帯付き">
          <figure><img src="https://static.mercdn.net/2.jpg" alt="宇多田ヒカル DEEP RIVER 帯付き"></figure>
          <div><span>¥</span><span>1,250</span></div>
        </a>
      </li>
    </ul></div></body></html>
    """

    private val surugayaHtml = """
    <html><body><div class="search_result">
      <div class="item">
        <div class="thumb"><a href="/product/detail/123456789"><img src="/pics/1.jpg" alt=""></a></div>
        <p class="title"><a href="/product/detail/123456789">【中古】First Love / 宇多田ヒカル</a></p>
        <p><span class="text-muted">定価： 3,059円</span></p>
        <p class="price">中古：<span class="adjust_price">680円</span></p>
      </div>
      <div class="item">
        <div class="thumb"><a href="/product/detail/987654321"><img src="/pics/2.jpg" alt=""></a></div>
        <p class="title"><a href="/product/detail/987654321">【中古】DEEP RIVER / 宇多田ヒカル</a></p>
        <p class="price">中古：<span class="adjust_price">1,480円</span></p>
      </div>
    </div></body></html>
    """

    @Test
    fun `サーバ描画のグリッドから商品を取れる`() {
        val hits = Extract.extract(
            Sites.BOOKOFF, "https://shopping.bookoff.co.jp/search/keyword/x", bookoffHtml
        )
        assertEquals(3, hits.size)
        assertEquals(550, hits.first().price)
        assertEquals(
            "https://shopping.bookoff.co.jp/used/0012345678", hits.first().url
        )
        assertEquals("https://shopping.bookoff.co.jp/img/a.jpg", hits.first().image)
    }

    @Test
    fun `題名はサムネの alt ではなくリンクの文字から取る`() {
        val hits = Extract.extract(
            Sites.BOOKOFF, "https://shopping.bookoff.co.jp/search/keyword/x", bookoffHtml
        )
        assertEquals("First Love／宇多田ヒカル", hits.first { it.price == 550 }.title)
    }

    @Test
    fun `ポイントや送料の数字を価格にしない`() {
        val hits = Extract.extract(
            Sites.BOOKOFF, "https://shopping.bookoff.co.jp/search/keyword/x", bookoffHtml
        )
        assertTrue(hits.none { it.price == 5 })      // 5ポイント進呈
        assertTrue(hits.none { it.price == 1650 })   // 1,650円以上で送料無料
    }

    @Test
    fun `価格のない推薦枠は結果に混ざらない`() {
        val hits = Extract.extract(
            Sites.BOOKOFF, "https://shopping.bookoff.co.jp/search/keyword/x", bookoffHtml
        )
        assertTrue(hits.none { it.url.contains("0011112222") })
    }

    @Test
    fun `中古と新品を見分ける`() {
        val hits = Extract.extract(
            Sites.BOOKOFF, "https://shopping.bookoff.co.jp/search/keyword/x", bookoffHtml
        )
        assertTrue(hits.first { it.price == 550 }.used)
        assertFalse(hits.first { it.price == 3300 }.used)
    }

    @Test
    fun `円記号と数字が別要素に割れていても読める`() {
        val hits = Extract.extract(
            Sites.MERCARI, "https://jp.mercari.com/search?keyword=x", mercariHtml
        )
        assertEquals(2, hits.size)
        assertEquals(480, hits.first().price)
        assertTrue(hits.first().title.contains("First Love"))
        assertTrue(hits.all { it.used })
    }

    @Test
    fun `定価が併記されていても中古価格のほうを取る`() {
        val hits = Extract.extract(
            Sites.SURUGAYA, "https://www.suruga-ya.jp/search?search_word=x", surugayaHtml
        )
        assertEquals(2, hits.size)
        assertEquals(680, hits.first().price)
        assertEquals("【中古】First Love / 宇多田ヒカル", hits.first().title)
    }

    @Test
    fun `描画前で中身が無いページでも落ちない`() {
        val hits = Extract.extract(
            Sites.BOOKOFF, "https://shopping.bookoff.co.jp/",
            "<html><body><div id=\"app\"></div></body></html>"
        )
        assertTrue(hits.isEmpty())
    }

    @Test
    fun `キーワードは URL に入る形で埋め込まれる`() {
        assertEquals(
            "https://jp.mercari.com/search?keyword=%E5%AE%87%E5%A4%9A%E7%94%B0",
            fillTemplate("https://jp.mercari.com/search?keyword={q}", "宇多田")
        )
        assertEquals(
            "https://x/a%20b",
            fillTemplate("https://x/{q}", "a b")
        )
    }
}
