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
        }
    }

    fun cancel() {
        cancelled = true
    }

    fun clear() = lock.withLock {
        if (running) return@withLock
        hitList.clear()
        states.clear()
    }

    // ── 検索 ────────────────────────────────────────────
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
            sites.forEach { states[it.id] = SiteState(it.id, "wait") }
        }
        publish("start", stateJson())

        Thread {
            try {
                val plain = sites.filter { !it.needsJs }
                val js = sites.filter { it.needsJs }

                if (plain.isNotEmpty()) {
                    val pool = Executors.newFixedThreadPool(minOf(4, plain.size))
                    val latch = CountDownLatch(plain.size)
                    for (s in plain) {
                        pool.execute {
                            try {
                                runSite(app, s)
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
                    runSite(app, s)
                }
            } catch (e: Exception) {
                // 個々のサイトの失敗は runSite が拾う。ここに来るのは想定外だけ
            } finally {
                lock.withLock { running = false }
                publish("done", stateJson())
            }
        }.start()
    }

    private fun setState(s: SiteState) {
        lock.withLock { states[s.id] = s }
        publish("site", siteJson(s))
    }

    private fun runSite(ctx: Context, site: Site) {
        if (cancelled) {
            setState(SiteState(site.id, "error", error = "中止"))
            return
        }
        setState(SiteState(site.id, "run"))

        var lastUrl: String? = null
        var lastError: String? = null

        /** 取れたら結果を流して true。 */
        fun attempt(tpl: String, useJs: Boolean): Boolean {
            val url = fillTemplate(tpl, keyword)
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
            lock.withLock { hitList.addAll(hits) }
            publish("hits", JSONObject().apply {
                put("site", site.id)
                put("items", JSONArray().apply { hits.forEach { put(hitJson(it)) } })
            })
            setState(SiteState(site.id, "ok", hits.size, url))
            return true
        }

        val plan = ArrayList(templatesFor(ctx, site))
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

        setState(SiteState(site.id, "error", 0, lastUrl, lastError ?: "取得できなかった"))
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
}
