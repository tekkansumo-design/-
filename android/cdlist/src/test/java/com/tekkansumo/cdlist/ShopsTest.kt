package com.tekkansumo.cdlist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** 商品ページの在庫店舗。ここが「店舗から逆に引く」ための足がかりになる。 */
class ShopsTest {

    private val url = "https://shopping.bookoff.co.jp/used/0012345678"

    private val html = """
        <!doctype html><html><body>
        <h1>七人の侍</h1>
        <a href="https://www.bookoff.co.jp/shop/shop10141.html">BOOKOFF 町田中央通り店</a>
        <a href="https://www.bookoff.co.jp/shop/shop10233.html">BOOKOFF 横浜西口店</a>
        <a href="https://www.bookoff.co.jp/shop/shop10999.html">BOOKOFF SUPER BAZAAR 相模原店</a>
        <a href="https://www.bookoff.co.jp/shop/">店舗検索</a>
        <a href="/help/">ヘルプ</a>
        </body></html>
    """.trimIndent()

    @Test
    fun `実店舗のリンクだけを拾う`() {
        val shops = Shops.parse(html, url)
        assertEquals(3, shops.size)
        assertEquals("BOOKOFF 町田中央通り店", shops[0])
        assertFalse(shops.contains("店舗検索"))
    }

    @Test
    fun `店舗名の一部で絞れる`() {
        val shops = Shops.parse(html, url)
        assertEquals(listOf("BOOKOFF 町田中央通り店"), Shops.filter(shops, "町田"))
        assertEquals(emptyList<String>(), Shops.filter(shops, "札幌"))
    }

    @Test
    fun `店舗名が空なら在庫のある店をすべて返す`() {
        val shops = Shops.parse(html, url)
        assertEquals(3, Shops.filter(shops, "").size)
    }

    @Test
    fun `全角半角の違いは無視して絞る`() {
        val shops = listOf("BOOKOFF ＳＵＰＥＲ ＢＡＺＡＡＲ 相模原店")
        assertEquals(1, Shops.filter(shops, "super bazaar").size)
    }
}
