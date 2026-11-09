package com.tekkansumo.ebayscanner

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * フレームの解析結果から「いま写っている商品」と「値札の値段」を決め、
 * eBay の相場を調べて利益を計算する。UI スレッドだけで使う。
 */
class ScanEngine(
    private val prefs: Prefs,
    private val ebay: EbayClient,
    private val scope: CoroutineScope,
    private val ui: Listener,
) {
    enum class Kind(val label: String, val trust: String) {
        GTIN("バーコード", "高"),
        KEYWORD("型番", "中"),
        IMAGE("画像", "低・要確認"),
    }

    data class Target(val kind: Kind, val key: String, val query: String, val model: String? = null)

    data class Lookup(
        val target: Target,
        val items: List<EbayClient.Item>,
        val stats: PriceStats?,
        /** 型番がタイトルに含まれる出品だけに絞れたか。 */
        val narrowed: Boolean,
    )

    data class Eval(
        val sellUsd: Double,
        val sellJpy: Int,
        val profitJpy: Int?,
        val maxPurchaseJpy: Int,
        val hit: Boolean,
    )

    interface Listener {
        fun onPrice(yen: Int?, manual: Boolean)
        fun onTarget(target: Target?, searching: Boolean)
        fun onLookup(lookup: Lookup?, eval: Eval?)
        fun onError(message: String)
        fun onHit(lookup: Lookup, eval: Eval)
    }

    val price = PriceStabilizer()

    var target: Target? = null
        private set
    private var lookup: Lookup? = null

    private val cache = object : LinkedHashMap<String, Lookup>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Lookup>?) = size > 150
    }
    private var job: Job? = null

    private var lastStrongAt = 0L
    private var lastGtinAt = 0L
    private var pendingKeyword: String? = null
    private var prevHash: Long? = null
    private var lastImageHash: Long? = null
    private var lastImageAt = 0L

    /** 失敗した検索は少し待ってからやり直す（毎フレーム通信しないように）。 */
    private val failedAt = HashMap<String, Long>()

    /** 同じ商品・同じ値段で何度も鳴らさない。 */
    private val alerted = HashMap<String, Long>()

    fun wantsImage(): Boolean =
        prefs.imageSearch && prefs.hasCredentials &&
            System.currentTimeMillis() - lastStrongAt > 3000

    fun onFrame(f: FrameAnalyzer.Frame) {
        val now = System.currentTimeMillis()

        if (price.offer(PriceParser.parse(f.lines), now)) {
            ui.onPrice(price.confirmed, price.manual)
            reevaluate()
        }

        // 1. バーコードがあれば最優先（最も確実）
        f.barcodes.firstOrNull()?.let { code ->
            lastStrongAt = now
            lastGtinAt = now
            select(Target(Kind.GTIN, "gtin:$code", code))
            return
        }

        // 2. 型番・ブランド名。読み違いでぶれるので 2 フレーム続けて同じなら採用
        val q = KeywordExtractor.extract(f.text)
        if (q != null) {
            lastStrongAt = now
            if (pendingKeyword == q.text && now - lastGtinAt > 5000) {
                select(Target(Kind.KEYWORD, "q:${q.text}", q.text, q.model))
            }
            pendingKeyword = q.text
            return
        }
        pendingKeyword = null

        // 3. 写真で検索。カメラが止まっていて、前回の画像検索から写っているものが変わったときだけ
        val h = f.hash
        val jpeg = f.jpeg
        val steady = h != null && prevHash != null && ImageHash.distance(h, prevHash!!) <= 6
        prevHash = h
        if (h == null || jpeg == null || !steady || !wantsImage()) return
        if (now - lastImageAt < 4000) return
        lastImageHash?.let { if (ImageHash.distance(it, h) <= 12) return }
        lastImageAt = now
        lastImageHash = h
        select(Target(Kind.IMAGE, "img:${java.lang.Long.toHexString(h)}", "写真から検索"), jpeg)
    }

    private fun select(t: Target, jpeg: ByteArray? = null) {
        if (t.key == target?.key) return
        if (System.currentTimeMillis() - (failedAt[t.key] ?: 0L) < 15_000) return
        target = t
        lookup = null
        job?.cancel()

        cache[t.key]?.let {
            lookup = it
            ui.onTarget(t, false)
            reevaluate()
            return
        }
        if (!prefs.hasCredentials) {
            ui.onTarget(t, false)
            ui.onError("設定で eBay の鍵を入力すると相場を調べられます")
            return
        }
        ui.onTarget(t, true)
        ui.onLookup(null, null)
        job = scope.launch {
            try {
                val id = prefs.clientId
                val sec = prefs.clientSecret
                val jp = prefs.preferJapanSellers
                val items = when (t.kind) {
                    Kind.GTIN -> ebay.searchGtin(t.query, id, sec, jp)
                    Kind.KEYWORD -> ebay.searchKeywords(t.query, id, sec, jp)
                    Kind.IMAGE -> ebay.searchImage(jpeg!!, id, sec, jp)
                }
                var used = items
                var narrowed = false
                if (t.model != null) {
                    val m = items.filter { KeywordExtractor.titleHasModel(it.title, t.model) }
                    if (m.isNotEmpty()) {
                        used = m
                        narrowed = true
                    }
                }
                val l = Lookup(t, used, Profit.stats(used.map { it.totalUsd }), narrowed)
                cache[t.key] = l
                if (target?.key == t.key) {
                    lookup = l
                    ui.onTarget(t, false)
                    reevaluate()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (target?.key == t.key) {
                    ui.onTarget(t, false)
                    ui.onError(e.message ?: "通信エラー")
                    // 失敗した検索は、少し待てばもう一度試せるように外す
                    failedAt[t.key] = System.currentTimeMillis()
                    target = null
                }
            }
        }
    }

    fun setManualPrice(yen: Int?) {
        price.setManual(yen)
        ui.onPrice(price.confirmed, price.manual)
        reevaluate()
    }

    /** 今の商品を忘れて、次に写ったものを調べ直す。 */
    fun clearTarget() {
        job?.cancel()
        target = null
        lookup = null
        lastImageHash = null
        pendingKeyword = null
        ui.onTarget(null, false)
        ui.onLookup(null, null)
    }

    fun reevaluate() {
        val l = lookup
        val s = l?.stats
        if (l == null || s == null) {
            ui.onLookup(l, null)
            return
        }
        val c = prefs.profitConfig()
        val buy = price.confirmed
        val profit = buy?.let { Profit.profitJpy(s.median, it, c) }
        // 出品が少なすぎる相場は当てにならないので鳴らさない
        val hit = profit != null && profit >= c.thresholdJpy && s.count >= 3
        val e = Eval(
            sellUsd = s.median,
            sellJpy = (s.median * c.fxJpyPerUsd).toInt(),
            profitJpy = profit,
            maxPurchaseJpy = Profit.maxPurchaseJpy(s.median, c),
            hit = hit,
        )
        ui.onLookup(l, e)
        if (hit) {
            val k = "${l.target.key}@$buy"
            val now = System.currentTimeMillis()
            if (now - (alerted[k] ?: 0L) > 120_000) {
                alerted[k] = now
                ui.onHit(l, e)
            }
        }
    }
}
