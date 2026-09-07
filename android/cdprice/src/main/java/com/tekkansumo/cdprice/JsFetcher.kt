package com.tekkansumo.cdprice

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * JavaScript で組み立てられるページ（メルカリなど）用の取得。
 *
 * 画面に出さない WebView にページを読ませ、描画が落ち着いたところで
 * outerHTML を回収する。あとは普通のページと同じ [Extract] に流せる。
 * WebView はメインスレッド専用かつ同時に複数動かすと重いので、1本ずつ順番に使う。
 */
object JsFetcher {

    private val lock = ReentrantLock()
    private val main = Handler(Looper.getMainLooper())

    /** 取得できた HTML。失敗なら null。 */
    @SuppressLint("SetJavaScriptEnabled")
    fun fetch(ctx: Context, url: String, timeoutMs: Long = 25_000): Res = lock.withLock {
        val latch = CountDownLatch(1)
        var html = ""
        var error: String? = null
        var web: WebView? = null

        main.post {
            try {
                val w = WebView(ctx.applicationContext)
                web = w
                w.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    userAgentString = Http.UA
                    loadsImagesAutomatically = false   // 表示しないので画像は要らない
                    blockNetworkImage = true
                }
                var finished = false
                var lastLen = -1
                var stable = 0
                val deadline = System.currentTimeMillis() + timeoutMs

                lateinit var poll: Runnable
                poll = Runnable {
                    val w2 = web ?: return@Runnable
                    if (latch.count == 0L) return@Runnable
                    w2.evaluateJavascript("document.documentElement.outerHTML") { v ->
                        val s = try {
                            JSONArray("[$v]").getString(0)
                        } catch (e: Exception) {
                            ""
                        }
                        // 商品らしい金額が出ていて、かつ長さが伸びなくなったら完了とみなす
                        val hasPrice = s.contains('¥') || s.contains('円') || s.contains('￥')
                        if (hasPrice && s.length in (lastLen - 200)..(lastLen + 200)) stable++ else stable = 0
                        lastLen = s.length
                        if (s.length > html.length) html = s

                        if (stable >= 2 || System.currentTimeMillis() > deadline) {
                            if (html.isEmpty()) error = "描画待ちで中身が取れなかった"
                            latch.countDown()
                        } else {
                            main.postDelayed(poll, 700)
                        }
                    }
                }

                w.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, u: String?) {
                        if (finished) return
                        finished = true
                        main.postDelayed(poll, 1200)
                    }
                }
                w.loadUrl(url)
                // onPageFinished が来ないページ用の保険
                main.postDelayed({ if (!finished) { finished = true; poll.run() } }, 8000)
            } catch (e: Exception) {
                error = e.javaClass.simpleName + ": " + (e.message ?: "")
                latch.countDown()
            }
        }

        val ok = latch.await(timeoutMs + 12_000, TimeUnit.MILLISECONDS)
        main.post { web?.let { it.stopLoading(); it.destroy() } }
        if (!ok && html.isEmpty()) error = error ?: "時間切れ"

        Res(url, if (html.isNotEmpty()) 200 else 0, html, error.takeIf { html.isEmpty() })
    }
}
