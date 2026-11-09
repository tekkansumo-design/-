package com.tekkansumo.ebayscanner

import kotlin.math.floor
import kotlin.math.roundToInt

/** 利益計算に使う条件。設定画面で変えられる。 */
data class ProfitConfig(
    /** eBay 手数料（落札手数料 + 海外手数料 + 決済・為替コストなど）の合計割合。 */
    val feeRate: Double = 0.17,
    /** 1 注文あたりの固定手数料（USD）。 */
    val fixedFeeUsd: Double = 0.40,
    /** 国際送料 + 梱包材などの見込み（円）。 */
    val shippingJpy: Int = 2500,
    /** 1 USD あたりの円。 */
    val fxJpyPerUsd: Double = 150.0,
    /** これ以上の利益が見込めたら知らせる（円）。 */
    val thresholdJpy: Int = 3000,
)

/** eBay の出品価格（送料込み、USD）の統計。 */
data class PriceStats(val count: Int, val median: Double, val low: Double, val high: Double)

object Profit {

    /**
     * 価格の一覧から相場を出す。極端な値（ジャンク品や抱き合わせ）の影響を減らすため
     * 上下 20% を除いてから中央値をとる。
     */
    fun stats(pricesUsd: List<Double>): PriceStats? {
        val s = pricesUsd.filter { it > 0 }.sorted()
        if (s.isEmpty()) return null
        val cut = floor(s.size * 0.2).toInt()
        val core = if (s.size - 2 * cut >= 1) s.subList(cut, s.size - cut) else s
        val mid = core.size / 2
        val median = if (core.size % 2 == 1) core[mid] else (core[mid - 1] + core[mid]) / 2
        return PriceStats(s.size, median, core.first(), core.last())
    }

    /** 売れたときに手元に残る円（仕入れ値を引く前）。 */
    fun netJpy(sellUsd: Double, c: ProfitConfig): Int {
        val afterFees = sellUsd * (1 - c.feeRate) - c.fixedFeeUsd
        return (afterFees * c.fxJpyPerUsd).roundToInt() - c.shippingJpy
    }

    /** 見込み利益（円）。 */
    fun profitJpy(sellUsd: Double, purchaseJpy: Int, c: ProfitConfig): Int =
        netJpy(sellUsd, c) - purchaseJpy

    /** この値段以下で仕入れれば、設定した利益が出る（円）。 */
    fun maxPurchaseJpy(sellUsd: Double, c: ProfitConfig): Int =
        netJpy(sellUsd, c) - c.thresholdJpy
}
