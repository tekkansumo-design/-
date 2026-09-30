package com.tekkansumo.ebaylister

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.tekkansumo.ebaylister.core.Api
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * アプリ内ブラウザで各サイトにログインしてもらい、キー類はページから自動で読み取って保存する。
 * 人がやるのはログイン・同意・支払い・送料の決定だけ。
 *
 *   ① Anthropic のキー   console でキーを作ると、表示されたキーを読み取り、使えるか確かめて保存
 *   ② eBay のキー        keyset を作ると App ID / Cert ID を読み取る。本番で使えなければ削除通知の免除へ
 *   ③ RuName            戻り先 URL の欄を自動入力し、保存後の RuName を読み取る
 *   ④ 連携               同意後の戻り先（code 付き URL）をこの画面で横取りしてトークンに交換
 *   ⑤ 出品の準備          郵便番号だけ入れてもらい /api/ebay/auto_setup
 *   ⑥ 送料ポリシー        作ってもらい、できたら自動で選ぶ
 */
class SetupActivity : AppCompatActivity() {

    private enum class Step { ANTHROPIC, EBAY_KEYS, EBAY_PUSH, RUNAME, LINK, SELLER, SHIPPING, DONE }

    private lateinit var web: WebView
    private lateinit var title: TextView
    private lateinit var detail: TextView
    private lateinit var zip: EditText
    private lateinit var action: Button
    private lateinit var skip: Button

    private val ui = Handler(Looper.getMainLooper())
    private val bg = Executors.newSingleThreadExecutor()
    private var step = Step.DONE
    private var busy = false
    private var ticks = 0
    private var lastTried = ""
    private var linking = false
    private val api: Api get() = MainActivity.api(this)

    private val tick = object : Runnable {
        override fun run() {
            poll()
            ui.postDelayed(this, 1500)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0b63ce"))
            setPadding(dp(16), dp(12), dp(16), dp(10))
        }
        title = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 17f; setTypeface(typeface, Typeface.BOLD)
        }
        detail = TextView(this).apply { setTextColor(Color.WHITE); textSize = 14f; setPadding(0, dp(4), 0, dp(6)) }
        zip = EditText(this).apply {
            hint = "発送元の郵便番号（例: 150-0001）"; inputType = InputType.TYPE_CLASS_PHONE
            setTextColor(Color.WHITE); setHintTextColor(Color.parseColor("#cfe0f7")); visibility = View.GONE
        }
        action = Button(this).apply { visibility = View.GONE }
        skip = Button(this).apply { text = "この手順を飛ばす" }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(action, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(skip, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        header.addView(title)
        header.addView(detail)
        header.addView(zip, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        header.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        web = WebView(this)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            textZoom = 100
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean =
                request?.url?.let { catchAuthCode(it) } ?: false

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                url?.let { catchAuthCode(Uri.parse(it)) }
            }
        }

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(web, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        setContentView(root)

        skip.setOnClickListener { next(step) }
        goTo(firstPending())
        ui.postDelayed(tick, 1500)
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        bg.shutdownNow()
        web.destroy()
        super.onDestroy()
    }

    // ── 手順の進め方 ──
    private fun conf(): JSONObject = JSONObject(api.handle("GET", "/api/config", JSONObject()).second)

    private fun firstPending(after: Step? = null): Step {
        val c = conf()
        val order = Step.values().toList()
        val start = if (after == null) 0 else order.indexOf(after) + 1
        for (s in order.drop(start)) {
            val todo = when (s) {
                Step.ANTHROPIC -> !c.optBoolean("has_anthropic_api_key")
                Step.EBAY_KEYS -> c.optString("client_id").isEmpty() || !c.optBoolean("has_client_secret")
                Step.EBAY_PUSH -> false                       // キーが本番で弾かれたときだけ入る
                Step.RUNAME -> c.optString("ru_name").isEmpty()
                Step.LINK -> !c.optBoolean("has_refresh_token")
                Step.SELLER -> listOf("payment_policy_id", "return_policy_id", "merchant_location_key")
                    .any { c.optString(it).isEmpty() }
                Step.SHIPPING -> c.optString("fulfillment_policy_id").isEmpty()
                Step.DONE -> true
            }
            if (todo) return s
        }
        return Step.DONE
    }

    private fun next(from: Step) = goTo(firstPending(from))

    private fun goTo(s: Step) {
        step = s
        busy = false
        lastTried = ""
        linking = false
        zip.visibility = View.GONE
        action.visibility = View.GONE
        skip.visibility = View.VISIBLE
        when (s) {
            Step.ANTHROPIC -> show("① AI（Anthropic）のキー",
                "ログインしてください（初めてなら登録）。クレジット未購入なら先に Billing で購入し、" +
                    "Keys で「Create Key」を押してください。表示されたキーを自動で読み取って保存します。",
                "https://console.anthropic.com/settings/keys")
            Step.EBAY_KEYS -> show("② eBay のキー",
                "eBay 開発者サイトにログインしてください（初めてなら登録。承認に時間がかかることがあります）。" +
                    "Production の「Create a keyset」を押すと、App ID と Cert ID を自動で読み取ります。",
                "https://developer.ebay.com/my/keys")
            Step.EBAY_PUSH -> {
                show("②' 削除通知の免除",
                    "本番キーを使うには必要です。Marketplace Account Deletion で「Exempted」をオンにし、" +
                        "理由に「I do not persist eBay data」を選んで保存してから「確認する」を押してください。",
                    "https://developer.ebay.com/my/push/")
                button("確認する") { recheckKeys() }
            }
            Step.RUNAME -> show("③ RuName（戻り先）",
                "「Get a Token from eBay via Your Application」→「Add eBay Redirect URL」を押してください。" +
                    "URL の欄は自動で入れます。保存すると RuName を読み取ります。",
                "https://developer.ebay.com/my/auth/?env=production&index=0")
            Step.LINK -> {
                show("④ eBay アカウント連携", "出品に使う eBay アカウントでログインし、「同意する（Agree）」を押してください。", null)
                work({ JSONObject(ok(api.handle("GET", "/api/ebay/auth_url", JSONObject()))).getString("url") }) { url ->
                    web.loadUrl(url)
                }
            }
            Step.SELLER -> {
                show("⑤ 出品の準備",
                    "発送元の郵便番号を入れて「自動で設定」を押してください。ビジネスポリシーの有効化、" +
                        "支払・返品ポリシーの作成、発送元の登録をまとめて行います。", "about:blank")
                zip.visibility = View.VISIBLE
                button("自動で設定") { autoSetup(zip.text.toString()) }
            }
            Step.SHIPPING -> show("⑥ 送料ポリシー",
                "送料はご自身で決める必要があるため、ここだけ作ってください（国際配送の送料・発送までの日数）。" +
                    "作って保存すると自動で選びます。",
                Api.SHIPPING_POLICY_URL)
            Step.DONE -> {
                show("設定の確認", "接続テストをしています...", "about:blank")
                skip.visibility = View.GONE
                button("閉じる") { finish() }
                work({ JSONObject(ok(api.handle("POST", "/api/ebay/test", JSONObject()))) }) { j ->
                    val steps = j.getJSONArray("steps")
                    detail.text = (0 until steps.length()).joinToString("\n") {
                        val s2 = steps.getJSONObject(it)
                        (if (s2.getBoolean("ok")) "✅ " else "❌ ") + s2.getString("label") + "  " + s2.getString("detail")
                    } + if (j.getBoolean("ok")) "\n\nすべて OK です。出品できます。" else ""
                }
            }
        }
    }

    private fun show(t: String, d: String, url: String?) {
        title.text = t
        detail.text = d
        if (url != null) web.loadUrl(url)
    }

    private fun button(label: String, onClick: () -> Unit) {
        action.text = label
        action.visibility = View.VISIBLE
        action.setOnClickListener { if (!busy) onClick() }
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    /** Api.handle の失敗を例外に変える。 */
    private fun ok(r: Pair<Boolean, String>): String {
        if (!r.first) throw IllegalStateException(JSONObject(r.second).optString("error"))
        return r.second
    }

    /** 通信は裏で、結果の反映は画面スレッドで。失敗は説明欄に出す。 */
    private fun <T> work(job: () -> T, done: (T) -> Unit) {
        busy = true
        bg.execute {
            val r = try { Result.success(job()) } catch (e: Exception) { Result.failure(e) }
            ui.post {
                if (isFinishing || isDestroyed) return@post
                busy = false
                r.onSuccess(done).onFailure { detail.text = "⚠ " + (it.message ?: it.toString()) + "\n\n" + detail.text.lines().last() }
            }
        }
    }

    // ── ページからの読み取り ──
    private fun poll() {
        if (busy) return
        ticks++
        when (step) {
            Step.ANTHROPIC, Step.EBAY_KEYS, Step.RUNAME ->
                web.evaluateJavascript(scanJs(fill = step == Step.RUNAME)) { res -> onScan(res) }
            // 送料ポリシーは画面に出ないので API でできたかを確かめる（約 9 秒ごと）
            Step.SHIPPING -> if (ticks % 6 == 0) autoSetup("", quiet = true)
            else -> {}
        }
    }

    private fun onScan(res: String?) {
        val found = try { JSONObject(JSONArray("[$res]").optString(0)) } catch (e: Exception) { return }
        when (step) {
            Step.ANTHROPIC -> {
                val key = found.optString("ant")
                if (key.isEmpty() || key == lastTried) return
                lastTried = key
                work({ ok(api.handle("POST", "/api/anthropic/key", JSONObject().put("key", key))) }) {
                    toast("AI のキーを保存しました")
                    next(Step.ANTHROPIC)
                }
            }
            Step.EBAY_KEYS -> {
                val app = found.optString("app")
                val cert = found.optString("cert")
                if (app.isEmpty() || cert.isEmpty()) {
                    if (app.isNotEmpty() && !detail.text.contains("Cert ID が"))
                        detail.text = detail.text.toString() + "\nCert ID が隠れている場合は、表示するボタンを押してください。"
                    return
                }
                if ("$app|$cert" == lastTried) return
                lastTried = "$app|$cert"
                work({
                    ok(api.handle("POST", "/api/config", JSONObject().put("client_id", app)
                        .put("client_secret", cert).put("ebay_env", "production")))
                    keysWork()
                }) { good ->
                    toast("eBay のキーを保存しました")
                    if (good) next(Step.EBAY_PUSH) else goTo(Step.EBAY_PUSH)
                }
            }
            Step.RUNAME -> {
                val ru = found.optString("ru")
                if (ru.isEmpty() || ru == lastTried) return
                lastTried = ru
                work({ ok(api.handle("POST", "/api/config", JSONObject().put("ru_name", ru))) }) {
                    toast("RuName を保存しました")
                    next(Step.RUNAME)
                }
            }
            else -> {}
        }
    }

    /** eBay のキーでアプリ用トークンが取れるか（本番は削除通知の免除が済むまで取れない）。 */
    private fun keysWork(): Boolean {
        val t = JSONObject(ok(api.handle("POST", "/api/ebay/test", JSONObject()))).getJSONArray("steps")
        return (0 until t.length()).map { t.getJSONObject(it) }
            .firstOrNull { it.getString("label").startsWith("eBay のキー") }?.getBoolean("ok") ?: false
    }

    private fun recheckKeys() = work({ keysWork() }) { good ->
        if (good) next(Step.EBAY_PUSH)
        else detail.text = "まだキーが使えません。免除の設定を保存してから数分待って、もう一度「確認する」を押してください。"
    }

    private fun autoSetup(postal: String, quiet: Boolean = false) {
        work({ JSONObject(ok(api.handle("POST", "/api/ebay/auto_setup", JSONObject().put("postal_code", postal)))) }) { j ->
            val steps = j.getJSONArray("steps")
            if (steps.length() > 0) toast((0 until steps.length()).joinToString("\n") { steps.getString(it) })
            when {
                j.getBoolean("need_postal") -> if (!quiet) detail.text = "発送元がありません。郵便番号を入れてもう一度押してください。"
                j.getBoolean("need_shipping") -> if (step != Step.SHIPPING) goTo(Step.SHIPPING)
                else -> next(Step.SHIPPING)
            }
        }
    }

    /** eBay の同意後、戻り先（code 付き）に移る瞬間を横取りしてトークンに交換する。 */
    private fun catchAuthCode(u: Uri): Boolean {
        if (step != Step.LINK || linking) return false
        val code = try { u.getQueryParameter("code") } catch (e: Exception) { null } ?: return false
        if (!code.startsWith("v^")) return false
        linking = true
        web.stopLoading()
        web.loadUrl("about:blank")
        detail.text = "連携しています..."
        work({ ok(api.handle("POST", "/api/ebay/auth_code", JSONObject().put("code", u.toString()))) }) {
            toast("eBay アカウントと連携しました")
            next(Step.LINK)
        }
        return true
    }

    companion object {
        /**
         * ページの文字と入力欄の値から、キー類を正規表現で探す（画面の作りが変わっても拾えるように）。
         * fill=true のときは RuName 登録フォームの URL 欄に https://example.com/ を入れる。
         */
        fun scanJs(fill: Boolean) = """
(function(fill){
  var t=document.body?document.body.innerText:'';
  var ins=document.querySelectorAll('input,textarea');
  for(var i=0;i<ins.length;i++){t+='\n'+(ins[i].value||'');}
  if(fill){
    var set=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set;
    for(var j=0;j<ins.length;j++){
      var e=ins[j];
      if(e.tagName!=='INPUT'||e.value||(e.type&&!/^(text|url)$/i.test(e.type)))continue;
      var lab=[e.name,e.id,e.placeholder,e.getAttribute('aria-label')].join(' ');
      var l=e.id&&document.querySelector('label[for="'+e.id+'"]'); if(l)lab+=' '+l.innerText;
      var r=e.closest('tr'); if(r)lab+=' '+(r.innerText||'').slice(0,120);
      if(/privacy|accept|declin/i.test(lab)){
        set.call(e,'https://example.com/');
        e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));
      }
    }
  }
  function m(re){var x=t.match(re);return x?x[0]:'';}
  var ru=t.match(/RuName[^A-Za-z0-9]*(?:\([^)]*\))?[^A-Za-z0-9]*([A-Za-z0-9_]+(?:-[A-Za-z0-9_]+){3,})/i);
  return JSON.stringify({
    ant:m(/sk-ant-[A-Za-z0-9_\-]{20,}/),
    app:m(/\b[A-Za-z0-9_]+-[A-Za-z0-9_]+-PRD-[0-9a-z]{6,}-[0-9a-z]{6,}\b/),
    cert:m(/\bPRD-[0-9a-f]{8,}(?:-[0-9a-f]{4}){3,4}(?![0-9a-z-])/i),
    ru:(ru&&!/-PRD-/.test(ru[1]))?ru[1]:''
  });
})($fill)
""".trimIndent()
    }
}
