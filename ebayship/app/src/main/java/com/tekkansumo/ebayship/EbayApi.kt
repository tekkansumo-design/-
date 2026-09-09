package com.tekkansumo.ebayship

import android.content.Context
import android.util.Base64
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/** eBay Sell Fulfillment API。発送待ちの取得と、追跡番号の登録。 */
object EbayApi {

    class ApiException(message: String) : Exception(message)

    /** sell.fulfillment だけあれば一覧取得も発送登録もできる。 */
    const val SCOPE = "https://api.ebay.com/oauth/api_scope/sell.fulfillment"

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * サンドボックスか本番か。
     *
     * App ID は "-SBX-" か "-PRD-" を含んでいて、それ自体がどちらの鍵かを表している。
     * 設定の環境と食い違っていると認可画面が "OAuth client was not found" を返すだけで、
     * 食い違いに意味がある場面は無いので、鍵の側を正として扱い、
     * 設定の環境は App ID から読み取れないときだけ見る。
     */
    private fun sandbox(ctx: Context): Boolean {
        val id = Db.get(ctx, "ebayClientId").uppercase()
        if (id.contains("-SBX-")) return true
        if (id.contains("-PRD-")) return false
        return Db.get(ctx, "ebayEnv") == "sandbox"
    }

    /** 設定画面に出す用。App ID から読み取った環境の名前。 */
    fun envName(ctx: Context): String = if (sandbox(ctx)) "サンドボックス" else "本番"

    /**
     * 連携を始める前に、明らかにおかしい設定を見つける。
     * 見つからなければ null。
     */
    fun preflight(ctx: Context): String? {
        val s = Db.settings(ctx)
        val id = s.optString("ebayClientId")
        val secret = s.optString("ebayClientSecret")
        val ru = s.optString("ebayRuName")

        if (id.isEmpty()) return "App ID (Client ID) が未入力です"
        if (secret.isEmpty()) return "Cert ID (Client Secret) が未入力です"
        if (ru.isEmpty()) return "RuName が未入力です"

        // App ID と RuName は形が違う。取り違えるとこの先で必ず失敗する
        if (ru.uppercase().contains("-SBX-") || ru.uppercase().contains("-PRD-")) {
            return "RuName の欄に App ID が入っているようです。\n" +
                    "RuName は SBX / PRD を含まない別の文字列です。\n" +
                    "Application Keys の User Tokens から確認してください。"
        }
        // 鍵の環境が揃っていないと、どちらの認可サーバーでも弾かれる
        val idSbx = id.uppercase().contains("-SBX-")
        val secretSbx = secret.uppercase().startsWith("SBX-")
        if (id.uppercase().let { it.contains("-SBX-") || it.contains("-PRD-") } &&
            secret.uppercase().let { it.startsWith("SBX-") || it.startsWith("PRD-") } &&
            idSbx != secretSbx
        ) {
            return "App ID と Cert ID の環境が揃っていません。\n" +
                    "App ID は${if (idSbx) "サンドボックス" else "本番"}、" +
                    "Cert ID は${if (secretSbx) "サンドボックス" else "本番"}のものです。\n" +
                    "同じキーセットの組で入れ直してください。"
        }
        return null
    }

    fun apiBase(ctx: Context) =
        if (sandbox(ctx)) "https://api.sandbox.ebay.com" else "https://api.ebay.com"

    private fun authBase(ctx: Context) =
        if (sandbox(ctx)) "https://auth.sandbox.ebay.com" else "https://auth.ebay.com"

    /** 同意画面の URL。ここで出た code を tokenFromCode に渡す。 */
    fun consentUrl(ctx: Context): String {
        val s = Db.settings(ctx)
        val clientId = s.optString("ebayClientId")
        val ruName = s.optString("ebayRuName")
        return authBase(ctx) + "/oauth2/authorize" +
                "?client_id=" + enc(clientId) +
                "&response_type=code" +
                "&redirect_uri=" + enc(ruName) +
                "&scope=" + enc(SCOPE) +
                "&prompt=login"
    }

    private fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    private fun basic(ctx: Context): String {
        val s = Db.settings(ctx)
        val raw = s.optString("ebayClientId") + ":" + s.optString("ebayClientSecret")
        return "Basic " + Base64.encodeToString(raw.toByteArray(), Base64.NO_WRAP)
    }

    // ------------------------------------------------------------------ トークン

    /** 同意画面で得た code を refresh token に替えて保存する。 */
    fun tokenFromCode(ctx: Context, code: String) {
        val s = Db.settings(ctx)
        val body = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", s.optString("ebayRuName"))
            .build()
        val res = postToken(ctx, body)
        val refresh = res.optString("refresh_token")
        if (refresh.isEmpty()) throw ApiException("refresh token が返ってきませんでした")
        Db.save(ctx, JSONObject().apply {
            put("ebayRefreshToken", refresh)
            put("ebayAccessToken", res.optString("access_token"))
            put(
                "ebayAccessExpiresAt",
                (System.currentTimeMillis() + res.optLong("expires_in", 7200L) * 1000L).toString()
            )
        })
    }

    /** 有効なアクセストークン。切れていれば refresh token で取り直す。 */
    private fun accessToken(ctx: Context): String {
        val s = Db.settings(ctx)
        val cached = s.optString("ebayAccessToken")
        val expires = s.optString("ebayAccessExpiresAt").toLongOrNull() ?: 0L
        // 期限ぎりぎりで使うと通信中に切れるので 2 分の余裕を見る
        if (cached.isNotEmpty() && expires - 120_000L > System.currentTimeMillis()) return cached

        val refresh = s.optString("ebayRefreshToken")
        if (refresh.isEmpty()) throw ApiException("eBay と未連携です。設定から連携してください")

        val body = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refresh)
            .add("scope", SCOPE)
            .build()
        val res = postToken(ctx, body)
        val token = res.optString("access_token")
        if (token.isEmpty()) throw ApiException("アクセストークンを取得できませんでした")
        Db.save(ctx, JSONObject().apply {
            put("ebayAccessToken", token)
            put(
                "ebayAccessExpiresAt",
                (System.currentTimeMillis() + res.optLong("expires_in", 7200L) * 1000L).toString()
            )
        })
        return token
    }

    private fun postToken(ctx: Context, body: FormBody): JSONObject {
        val req = Request.Builder()
            .url(apiBase(ctx) + "/identity/v1/oauth2/token")
            .addHeader("Authorization", basic(ctx))
            .addHeader("Content-Type", "application/x-www-form-urlencoded")
            .post(body)
            .build()
        http.newCall(req).execute().use { r ->
            val text = r.body?.string() ?: ""
            if (!r.isSuccessful) throw ApiException("認証に失敗しました (${r.code}) $text")
            return JSONObject(text)
        }
    }

    /** 連携を切る。 */
    fun unlink(ctx: Context) {
        Db.save(ctx, JSONObject().apply {
            put("ebayRefreshToken", "")
            put("ebayAccessToken", "")
            put("ebayAccessExpiresAt", "0")
        })
    }

    // -------------------------------------------------------------------- 注文

    /**
     * 発送待ち（未発送）の注文。
     * eBay の Awaiting shipment は orderfulfillmentstatus が NOT_STARTED か IN_PROGRESS のもの。
     */
    fun fetchAwaitingShipment(ctx: Context): List<JSONObject> {
        val token = accessToken(ctx)
        val filter = "orderfulfillmentstatus:%7BNOT_STARTED%7CIN_PROGRESS%7D"
        val out = ArrayList<JSONObject>()
        var offset = 0
        while (true) {
            val url = apiBase(ctx) +
                    "/sell/fulfillment/v1/order?limit=50&offset=$offset&filter=$filter"
            val req = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Accept", "application/json")
                .addHeader("X-EBAY-C-MARKETPLACE-ID", "EBAY_US")
                .get()
                .build()
            val body = http.newCall(req).execute().use { r ->
                val text = r.body?.string() ?: ""
                if (!r.isSuccessful) throw ApiException("注文の取得に失敗しました (${r.code}) $text")
                JSONObject(text)
            }
            val arr = body.optJSONArray("orders") ?: JSONArray()
            for (i in 0 until arr.length()) out.add(simplify(arr.getJSONObject(i)))
            val total = body.optInt("total", out.size)
            offset += arr.length()
            if (arr.length() == 0 || offset >= total) break
        }
        return out
    }

    /** API の返事から画面と送り状で使うぶんだけ取り出す。 */
    private fun simplify(o: JSONObject): JSONObject {
        val items = JSONArray()
        var title = ""
        var qty = 0
        val li = o.optJSONArray("lineItems") ?: JSONArray()
        for (i in 0 until li.length()) {
            val it = li.getJSONObject(i)
            val t = it.optString("title")
            val q = it.optInt("quantity", 1)
            if (title.isEmpty()) title = t
            qty += q
            items.put(JSONObject().apply {
                put("lineItemId", it.optString("lineItemId"))
                put("title", t)
                put("quantity", q)
                put("sku", it.optString("sku"))
                val cost = it.optJSONObject("lineItemCost")
                put("price", cost?.optString("value") ?: "")
                put("currency", cost?.optString("currency") ?: "")
            })
        }
        if (li.length() > 1) title = "$title ほか${li.length() - 1}点"

        val ship = o.optJSONArray("fulfillmentStartInstructions")
            ?.optJSONObject(0)?.optJSONObject("shippingStep")
        val to = ship?.optJSONObject("shipTo")
        val addr = to?.optJSONObject("contactAddress")
        val address = JSONObject().apply {
            put("name", to?.optString("fullName") ?: "")
            put("line1", addr?.optString("addressLine1") ?: "")
            put("line2", addr?.optString("addressLine2") ?: "")
            put("city", addr?.optString("city") ?: "")
            put("state", addr?.optString("stateOrProvince") ?: "")
            put("postal", addr?.optString("postalCode") ?: "")
            put("country", addr?.optString("countryCode") ?: "")
            put("phone", to?.optJSONObject("primaryPhone")?.optString("phoneNumber") ?: "")
            put("email", to?.optString("email") ?: "")
        }

        val total = o.optJSONObject("pricingSummary")?.optJSONObject("total")

        return JSONObject().apply {
            put("orderId", o.optString("orderId"))
            put("legacyOrderId", o.optString("legacyOrderId"))
            put("creationDate", o.optString("creationDate"))
            put("fulfillmentStatus", o.optString("orderFulfillmentStatus"))
            put("buyer", o.optJSONObject("buyer")?.optString("username") ?: "")
            put("title", title)
            put("quantity", qty)
            put("lineItems", items)
            put("address", address)
            put("totalValue", total?.optString("value") ?: "")
            put("totalCurrency", total?.optString("currency") ?: "")
        }
    }

    // ---------------------------------------------------------------- 発送登録

    /**
     * 追跡番号を eBay に登録する（Awaiting shipment から外れる）。
     * 返り値は fulfillmentId。
     */
    fun createFulfillment(
        ctx: Context, order: JSONObject, trackingNo: String, carrier: String
    ): String {
        val token = accessToken(ctx)
        val orderId = order.getString("orderId")

        val lines = JSONArray()
        val items = order.optJSONArray("lineItems") ?: JSONArray()
        for (i in 0 until items.length()) {
            val it = items.getJSONObject(i)
            lines.put(JSONObject().apply {
                put("lineItemId", it.optString("lineItemId"))
                put("quantity", it.optInt("quantity", 1))
            })
        }

        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        iso.timeZone = TimeZone.getTimeZone("UTC")

        val payload = JSONObject().apply {
            put("lineItems", lines)
            put("shippedDate", iso.format(Date()))
            put("shippingCarrierCode", carrier)
            put("trackingNumber", trackingNo)
        }

        val req = Request.Builder()
            .url(apiBase(ctx) + "/sell/fulfillment/v1/order/$orderId/shipping_fulfillment")
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        http.newCall(req).execute().use { r ->
            val text = r.body?.string() ?: ""
            if (!r.isSuccessful) throw ApiException("発送登録に失敗しました (${r.code}) $text")
            // 作成された fulfillment は Location ヘッダの末尾に入る
            val loc = r.header("Location") ?: ""
            return loc.substringAfterLast('/', "")
        }
    }
}
