package com.tekkansumo.ebayship

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle

import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject

/**
 * 国際郵便マイページを開いて、送り状の中身を流し込む画面。
 *
 * 全自動で最後まで押し切るのではなく、画面は見せたまま入力だけ肩代わりする。
 * 相手のサイトの作りが変わっても手で続けられるし、
 * 入らなかった欄はその場で「記録」して次回から自動にできる。
 */
class JpPostActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var status: TextView
    private var orderId: String = ""
    private var order: JSONObject? = null
    private var autoFill = true
    private var picking = false
    private var lastPageKey = ""

    companion object {
        fun intent(ctx: Context, orderId: String): Intent =
            Intent(ctx, JpPostActivity::class.java).putExtra("orderId", orderId)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        orderId = intent.getStringExtra("orderId") ?: ""
        order = Db.order(this, orderId)
        if (order == null) {
            Toast.makeText(this, "注文が見つかりません", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#16110d"))
        }

        status = TextView(this).apply {
            setTextColor(Color.parseColor("#f0e6da"))
            setBackgroundColor(Color.parseColor("#221b14"))
            textSize = 12f
            setPadding(24, 18, 24, 18)
            text = "読み込んでいます…"
        }
        root.addView(status, wide())

        web = WebView(this)
        root.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        root.addView(buttonBar(), wide())
        setContentView(root)

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
            userAgentString = userAgentString.replace("; wv", "")
        }
        web.addJavascriptInterface(Bridge(), "Jp")
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?, request: WebResourceRequest?
            ): Boolean = false

            override fun onPageFinished(view: WebView?, url: String?) {
                lastPageKey = pageKey(url)
                inject { if (autoFill) autoStep() }
            }
        }
        web.loadUrl(Db.get(this, "jpStartUrl"))
    }

    private fun wide() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun buttonBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#221b14"))
            setPadding(12, 10, 12, 10)
        }
        bar.addView(btn("自動入力") { fillNow(true) })
        bar.addView(btn("ログイン") { autoLogin(true) })
        bar.addView(btn("欄を記録") { togglePick() })
        bar.addView(btn("項目一覧") { describe() })
        bar.addView(btn("登録できた") { confirmDone() })
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(bar)
        }
        return scroll
    }

    private fun btn(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = 12f
            isAllCaps = false
            setOnClickListener { onClick() }
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.rightMargin = 10
            layoutParams = lp
        }

    private fun say(msg: String) {
        runOnUiThread { status.text = msg }
    }

    /** 画面ごとに覚え書きを分けるためのキー。クエリは無視してパスだけ使う。 */
    private fun pageKey(url: String?): String {
        if (url.isNullOrEmpty()) return "?"
        return try {
            val u = Uri.parse(url)
            (u.host ?: "") + (u.path ?: "")
        } catch (e: Exception) {
            url
        }
    }

    private fun inject(then: () -> Unit) {
        web.evaluateJavascript(JpPostScript.JS) { then() }
    }

    /** ページが変わるたびに呼ぶ。ログイン画面ならログイン、それ以外なら入力。 */
    private fun autoStep() {
        autoLogin(false)
        fillNow(false)
    }

    private fun autoLogin(manual: Boolean) {
        val id = Db.get(this, "jpLoginId")
        val pw = Db.get(this, "jpPassword")
        if (id.isEmpty() || pw.isEmpty()) {
            if (manual) say("設定にマイページの ID とパスワードを入れてください")
            return
        }
        val data = JSONObject().apply {
            put("loginId", id)
            put("loginPw", pw)
        }
        runFill(data) { rep ->
            val n = rep.optJSONArray("filled")?.length() ?: 0
            if (n >= 2) say("ログイン情報を入れました。ログインボタンを押してください")
            else if (manual) say("ログイン欄が見つかりませんでした")
        }
    }

    /** 送り状の中身を入れる。manual なら結果を必ず知らせる。 */
    private fun fillNow(manual: Boolean) {
        val o = order ?: return
        val data = shipmentData(o)
        runFill(data) { rep ->
            val filled = rep.optJSONArray("filled") ?: JSONArray()
            val missing = rep.optJSONArray("missing") ?: JSONArray()
            if (filled.length() == 0 && !manual) return@runFill
            val names = (0 until missing.length()).map { label(missing.getString(it)) }
            val head = "${filled.length()}項目を入力しました"
            say(
                if (names.isEmpty()) "$head。内容を確かめて次へ進んでください"
                else "$head / 入らなかった欄: ${names.joinToString("、")}"
            )
        }
    }

    /** 入れた結果を受け取りつつ、当たった欄はそのまま覚えておく。 */
    private fun runFill(data: JSONObject, then: (JSONObject) -> Unit) {
        val profile = Db.jpProfile(this).optJSONObject(lastPageKey) ?: JSONObject()
        val js = "window.__jp && window.__jp.fill(" +
                data.toString() + "," + profile.toString() + ")"
        web.evaluateJavascript(js) { raw ->
            val rep = parse(raw) ?: return@evaluateJavascript
            // 推測で当たった欄は次回のために控えておく
            val filled = rep.optJSONArray("filled") ?: JSONArray()
            for (i in 0 until filled.length()) {
                val f = filled.getJSONObject(i)
                if (!f.optBoolean("learned")) {
                    val sel = f.optString("selector")
                    if (sel.isNotEmpty()) {
                        Db.saveJpProfile(this, lastPageKey, f.optString("field"), sel)
                    }
                }
            }
            then(rep)
        }
    }

    /** evaluateJavascript は JSON 文字列を二重に包んで返すので剥がす。 */
    private fun parse(raw: String?): JSONObject? {
        if (raw.isNullOrEmpty() || raw == "null") return null
        return try {
            val inner = if (raw.startsWith("\"")) JSONArray("[$raw]").getString(0) else raw
            JSONObject(inner)
        } catch (e: Exception) {
            null
        }
    }

    /** 注文と設定から、送り状に入れる値を組み立てる。 */
    private fun shipmentData(o: JSONObject): JSONObject {
        val a = o.optJSONObject("address") ?: JSONObject()
        val f = o.optJSONObject("form") ?: JSONObject()
        val s = Db.settings(this)
        val line = listOf(a.optString("line1"), a.optString("line2"))
            .filter { it.isNotEmpty() }.joinToString(" ")
        return JSONObject().apply {
            put("toName", a.optString("name"))
            put("toPostal", a.optString("postal"))
            put("toAddress", line)
            put("toCity", a.optString("city"))
            put("toState", a.optString("state"))
            put("toCountry", Countries.candidates(a.optString("country")))
            put("toPhone", a.optString("phone"))
            put("toEmail", a.optString("email"))
            put("fromName", s.optString("fromName"))
            put("fromPostal", s.optString("fromPostal"))
            put("fromAddress", s.optString("fromAddress"))
            put("fromPhone", s.optString("fromPhone"))
            put("content", f.optString("content"))
            put("quantity", f.optString("quantity"))
            put("weight", f.optString("weight"))
            put("value", f.optString("value"))
            put("hsCode", f.optString("hsCode"))
            put("origin", Countries.candidates(f.optString("origin", "JP")))
        }
    }

    private fun label(field: String): String =
        JpPostScript.FIELD_LABELS.firstOrNull { it.first == field }?.second ?: field

    // ------------------------------------------------------------- 欄の記録

    private fun togglePick() {
        picking = !picking
        inject {
            web.evaluateJavascript("window.__jp.pick(${picking})", null)
            say(
                if (picking) "覚えさせたい入力欄を画面上でタップしてください"
                else "記録をやめました"
            )
        }
    }

    /** JS 側で欄が選ばれたら、それがどの項目かを聞いて覚える。 */
    private fun onPicked(selector: String, labelText: String) {
        val items = JpPostScript.FIELD_LABELS
        val names = items.map { it.second }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("この欄は？")
            .setMessage(if (labelText.isEmpty()) selector else "$labelText\n$selector")
            .setItems(names) { _, which ->
                Db.saveJpProfile(this, lastPageKey, items[which].first, selector)
                say("「${names[which]}」として覚えました")
            }
            .setNegativeButton("やめる", null)
            .show()
    }

    private fun describe() {
        inject {
            web.evaluateJavascript("window.__jp.describe()") { raw ->
                val rep = parse(raw)
                val fields = rep?.optJSONArray("fields") ?: JSONArray()
                val sb = StringBuilder()
                for (i in 0 until fields.length()) {
                    val f = fields.getJSONObject(i)
                    sb.append(i + 1).append(". ")
                        .append(f.optString("tag")).append("/")
                        .append(f.optString("type")).append("  ")
                        .append(f.optString("label")).append("\n    ")
                        .append(f.optString("selector")).append("\n")
                }
                if (sb.isEmpty()) sb.append("入力欄が見つかりませんでした")
                AlertDialog.Builder(this)
                    .setTitle("この画面の入力欄 (${fields.length()})")
                    .setMessage(sb.toString())
                    .setPositiveButton("閉じる", null)
                    .show()
            }
        }
    }

    // --------------------------------------------------------------- 仕上げ

    private fun confirmDone() {
        AlertDialog.Builder(this)
            .setTitle("登録は終わりましたか？")
            .setMessage(
                "マイページで登録とメール送信まで終わっていたら「終わった」を押してください。" +
                        "二次元コードのメールが届いたら、一覧からリンクボタンが出せます。"
            )
            .setPositiveButton("終わった") { _, _ ->
                Db.patchOrder(
                    this, orderId,
                    JSONObject().apply {
                        put("state", "submitted")
                        put("submittedAt", System.currentTimeMillis())
                    }
                )
                setResult(Activity.RESULT_OK)
                finish()
            }
            .setNegativeButton("まだ", null)
            .show()
    }

    override fun onPause() {
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    inner class Bridge {
        @JavascriptInterface
        fun picked(selector: String, label: String, tag: String) {
            runOnUiThread { onPicked(selector, label) }
        }
    }
}
