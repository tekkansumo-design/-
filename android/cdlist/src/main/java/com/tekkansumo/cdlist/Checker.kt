package com.tekkansumo.cdlist

import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** 在庫を見に行く作品 1 件。 */
data class Target(val id: String, val title: String)

/**
 * 同時に飛ばしてよいリクエスト数を、相手の返事を見ながら上下させる。
 * 403/503 が返ったら 1 つ減らして待ち、うまくいき続けたら 1 つ増やす。
 */
object Gate {

    private const val MIN = 1
    private const val MAX = 6
    private const val START = 3
    private const val RAMP = 4
    private val BACKOFF = longArrayOf(15_000, 30_000, 60_000, 120_000, 180_000)

    private val lock = ReentrantLock()
    private val room = lock.newCondition()
    private var permits = START
    private var active = 0
    private var okStreak = 0
    private var errLevel = 0

    val workers: Int get() = lock.withLock { permits }

    fun reset() = lock.withLock {
        permits = START; active = 0; okStreak = 0; errLevel = 0
    }

    fun acquire(cancelled: () -> Boolean): Boolean {
        lock.withLock {
            while (active >= permits && !cancelled()) {
                room.await(500, TimeUnit.MILLISECONDS)
            }
            if (cancelled()) return false
            active++
            return true
        }
    }

    fun release() = lock.withLock {
        active--
        room.signalAll()
    }

    /** 待つべき時間（ミリ秒）を返す。 */
    fun onError(): Long = lock.withLock {
        okStreak = 0
        if (permits > MIN) permits--
        val w = BACKOFF[minOf(errLevel, BACKOFF.size - 1)]
        errLevel++
        w
    }

    fun onOk() = lock.withLock {
        okStreak++
        errLevel = 0
        if (okStreak >= RAMP && permits < MAX) {
            permits++
            okStreak = 0
            room.signalAll()
        }
    }

    fun wake() = lock.withLock { room.signalAll() }
}

/**
 * 表示中の作品を順に BOOKOFF で照会する。
 * 進み具合は listener 経由で画面（window.__native）へ流す。
 */
object Checker {

    private val listeners = CopyOnWriteArrayList<(String, JSONObject) -> Unit>()

    fun addListener(l: (String, JSONObject) -> Unit) { listeners.add(l) }
    fun removeListener(l: (String, JSONObject) -> Unit) { listeners.remove(l) }

    private fun emit(kind: String, data: JSONObject) {
        for (l in listeners) {
            try {
                l(kind, data)
            } catch (e: Exception) {
                // 画面が閉じかけでも照会自体は続ける
            }
        }
    }

    @Volatile
    var running = false
        private set

    private val cancelled = AtomicBoolean(false)
    val results = ConcurrentHashMap<String, JSONObject>()

    private var artist = ""
    private var plan: List<Target> = emptyList()

    fun setPlan(a: String, items: List<Target>): Int {
        artist = a
        plan = items
        return plan.size
    }

    fun cancel() {
        cancelled.set(true)
        Gate.wake()
    }

    fun start(): Boolean {
        if (running || plan.isEmpty()) return false
        running = true
        cancelled.set(false)
        results.clear()
        Gate.reset()

        val queue = ConcurrentLinkedQueue(plan)
        val total = plan.size
        val done = AtomicInteger(0)
        emit("status", JSONObject().put("msg", "${total}件を照会します"))

        val threads = (1..minOf(6, maxOf(1, total))).map {
            Thread {
                while (!cancelled.get()) {
                    val t = queue.poll() ?: break
                    val res = try {
                        checkOne(t)
                    } catch (e: Exception) {   // 1 件の失敗で全体を止めない
                        JSONObject().put("state", "error")
                            .put("note", "${e.javaClass.simpleName}: ${e.message}")
                    }
                    res.put("id", t.id)
                    results[t.id] = res
                    val d = done.incrementAndGet()
                    emit("row", res)
                    emit("progress", JSONObject()
                        .put("done", d).put("total", total).put("workers", Gate.workers))
                }
            }.apply { isDaemon = true }
        }

        Thread {
            threads.forEach { it.start() }
            threads.forEach { it.join() }
            running = false
            emit("status", JSONObject()
                .put("msg", if (cancelled.get()) "中止しました" else "完了"))
            emit("end", JSONObject().put("cancelled", cancelled.get()))
        }.apply { isDaemon = true }.start()
        return true
    }

    private fun checkOne(t: Target): JSONObject {
        // アーティスト名込み → 作品名だけ、の順に試す（同じ語なら 1 回だけ）
        val words = listOf("$artist ${t.title}".trim(), t.title)
            .filter { it.isNotEmpty() }.distinct()
        for ((i, kw) in words.withIndex()) {
            val (items, url, err) = search(kw)
            if (err == "cancelled") return JSONObject().put("state", "cancelled")
            if (err != null) {
                return JSONObject().put("state", "error").put("note", err).put("search", url)
            }
            val (best, hits) = Bookoff.pickBest(items, artist, t.title)
            if (best != null) {
                return JSONObject().apply {
                    put("state", if (best.soldOut) "sold" else "stock")
                    val p = best.price
                    if (p != null) put("price", p)
                    put("name", best.title)
                    put("url", best.url)
                    put("new", best.isNew)
                    put("hits", hits)
                    put("search", url)
                }
            }
            if (i == words.size - 1) {
                return JSONObject().put("state", "none").put("search", url)
            }
        }
        return JSONObject().put("state", "none")
    }

    /** 戻り値は (見つかった商品, 使った URL, エラー)。 */
    private fun search(keyword: String): Triple<List<Found>, String, String?> {
        val known = Bookoff.good
        val tries = (if (known != null) listOf(known) else emptyList()) +
                Bookoff.TEMPLATES.filter { it != known }
        var lastUrl = ""
        var lastErr: String? = null

        for (tpl in tries) {
            val url = Bookoff.urlFor(tpl, keyword)
            lastUrl = url
            while (!cancelled.get()) {
                if (!Gate.acquire { cancelled.get() }) {
                    return Triple(emptyList(), url, "cancelled")
                }
                val res = try {
                    Http.get(url)
                } finally {
                    Gate.release()
                }

                if (res.error != null) {
                    val wait = Gate.onError()
                    emit("status", JSONObject().put(
                        "msg", "通信エラー ${wait / 1000}秒待機 / 並列 ${Gate.workers}"))
                    if (sleepUnlessCancelled(wait)) {
                        return Triple(emptyList(), url, "cancelled")
                    }
                    continue
                }
                if (res.code == 403 || res.code == 429 || res.code == 503) {
                    val wait = Gate.onError()
                    emit("status", JSONObject().put(
                        "msg", "HTTP ${res.code} — ${wait / 1000}秒待機 / 並列 ${Gate.workers}"))
                    if (sleepUnlessCancelled(wait)) {
                        return Triple(emptyList(), url, "cancelled")
                    }
                    continue
                }
                if (res.code != 200) {
                    lastErr = "HTTP ${res.code}"
                    break                       // この URL の形が違う。次の候補へ
                }

                Gate.onOk()
                val items = Bookoff.parse(res.body)
                if (items.isNotEmpty()) {
                    Bookoff.good = tpl
                    return Triple(items, url, null)
                }
                if (Bookoff.noHit(res.body)) {
                    // ページは開けている。単に商品が無いだけ
                    Bookoff.good = tpl
                    return Triple(emptyList(), url, null)
                }
                break
            }
            if (cancelled.get()) return Triple(emptyList(), lastUrl, "cancelled")
        }
        return Triple(emptyList(), lastUrl, lastErr ?: "検索結果を読み取れませんでした")
    }

    /** 中止ボタンで即抜けられる待ち。中止されたら true。 */
    private fun sleepUnlessCancelled(ms: Long): Boolean {
        val until = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < until) {
            if (cancelled.get()) return true
            try {
                TimeUnit.MILLISECONDS.sleep(200)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return true
            }
        }
        return cancelled.get()
    }
}
