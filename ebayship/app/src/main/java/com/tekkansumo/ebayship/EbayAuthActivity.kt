package com.tekkansumo.ebayship

import android.annotation.SuppressLint
import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * eBay の同意画面を出して、戻ってきた URL から認可コードを拾う。
 *
 * RuName の飛び先は開発者が自分で決めた URL なので、実際に開けるとは限らない。
 * そのため「読み込めたか」ではなく「code が付いた URL に飛ぼうとしたか」で判定する。
 */
class EbayAuthActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private var done = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        status = TextView(this).apply {
            text = "eBay にログインして許可してください"
            setPadding(32, 28, 32, 28)
            gravity = Gravity.CENTER_VERTICAL
        }
        root.addView(
            status,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        val web = WebView(this)
        root.addView(
            web,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
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

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                handle(url?.let { Uri.parse(it) })
            }
        }

        val s = Db.settings(this)
        if (s.optString("ebayClientId").isEmpty() || s.optString("ebayRuName").isEmpty()) {
            finishWith("App ID と RuName を先に設定してください")
            return
        }
        web.loadUrl(EbayApi.consentUrl(this))
    }

    /** code を見つけたら true（＝この URL は読み込ませない）。 */
    private fun handle(uri: Uri?): Boolean {
        if (done || uri == null) return false
        val code = uri.getQueryParameter("code") ?: return false
        done = true
        status.text = "認可コードを受け取りました。トークンに交換しています…"
        Thread {
            val msg = try {
                EbayApi.tokenFromCode(this, code)
                "eBay と連携しました"
            } catch (e: Exception) {
                e.message ?: "連携に失敗しました"
            }
            runOnUiThread { finishWith(msg) }
        }.apply { isDaemon = true }.start()
        return true
    }

    private fun finishWith(msg: String) {
        setResult(Activity.RESULT_OK, intent.putExtra("msg", msg))
        finish()
    }
}
