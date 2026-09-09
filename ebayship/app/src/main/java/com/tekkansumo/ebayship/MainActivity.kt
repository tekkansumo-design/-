package com.tekkansumo.ebayship

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一覧と入力欄の画面。見た目は assets/index.html に置いて、
 * ここは JS から呼ばれる窓口と、時間のかかる処理の受け皿だけ持つ。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView

    private val openJpPost = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { emit("orders", JSONObject().put("orders", Db.listJson(this))) }

    private val openAuth = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        val msg = res.data?.getStringExtra("msg") ?: "認証画面を閉じました"
        emit("toast", JSONObject().put("msg", msg))
        emit("settings", Db.settingsForUi(this))
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(web)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            textZoom = 100
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?, request: WebResourceRequest?
            ): Boolean {
                val u = request?.url ?: return false
                if (u.scheme == "http" || u.scheme == "https") {
                    startActivity(Intent(Intent.ACTION_VIEW, u))
                    return true
                }
                return false
            }
        }
        web.addJavascriptInterface(Bridge(), "App")
        web.loadUrl("file:///android_asset/index.html")
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    /** 画面へ知らせる。JS 側の window.__native(kind, data)。 */
    private fun emit(kind: String, data: JSONObject) {
        val k = JSONObject.quote(kind)
        val p = JSONObject.quote(data.toString())
        web.post {
            web.evaluateJavascript(
                "window.__native && window.__native(JSON.parse($k), JSON.parse($p))", null
            )
        }
    }

    private fun bg(body: () -> Unit) {
        Thread { body() }.apply { isDaemon = true }.start()
    }

    private fun fail(msg: String) = emit("toast", JSONObject().put("msg", msg).put("bad", true))

    // ------------------------------------------------------------------ 処理

    /** eBay から発送待ちを取り直す。 */
    private fun refresh() {
        emit("busy", JSONObject().put("on", true).put("msg", "eBay から取得しています…"))
        bg {
            try {
                val fetched = EbayApi.fetchAwaitingShipment(this)
                val merged = Db.mergeOrders(this, fetched)
                emit(
                    "orders",
                    JSONObject()
                        .put("orders", JSONArray(merged))
                        .put("msg", "${merged.size}件")
                )
            } catch (e: Exception) {
                fail(e.message ?: "取得に失敗しました")
                emit("orders", JSONObject().put("orders", Db.listJson(this)))
            } finally {
                emit("busy", JSONObject().put("on", false))
            }
        }
    }

    /**
     * 二次元コードのメールを見に行き、まだどの注文にも結び付いていないものを
     * 登録の早い順に割り当てる。
     */
    private fun checkMail(orderId: String) {
        emit("busy", JSONObject().put("on", true).put("msg", "メールを確認しています…"))
        bg {
            try {
                val waiting = Db.list(this)
                    .filter { it.optString("state") == "submitted" && it.optString("qrUrl").isEmpty() }
                    .filter { orderId.isEmpty() || it.optString("orderId") == orderId }
                    .sortedBy { it.optLong("submittedAt") }
                if (waiting.isEmpty()) {
                    fail("二次元コード待ちの注文がありません")
                    return@bg
                }
                val since = (waiting.minOfOrNull { it.optLong("submittedAt") } ?: 0L)
                    .let { if (it > 0) it - 10 * 60_000L else 0L }
                val mails = MailFetcher.fetch(this, since)
                val used = Db.usedQrUrls(this)
                val fresh = mails
                    .filter { it.optString("qrUrl").isNotEmpty() && !used.contains(it.optString("qrUrl")) }
                    .sortedBy { it.optLong("receivedAt") }

                // 登録の早い注文から、届いた順にメールを当てていく。
                var mi = 0
                var n = 0
                for (o in waiting) {
                    var assigned = false
                    while (mi < fresh.size) {
                        val m = fresh[mi]
                        val rec = m.optLong("receivedAt")
                        val sub = o.optLong("submittedAt")
                        // 登録より前に届いたメールはこの注文のものではない
                        if (rec > 0 && sub > 0 && rec < sub - 10 * 60_000L) {
                            mi++
                            continue
                        }
                        Db.patchOrder(this, o.getString("orderId"), JSONObject().apply {
                            put("state", "qr")
                            put("qrUrl", m.optString("qrUrl"))
                            put("qrReceivedAt", rec)
                            if (m.optString("trackingNo").isNotEmpty()) {
                                put("trackingNo", m.optString("trackingNo"))
                            }
                        })
                        mi++
                        n++
                        assigned = true
                        break
                    }
                    if (!assigned) break
                }
                emit(
                    "orders",
                    JSONObject()
                        .put("orders", Db.listJson(this))
                        .put("msg", if (n > 0) "${n}件に二次元コードを結び付けました" else "新しいメールはまだ届いていません")
                )
            } catch (e: Exception) {
                fail(e.message ?: "メールの確認に失敗しました")
            } finally {
                emit("busy", JSONObject().put("on", false))
            }
        }
    }

    /** eBay に追跡番号を登録して発送済みにする。 */
    private fun markShipped(orderId: String, trackingNo: String) {
        val o = Db.order(this, orderId)
        if (o == null) {
            fail("注文が見つかりません")
            return
        }
        if (trackingNo.isBlank()) {
            fail("追跡番号（お問い合わせ番号）を入れてください")
            return
        }
        emit("busy", JSONObject().put("on", true).put("msg", "eBay に追跡番号を登録しています…"))
        bg {
            try {
                val carrier = Db.get(this, "carrierCode").ifEmpty { "JapanPost" }
                val id = EbayApi.createFulfillment(this, o, trackingNo.trim(), carrier)
                Db.patchOrder(this, orderId, JSONObject().apply {
                    put("state", "shipped")
                    put("trackingNo", trackingNo.trim())
                    put("shippedAt", System.currentTimeMillis())
                    put("fulfillmentId", id)
                })
                emit(
                    "orders",
                    JSONObject().put("orders", Db.listJson(this)).put("msg", "発送済みにしました")
                )
            } catch (e: Exception) {
                fail(e.message ?: "発送登録に失敗しました")
            } finally {
                emit("busy", JSONObject().put("on", false))
            }
        }
    }

    // ------------------------------------------------------------- JS の窓口

    inner class Bridge {

        @JavascriptInterface
        fun getOrders(): String = Db.listJson(this@MainActivity).toString()

        @JavascriptInterface
        fun refresh() {
            runOnUiThread { this@MainActivity.refresh() }
        }

        @JavascriptInterface
        fun getSettings(): String = Db.settingsForUi(this@MainActivity).toString()

        @JavascriptInterface
        fun saveSettings(json: String) {
            Db.save(this@MainActivity, JSONObject(json))
        }

        /** まとめて貼り付けた設定を取り込む。 */
        @JavascriptInterface
        fun importSettings(text: String) {
            val got = Db.importText(this@MainActivity, text)
            if (got.isEmpty()) {
                fail("読み取れる設定がありませんでした")
                return
            }
            emit("settings", Db.settingsForUi(this@MainActivity))
            emit("toast", JSONObject().put("msg", "${got.size}項目を取り込みました"))
        }

        @JavascriptInterface
        fun linkEbay() {
            runOnUiThread {
                openAuth.launch(Intent(this@MainActivity, EbayAuthActivity::class.java))
            }
        }

        @JavascriptInterface
        fun unlinkEbay() {
            EbayApi.unlink(this@MainActivity)
            emit("settings", Db.settingsForUi(this@MainActivity))
            emit("toast", JSONObject().put("msg", "連携を解除しました"))
        }

        /** 入力欄の中身を注文に保存する。 */
        @JavascriptInterface
        fun saveForm(orderId: String, formJson: String) {
            Db.patchOrder(
                this@MainActivity, orderId,
                JSONObject().put("form", JSONObject(formJson))
            )
        }

        /** 決定。入力を保存してマイページの画面へ進む。 */
        @JavascriptInterface
        fun submit(orderId: String, formJson: String) {
            Db.patchOrder(
                this@MainActivity, orderId,
                JSONObject().put("form", JSONObject(formJson))
            )
            runOnUiThread { openJpPost.launch(JpPostActivity.intent(this@MainActivity, orderId)) }
        }

        @JavascriptInterface
        fun checkMail(orderId: String) {
            runOnUiThread { this@MainActivity.checkMail(orderId) }
        }

        @JavascriptInterface
        fun markShipped(orderId: String, trackingNo: String) {
            runOnUiThread { this@MainActivity.markShipped(orderId, trackingNo) }
        }

        @JavascriptInterface
        fun openUrl(url: String) {
            if (url.isEmpty()) return
            runOnUiThread {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (e: Exception) {
                    fail("リンクを開けませんでした")
                }
            }
        }

        /** 発送済みを一覧から片付ける。 */
        @JavascriptInterface
        fun clearShipped() {
            val n = Db.removeShipped(this@MainActivity)
            emit(
                "orders",
                JSONObject().put("orders", Db.listJson(this@MainActivity)).put("msg", "${n}件を片付けました")
            )
        }

        /** 覚えさせた入力欄の位置を全部忘れる。 */
        @JavascriptInterface
        fun resetJpProfile() {
            Db.clearJpProfile(this@MainActivity)
            emit("toast", JSONObject().put("msg", "入力欄の記録を消しました"))
        }

        /** 状態を手で戻す（登録し直したいとき）。 */
        @JavascriptInterface
        fun setState(orderId: String, state: String) {
            Db.patchOrder(this@MainActivity, orderId, JSONObject().put("state", state))
            emit("orders", JSONObject().put("orders", Db.listJson(this@MainActivity)))
        }
    }
}
