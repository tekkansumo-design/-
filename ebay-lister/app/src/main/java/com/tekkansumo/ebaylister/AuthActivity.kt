package com.tekkansumo.ebaylister

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity

/**
 * eBay の同意画面を表示し、承認後に RuName の accept URL へ戻ってきたところで
 * URL の code パラメータを取り出して呼び出し元に返す。
 */
class AuthActivity : AppCompatActivity() {

    private var done = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL) ?: return finish()

        val web = WebView(this)
        setContentView(web)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        CookieManager.getInstance().setAcceptCookie(true)

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                check(request.url)

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                check(Uri.parse(url))
            }
        }
        web.loadUrl(url)
    }

    /** 戻り URL なら結果を返して閉じる。true ならページ遷移を止める。 */
    private fun check(uri: Uri): Boolean {
        if (done) return true
        val host = uri.host.orEmpty()
        if (host.startsWith("signin.")) return false
        val code = runCatching { uri.getQueryParameter("code") }.getOrNull()
        val failed = runCatching {
            uri.getQueryParameter("isAuthSuccessful") == "false" || uri.getQueryParameter("error") != null
        }.getOrDefault(false)
        if (!code.isNullOrBlank()) {
            done = true
            setResult(RESULT_OK, Intent().putExtra(EXTRA_CODE, code))
            finish()
            return true
        }
        if (failed && !host.startsWith("auth.") ) {
            done = true
            setResult(RESULT_CANCELED)
            finish()
            return true
        }
        return false
    }

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_CODE = "code"
    }
}
