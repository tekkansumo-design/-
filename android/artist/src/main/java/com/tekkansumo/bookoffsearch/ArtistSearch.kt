package com.tekkansumo.bookoffsearch

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * アーティスト検索の本体。
 *
 *   1. CD / DVD のジャンルで中古商品を検索し、全ページの一覧を取る
 *   2. 各商品ページを開いて入荷店舗と JAN コードを取る
 *   3. 入荷店舗がある商品だけ、JAN で Amazon を引いて中古最安値を取る
 *   4. 「Amazon 中古最安値 − ブックオフ中古価格」が閾値以上の商品だけ残す
 *   5. 店舗ごとに商品をまとめ、在庫商品数の多い順に並べる
 *
 * Amazon は TLS の指紋でボットを弾くので、OkHttp ではなく画面側の WebView
 * （本物の Chrome）から取る。amazonFetch が無いとき（画面が閉じている）は未取得にする。
 *
 * 画面は stateJson() を定期的に読むだけ（version が変わったときだけ中身を作る）。
 */
object ArtistSearch {

    /** ブックオフのジャンルコード。音楽ソフト=31、映像ソフト（DVD・BD）=71。 */
    val GENRES = linkedMapOf("cd" to ("31" to "CD"), "dvd" to ("71" to "DVD"))

    private const val UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
    private const val PER_PAGE = 120
    private const val MAX_PAGES = 40          // 1 ジャンル 4800 件で打ち切り
    private const val WORKERS = 3
    private const val MAX_TRIES = 6
    private const val AOD_PAGES = 3           // 全出品は 1 ページ 10 件前後。中古を探すのはここまで
    private val BACKOFF = intArrayOf(10, 20, 40, 60, 90)

    data class Params(
        val artist: String,
        val genres: List<String>,
        val minDiff: Int,
        val byAuthor: Boolean,
        val strict: Boolean
    )

    /** 1 商品の進み具合。stores が null なら商品ページ未取得。 */
    private class Row(val p: Product, val genre: String) {
        @Volatile var stores: List<StoreHit>? = null
        @Volatile var declared: Int? = null
        @Volatile var viaWeb = false
        @Volatile var jan: String? = null
        @Volatile var asin: String? = null
        @Volatile var amazon: Int? = null
        /** Amazon が取れなかった理由（JAN なし / 見つからない / 中古なし / ブロック など）。 */
        @Volatile var amazonNote: String? = null
        @Volatile var error: String? = null
        @Volatile var finished = false

        val gap: Int? get() {
            val a = amazon ?: return null
            val b = p.price ?: return null
            return a - b
        }
    }

    /** JS 描画のときに WebView で開いて描画後の HTML を返す。画面が無いときは null。 */
    @Volatile var renderer: ((String) -> String?)? = null

    /** Amazon の URL を WebView から取って (HTTP ステータス, 本文) を返す。画面が無いときは null。 */
    @Volatile var amazonFetch: ((String) -> Pair<Int, String>?)? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val lock = Object()
    private val cancelMon = Object()
    private val amazonLock = Object()   // Amazon へは 1 本ずつ

    @Volatile var running = false; private set
    @Volatile private var cancelled = false
    private val version = AtomicInteger(0)

    private var params: Params? = null
    private var phase = "idle"          // idle / list / stores / done / cancelled / error
    private var message = ""
    private val rows = ArrayList<Row>()  // 一覧で見つけた中古商品（名前の絞り込み後）
    private var unmatched = 0            // アーティスト名が一致せず除外した数
    private var doneCount = 0
    @Volatile private var amazonBlocked = 0   // 連続でロボット確認になった回数

    /** 同じ JAN を何度も引かないよう、プロセスが生きている間は覚えておく。 */
    private val amazonCache = HashMap<String, Triple<String?, Int?, String?>>()

    private fun touch() = version.incrementAndGet()

    private fun setMsg(s: String) {
        synchronized(lock) { message = s }
        touch()
    }

    fun start(p: Params): Boolean {
        synchronized(lock) {
            if (running) return false
            running = true
            params = p
            phase = "list"
            message = "検索中…"
            unmatched = 0; doneCount = 0; amazonBlocked = 0
            rows.clear()
        }
        synchronized(cancelMon) { cancelled = false }
        touch()
        Thread({ run(p) }, "artist-main").apply { isDaemon = true }.start()
        return true
    }

    fun cancel() {
        synchronized(cancelMon) {
            cancelled = true
            cancelMon.notifyAll()
        }
    }

    private fun sleepCancellable(sec: Double) {
        val end = System.currentTimeMillis() + (sec * 1000).toLong()
        synchronized(cancelMon) {
            while (!cancelled) {
                val left = end - System.currentTimeMillis()
                if (left <= 0) return
                try { cancelMon.wait(left) } catch (e: InterruptedException) { return }
            }
        }
    }

    private fun run(p: Params) {
        var endPhase = "done"
        try {
            collect(p)
            if (!cancelled) fetchAll()
            if (cancelled) endPhase = "cancelled"
        } catch (e: Exception) {
            endPhase = "error"
            setMsg("エラー: ${e.javaClass.simpleName} ${e.message ?: ""}")
        } finally {
            synchronized(lock) {
                phase = endPhase
                if (endPhase == "done") {
                    message = "完了"
                } else if (endPhase == "cancelled") {
                    message = "中止しました"
                }
                running = false
            }
            touch()
        }
    }

    // ───────────────────────── 1. 一覧 ─────────────────────────

    /** 検索 URL。サイトの検索フォームと同じ組み立て（/search/stock/used/genre/31/author/…）。 */
    fun searchUrl(p: Params, genreCode: String): HttpUrl {
        val b = ArtistParser.BASE.toHttpUrl().newBuilder()
            .addPathSegment("search")
            .addPathSegment("stock").addPathSegment("used")
            .addPathSegment("genre").addPathSegment(genreCode)
            .addPathSegment(if (p.byAuthor) "author" else "keyword")
            .addPathSegment(encodeMark(p.artist.trim()))
            .addQueryParameter("per-page", PER_PAGE.toString())
        return b.build()
    }

    /** サイトの encodeMark() 相当。? / # % は二重エンコードして渡す。 */
    private fun encodeMark(s: String) = s.replace("%", "%25")
        .replace("?", "%3F").replace("/", "%2F").replace("#", "%23")

    private fun collect(p: Params) {
        val seen = HashSet<String>()
        for (g in p.genres) {
            val (code, label) = GENRES[g] ?: continue
            var url: String? = searchUrl(p, code).toString()
            var page = 1
            while (url != null && page <= MAX_PAGES && !cancelled) {
                setMsg("$label の一覧を取得中（${page}ページ目）")
                val html = get(url, ArtistParser.BASE + "/") ?: break
                val sp = ArtistParser.parseSearch(html, url)
                synchronized(lock) {
                    for (it in sp.items) {
                        if (!seen.add(it.pid)) continue
                        if (p.strict && !ArtistParser.artistMatches(it.artist, it.title, p.artist)) {
                            unmatched++
                        } else {
                            rows.add(Row(it, label))
                        }
                    }
                }
                touch()
                if (sp.items.isEmpty()) break
                url = sp.next
                page++
            }
        }
    }

    // ───────────────────────── 2. 商品ページと Amazon ─────────────────────────

    private fun fetchAll() {
        val targets = synchronized(lock) {
            phase = "stores"
            ArrayList(rows)
        }
        touch()
        if (targets.isEmpty()) return

        val idx = AtomicInteger(0)
        val threads = (1..WORKERS).map { n ->
            Thread({
                while (!cancelled) {
                    val i = idx.getAndIncrement()
                    if (i >= targets.size) break
                    val r = targets[i]
                    try {
                        fetchStores(r)
                        if (!cancelled && !r.stores.isNullOrEmpty()) fetchAmazon(r)
                    } catch (e: Exception) {
                        r.error = "internal: ${e.javaClass.simpleName}"
                    }
                    r.finished = true
                    synchronized(lock) {
                        doneCount++
                        message = "確認中 $doneCount / ${targets.size}"
                    }
                    touch()
                }
            }, "artist-$n").apply { isDaemon = true }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
    }

    private fun fetchStores(r: Row) {
        val html = get(r.p.url, ArtistParser.BASE + "/") ?: run {
            if (r.error == null) r.error = if (cancelled) "中止" else "ブックオフの商品ページを取得できませんでした"
            return
        }
        r.jan = ArtistParser.parseJan(html)
        var info = ArtistParser.parseStores(html, r.p.url)

        // 見出しでは入荷があるのに一覧が空、またはモーダル自体が無い → JS 描画を疑う
        val suspicious = info.stores.isEmpty() &&
                ((info.declared ?: 0) > 0 || !info.modalFound)
        if (suspicious) {
            val rendered = renderer?.invoke(r.p.url)
            if (rendered != null) {
                val w = ArtistParser.parseStores(rendered, r.p.url)
                if (w.stores.isNotEmpty() || w.declared != null) {
                    info = w
                    r.viaWeb = true
                }
                if (r.jan == null) r.jan = ArtistParser.parseJan(rendered)
            }
        }
        r.declared = info.declared
        r.stores = info.stores
        if (info.stores.isEmpty() && (info.declared ?: 0) > 0) {
            r.error = "入荷${info.declared}店と表示されているが店舗名を読めませんでした"
        }
    }

    private fun fetchAmazon(r: Row) {
        val jan = r.jan
        if (jan == null) {
            r.amazonNote = "JANコードが見つからず Amazon を検索できません"
            return
        }
        synchronized(amazonCache) { amazonCache[jan] }?.let { (asin, price, note) ->
            r.asin = asin; r.amazon = price; r.amazonNote = note
            return
        }
        synchronized(amazonLock) {
            if (cancelled) return
            val (asin, price, note) = amazonLookup(jan)
            r.asin = asin; r.amazon = price; r.amazonNote = note
            // ブロックや通信失敗は次回やり直せるように覚えない
            if (price != null || note == NOTE_NOT_FOUND || note == NOTE_NO_USED) {
                synchronized(amazonCache) { amazonCache[jan] = Triple(asin, price, note) }
            }
        }
    }

    private const val NOTE_NOT_FOUND = "Amazon に該当商品が見つかりません"
    private const val NOTE_NO_USED = "Amazon に中古の出品がありません"

    /** JAN → ASIN → 中古最安値。戻り値は (ASIN, 価格, 取れなかった理由)。 */
    private fun amazonLookup(jan: String): Triple<String?, Int?, String?> {
        val search = amazonGet(AmazonParser.searchUrl(jan), "Amazon で検索中")
            ?: return Triple(null, null, amazonFailNote())
        val asin = AmazonParser.firstAsin(search) ?: return Triple(null, null, NOTE_NOT_FOUND)

        // 全出品（中古で絞る）→ 絞らずに数ページ → 商品ページ、の順で中古を探す
        var min: Int? = null
        var gotAny = false
        amazonGet(AmazonParser.aodUrl(asin, 1, true), "Amazon の中古出品を確認中")?.let {
            gotAny = true
            min = AmazonParser.usedMinFromAod(it)
        }
        if (min == null) {
            for (page in 1..AOD_PAGES) {
                val h = amazonGet(AmazonParser.aodUrl(asin, page, false), "Amazon の出品を確認中") ?: break
                gotAny = true
                AmazonParser.usedMinFromAod(h)?.let { m -> min = if (min == null) m else minOf(min!!, m) }
                if (!AmazonParser.aodHasNext(h) || AmazonParser.aodOfferCount(h) == 0) break
            }
        }
        if (min == null) {
            amazonGet(AmazonParser.productUrl(asin), "Amazon の商品ページを確認中")?.let {
                gotAny = true
                min = AmazonParser.usedMinFromProduct(it)
            }
        }
        return when {
            min != null -> Triple(asin, min, null)
            gotAny -> Triple(asin, null, NOTE_NO_USED)
            else -> Triple(asin, null, amazonFailNote())
        }
    }

    private fun amazonFailNote() = if (amazonBlocked > 0)
        "Amazon にロボット確認を出されて取得できませんでした" else "Amazon から取得できませんでした"

    /** Amazon の 1 ページ。ロボット確認・失敗は待って 2 回まで再試行。 */
    private fun amazonGet(url: String, label: String): String? {
        val fetch = amazonFetch ?: return null
        for (attempt in 1..3) {
            if (cancelled) return null
            setMsg("$label（確認 $doneCount / ${rows.size}）")
            sleepCancellable(0.8 + Math.random() * 0.8)   // 間隔を空けて人の操作に近づける
            val res = fetch(url)
            if (res != null && res.first in 200..299 && !AmazonParser.isBlocked(res.second)) {
                amazonBlocked = 0
                return res.second
            }
            if (res != null && res.first == 404) return null
            if (res != null && AmazonParser.isBlocked(res.second)) amazonBlocked++
            val w = 15 * attempt
            setMsg("Amazon の応答待ち（${res?.first ?: "通信エラー"}）${w}秒待って再試行")
            sleepCancellable(w.toDouble())
        }
        return null
    }

    /** 200 の本文を返す。403/429/503・通信エラーは待って再試行、404 などは null。 */
    private fun get(url: String, referer: String): String? {
        var tries = 0
        while (!cancelled && tries < MAX_TRIES) {
            tries++
            var code = -1
            var body: String? = null
            var netErr: String? = null
            try {
                val req = Request.Builder().url(url)
                    .header("User-Agent", UA)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "ja,en-US;q=0.9,en;q=0.8")
                    .header("Referer", referer)
                    .build()
                client.newCall(req).execute().use { resp ->
                    code = resp.code
                    if (code == 200) body = resp.body?.string()
                }
            } catch (e: Exception) {
                netErr = e.javaClass.simpleName
            }
            if (body != null) return body
            if (code == 404 || code == 410) return null
            val w = BACKOFF[minOf(tries - 1, BACKOFF.size - 1)]
            setMsg((if (netErr != null) "通信エラー ($netErr)" else "HTTP $code") + " — ${w}秒待って再試行")
            sleepCancellable(w + Math.random() * 2)
        }
        return null
    }

    // ───────────────────────── 画面向け ─────────────────────────

    fun version() = version.get()

    fun stateJson(): JSONObject = synchronized(lock) {
        val p = params
        val min = p?.minDiff ?: 500
        val stores = LinkedHashMap<String, JSONObject>()
        val counts = HashMap<String, Int>()
        val products = JSONArray()

        var withStores = 0; var noStores = 0; var noAmazon = 0; var below = 0
        for (r in rows) {
            if (!r.finished) continue
            val list = r.stores
            when {
                list.isNullOrEmpty() -> noStores++
                r.amazon == null -> noAmazon++
                (r.gap ?: Int.MIN_VALUE) < min -> below++
            }
            if (!list.isNullOrEmpty()) withStores++
        }

        // 条件を満たした商品だけを画面に出す
        val hits = rows.filter { r -> r.finished && !r.stores.isNullOrEmpty() && (r.gap ?: Int.MIN_VALUE) >= min }
        // Amazon が取れなかった在庫あり商品は、理由が分かるよう別に出す
        val misses = rows.filter { r -> r.finished && !r.stores.isNullOrEmpty() && r.amazon == null }

        fun productJson(i: Int, r: Row, hit: Boolean) = JSONObject().apply {
            put("i", i)
            put("hit", hit)
            put("pid", r.p.pid)
            put("url", r.p.url)
            put("title", r.p.title)
            put("artist", r.p.artist)
            put("genre", r.genre)
            put("price", r.p.price ?: JSONObject.NULL)
            put("amazon", r.amazon ?: JSONObject.NULL)
            put("gap", r.gap ?: JSONObject.NULL)
            put("amzUrl", r.asin?.let { AmazonParser.productUrl(it) } ?: JSONObject.NULL)
            put("jan", r.jan ?: JSONObject.NULL)
            put("online", r.p.onlineStock)
            put("count", r.stores?.size ?: 0)
            put("error", (r.amazonNote ?: r.error) ?: JSONObject.NULL)
        }

        for ((i, r) in hits.withIndex()) {
            products.put(productJson(i, r, true))
            for (s in r.stores ?: continue) {
                val key = s.name + "|" + s.address
                val o = stores.getOrPut(key) {
                    JSONObject().apply {
                        put("name", s.name)
                        put("address", s.address)
                        put("url", s.url ?: JSONObject.NULL)
                        put("pref", s.pref ?: JSONObject.NULL)
                        put("items", JSONArray())
                    }
                }
                o.getJSONArray("items").put(i)
                counts[key] = (counts[key] ?: 0) + 1
            }
        }
        for ((k, r) in misses.withIndex()) products.put(productJson(hits.size + k, r, false))

        // 在庫商品数の多い順。同数なら都道府県順→店名
        val sorted = stores.entries.sortedWith(
            compareByDescending<Map.Entry<String, JSONObject>> { counts[it.key] ?: 0 }
                .thenBy { ArtistParser.PREFS.indexOf(it.value.optString("pref")).let { n -> if (n < 0) 99 else n } }
                .thenBy { it.value.optString("name") }
        )
        val storeArr = JSONArray()
        for (e in sorted) storeArr.put(e.value)

        JSONObject().apply {
            put("version", version.get())
            put("running", running)
            put("phase", phase)
            put("message", message)
            put("artist", p?.artist ?: "")
            put("minDiff", min)
            put("listed", rows.size)
            put("unmatched", unmatched)
            put("done", doneCount)
            put("withStores", withStores)
            put("noStores", noStores)
            put("noAmazon", noAmazon)
            put("below", below)
            put("targets", hits.size)
            put("products", products)
            put("stores", storeArr)
        }
    }

    /** 共有用のテキスト（店舗ランキングと商品別）。 */
    fun shareText(prefFilter: String?): String {
        val st = stateJson()
        val products = st.getJSONArray("products")
        val sb = StringBuilder()
        sb.append("BOOKOFF 「${st.optString("artist")}」 Amazon中古最安値より${st.optInt("minDiff")}円以上安い CD/DVD の在庫店舗\n")
        if (!prefFilter.isNullOrEmpty()) sb.append("（$prefFilter のみ）\n")
        sb.append('\n')
        val stores = st.getJSONArray("stores")
        var rank = 0
        for (k in 0 until stores.length()) {
            val s = stores.getJSONObject(k)
            if (!prefFilter.isNullOrEmpty() && s.optString("pref") != prefFilter) continue
            val items = s.getJSONArray("items")
            rank++
            sb.append("$rank. ${s.optString("name")}（${items.length()}点）${s.optString("address")}\n")
            for (j in 0 until items.length()) {
                sb.append("   ・").append(productLine(products.getJSONObject(items.getInt(j)))).append('\n')
            }
        }

        // 商品別の在庫店舗（店舗の並びは在庫数の多い順のまま）
        val byProduct = HashMap<Int, MutableList<String>>()
        for (k in 0 until stores.length()) {
            val s = stores.getJSONObject(k)
            if (!prefFilter.isNullOrEmpty() && s.optString("pref") != prefFilter) continue
            val items = s.getJSONArray("items")
            for (j in 0 until items.length()) {
                byProduct.getOrPut(items.getInt(j)) { ArrayList() }.add(s.optString("name"))
            }
        }
        if (byProduct.isNotEmpty()) {
            sb.append("\n■ 商品別\n")
            for (i in byProduct.keys.sortedByDescending { byProduct[it]!!.size }) {
                val names = byProduct[i]!!
                sb.append("・").append(productLine(products.getJSONObject(i))).append("（${names.size}店）\n")
                sb.append("   ${names.joinToString("、")}\n")
            }
        }
        return sb.toString()
    }

    private fun productLine(p: JSONObject): String {
        val sb = StringBuilder("[${p.optString("genre")}] ${p.optString("title")}")
        if (!p.isNull("price")) sb.append(" ブックオフ¥${p.optInt("price")}")
        if (!p.isNull("amazon")) sb.append(" / Amazon中古¥${p.optInt("amazon")}")
        if (!p.isNull("gap")) sb.append("（差${p.optInt("gap")}円）")
        return sb.toString()
    }
}
