package com.tekkansumo.ebayship

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * eBay の同意画面を出して、戻ってきた URL から認可コードを拾う。
 *
 * RuName の飛び先は開発者が自分で決めた URL なので、実際に開けるとは限らない。
 * そのため「読み込めたか」ではなく「code が付いた URL に飛ぼうとしたか」で判定する。
 *
 * うまくいかないときに何が起きたのか分からないのがいちばん困るので、
 * 失敗したらこの画面に理由を出したまま留まる。閉じるのは自分で押してもらう。
 */
class EbayAuthActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var closeBtn: Button
    private lateinit var web: WebView
    private var done = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#16110d"))
        }

        status = TextView(this).apply {
            setTextColor(Color.parseColor("#f0e6da"))
            textSize = 12f
            setPadding(28, 22, 28, 22)
            setTextIsSelectable(true)   // 長いエラーをコピーして貼れるように
            text = "eBay にログインして許可してください"
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#221b14"))
            addView(status)
        }
        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        web = WebView(this)
        root.addView(
            web,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        closeBtn = Button(this).apply {
            text = "閉じる"
            isAllCaps = false
            setOnClickListener { finishWith(null) }
        }
        root.addView(
            closeBtn,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        setContentView(root)

        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString = userAgentString.replace("; wv", "")
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?, request: WebResourceRequest?
            ): Boolean = handle(request?.url)

            override fun onPageStarted(
                view: WebView?, url: String?, favicon: android.graphics.Bitmap?
            ) {
                handle(url?.let { Uri.parse(it) })
            }
        }

        // 先に設定の取り違えを止める。ここで弾けるものは eBay まで行かせない
        val bad = EbayApi.preflight(this)
        if (bad != null) {
            show("設定を見直してください\n\n$bad")
            return
        }

        val url = EbayApi.consentUrl(this)
        show("${EbayApi.envName(this)}の eBay にログインして許可してください")
        web.loadUrl(url)
    }

    private fun show(msg: String) {
        runOnUiThread { status.text = msg }
    }

    /**
     * 失敗の中身をそのまま出す。何が返ってきたか読めないと直しようがないので、
     * eBay の言い分は削らずに載せて、思い当たる原因を下に足す。
     */
    private fun showError(head: String, detail: String) {
        val hint = when {
            detail.contains("invalid_client") || detail.contains("401") ->
                "\n\n思い当たること:\n" +
                        "・Cert ID (Client Secret) が違う、または作り直して古いままになっている\n" +
                        "・App ID と Cert ID が別のキーセットのもの\n" +
                        "・Cert ID を入れ直したあと保存を押していない"
            detail.contains("invalid_grant") ->
                "\n\n認可コードが期限切れか、すでに使われています。もう一度やり直してください。"
            detail.contains("redirect_uri") || detail.contains("invalid_request") ->
                "\n\nRuName が eBay 側の登録と一致していない可能性があります。"
            else -> ""
        }
        show("$head\n\n$detail$hint\n\n（この文字は選んでコピーできます）")
    }

    /** code を見つけたら true（＝この URL は読み込ませない）。 */
    private fun handle(uri: Uri?): Boolean {
        if (done || uri == null) return false
        val code = uri.getQueryParameter("code") ?: return false
        done = true
        show("認可コードを受け取りました。トークンに交換しています…")
        Thread {
            try {
                EbayApi.tokenFromCode(this, code)
                runOnUiThread { finishWith("eBay と連携しました") }
            } catch (e: Exception) {
                val detail = e.message ?: e.javaClass.simpleName
                runOnUiThread {
                    done = false      // やり直せるようにしておく
                    showError("トークンの取得に失敗しました", detail)
                }
            }
        }.apply { isDaemon = true }.start()
        return true
    }

    private fun finishWith(msg: String?) {
        if (msg != null) setResult(Activity.RESULT_OK, intent.putExtra("msg", msg))
        finish()
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }
}
