package com.tekkansumo.cdprice

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** 1 サイト分の経過。UI のチップに出す。 */
data class SiteState(
    val id: String,
    val status: String,      // wait / run / ok / empty / error
    val count: Int = 0,
    val url: String? = null,
    val error: String? = null
)

/**
 * 各サイトを検索して結果を集める本体。
 *
 * アーティスト名で 1 回検索したあと、MusicBrainz から発売作の一覧を取って
 * 突き合わせる。店の結果だけでは「何枚あるうちの何枚が見つかったのか」が
 * 分からないが、一覧があれば抜けている盤を名指しで探し直せる。
 *
 * 通信できるサイトどうしは並行に、JavaScript が要るサイトは
 * WebView を 1 本ずつ使うので順番に流れる。
 */
object Finder {

    private const val PER_SITE = 60

    private val lock = ReentrantLock()
    private val listeners = CopyOnWriteArrayList<(String, JSONObject) -> Unit>()

    private val hitList = ArrayList<Hit>()
    private val states = LinkedHashMap<String, SiteState>()

    @Volatile var running = false; private set
    @Volatile var keyword = ""; private set
    @Volatile private var cancelled = false

    // ── ディスコグラフィ ────────────────────────────────
    @Volatile private var artists: List<ArtistRef> = emptyList()
    @Volatile private var artist: ArtistRef? = null
    @Volatile private var albums: List<Album> = emptyList()
    @Volatile private var discoError: String? = null
    @Volatile private var discoLoading = false

    fun addListener(l: (String, JSONObject) -> Unit) = listeners.add(l)
    fun removeListener(l: (String, JSONObject) -> Unit) = listeners.remove(l)

    private fun publish(kind: String, data: JSONObject) {
        for (l in listeners) {
            try {
                l(kind, data)
            } catch (e: Exception) {
                // 片方の購読者が落ちても検索は止めない
            }
        }
    }

    fun hits(): List<Hit> = lock.withLock { ArrayList(hitList) }

    fun stateJson(): JSONObject = lock.withLock {
        JSONObject().apply {
            put("running", running)
            put("keyword", keyword)
            put("sites", JSONArray().apply { states.values.forEach { put(siteJson(it)) } })
            put("hits", JSONArray().apply { hitList.forEach { put(hitJson(it)) } })
            put("disco", discoJson())
        }
    }

    fun cancel() {
        cancelled = true
    }

    fun clear() = lock.withLock {
        if (running) return@withLock
        hitList.clear()
        states.clear()
        artists = emptyList()
        artist = null
        albums = emptyList()
        discoError = null
    }

    // ── アーティスト名で検索 ────────────────────────────
    fun start(ctx: Context, word: String, siteIds: List<String>) {
        val app = ctx.applicationContext
        val sites = siteIds.mapNotNull { Sites.byId(it) }
        if (word.isBlank() || sites.isEmpty()) return

        lock.withLock {
            if (running) return
            running = true
            cancelled = false
            keyword = word.trim()
            hitList.clear()
            states.clear()
            artists = emptyList()
            artist = null
            albums = emptyList()
            discoError = null
            sites.forEach { states[it.id] = SiteState(it.id, "wait") }
        }
        publish("start", stateJson())

        // 発売作の一覧は店の検索と関わりがないので、待たせずに横で取る
        fetchDisco(keyword)

        Thread {
            try {
                sweep(app, sites, keyword, report = true, quick = false)
            } catch (e: Exception) {
                // 個々のサイトの失敗は runSite が拾う。ここに来るのは想定外だけ
            } finally {
                lock.withLock { running = false }
                publish("done", stateJson())
            }
        }.start()
    }

    /** 見つからなかった盤を、題名を足して名指しで探し直す。結果は積み増す。 */
    fun searchAlbums(ctx: Context, titles: List<String>, siteIds: List<String>) {
        val app = ctx.applicationContext
        val list = titles.filter { it.isNotBlank() }
        if (list.isEmpty()) return

        // 本検索で取れたサイトだけに絞る。取れなかった先を何度も叩いても無駄になる
        val ok = lock.withLock { states.values.filter { it.status == "ok" }.map { it.id } }
        val sites = (ok.ifEmpty { siteIds }).mapNotNull { Sites.byId(it) }
        if (sites.isEmpty()) return

        lock.withLock {
            if (running) return
            running = true
            cancelled = false
        }
        publish("subrun", JSONObject().apply {
            put("running", true); put("index", 0); put("total", list.size); put("title", list.first())
        })

        Thread {
            try {
                for ((i, t) in list.withIndex()) {
                    if (cancelled) break
                    publish("subrun", JSONObject().apply {
                        put("running", true); put("index", i); put("total", list.size); put("title", t)
                    })
                    sweep(app, sites, "$keyword $t", report = false, quick = true)
                }
            } catch (e: Exception) {
                // 個々のサイトの失敗は runSite が拾う
            } finally {
                lock.withLock { running = false }
                publish("subrun", JSONObject().apply {
                    put("running", false); put("index", list.size); put("total", list.size)
                })
                publish("done", stateJson())
            }
        }.start()
    }

    /** 選び直したアーティストで一覧を取り直す。 */
    fun pickArtist(mbid: String) {
        val a = artists.firstOrNull { it.id == mbid } ?: return
        artist = a
        albums = emptyList()
        discoError = null
        Thread {
            discoLoading = true
            publish("disco", discoJson())
            try {
                albums = MusicBrainz.albums(a.id)
            } catch (e: Exception) {
                discoError = e.message ?: e.javaClass.simpleName
            } finally {
                discoLoading = false
                publish("disco", discoJson())
            }
        }.start()
    }

    private fun fetchDisco(word: String) {
        Thread {
            discoLoading = true
            publish("disco", discoJson())
            try {
                val found = MusicBrainz.searchArtists(word)
                artists = found
                val a = found.firstOrNull()
                artist = a
                publish("disco", discoJson())
                if (a != null) albums = MusicBrainz.albums(a.id)
                else discoError = "このアーティストが見つかりませんでした"
            } catch (e: Exception) {
                discoError = e.message ?: e.javaClass.simpleName
            } finally {
                discoLoading = false
                publish("disco", discoJson())
            }
        }.start()
    }

    // ── サイトを一巡する ────────────────────────────────
    private fun sweep(ctx: Context, sites: List<Site>, word: String, report: Boolean, quick: Boolean) {
        val plain = sites.filter { !it.needsJs }
        val js = sites.filter { it.needsJs }

        if (plain.isNotEmpty()) {
            val pool = Executors.newFixedThreadPool(minOf(4, plain.size))
            val latch = CountDownLatch(plain.size)
            for (s in plain) {
                pool.execute {
                    try {
                        runSite(ctx, s, word, report, quick)
                    } finally {
                        latch.countDown()
                    }
                }
            }
            latch.await()
            pool.shutdown()
        }
        // WebView は 1 本しか使えないので後ろで順番に
        for (s in js) {
            if (cancelled) break
            runSite(ctx, s, word, report, quick)
        }
    }

    private fun setState(s: SiteState) {
        lock.withLock { states[s.id] = s }
        publish("site", siteJson(s))
    }

    /**
     * @param report サイトの状態を UI に出すか。盤ごとの追加検索では本検索の
     *   件数を上書きしたくないので出さない。
     * @param quick 前に通った形だけを 1 回試す。追加検索は数が多いので粘らない。
     */
    private fun runSite(ctx: Context, site: Site, word: String, report: Boolean, quick: Boolean) {
        if (cancelled) {
            if (report) setState(SiteState(site.id, "error", error = "中止"))
            return
        }
        if (report) setState(SiteState(site.id, "run"))

        var lastUrl: String? = null
        var lastError: String? = null

        /** 取れたら結果を流して true。 */
        fun attempt(tpl: String, useJs: Boolean): Boolean {
            val url = fillTemplate(tpl, word)
            lastUrl = url
            val res = if (useJs) JsFetcher.fetch(ctx, url) else Http.get(url)
            if (res.body.isEmpty()) {
                lastError = res.error ?: "応答が空だった（HTTP ${res.code}）"
                return false
            }
            val hits = try {
                Extract.extract(site, res.url, res.body, PER_SITE)
            } catch (e: Exception) {
                lastError = "解析に失敗: " + (e.message ?: e.javaClass.simpleName)
                return false
            }
            if (hits.isEmpty()) {
                lastError = "商品の並びが見つからないページだった"
                return false
            }
            Prefs.learn(ctx, site.id, tpl)
            val added = lock.withLock {
                val known = hitList.mapTo(HashSet()) { it.url }
                val fresh = hits.filter { it.url !in known }
                hitList.addAll(fresh)
                fresh
            }
            if (added.isNotEmpty()) {
                publish("hits", JSONObject().apply {
                    put("site", site.id)
                    put("items", JSONArray().apply { added.forEach { put(hitJson(it)) } })
                })
            }
            if (report) setState(SiteState(site.id, "ok", hits.size, url))
            return true
        }

        val plan = ArrayList(templatesFor(ctx, site))

        if (quick) {
            // 前に通った形だけを 1 回。粘らないぶん速い
            if (!attempt(plan.first(), site.needsJs) && report) {
                setState(SiteState(site.id, "error", 0, lastUrl, lastError))
            }
            return
        }

        for (tpl in plan) {
            if (cancelled) break
            if (attempt(tpl, site.needsJs)) return
        }

        // 候補が全滅。トップページの検索フォームから URL の形を割り出して試す。
        if (!cancelled) {
            val d = try {
                Http.discoverTemplate(site)
            } catch (e: Exception) {
                null
            }
            if (d != null && d !in plan) {
                if (attempt(d, site.needsJs)) return
                plan.add(d)
            }
        }

        // それでも駄目なら、描画待ちが要るページかもしれない。
        // いちばん有望な形ひとつだけ、ブラウザと同じやり方で取り直す。
        if (!cancelled && !site.needsJs && attempt(plan.first(), true)) return

        if (report) setState(SiteState(site.id, "error", 0, lastUrl, lastError ?: "取得できなかった"))
    }

    /** 試す順番。利用者の指定 → 前回通った形 → 既定の候補。 */
    fun templatesFor(ctx: Context, site: Site): List<String> {
        val out = LinkedHashSet<String>()
        Prefs.override(ctx, site.id)?.let { out.add(it) }
        Prefs.learned(ctx, site.id)?.let { out.add(it) }
        out.addAll(site.templates)
        return out.toList()
    }

    // ── JSON ────────────────────────────────────────────
    private fun siteJson(s: SiteState) = JSONObject().apply {
        put("id", s.id)
        put("label", Sites.byId(s.id)?.label ?: s.id)
        put("status", s.status)
        put("count", s.count)
        put("url", s.url ?: JSONObject.NULL)
        put("error", s.error ?: JSONObject.NULL)
    }

    private fun hitJson(h: Hit) = JSONObject().apply {
        put("site", h.site)
        put("label", Sites.byId(h.site)?.label ?: h.site)
        put("title", h.title)
        put("price", h.price)
        put("url", h.url)
        put("image", h.image ?: JSONObject.NULL)
        put("used", h.used)
    }

    private fun discoJson() = JSONObject().apply {
        put("loading", discoLoading)
        put("error", discoError ?: JSONObject.NULL)
        put("artistId", artist?.id ?: JSONObject.NULL)
        put("artists", JSONArray().apply {
            artists.forEach {
                put(JSONObject().apply {
                    put("id", it.id)
                    put("name", it.name)
                    put("line", it.line)
                    put("japanese", it.japanese ?: JSONObject.NULL)
                })
            }
        })
        put("albums", JSONArray().apply {
            albums.forEach {
                put(JSONObject().apply {
                    put("id", it.id)
                    put("title", it.title)
                    put("year", it.year)
                    put("kind", it.kind)
                })
            }
        })
    }
}
