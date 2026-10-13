package com.tekkansumo.bookoffsearch

import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class SpApiException(msg: String) : Exception(msg)

/**
 * Amazon Selling Partner API（出品者向けの公式 API）。日本は極東リージョン。
 *
 *   認証:   LWA の refresh token → access token（1 時間有効）。2023 年 10 月以降 SigV4 署名は不要
 *   JAN→ASIN: Catalog Items 2022-04-01  GET /catalog/2022-04-01/items?identifiers=…（20 件まで、2 回/秒）
 *   中古最安値: Product Pricing v0  POST /batches/products/pricing/v0/itemOffers（20 件まで、10 秒に 1 回）
 *
 * セラーセントラルで作った「自分用アプリ」の LWA クライアント ID / シークレットと、
 * 「アプリを承認」で出る refresh token を使う。ロールは「商品の出品」と「価格」が要る。
 */
class SpApi(
    private val clientId: String,
    private val clientSecret: String,
    private val refreshToken: String,
    private val sleeper: (Double) -> Unit = { Thread.sleep((it * 1000).toLong()) },
    private val endpoint: String = ENDPOINT,
    private val tokenUrl: String = TOKEN_URL
) {
    companion object {
        const val ENDPOINT = "https://sellingpartnerapi-fe.amazon.com"
        const val MARKETPLACE_JP = "A1VC38T7YXB528"
        const val TOKEN_URL = "https://api.amazon.com/auth/o2/token"
        private const val BATCH = 20
        private val JSON_TYPE = "application/json".toMediaType()

        /** Catalog の結果から JAN → ASIN。識別子の種類名（EAN/JAN/GTIN）ではなく値で照合する。 */
        fun parseCatalog(json: String, jans: List<String>): Map<String, String> {
            val out = HashMap<String, String>()
            val items = JSONObject(json).optJSONArray("items") ?: return out
            val wanted = jans.associateBy { it.trimStart('0') }
            for (k in 0 until items.length()) {
                val item = items.getJSONObject(k)
                val asin = item.optString("asin")
                if (asin.isEmpty()) continue
                val byMarket = item.optJSONArray("identifiers") ?: JSONArray()
                for (m in 0 until byMarket.length()) {
                    val ids = byMarket.getJSONObject(m).optJSONArray("identifiers") ?: continue
                    for (i in 0 until ids.length()) {
                        val v = ids.getJSONObject(i).optString("identifier").trimStart('0')
                        val jan = wanted[v] ?: continue
                        out.putIfAbsent(jan, asin)
                    }
                }
            }
            // 1 件だけ問い合わせて 1 件だけ返ってきたなら、識別子が付いていなくてもそれ
            if (out.isEmpty() && jans.size == 1 && items.length() == 1) {
                items.getJSONObject(0).optString("asin").takeIf { it.isNotEmpty() }?.let { out[jans[0]] = it }
            }
            return out
        }

        /**
         * getItemOffersBatch の結果から ASIN → 中古最安値（送料別の本体価格）。
         * 値が null の ASIN は「中古の出品なし」。結果に出てこない ASIN は失敗扱い。
         */
        fun parseOffersBatch(json: String, asins: List<String>): Map<String, Int?> {
            val out = HashMap<String, Int?>()
            val res = JSONObject(json).optJSONArray("responses") ?: return out
            for (k in 0 until res.length()) {
                val r = res.getJSONObject(k)
                val code = r.optJSONObject("status")?.optInt("statusCode") ?: 0
                val body = r.optJSONObject("body") ?: continue
                val payload = body.optJSONObject("payload") ?: body
                val asin = payload.optString("ASIN").ifEmpty {
                    r.optJSONObject("request")?.optString("Asin") ?: ""
                }.ifEmpty { asins.getOrNull(k) ?: "" }
                if (asin.isEmpty() || code !in 200..299) continue

                var min: Int? = null
                val lows = payload.optJSONObject("Summary")?.optJSONArray("LowestPrices")
                if (lows != null) for (i in 0 until lows.length()) {
                    val lp = lows.getJSONObject(i)
                    if (!lp.optString("condition").equals("used", true)) continue
                    val p = amount(lp.optJSONObject("ListingPrice")) ?: continue
                    if (min == null || p < min) min = p
                }
                if (min == null) {
                    // 要約が無いときは出品一覧から
                    val offers = payload.optJSONArray("Offers")
                    if (offers != null) for (i in 0 until offers.length()) {
                        val o = offers.getJSONObject(i)
                        val cond = o.optString("SubCondition") + o.optString("ItemCondition")
                        if (cond.contains("new", true) && !cond.contains("used", true)) continue
                        val p = amount(o.optJSONObject("ListingPrice")) ?: continue
                        if (min == null || p < min) min = p
                    }
                }
                out[asin] = min
            }
            return out
        }

        private fun amount(o: JSONObject?): Int? {
            if (o == null || !o.has("Amount")) return null
            return Math.round(o.optDouble("Amount")).toInt().takeIf { it > 0 }
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    private var token: String? = null
    private var tokenExpiry = 0L

    /** access token。期限の 1 分前に取り直す。 */
    @Synchronized
    fun accessToken(): String {
        val now = System.currentTimeMillis()
        token?.let { if (now < tokenExpiry - 60_000) return it }
        val form = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("client_id", clientId)
            .add("client_secret", clientSecret)
            .build()
        val req = Request.Builder().url(tokenUrl).post(form).build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                val err = try {
                    JSONObject(body).let { it.optString("error_description").ifEmpty { it.optString("error") } }
                } catch (e: Exception) { "" }
                throw SpApiException("Amazon へのログインに失敗しました（HTTP ${resp.code} $err）。" +
                        "クライアント ID・シークレット・リフレッシュトークンを確認してください")
            }
            val o = JSONObject(body)
            token = o.getString("access_token")
            tokenExpiry = now + o.optLong("expires_in", 3600) * 1000
            return token!!
        }
    }

    /** API を 1 回呼ぶ。429 は待って再試行。戻り値は本文。 */
    private fun call(method: String, path: String, body: String? = null, retryWait: Double): String {
        var tries = 0
        while (true) {
            tries++
            val b = Request.Builder().url(endpoint + path)
                .header("x-amz-access-token", accessToken())
                .header("Accept", "application/json")
                .header("User-Agent", "BookoffSearch/1.0 (Language=Kotlin; Platform=Android)")
            if (body != null) b.method(method, body.toRequestBody(JSON_TYPE)) else b.method(method, null)
            client.newCall(b.build()).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                if (resp.isSuccessful) return text
                if ((resp.code == 429 || resp.code >= 500) && tries < 5) {
                    sleeper(retryWait * tries)
                    return@use
                }
                val msg = try {
                    JSONObject(text).optJSONArray("errors")?.optJSONObject(0)?.optString("message") ?: ""
                } catch (e: Exception) { "" }
                throw SpApiException(when (resp.code) {
                    401, 403 -> "SP-API の権限がありません（HTTP ${resp.code}）。アプリに「商品の出品」と「価格」のロールが付いているか確認してください。$msg"
                    400 -> "SP-API が要求を受け付けませんでした（HTTP 400）。$msg"
                    else -> "SP-API エラー（HTTP ${resp.code}）。$msg"
                })
            }
        }
    }

    /** JAN → ASIN。見つからない JAN は結果に含まれない。 */
    fun asinsByJan(jans: List<String>, progress: (Int, Int) -> Unit = { _, _ -> }): Map<String, String> {
        val out = HashMap<String, String>()
        var typ = "JAN"
        for ((n, chunk) in jans.chunked(BATCH).withIndex()) {
            progress(n * BATCH, jans.size)
            fun path(t: String) = "/catalog/2022-04-01/items?marketplaceIds=$MARKETPLACE_JP" +
                    "&identifiersType=$t&includedData=identifiers,summaries&pageSize=$BATCH" +
                    "&identifiers=" + URLEncoder.encode(chunk.joinToString(","), "UTF-8")
            val text = try {
                call("GET", path(typ), retryWait = 1.0)
            } catch (e: SpApiException) {
                // 識別子の種類名を受け付けないときは EAN で（JAN は EAN-13 と同じ番号）
                if (typ == "JAN" && e.message?.contains("400") == true) {
                    typ = "EAN"
                    call("GET", path(typ), retryWait = 1.0)
                } else throw e
            }
            out.putAll(parseCatalog(text, chunk))
            sleeper(0.6)   // 2 回/秒の制限
        }
        return out
    }

    /** ASIN → 中古最安値（null は中古の出品なし）。10 秒に 1 回しか呼べない。 */
    fun usedLowest(asins: List<String>, progress: (Int, Int) -> Unit = { _, _ -> }): Map<String, Int?> {
        val out = HashMap<String, Int?>()
        for ((n, chunk) in asins.chunked(BATCH).withIndex()) {
            if (n > 0) sleeper(10.5)
            progress(n * BATCH, asins.size)
            val reqs = JSONArray()
            for (a in chunk) reqs.put(JSONObject().apply {
                put("uri", "/products/pricing/v0/items/$a/offers")
                put("method", "GET")
                put("MarketplaceId", MARKETPLACE_JP)
                put("ItemCondition", "Used")
                put("CustomerType", "Consumer")
            })
            val text = call("POST", "/batches/products/pricing/v0/itemOffers",
                JSONObject().put("requests", reqs).toString(), retryWait = 12.0)
            out.putAll(parseOffersBatch(text, chunk))
        }
        return out
    }
}
