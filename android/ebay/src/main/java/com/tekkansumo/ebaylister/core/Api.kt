package com.tekkansumo.ebaylister.core

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong

/**
 * 画面から呼ばれる窓口。ebay_lister.py の Flask ルートと同じパス・同じ JSON を返すので、
 * 画面（HTML/JS）は Web 版と共通のまま使える。
 */
class Api(private val store: ConfStore) {

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS).build()
    val ebay = Ebay(http)
    val prices = Prices(http, ebay)
    val claude = Claude()

    private class Draft(
        val photos: List<Photo>, val notes: String, val listing: JSONObject, val created: Long,
        var imageUrls: List<String>? = null, var uploadedSig: List<Int>? = null,
    )

    private val drafts = LinkedHashMap<String, Draft>()
    private var oauthState: String? = null
    private val rnd = SecureRandom()

    private fun token(n: Int): String {
        val b = ByteArray(n).also { rnd.nextBytes(it) }
        return b.joinToString("") { "%02x".format(it) }
    }

    private fun conf() = Conf.load(store)
    private fun save(c: JSONObject) = store.save(c.toString())

    /** 戻り値は (HTTP 相当の成否, JSON)。失敗時は {"error": "..."}。 */
    fun handle(method: String, path: String, body: JSONObject): Pair<Boolean, String> = try {
        true to route(method, path, body).toString()
    } catch (e: AppError) {
        false to JSONObject().put("error", e.message).toString()
    } catch (e: IOException) {
        false to JSONObject().put("error", "通信エラー: ${e.message}").toString()
    } catch (e: Exception) {
        false to JSONObject().put("error", "内部エラー: ${e.javaClass.simpleName}: ${e.message}").toString()
    }

    private fun route(method: String, path: String, b: JSONObject): Any = when ("$method $path") {
        "GET /api/meta" -> Conf.meta()
        "GET /api/config" -> Conf.public(conf())
        "POST /api/config" -> Conf.public(Conf.applyPatch(conf(), b).also { save(it) })
        "POST /api/config/clear_secret" -> clearSecret(b)
        "GET /api/ebay/auth_url" -> {
            oauthState = token(16)
            JSONObject().put("url", ebay.authUrl(conf(), oauthState!!))
        }
        "POST /api/ebay/auth_code" -> authCode(b)
        "GET /api/ebay/policies" -> ebay.policies(conf())
        "POST /api/ebay/location" -> location(b)
        "POST /api/identify" -> identify(b)
        "POST /api/categories" -> {
            val q = b.optString("q").trim()
            if (q.isEmpty()) throw AppError("キーワードを入力してください")
            ebay.categorySuggestions(conf(), q)
        }
        "POST /api/aspects" -> aspects(b)
        "POST /api/research" -> {
            val q = b.optString("q").trim()
            if (q.isEmpty()) throw AppError("キーワードを入力してください")
            prices.research(conf(), q.take(200))
        }
        "POST /api/list" -> list(b)
        else -> throw AppError("不明な操作です: $method $path")
    }

    private fun clearSecret(b: JSONObject): JSONObject {
        val k = b.optString("key")
        if (k !in Conf.SECRETS) throw AppError("不明な項目です")
        val c = conf().put(k, "")
        if (k == "refresh_token") c.put("refresh_token_expires", "")
        save(c)
        return Conf.public(c)
    }

    /** 同意後にリダイレクトされた URL（または code だけ）を受け取ってトークンに交換する。 */
    private fun authCode(b: JSONObject): JSONObject {
        val raw = b.optString("code").trim()
        if (raw.isEmpty()) throw AppError("リダイレクト後の URL か code を貼り付けてください")
        var code = raw
        if ("://" in raw || "code=" in raw) {
            val u = (if ("://" in raw) raw else "https://x/?" + raw.substringAfter("?")).toHttpUrlOrNull()
                ?: throw AppError("URL を読み取れません")
            code = u.queryParameter("code").orEmpty()
            val state = u.queryParameter("state").orEmpty()
            if (state.isNotEmpty() && oauthState != null && state != oauthState)
                throw AppError("state が一致しません。もう一度「eBay と連携」からやり直してください")
        }
        if (code.isEmpty()) throw AppError("URL に code が含まれていません")
        val c = conf()
        val j = ebay.exchangeCode(c, code)
        c.put("refresh_token", j.optString("refresh_token"))
        val exp = j.optLong("refresh_token_expires_in", 0)
        c.put("refresh_token_expires", if (exp > 0)
            SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(System.currentTimeMillis() + exp * 1000)) else "")
        save(c)
        oauthState = null
        return Conf.public(c)
    }

    private fun location(b: JSONObject): JSONObject {
        val key = b.optString("key").replace(Regex("[^A-Za-z0-9_-]"), "").take(36)
        if (key.isEmpty()) throw AppError("発送元キーは英数字で入力してください")
        val addr = JSONObject()
        for (k in listOf("postalCode", "city", "stateOrProvince", "addressLine1")) {
            val v = b.optString(k).trim()
            if (v.isNotEmpty()) addr.put(k, v)
        }
        addr.put("country", b.optString("country").ifEmpty { "JP" }.uppercase())
        if (!addr.has("postalCode") && !addr.has("city")) throw AppError("郵便番号か市区町村を入力してください")
        val c = conf()
        ebay.createLocation(c, key, addr)
        c.put("merchant_location_key", key)
        save(c)
        return JSONObject().put("ok", true).put("key", key)
    }

    private fun readPhotos(b: JSONObject): List<Photo> {
        val arr = b.optJSONArray("images") ?: JSONArray()
        if (arr.length() == 0) throw AppError("画像を選んでください")
        if (arr.length() > MAX_IMAGES) throw AppError("画像は $MAX_IMAGES 枚までです")
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            val mime = o.optString("mime").lowercase()
            if (mime !in IMAGE_MIMES) throw AppError("対応していない画像形式です ($mime)")
            val data = Base64.getDecoder().decode(o.getString("data"))
            if (data.size > MAX_IMAGE_BYTES) throw AppError("画像が大きすぎます（5MB まで）")
            Photo(data, mime)
        }
    }

    private fun identify(b: JSONObject): JSONObject {
        val c = conf()
        val photos = readPhotos(b)
        val hint = b.optString("hint").trim().take(500)
        val hasKeys = c.optString("client_id").isNotEmpty() && c.optString("client_secret").isNotEmpty()
        var hints = emptyList<String>()
        if (hasKeys && photos[0].mime == "image/jpeg") {
            hints = try { ebay.searchByImage(c, photos[0].data) } catch (e: Exception) { emptyList() }  // 補助なので失敗は無視
        }
        val (notes, sources) = claude.research(c, photos, hint, hints)
        val listing = claude.draftListing(c, photos, notes, hint)
        val id = token(8)
        synchronized(drafts) {
            if (drafts.size >= MAX_DRAFTS) drafts.remove(drafts.minByOrNull { it.value.created }!!.key)
            drafts[id] = Draft(photos, notes, listing, System.currentTimeMillis())
        }
        var cats = JSONArray()
        if (hasKeys) {
            try {
                cats = ebay.categorySuggestions(c, listing.optString("search_query").ifEmpty { listing.optString("ebay_title") })
            } catch (e: Exception) {
                listing.put("notes_ja", (listing.optString("notes_ja") + "\n（カテゴリ候補を取得できませんでした: ${e.message}）").trim())
            }
        }
        return JSONObject().put("draft_id", id).put("listing", listing).put("notes", notes)
            .put("sources", sources).put("ebay_hints", JSONArray(hints)).put("categories", cats)
            .put("sku", "AI-" + SimpleDateFormat("yyMMddHHmmss", Locale.US).format(Date()))
    }

    private fun draft(id: String): Draft = synchronized(drafts) { drafts[id] }
        ?: throw AppError("下書きが見つかりません（アプリを再起動した場合は撮り直してください）")

    private fun aspects(b: JSONObject): JSONObject {
        val cid = b.optString("category_id").trim()
        if (cid.isEmpty() || !cid.all { it.isDigit() }) throw AppError("カテゴリ ID を指定してください")
        val c = conf()
        val aspects = ebay.itemAspects(c, cid)
        var filled = JSONArray()
        var missing = JSONArray()
        val did = b.optString("draft_id")
        if (did.isNotEmpty()) {
            val d = draft(did)
            val product = JSONObject(d.listing.toString()).apply { remove("description_html") }
            val r = claude.fillAspects(c, product, d.notes, aspects)
            filled = r.first
            missing = r.second
        }
        return JSONObject().put("aspects", JSONArray(aspects.map {
            JSONObject().put("name", it.name).put("required", it.required).put("mode", it.mode)
                .put("multi", it.multi).put("values", JSONArray(it.values.take(200)))
        })).put("filled", filled).put("missing", missing)
    }

    private fun cleanAspects(rows: JSONArray?): JSONObject {
        val out = LinkedHashMap<String, MutableList<String>>()
        for (i in 0 until (rows?.length() ?: 0)) {
            val r = rows!!.optJSONObject(i) ?: continue
            val name = r.optString("name").trim().take(65)
            val v = r.optString("value").trim()
            if (name.isEmpty() || v.isEmpty()) continue
            val vals = if (r.optBoolean("multi")) v.split(",").map { it.trim().take(65) }.filter { it.isNotEmpty() }
            else listOf(v.take(65))
            out.getOrPut(name) { ArrayList() }.addAll(vals)
        }
        return JSONObject().apply { out.forEach { (k, v) -> put(k, JSONArray(v)) } }
    }

    private fun list(b: JSONObject): JSONObject {
        val c = conf()
        val d = draft(b.optString("draft_id"))
        val sku = b.optString("sku").replace(Regex("[^A-Za-z0-9_-]"), "").take(50)
        val title = b.optString("title").trim()
        val desc = b.optString("description").trim()
        val cond = b.optString("condition")
        val cid = b.optString("category_id").trim()
        val price = b.optString("price").toDoubleOrNull()?.let { (it * 100).roundToLong() / 100.0 }
        val qty = b.optString("quantity").ifEmpty { "1" }.toIntOrNull()
        if (price == null || qty == null) throw AppError("価格と数量を数値で入力してください")
        val problems = ArrayList<String>()
        if (sku.isEmpty()) problems.add("SKU")
        if (title.isEmpty() || title.length > 80) problems.add("タイトル（1〜80 文字）")
        if (desc.isEmpty()) problems.add("説明")
        if (cond !in Conf.CONDITION_KEYS) problems.add("状態")
        if (cid.isEmpty() || !cid.all { it.isDigit() }) problems.add("カテゴリ")
        if (price <= 0 || qty <= 0) problems.add("価格・数量")
        for ((k, label) in listOf("fulfillment_policy_id" to "送料ポリシー", "payment_policy_id" to "支払ポリシー",
            "return_policy_id" to "返品ポリシー", "merchant_location_key" to "発送元")) {
            if (c.optString(k).isEmpty()) problems.add("$label（設定タブ）")
        }
        if (problems.isNotEmpty()) throw AppError("入力が足りません: " + problems.joinToString("、"))

        val idx = b.optJSONArray("image_indexes")
        val photos = if (idx == null) d.photos
        else (0 until idx.length()).map { idx.optInt(it, -1) }.filter { it in d.photos.indices }.map { d.photos[it] }
        if (photos.isEmpty()) throw AppError("出品に使う画像を 1 枚以上選んでください")
        val steps = ArrayList<String>()
        val sig = photos.map { it.data.size }
        val urls = if (d.uploadedSig == sig && d.imageUrls != null) d.imageUrls!!   // 再送信時は上げ直さない
        else photos.map { ebay.uploadImage(c, it.data, it.mime) }.also { d.imageUrls = it; d.uploadedSig = sig }
        steps.add("画像 ${urls.size} 枚をアップロード")

        val product = JSONObject().put("title", title).put("description", desc)
            .put("imageUrls", JSONArray(urls)).put("aspects", cleanAspects(b.optJSONArray("aspects")))
        val L = d.listing
        if (L.optString("brand").isNotEmpty()) product.put("brand", L.optString("brand").take(65))
        if (L.optString("model_number").isNotEmpty()) product.put("mpn", L.optString("model_number").take(65))
        val jan = L.optString("jan_code").filter { it.isDigit() }
        if (jan.length == 13) product.put("ean", JSONArray(listOf(jan)))
        else if (jan.length == 12) product.put("upc", JSONArray(listOf(jan)))
        val item = JSONObject().put("product", product).put("condition", cond)
            .put("availability", JSONObject().put("shipToLocationAvailability", JSONObject().put("quantity", qty)))
        val note = b.optString("condition_notes").trim()
        if (note.isNotEmpty() && !cond.startsWith("NEW")) item.put("conditionDescription", note.take(1000))
        ebay.putInventoryItem(c, sku, item)
        steps.add("在庫アイテム $sku を登録")

        val offer = JSONObject().put("sku", sku).put("marketplaceId", c.optString("marketplace_id"))
            .put("format", "FIXED_PRICE").put("availableQuantity", qty).put("categoryId", cid)
            .put("listingDescription", desc).put("merchantLocationKey", c.optString("merchant_location_key"))
            .put("listingPolicies", JSONObject()
                .put("fulfillmentPolicyId", c.optString("fulfillment_policy_id"))
                .put("paymentPolicyId", c.optString("payment_policy_id"))
                .put("returnPolicyId", c.optString("return_policy_id")))
            .put("pricingSummary", JSONObject().put("price", JSONObject()
                .put("value", String.format(Locale.US, "%.2f", price)).put("currency", Conf.market(c).currency)))
        val offerId = ebay.upsertOffer(c, offer)
        steps.add("オファー $offerId を作成（未公開）")
        val out = JSONObject().put("sku", sku).put("offer_id", offerId)
            .put("seller_hub", ebay.webHost(c) + "/sh/lst/drafts")
        if (b.optBoolean("publish")) {
            val lid = ebay.publishOffer(c, offerId)
            steps.add("出品しました（Item ID $lid）")
            out.put("listing_id", lid).put("url", "${ebay.webHost(c)}/itm/$lid")
        }
        return out.put("steps", JSONArray(steps))
    }

    companion object {
        const val MAX_DRAFTS = 20
        const val MAX_IMAGES = 12
        const val MAX_IMAGE_BYTES = 5 * 1024 * 1024
        val IMAGE_MIMES = setOf("image/jpeg", "image/png", "image/webp", "image/gif")
    }
}
