package com.tekkansumo.cdlist

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** 1 回分の取得結果。error が入っているときは body は空。 */
data class Res(val url: String, val code: Int, val body: String, val error: String?)

object Http {

    const val UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"

    /** MusicBrainz は素性の分かる User-Agent を求めるので、店の取得とは分ける。 */
    const val APP_UA = "BookoffArtistCD/1.0 ( https://github.com/tekkansumo-design/- )"

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .followRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    fun get(
        url: String,
        ua: String = UA,
        accept: String = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
    ): Res {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", ua)
                .header("Accept", accept)
                .header("Accept-Language", "ja,en-US;q=0.9,en;q=0.8")
                .build()
            client.newCall(req).execute().use { r ->
                Res(r.request.url.toString(), r.code, r.body?.string() ?: "", null)
            }
        } catch (e: Exception) {
            Res(url, 0, "", e.javaClass.simpleName + ": " + (e.message ?: ""))
        }
    }
}
