package com.tekkansumo.bookoffchecker

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class ItemResult(
    val pid: String,
    val name: String,
    val shops: List<Shop>,
    val error: String?
)

/**
 * 在庫チェックの本体。Python 版の Session + run_check 相当。
 *
 * workers はスレッド数ではなく「同時に飛ばしてよいリクエスト数」で、
 * acquire()/release() で実際の上限として効かせる。403/503 が来たら
 * 段階的に待って枠を減らし、連続成功で戻す。
 */
object Checker {

    const val MIN_WORKERS = 1
    const val MAX_WORKERS = 10      // 上限。ここまで上がるかはサイト側の許容次第
    const val START_WORKERS = 5
    const val RAMP_AFTER = 3        // 連続成功がこれだけ続いたら枠を1つ増やす
    private val BACKOFF = intArrayOf(15, 30, 60, 120, 180)

    private val lock = ReentrantLock()
    private val gate = lock.newCondition()
    private val cancelMon = Object()

    private val resultsMap = LinkedHashMap<String, ItemResult>()

    @Volatile var running = false; private set
    @Volatile var total = 0; private set
    @Volatile var done = 0; private set
    @Volatile var workers = START_WORKERS; private set
    @Volatile var cancelled = false; private set

    private var active = 0
    private var okStreak = 0
    private var errLevel = 0

    /** UI とサービスの両方が購読するのでリスナは複数持つ。 */
    private val listeners = CopyOnWriteArrayList<(String, JSONObject) -> Unit>()

    fun addListener(l: (String, JSONObject) -> Unit) = listeners.add(l)
    fun removeListener(l: (String, JSONObject) -> Unit) = listeners.remove(l)

    private fun publish(kind: String, data: JSONObject) {
        for (l in listeners) {
            try {
                l(kind, data)
            } catch (e: Exception) {
                // 片方のリスナが落ちても処理は止めない
            }
        }
    }

    fun results(): List<ItemResult> = lock.withLock { ArrayList(resultsMap.values) }

    fun hitCount(): Int = lock.withLock { resultsMap.values.count { it.shops.isNotEmpty() } }

    fun reset(): Boolean = lock.withLock {
        if (running) return false
        resultsMap.clear()
        done = 0
        total = 0
        true
    }

    fun cancel() {
        synchronized(cancelMon) {
            cancelled = true
            cancelMon.notifyAll()
        }
        lock.withLock { gate.signalAll() }
    }

    /** 中止ボタンで即座に抜けられる待機。最大 180 秒待たされない。 */
    private fun sleepCancellable(seconds: Double) {
        val end = System.currentTimeMillis() + (seconds * 1000).toLong()
        synchronized(cancelMon) {
            while (!cancelled) {
                val left = end - System.currentTimeMillis()
                if (left <= 0) return
                try {
                    cancelMon.wait(left)
                } catch (e: InterruptedException) {
                    return
                }
            }
        }
    }

    private fun acquire(): Boolean {
        lock.withLock {
            while (active >= workers && !cancelled) {
                gate.await(500, TimeUnit.MILLISECONDS)
            }
            if (cancelled) return false
            active++
            return true
        }
    }

    private fun release() {
        lock.withLock {
            active--
            gate.signal()
        }
    }

    private fun onError(): Int {
        lock.withLock {
            okStreak = 0
            workers = maxOf(MIN_WORKERS, workers - 1)
            val wait = BACKOFF[minOf(errLevel, BACKOFF.size - 1)]
            errLevel++
            return wait
        }
    }

    private fun onOk() {
        lock.withLock {
            okStreak++
            errLevel = 0
            if (okStreak >= RAMP_AFTER && workers < MAX_WORKERS) {
                workers++
                okStreak = 0
                gate.signalAll()   // 枠が増えたので待機中のスレッドを起こす
            }
        }
    }

    /** 二重起動を防いで開始する。開始したら true。 */
    fun start(ids: List<String>): Boolean {
        lock.withLock {
            if (running) return false
            running = true
            resultsMap.clear()
            total = ids.size
            done = 0
            workers = START_WORKERS
            active = 0
            okStreak = 0
            errLevel = 0
        }
        synchronized(cancelMon) { cancelled = false }

        Thread({ runAll(ids) }, "checker-main").apply { isDaemon = true }.start()
        return true
    }

    private fun runAll(ids: List<String>) {
        val idx = AtomicInteger(0)
        val threads = (1..MAX_WORKERS).map { n ->
            Thread({ worker(ids, idx) }, "checker-$n").apply { isDaemon = true }
        }
        try {
            threads.forEach { it.start() }
            threads.forEach { it.join() }
        } catch (e: InterruptedException) {
            // 何もしない。下の finally で終了通知は必ず出す
        } finally {
            lock.withLock {
                running = false
                gate.signalAll()
            }
            publish("end", JSONObject().apply {
                put("done", done)
                put("total", total)
                put("cancelled", cancelled)
                put("hits", hitCount())
            })
        }
    }

    private fun worker(ids: List<String>, idx: AtomicInteger) {
        while (!cancelled) {
            val i = idx.getAndIncrement()
            if (i >= ids.size) return
            val pid = ids[i]

            val res = try {
                fetchStores(pid)
            } catch (e: Exception) {
                // 例外でスレッドが死ぬと done が total に届かず終わらなくなる
                ItemResult(pid, pid, emptyList(), "internal: ${e.javaClass.simpleName}")
            }

            var d = 0
            var t = 0
            lock.withLock {
                resultsMap[pid] = res
                done++
                d = done
                t = total
            }
            publish("row", rowJson(res, d, t))
        }
    }

    private fun rowJson(r: ItemResult, d: Int, t: Int) = JSONObject().apply {
        put("pid", r.pid)
        put("name", r.name)
        put("count", r.shops.size)
        put("shops", shopsJson(r.shops))
        put("error", r.error ?: JSONObject.NULL)
        put("done", d)
        put("total", t)
        put("workers", workers)
    }

    fun shopsJson(shops: List<Shop>): JSONArray {
        val a = JSONArray()
        for (s in shops) {
            a.put(JSONObject().apply {
                put("name", s.name)
                put("url", s.url)
            })
        }
        return a
    }

    /** 成功するまでリトライ（中止で打ち切り）。 */
    private fun fetchStores(pid: String): ItemResult {
        val url = Scraper.url(pid)
        while (!cancelled) {
            if (!acquire()) break

            var code = -1
            var body: String? = null
            var netErr: String? = null
            try {
                Scraper.fetch(pid).use { resp ->
                    code = resp.code
                    if (code == 200) body = resp.body?.string()
                }
            } catch (e: Exception) {
                netErr = e.javaClass.simpleName
            } finally {
                release()
            }

            if (netErr != null) {
                val w = onError()
                publish("status", msg("通信エラー ($netErr) ${w}秒待機 / 並列 $workers"))
                sleepCancellable(w.toDouble())
                continue
            }
            if (code == 403 || code == 429 || code == 503) {
                val w = onError()
                publish("status", msg("HTTP $code — ${w}秒待機 / 並列 $workers"))
                sleepCancellable(w + Math.random() * 2)
                continue
            }
            if (code == 404) {
                return ItemResult(pid, "(404) $pid", emptyList(), "404")
            }
            if (code != 200 || body == null) {
                val w = onError()
                publish("status", msg("HTTP $code — ${w}秒待機"))
                sleepCancellable(w.toDouble())
                continue
            }

            onOk()
            val (name, shops) = Scraper.parse(url, body!!)
            return ItemResult(pid, name ?: pid, shops, null)
        }
        return ItemResult(pid, pid, emptyList(), "cancelled")
    }

    private fun msg(s: String) = JSONObject().apply { put("msg", s) }

    fun stateJson(): JSONObject {
        val items = JSONArray()
        for (r in results()) {
            items.put(JSONObject().apply {
                put("pid", r.pid)
                put("name", r.name)
                put("count", r.shops.size)
                put("shops", shopsJson(r.shops))
                put("error", r.error ?: JSONObject.NULL)
            })
        }
        return JSONObject().apply {
            put("running", running)
            put("total", total)
            put("done", done)
            put("workers", workers)
            put("items", items)
        }
    }
}
