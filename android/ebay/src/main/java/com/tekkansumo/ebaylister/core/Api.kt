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
    val free = Free(http, ebay)

    private class Draft(
        val photos: List<Photo>, val notes: String, val listing: JSONObject, val created: Long,
        var imageUrls: List<String>? = null, var uploadedSig: List<Int>? = null,
        val refAspects: Map<String, String>? = null,      // 無料モードで手本にした出品の項目
    )

    private fun putDraft(d: Draft): String {
        val id = token(8)
        synchronized(drafts) {
            if (drafts.size >= MAX_DRAFTS) drafts.remove(drafts.minByOrNull { it.value.created }!!.key)
            drafts[id] = d
        }
        return id
    }

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
        "POST /api/ebay/test" -> connectionTest()
        "POST /api/anthropic/key" -> anthropicKey(b)
        "POST /api/ebay/auto_setup" -> autoSetup(b)
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
        if ("/oauth2/authorize" in raw) throw AppError("これは同意画面を開く URL です。この URL を開いてログインし" +
            "「同意する（Agree）」を押したあと、移動した先のページの URL（code= が入っています）を貼ってください")
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

    /**
     * 連携後の出品準備をまとめて行う。何度呼んでもよい（あるものは作らず選ぶだけ）。
     * 送料ポリシーだけは送料の決定が要るので作らず、あれば選ぶ。ebay_lister.py の api_auto_setup と同じ。
     */
    private fun autoSetup(b: JSONObject): JSONObject {
        val c = conf()
        val steps = JSONArray()
        val programs = try { ebay.optedInPrograms(c) } catch (e: EbayError) { emptyList() }
        if ("SELLING_POLICY_MANAGEMENT" !in programs) {
            try {
                ebay.optIn(c, "SELLING_POLICY_MANAGEMENT")
                steps.put("ビジネスポリシーを有効化しました")
            } catch (e: EbayError) {
                if ("already" !in e.message.orEmpty().lowercase()) throw AppError(explain(e.message.orEmpty()))
            }
        }
        val pol = try { ebay.policies(c) } catch (e: EbayError) {
            throw AppError(explain(e.message.orEmpty()) +
                "\n（有効化した直後は反映まで数分かかることがあります。少し待ってもう一度お試しください）")
        }
        fun one(id: String, name: String) = JSONArray(listOf(JSONObject().put("id", id).put("name", name)))
        if (pol.getJSONArray("payment").length() == 0) {
            val id = ebay.createPolicy(c, "payment", JSONObject().put("name", "AI Lister Payment").put("immediatePay", true))
            pol.put("payment", one(id, "AI Lister Payment"))
            steps.put("支払ポリシーを作成しました（即時支払い）")
        }
        if (pol.getJSONArray("return").length() == 0) {
            val id = ebay.createPolicy(c, "return", JSONObject().put("name", "AI Lister Returns 30 days")
                .put("returnsAccepted", true).put("returnPeriod", JSONObject().put("value", 30).put("unit", "DAY"))
                .put("returnShippingCostPayer", "BUYER"))
            pol.put("return", one(id, "AI Lister Returns 30 days"))
            steps.put("返品ポリシーを作成しました（30 日以内・返送料は購入者負担）")
        }
        val zip = b.optString("postal_code").filter { it.isDigit() || it == '-' }
        if (pol.getJSONArray("location").length() == 0 && zip.isNotEmpty()) {
            ebay.createLocation(c, "JP-HOME", JSONObject().put("postalCode", zip).put("country", "JP"))
            pol.put("location", one("JP-HOME", "JP-HOME"))
            steps.put("発送元（〒$zip）を登録しました")
        }
        for ((key, kind) in listOf("fulfillment_policy_id" to "fulfillment", "payment_policy_id" to "payment",
            "return_policy_id" to "return", "merchant_location_key" to "location")) {
            val list = pol.getJSONArray(kind)
            val ids = (0 until list.length()).map { list.getJSONObject(it).optString("id") }
            if (c.optString(key) !in ids) c.put(key, ids.firstOrNull() ?: "")
        }
        save(c)
        return JSONObject().put("steps", steps).put("policies", pol).put("config", Conf.public(c))
            .put("need_shipping", pol.getJSONArray("fulfillment").length() == 0)
            .put("need_postal", pol.getJSONArray("location").length() == 0)
            .put("shipping_url", SHIPPING_POLICY_URL)
    }

    /** 貼り付けられた文字からキーを拾い、使えることを確かめてから保存する。 */
    private fun anthropicKey(b: JSONObject): JSONObject {
        val key = Regex("sk-ant-[A-Za-z0-9_\\-]{20,}").find(b.optString("key"))?.value
            ?: throw AppError("キーが見つかりません。「sk-ant-」で始まる文字列をまるごと貼ってください")
        claude.checkKey(key)
        val c = conf().put("anthropic_api_key", key)
        save(c)
        return Conf.public(c)
    }

    /** 設定を順に確かめ、どこで止まっているかを返す。ebay_lister.py の api_ebay_test と同じ。 */
    private fun connectionTest(): JSONObject {
        val c = conf()
        val steps = JSONArray()
        fun step(label: String, fn: () -> String): Boolean = try {
            steps.put(JSONObject().put("ok", true).put("label", label).put("detail", fn()))
            true
        } catch (e: AppError) {
            steps.put(JSONObject().put("ok", false).put("label", label).put("detail", explain(e.message.orEmpty())))
            false
        } catch (e: IOException) {
            steps.put(JSONObject().put("ok", false).put("label", label).put("detail", "eBay に接続できません。インターネット接続を確認してください"))
            false
        }
        step("Anthropic API キー") {
            if (c.optString("anthropic_api_key").isEmpty()) {
                if (c.optString("ai_mode") != "claude") return@step "未設定（無料モードで動きます）"
                throw AppError("未設定です（「Anthropic API かんたん登録」）")
            }
            claude.checkKey(c.optString("anthropic_api_key"))
            "有効です"
        }
        if (step("eBay のキー（App ID / Cert ID）") {
                ebay.token(c, "app")
                "App ID / Cert ID は有効です（${if (c.optString("ebay_env") == "sandbox") "Sandbox" else "本番"}）"
            }) {
            step("RuName") { c.optString("ru_name").ifEmpty { throw AppError("RuName が未設定です（手順④）") } }
            if (step("eBay アカウント連携") { ebay.token(c, "user"); "連携済み" } &&
                step("ビジネスポリシーと発送元") {
                    val p = ebay.policies(c)
                    val names = listOf("fulfillment" to "送料", "payment" to "支払", "return" to "返品", "location" to "発送元")
                    val missing = names.filter { p.getJSONArray(it.first).length() == 0 }.map { it.second }
                    if (missing.isNotEmpty()) throw AppError("eBay 側に ${missing.joinToString("・")} がまだありません")
                    names.joinToString("・") { "${it.second} ${p.getJSONArray(it.first).length()}" } + " 件"
                }) {
                step("出品に使うポリシーの選択") {
                    val miss = listOf("fulfillment_policy_id" to "送料", "payment_policy_id" to "支払",
                        "return_policy_id" to "返品", "merchant_location_key" to "発送元")
                        .filter { c.optString(it.first).isEmpty() }.map { it.second }
                    if (miss.isNotEmpty()) throw AppError("${miss.joinToString("・")} を選んで保存してください（出品ポリシーと発送元の欄）")
                    "選択済み"
                }
            }
        }
        val ok = steps.length() == 6 && (0 until steps.length()).all { steps.getJSONObject(it).getBoolean("ok") }
        return JSONObject().put("steps", steps).put("ok", ok)
    }

    /** よくある失敗に日本語の対処を添える。 */
    private fun explain(msg: String): String {
        val low = msg.lowercase()
        return when {
            "invalid_client" in low || "client authentication failed" in low -> msg +
                "\n→ App ID / Cert ID が違うか、本番と Sandbox の取り違えです。本番キーは「アカウント削除通知」の設定（手順②）が済むまで使えません"
            "invalid_grant" in low -> msg + "\n→ 連携の有効期限切れか取り消しです。手順⑤の「eBay と連携」をやり直してください"
            "invalid_scope" in low -> msg + "\n→ このキーには出品の権限がありません。キーの環境（本番/Sandbox）を確認してください"
            "20403" in low || "business polic" in low -> msg + "\n→ ビジネスポリシーが有効になっていません（手順⑥）"
            else -> msg
        }
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
        val sku = "AI-" + SimpleDateFormat("yyMMddHHmmss", Locale.US).format(Date())
        if (!Free.useClaude(c)) {
            val r = free.identify(c, photos, hint, b.optString("jan"))
            val id = putDraft(Draft(photos, r.notes, r.listing, System.currentTimeMillis(), refAspects = r.refAspects))
            try {
                val have = (0 until r.categories.length()).map { r.categories.getJSONObject(it).optString("id") }.toMutableSet()
                val more = ebay.categorySuggestions(c, r.listing.optString("search_query").ifEmpty { r.listing.optString("ebay_title") })
                for (i in 0 until more.length()) if (have.add(more.getJSONObject(i).optString("id"))) r.categories.put(more.getJSONObject(i))
            } catch (e: Exception) { /* 候補の追加は補助 */ }
            return JSONObject().put("draft_id", id).put("listing", r.listing).put("notes", r.notes)
                .put("sources", r.sources).put("ebay_hints", JSONArray()).put("categories", r.categories)
                .put("sku", sku).put("mode", "free")
        }
        val hasKeys = c.optString("client_id").isNotEmpty() && c.optString("client_secret").isNotEmpty()
        var hints = emptyList<String>()
        if (hasKeys && photos[0].mime == "image/jpeg") {
            hints = try { ebay.searchByImage(c, photos[0].data) } catch (e: Exception) { emptyList() }  // 補助なので失敗は無視
        }
        val (notes, sources) = claude.research(c, photos, hint, hints)
        val listing = claude.draftListing(c, photos, notes, hint)
        val id = putDraft(Draft(photos, notes, listing, System.currentTimeMillis()))
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
            .put("sku", sku).put("mode", "claude")
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
            val r = if (d.refAspects != null) Free.fillAspects(d.listing, d.refAspects, aspects)
            else claude.fillAspects(c, JSONObject(d.listing.toString()).apply { remove("description_html") }, d.notes, aspects)
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
        const val SHIPPING_POLICY_URL = "https://www.bizpolicy.ebay.com/businesspolicy/manage"
        const val MAX_DRAFTS = 20
        const val MAX_IMAGES = 12
        const val MAX_IMAGE_BYTES = 5 * 1024 * 1024
        val IMAGE_MIMES = setOf("image/jpeg", "image/png", "image/webp", "image/gif")
    }
}
