package com.tekkansumo.ebaylister.core

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/** eBay の検索結果 1 件（無料モードで使う分だけ）。 */
data class Summary(
    val title: String, val itemId: String, val url: String, val categoryId: String, val categoryName: String,
)

class FreeResult(
    val listing: JSONObject, val notes: String, val sources: JSONArray, val categories: JSONArray,
    val refAspects: LinkedHashMap<String, String>,
)

/** 無料モード（AI を使わず、バーコードと eBay の画像検索で作る）。ebay_lister.py の同名の節の移植。 */
class Free(private val http: OkHttpClient, private val ebay: Ebay) {

    /** Yahoo!ショッピングの無料 API で JAN から日本語の商品名を引く（Client ID があるときだけ）。 */
    private fun yahooLookup(c: JSONObject, jan: String): Pair<String, String>? {
        val id = c.optString("yahoo_client_id")
        if (id.isEmpty()) return null
        return try {
            val url = "https://shopping.yahooapis.jp/ShoppingWebService/V3/itemSearch".toHttpUrl().newBuilder()
                .addQueryParameter("appid", id).addQueryParameter("jan_code", jan)
                .addQueryParameter("results", "5").build()
            http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (r.code != 200) return null
                val hits = JSONObject(r.body?.string().orEmpty()).optJSONArray("hits") ?: return null
                if (hits.length() == 0) return null
                val h = hits.getJSONObject(0)
                h.optString("name") to (h.optJSONObject("brand")?.optString("name") ?: "")
            }
        } catch (e: Exception) {
            null
        }
    }

    fun identify(c: JSONObject, photos: List<Photo>, hint: String, jan: String): FreeResult {
        if (c.optString("client_id").isEmpty() || c.optString("client_secret").isEmpty())
            throw AppError("無料モードには eBay のキーが必要です（設定タブの「eBay API かんたん登録」）")
        val gtin = validGtin(jan)
        var refs = emptyList<Summary>()
        var basis = ""
        var ja: Pair<String, String>? = null
        if (gtin.isNotEmpty()) {
            refs = ebay.searchItems(c, gtin = gtin)
            basis = "gtin"
            ja = yahooLookup(c, gtin)
        }
        if (refs.isEmpty() && photos[0].mime == "image/jpeg") {
            refs = ebay.imageItems(c, photos[0].data)
            basis = "image"
        }
        if (refs.isEmpty() && Regex("[A-Za-z0-9]{3,}").containsMatchIn(hint)) {
            refs = ebay.searchItems(c, q = Regex("[A-Za-z0-9\\-]+").findAll(hint).joinToString(" ") { it.value }.take(100))
            basis = "keyword"
        }
        if (refs.isEmpty()) throw AppError("手がかりが見つかりませんでした。バーコードの写真を加えるか、" +
            "ヒントに英語の商品名や型番を入れてください")

        val top = refs.take(12)
        val (idx, score) = pickReference(top.map { it.title })
        val ref = top[idx]
        val aspects = LinkedHashMap<String, String>()
        val item = try { if (ref.itemId.isNotEmpty()) ebay.getItem(c, ref.itemId) else JSONObject() } catch (e: Exception) { JSONObject() }
        val la = item.optJSONArray("localizedAspects") ?: JSONArray()
        for (i in 0 until la.length()) {
            val a = la.getJSONObject(i)
            val n = a.optString("name").trim()
            val v = a.optString("value").trim()
            if (n.isNotEmpty() && v.isNotEmpty() && n.lowercase() !in SKIP_ASPECTS && n !in aspects) aspects[n] = v
        }
        val brand = item.optString("brand").ifEmpty { aspects["Brand"] ?: ja?.second ?: "" }
        val mpn = item.optString("mpn").ifEmpty { aspects["MPN"] ?: aspects["Model"] ?: "" }
        val specifics = JSONArray(aspects.entries.take(15).map { JSONObject().put("name", it.key).put("value", it.value) })

        val counts = LinkedHashMap<String, Pair<Int, String>>()
        for (r in top) if (r.categoryId.isNotEmpty()) {
            val cur = counts[r.categoryId]
            counts[r.categoryId] = ((cur?.first ?: 0) + 1) to r.categoryName
        }
        val cats = JSONArray(counts.entries.sortedByDescending { it.value.first }.map { (id, v) ->
            JSONObject().put("id", id).put("name", v.second).put("path", "${v.second}（類似出品 ${v.first} 件）")
        })

        val title = cleanTitle(ref.title)
        val query = commonQuery(top.map { it.title }, ref.title)
        val (confidence, how) = when (basis) {
            "gtin" -> "high" to "バーコード $gtin で eBay の同じ商品の出品が ${refs.size} 件見つかりました。"
            "image" -> (if (score >= 0.3) "medium" else "low") to
                "写真に似た eBay の出品 ${refs.size} 件から作りました（一致度 ${Math.round(score * 100)}%）。別の商品の可能性があります。"
            else -> "low" to "ヒントの語で eBay を検索した ${refs.size} 件から作りました。"
        }
        val listing = JSONObject()
            .put("identified", true).put("confidence", confidence)
            .put("product_name_ja", ja?.first ?: "").put("product_name_en", title)
            .put("brand", brand).put("model_number", mpn).put("jan_code", gtin).put("search_query", query)
            .put("ebay_title", title).put("description_html", description(title, specifics))
            .put("item_specifics", specifics).put("condition", "USED_GOOD").put("condition_notes", "")
            .put("notes_ja", how + "\n手本にした出品: " + ref.title +
                "\n状態は自動で判定できないので、写真を見て選んでください。説明文はひな形です。")
        val notes = "無料モード（AI なし）\n$how\n\n参考にした出品:\n" + top.joinToString("\n") { "- ${it.title}  (${it.url})" }
        val sources = JSONArray(top.filter { it.url.isNotEmpty() }.take(8).map { JSONObject().put("title", it.title).put("url", it.url) })
        return FreeResult(listing, notes, sources, cats, aspects)
    }

    companion object {
        private val TITLE_NOISE = setOf(
            "new", "used", "free", "shipping", "ship", "fast", "f/s", "from", "with", "and", "the", "for",
            "of", "a", "an", "in", "w/", "rare", "nm", "mint", "excellent", "lot", "tested", "working",
            "genuine", "authentic", "official", "very", "good", "condition", "item", "sealed", "brand",
            "japan", "japanese", "jp", "import", "ver", "version", "near", "box", "only", "fedex", "dhl",
        )
        /** 手本にした出品から持ってこない項目（その出品固有の情報）。 */
        private val SKIP_ASPECTS = setOf(
            "condition", "seller notes", "item condition", "custom bundle", "modified item",
            "non-domestic product", "california prop 65 warning", "unit quantity", "unit type",
        )

        private val TOKEN_RE = Regex("[a-z0-9][a-z0-9\\-.+/]*")
        private fun tokens(t: String) = TOKEN_RE.findAll(t.lowercase()).map { it.value }.toList()

        /** 他のタイトルと一番よく重なるもの（代表）を選ぶ。戻り値は (添字, 一致度 0〜1)。 */
        fun pickReference(titles: List<String>): Pair<Int, Double> {
            val sets = titles.map { tokens(it).toSet() }
            if (sets.size == 1) return 0 to 0.0
            var best = 0
            var bestScore = -1.0
            for ((i, a) in sets.withIndex()) {
                val sims = sets.withIndex().filter { it.index != i && (a + it.value).isNotEmpty() }
                    .map { (a intersect it.value).size.toDouble() / (a union it.value).size }
                val score = if (sims.isEmpty()) 0.0 else sims.average()
                if (score > bestScore) { best = i; bestScore = score }
            }
            return best to bestScore
        }

        /** 代表タイトルの語のうち、半数以上のタイトルに出てくるものを検索語にする。 */
        fun commonQuery(titles: List<String>, base: String): String {
            val df = HashMap<String, Int>()
            for (t in titles) for (w in tokens(t).toSet()) df[w] = (df[w] ?: 0) + 1
            val need = if (titles.size > 1) maxOf(2, (titles.size + 1) / 2) else 1
            val words = tokens(base).filter { it !in TITLE_NOISE && it.length > 1 }
            var pick = words.filter { (df[it] ?: 0) >= need }.distinct()
            if (pick.size < 2) pick = words.distinct().take(5)
            return pick.take(6).joinToString(" ")
        }

        fun cleanTitle(t: String) = t.replace(Regex("[^\\x20-\\x7E]"), " ").replace(Regex("\\s+"), " ").trim().take(80)

        fun validGtin(code: String): String {
            val d = code.filter { it.isDigit() }
            if (d.length !in listOf(8, 12, 13)) return ""
            val body = d.dropLast(1)
            val total = body.withIndex().sumOf { (i, ch) -> (ch - '0') * (if ((body.length - i) % 2 == 1) 3 else 1) }
            return if ((10 - total % 10) % 10 == d.last() - '0') d else ""
        }

        private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#x27;")

        fun description(title: String, specifics: JSONArray): String {
            val rows = (0 until specifics.length()).joinToString("") {
                val s = specifics.getJSONObject(it)
                "<li>${esc(s.getString("name"))}: ${esc(s.getString("value"))}</li>"
            }
            val japan = if ("japan" in title.lowercase()) "<p>This is a Japanese version item.</p>" else ""
            return "<h3>${esc(title)}</h3>$japan" +
                (if (rows.isNotEmpty()) "<h3>Specifications</h3><ul>$rows</ul>" else "") +
                "<h3>Condition</h3><p>Please check the photos carefully for the exact condition. " +
                "What you see in the photos is what you will receive.</p>" +
                "<h3>Shipping</h3><p>Ships from Japan with tracking. Import duties, taxes and charges " +
                "are not included in the item price and are the buyer's responsibility.</p>"
        }

        /** got（名前 → 値の候補）をカテゴリの定義に合わせる。選択式は許可値だけ残す。 */
        fun validate(aspects: List<Aspect>, got: List<Pair<String, List<String>>>): Pair<JSONArray, JSONArray> {
            val defs = aspects.associateBy { it.name.lowercase() }
            val out = JSONArray()
            val have = HashSet<String>()
            for ((name, raw) in got) {
                val a = defs[name.lowercase()] ?: continue
                if (a.name in have) continue
                var vals = raw.map { it.trim() }.filter { it.isNotEmpty() }
                if (a.mode == "SELECTION_ONLY" && a.values.isNotEmpty()) {
                    val allowed = a.values.associateBy { it.lowercase() }
                    vals = vals.mapNotNull { allowed[it.lowercase()] }
                }
                if (!a.multi) vals = vals.take(1)
                if (vals.isEmpty()) continue
                out.put(JSONObject().put("name", a.name).put("value", if (a.multi) vals.joinToString(", ") else vals[0])
                    .put("required", a.required))
                have.add(a.name)
            }
            val missing = JSONArray(aspects.filter { it.required && it.name !in have }.map { it.name })
            return out to missing
        }

        /** 手本にした出品の項目を、カテゴリの項目名に合わせて写す。 */
        fun fillAspects(listing: JSONObject, ref: Map<String, String>, aspects: List<Aspect>): Pair<JSONArray, JSONArray> {
            val src = LinkedHashMap(ref)
            if (listing.optString("brand").isNotEmpty()) src.putIfAbsent("Brand", listing.optString("brand"))
            if (listing.optString("model_number").isNotEmpty()) src.putIfAbsent("MPN", listing.optString("model_number"))
            return validate(aspects, src.map { it.key to listOf(it.value) })
        }

        fun useClaude(c: JSONObject): Boolean = when (c.optString("ai_mode").ifEmpty { "auto" }) {
            "claude" -> true
            "free" -> false
            else -> c.optString("anthropic_api_key").isNotEmpty()
        }
    }
}
