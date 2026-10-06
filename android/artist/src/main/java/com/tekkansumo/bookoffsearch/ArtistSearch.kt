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
 *   2. 「定価より N 円おトク」の N が閾値以上の商品だけ残す
 *   3. その商品ページを開いて入荷店舗を取る
 *   4. 店舗ごとに商品をまとめ、在庫商品数の多い順に並べる
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
    private val BACKOFF = intArrayOf(10, 20, 40, 60, 90)

    data class Params(
        val artist: String,
        val genres: List<String>,
        val minDiff: Int,
        val byAuthor: Boolean,
        val strict: Boolean
    )

    /** 商品ページの取得結果。stores が null なら未取得。 */
    private class Row(val p: Product, val genre: String) {
        @Volatile var stores: List<StoreHit>? = null
        @Volatile var declared: Int? = null
        @Volatile var error: String? = null
        @Volatile var viaWeb = false
    }

    /** JS 描画のときに WebView で開いて描画後の HTML を返す。画面が無いときは null。 */
    @Volatile var renderer: ((String) -> String?)? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val lock = Object()
    private val cancelMon = Object()

    @Volatile var running = false; private set
    @Volatile private var cancelled = false
    private val version = AtomicInteger(0)

    private var params: Params? = null
    private var phase = "idle"          // idle / list / stores / done / cancelled / error
    private var message = ""
    private var listed = 0               // 一覧で見つけた中古商品数
    private val rows = ArrayList<Row>()  // 価格差が閾値以上の商品
    private var below = 0                // 閾値未満で除外した数
    private var noDiff = 0               // おトク表記が無く判定できなかった数
    private var unmatched = 0            // アーティスト名が一致せず除外した数
    private var doneCount = 0

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
            listed = 0; below = 0; noDiff = 0; unmatched = 0; doneCount = 0
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
                        listed++
                        when {
                            p.strict && !ArtistParser.artistMatches(it.artist, it.title, p.artist) -> unmatched++
                            it.diff == null -> noDiff++
                            it.diff < p.minDiff -> below++
                            else -> rows.add(Row(it, label))
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

    // ───────────────────────── 2. 商品ページ ─────────────────────────

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
                    } catch (e: Exception) {
                        r.error = "internal: ${e.javaClass.simpleName}"
                    }
                    synchronized(lock) {
                        doneCount++
                        message = "店舗在庫を確認中 $doneCount / ${targets.size}"
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
            if (r.error == null) r.error = if (cancelled) "中止" else "取得失敗"
            return
        }
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
            }
        }
        r.declared = info.declared
        r.stores = info.stores
        if (info.stores.isEmpty() && (info.declared ?: 0) > 0) {
            r.error = "入荷${info.declared}店と表示されているが店舗名を読めませんでした"
        }
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
        val stores = LinkedHashMap<String, JSONObject>()
        val counts = HashMap<String, Int>()
        val products = JSONArray()

        for ((i, r) in rows.withIndex()) {
            val list = r.stores
            products.put(JSONObject().apply {
                put("i", i)
                put("pid", r.p.pid)
                put("url", r.p.url)
                put("title", r.p.title)
                put("artist", r.p.artist)
                put("genre", r.genre)
                put("price", r.p.price ?: JSONObject.NULL)
                put("diff", r.p.diff ?: JSONObject.NULL)
                put("pct", r.p.diffPct ?: JSONObject.NULL)
                put("online", r.p.onlineStock)
                put("checked", list != null)
                put("count", list?.size ?: 0)
                put("declared", r.declared ?: JSONObject.NULL)
                put("web", r.viaWeb)
                put("error", r.error ?: JSONObject.NULL)
            })
            for (s in list ?: continue) {
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
            put("minDiff", p?.minDiff ?: 500)
            put("listed", listed)
            put("below", below)
            put("noDiff", noDiff)
            put("unmatched", unmatched)
            put("targets", rows.size)
            put("done", doneCount)
            put("products", products)
            put("stores", storeArr)
        }
    }

    /** 共有用のテキスト（店舗ランキング）。 */
    fun shareText(prefFilter: String?): String {
        val st = stateJson()
        val products = st.getJSONArray("products")
        val sb = StringBuilder()
        sb.append("BOOKOFF 「${st.optString("artist")}」 定価より${st.optInt("minDiff")}円以上安い CD/DVD の在庫店舗\n")
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
                val p = products.getJSONObject(items.getInt(j))
                sb.append("   ・[${p.optString("genre")}] ${p.optString("title")}")
                if (!p.isNull("price")) sb.append(" ¥${p.optInt("price")}")
                if (!p.isNull("diff")) sb.append("（定価より${p.optInt("diff")}円安）")
                sb.append('\n')
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
                val p = products.getJSONObject(i)
                val names = byProduct[i]!!
                sb.append("・[${p.optString("genre")}] ${p.optString("title")}（${names.size}店）\n")
                sb.append("   ${names.joinToString("、")}\n")
            }
        }
        return sb.toString()
    }
}
