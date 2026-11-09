package com.tekkansumo.ebayscanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PriceParserTest {
    private fun l(t: String, h: Int = 20) = PriceParser.Line(t, h)

    @Test fun yenPrefix() = assertEquals(1980, PriceParser.parse(listOf(l("¥1,980"))))
    @Test fun yenSuffix() = assertEquals(550, PriceParser.parse(listOf(l("550円"))))
    @Test fun fullWidth() = assertEquals(2178, PriceParser.parse(listOf(l("税込２，１７８円"))))
    @Test fun backslashAsYen() = assertEquals(3300, PriceParser.parse(listOf(l("\\3,300"))))
    @Test fun spaceAfterComma() = assertEquals(1980, PriceParser.parse(listOf(l("¥1, 980"))))

    @Test fun prefersTaxIncluded() {
        val lines = listOf(l("本体価格 2,000円", 30), l("税込 2,200円", 24))
        assertEquals(2200, PriceParser.parse(lines))
    }

    @Test fun ignoresListPrice() {
        val lines = listOf(l("定価 ¥5,800", 26), l("¥1,100", 26))
        assertEquals(1100, PriceParser.parse(lines))
    }

    @Test fun biggestWins() {
        val lines = listOf(l("¥300", 12), l("¥2,480", 60))
        assertEquals(2480, PriceParser.parse(lines))
    }

    @Test fun noPrice() {
        assertNull(PriceParser.parse(listOf(l("4901234567894"), l("SONY WH-1000XM4"))))
    }

    @Test fun stabilizerNeedsTwo() {
        val s = PriceStabilizer()
        assertFalse(s.offer(1980, 0))
        assertTrue(s.offer(1980, 100))
        assertEquals(1980, s.confirmed)
        s.offer(null, 200)
        assertEquals(1980, s.confirmed) // 少し見失っても覚えている
        s.offer(null, 40_000)
        assertNull(s.confirmed)
    }

    @Test fun stabilizerManual() {
        val s = PriceStabilizer()
        s.setManual(500)
        s.offer(900, 0); s.offer(900, 1)
        assertEquals(500, s.confirmed)
    }
}

class KeywordExtractorTest {
    @Test fun brandAndModel() {
        val q = KeywordExtractor.extract("ソニー SONY ワイヤレスヘッドホン WH-1000XM4 ブラック 4548736112063")
        assertNotNull(q)
        assertEquals("SONY WH-1000XM4", q!!.text)
        assertEquals("WH-1000XM4", q.model)
    }

    @Test fun skipsUnits() {
        val q = KeywordExtractor.extract("容量 500ML 2024 MADE IN JAPAN 1000MAH")
        assertNull(q)
    }

    @Test fun brandAndWords() {
        val q = KeywordExtractor.extract("BANDAI TAMAGOTCHI ORIGINAL")
        assertEquals("BANDAI TAMAGOTCHI ORIGINAL", q!!.text)
    }

    @Test fun nothingUseful() = assertNull(KeywordExtractor.extract("ポケットモンスター カードゲーム"))

    @Test fun titleMatch() {
        assertTrue(KeywordExtractor.titleHasModel("Sony WH1000XM4 Wireless Headphones", "WH-1000XM4"))
        assertFalse(KeywordExtractor.titleHasModel("Sony WH-1000XM5", "WH-1000XM4"))
    }
}

class ProfitTest {
    private val c = ProfitConfig(feeRate = 0.17, fixedFeeUsd = 0.40, shippingJpy = 2500, fxJpyPerUsd = 150.0, thresholdJpy = 3000)

    @Test fun statsTrimsOutliers() {
        val s = Profit.stats(listOf(1.0, 40.0, 42.0, 45.0, 48.0, 50.0, 52.0, 55.0, 60.0, 500.0))!!
        assertEquals(10, s.count)
        assertEquals(49.0, s.median, 1e-9)
    }

    @Test fun profit() {
        // $60 → 60*0.83-0.4 = 49.4 USD → 7410円 → 送料引いて 4910円
        assertEquals(4910, Profit.netJpy(60.0, c))
        assertEquals(3910, Profit.profitJpy(60.0, 1000, c))
        assertEquals(1910, Profit.maxPurchaseJpy(60.0, c))
    }

    @Test fun hash() {
        val a = IntArray(64) { if (it < 32) 10 else 200 }
        val b = IntArray(64) { if (it < 30) 10 else 200 }
        assertEquals(2, ImageHash.distance(ImageHash.fromGray(a), ImageHash.fromGray(b)))
    }
}
