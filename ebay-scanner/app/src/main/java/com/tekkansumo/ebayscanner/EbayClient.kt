package com.tekkansumo.ebayscanner

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * eBay Browse API を呼ぶ。
 * アプリ用の鍵（Client ID / Client Secret）だけで使える
 * 「アプリケーショントークン」を取得して検索する。ユーザーのログインは不要。
 */
class EbayClient {

    data class Item(
        val title: String,
        /** 商品価格 + 買い手が払う送料（USD）。 */
        val totalUsd: Double,
        val url: String,
        val condition: String,
    )

    class ApiException(message: String) : IOException(message)

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private var token: String? = null
    private var tokenFor: String? = null
    private var tokenExpires = 0L

    private val base = "https://api.ebay.com/buy/browse/v1/item_summary"

    // 部品取り・ジャンク（7000）を除いた状態
    private val conditions = "conditionIds:{1000|1500|1750|2000|2010|2020|2030|2500|2750|3000|4000|5000|6000}"

    private fun filter(jpOnly: Boolean) = buildString {
        append("buyingOptions:{FIXED_PRICE},priceCurrency:USD,")
        append(conditions)
        if (jpOnly) append(",itemLocationCountry:JP")
    }

    private fun accessToken(id: String, secret: String): String {
        val now = System.currentTimeMillis()
        token?.let { if (tokenFor == id && now < tokenExpires) return it }
        val basic = Base64.encodeToString("$id:$secret".toByteArray(), Base64.NO_WRAP)
        val req = Request.Builder()
            .url("https://api.ebay.com/identity/v1/oauth2/token")
            .header("Authorization", "Basic $basic")
            .post(
                FormBody.Builder()
                    .add("grant_type", "client_credentials")
                    .add("scope", "https://api.ebay.com/oauth/api_scope")
                    .build()
            )
            .build()
        http.newCall(req).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                throw ApiException(
                    if (r.code == 401) "eBay の鍵が正しくありません（設定を確認）"
                    else "eBay 認証エラー ${r.code}"
                )
            }
            val j = JSONObject(body)
            val t = j.getString("access_token")
            token = t
            tokenFor = id
            tokenExpires = now + (j.optLong("expires_in", 7200) - 120) * 1000
            return t
        }
    }

    private fun parseItems(body: String): List<Item> {
        val arr = JSONObject(body).optJSONArray("itemSummaries") ?: return emptyList()
        val out = ArrayList<Item>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val price = o.optJSONObject("price") ?: continue
            if (price.optString("currency") != "USD") continue
            val v = price.optString("value").toDoubleOrNull() ?: continue
            var ship = 0.0
            o.optJSONArray("shippingOptions")?.optJSONObject(0)
                ?.optJSONObject("shippingCost")?.optString("value")?.toDoubleOrNull()
                ?.let { ship = it }
            out += Item(
                title = o.optString("title"),
                totalUsd = v + ship,
                url = o.optString("itemWebUrl"),
                condition = o.optString("condition"),
            )
        }
        return out
    }

    private fun call(id: String, secret: String, build: (Request.Builder) -> Request.Builder): List<Item> {
        val t = accessToken(id, secret)
        val req = build(
            Request.Builder()
                .header("Authorization", "Bearer $t")
                .header("X-EBAY-C-MARKETPLACE-ID", "EBAY_US")
        ).build()
        http.newCall(req).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (r.code == 401) token = null
            if (!r.isSuccessful) throw ApiException("eBay 検索エラー ${r.code}")
            return parseItems(body)
        }
    }

    private fun searchUrl(jpOnly: Boolean, extra: HttpUrl.Builder.() -> Unit): HttpUrl =
        "$base/search".toHttpUrl().newBuilder()
            .addQueryParameter("limit", "40")
            .addQueryParameter("filter", filter(jpOnly))
            .apply(extra)
            .build()

    /**
     * 日本発送を優先する設定なら、まず日本発送の出品だけで探し、
     * 3 件未満なら全世界の出品で探し直す。
     */
    private fun withFallback(preferJp: Boolean, run: (jpOnly: Boolean) -> List<Item>): List<Item> {
        if (preferJp) {
            val jp = run(true)
            if (jp.size >= 3) return jp
        }
        return run(false)
    }

    suspend fun searchGtin(gtin: String, id: String, secret: String, preferJp: Boolean) =
        withContext(Dispatchers.IO) {
            withFallback(preferJp) { jp ->
                call(id, secret) { it.url(searchUrl(jp) { addQueryParameter("gtin", gtin) }) }
            }
        }

    suspend fun searchKeywords(q: String, id: String, secret: String, preferJp: Boolean) =
        withContext(Dispatchers.IO) {
            withFallback(preferJp) { jp ->
                call(id, secret) { it.url(searchUrl(jp) { addQueryParameter("q", q) }) }
            }
        }

    suspend fun searchImage(jpeg: ByteArray, id: String, secret: String, preferJp: Boolean) =
        withContext(Dispatchers.IO) {
            val json = JSONObject().put("image", Base64.encodeToString(jpeg, Base64.NO_WRAP)).toString()
            withFallback(preferJp) { jp ->
                call(id, secret) {
                    it.url(
                        "$base/search_by_image".toHttpUrl().newBuilder()
                            .addQueryParameter("limit", "40")
                            .addQueryParameter("filter", filter(jp))
                            .build()
                    ).post(json.toRequestBody("application/json".toMediaType()))
                }
            }
        }

    /** 為替（1 USD = 何円）を無料 API から取る。 */
    suspend fun fetchUsdJpy(): Double? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url("https://open.er-api.com/v6/latest/USD").build()
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@use null
                JSONObject(r.body!!.string()).getJSONObject("rates").optDouble("JPY").takeIf { it > 50 }
            }
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        /** 実際に「売れた」価格を確認する eBay の検索ページ。 */
        fun soldSearchUrl(q: String): String =
            "https://www.ebay.com/sch/i.html".toHttpUrl().newBuilder()
                .addQueryParameter("_nkw", q)
                .addQueryParameter("LH_Sold", "1")
                .addQueryParameter("LH_Complete", "1")
                .build().toString()
    }
}
