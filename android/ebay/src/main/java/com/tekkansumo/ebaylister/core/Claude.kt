package com.tekkansumo.ebaylister.core

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.BadRequestException
import com.anthropic.errors.NotFoundException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.beta.messages.BetaBase64ImageSource
import com.anthropic.models.beta.messages.BetaContentBlockParam
import com.anthropic.models.beta.messages.BetaFallbacksParam
import com.anthropic.models.beta.messages.BetaImageBlockParam
import com.anthropic.models.beta.messages.BetaJsonOutputFormat
import com.anthropic.models.beta.messages.BetaMessage
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.BetaWebSearchTool20260209
import com.anthropic.models.beta.messages.MessageCreateParams
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.util.Base64

class Photo(val data: ByteArray, val mime: String)

/** 商品特定・調査・出品文。ebay_lister.py の「Claude」節の移植（プロンプトも同じ）。 */
class Claude {

    /** テスト用。設定すると API の宛先をここに向ける。 */
    var baseUrl: String? = null

    private var cached: Pair<String, AnthropicClient>? = null

    @Synchronized
    private fun client(c: JSONObject): AnthropicClient {
        val key = c.optString("anthropic_api_key")
        if (key.isEmpty()) throw AppError("Anthropic API キーが未設定です（設定タブ）")
        cached?.let { (k, cl) -> if (k == key) return cl }
        val b = AnthropicOkHttpClient.builder().apiKey(key).maxRetries(3).timeout(Duration.ofMinutes(10))
        baseUrl?.let { b.baseUrl(it) }
        val cl = b.build()
        cached = key to cl
        return cl
    }

    /** キーが使えるかを確かめる。models.retrieve はトークンを使わないので料金はかからない。 */
    fun checkKey(key: String) {
        val b = AnthropicOkHttpClient.builder().apiKey(key).maxRetries(1).timeout(Duration.ofSeconds(20))
        baseUrl?.let { b.baseUrl(it) }
        val cl = b.build()
        try {
            cl.models().retrieve(MODEL)
        } catch (e: UnauthorizedException) {
            throw AppError("キーが正しくありません（コピー漏れか、削除されたキーの可能性があります）")
        } catch (e: PermissionDeniedException) {
            throw AppError("このキーには使う権限がありません（組織やワークスペースの設定を確認してください）")
        } catch (e: NotFoundException) {
            throw AppError("このキーでは $MODEL を使えません")
        } catch (e: RateLimitException) {
            // 混雑しているだけならキー自体は有効
        } catch (e: AnthropicServiceException) {
            throw AppError("確認できませんでした (${e.statusCode()}): ${e.message}")
        } catch (e: AnthropicIoException) {
            throw AppError("Anthropic に接続できません。インターネット接続を確認してください")
        } finally {
            cl.close()
        }
    }

    private fun create(c: JSONObject, b: MessageCreateParams.Builder): BetaMessage {
        // 安全分類器が断ったときに別モデルで自動再実行させる（server-side fallback）
        b.model(MODEL).addBeta(FALLBACK_BETA).fallbacks(BetaFallbacksParam.ofDefault())
        val resp = try {
            client(c).beta().messages().create(b.build())
        } catch (e: UnauthorizedException) {
            throw AppError("Anthropic API キーが正しくありません")
        } catch (e: RateLimitException) {
            throw AppError("Anthropic API の利用上限に達しました。しばらく待ってください")
        } catch (e: BadRequestException) {
            if ("credit balance" in e.message.orEmpty().lowercase())
                throw AppError("Anthropic のクレジット残高が足りません。設定タブの「Anthropic API かんたん登録」②からクレジットを購入してください")
            throw AppError("Anthropic API エラー (${e.statusCode()}): ${e.message}")
        } catch (e: AnthropicServiceException) {
            throw AppError("Anthropic API エラー (${e.statusCode()}): ${e.message}")
        } catch (e: AnthropicIoException) {
            throw AppError("Anthropic API に接続できません")
        }
        if (resp.stopReason().orElse(null) == BetaStopReason.REFUSAL) throw AppError("AI がこの画像の処理を断りました")
        return resp
    }

    private fun images(photos: List<Photo>): List<BetaContentBlockParam> = photos.map {
        BetaContentBlockParam.ofImage(
            BetaImageBlockParam.builder().source(
                BetaBase64ImageSource.builder()
                    .data(Base64.getEncoder().encodeToString(it.data))
                    .mediaType(BetaBase64ImageSource.MediaType.of(it.mime))
                    .build()
            ).build()
        )
    }

    private fun jsonFormat(schema: JSONObject, effort: BetaOutputConfig.Effort): BetaOutputConfig {
        val s = BetaJsonOutputFormat.Schema.builder()
        for (k in schema.keys()) s.putAdditionalProperty(k, JsonValue.from(plain(schema.get(k))))
        return BetaOutputConfig.builder().effort(effort)
            .format(BetaJsonOutputFormat.builder().schema(s.build()).build()).build()
    }

    private fun firstJson(resp: BetaMessage): JSONObject {
        for (b in resp.content()) {
            val t = b.text().orElse(null) ?: continue
            return try { JSONObject(t.text()) } catch (e: Exception) { break }
        }
        if (resp.stopReason().orElse(null) == BetaStopReason.MAX_TOKENS)
            throw AppError("AI の出力が途中で切れました。もう一度試してください")
        throw AppError("AI の応答を解釈できませんでした")
    }

    /** 写真と Web 検索で調べる。戻り値は (調査メモ, 情報源)。 */
    fun research(c: JSONObject, photos: List<Photo>, hint: String, ebayHints: List<String>): Pair<String, JSONArray> {
        var text = "Identify this product and research it."
        if (hint.isNotEmpty()) text += "\nSeller's note (may be Japanese): $hint"
        if (ebayHints.isNotEmpty()) text += "\neBay image search returned visually similar listings " +
            "(may be wrong or a different variant):\n- " + ebayHints.joinToString("\n- ")
        val content = images(photos) + BetaContentBlockParam.ofText(text)
        val history = ArrayList<BetaMessage>()
        val notes = StringBuilder()
        val sources = JSONArray()
        val seen = HashSet<String>()
        repeat(5) {
            val b = MessageCreateParams.builder().maxTokens(16000).system(RESEARCH_SYSTEM)
                .addUserMessageOfBetaContentBlockParams(content)
                .addTool(BetaWebSearchTool20260209.builder().maxUses(8).build())
                .outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.MEDIUM).build())
            history.forEach { b.addMessage(it) }
            val resp = create(c, b)
            for (blk in resp.content()) {
                blk.text().ifPresent { notes.append(it.text()) }
                blk.webSearchToolResult().ifPresent { r ->
                    val ct = r.content()
                    if (ct.isResultBlocks()) for (w in ct.asResultBlocks()) {
                        if (seen.add(w.url()) && sources.length() < 15)
                            sources.put(JSONObject().put("title", w.title().ifEmpty { w.url() }).put("url", w.url()))
                    }
                }
            }
            if (resp.stopReason().orElse(null) != BetaStopReason.PAUSE_TURN) return notes.toString().trim() to sources
            // サーバー側の検索ループが上限で止まった。続きから再開させる
            history.add(resp)
        }
        return notes.toString().trim() to sources
    }

    fun draftListing(c: JSONObject, photos: List<Photo>, notes: String, hint: String): JSONObject {
        val prompt = LISTING_INSTRUCTIONS
            .replace("{mp}", c.optString("marketplace_id"))
            .replace("{hint}", if (hint.isNotEmpty()) "\nSeller's note: $hint\n" else "")
            .replace("{notes}", notes.ifEmpty { "(no notes)" })
        val b = MessageCreateParams.builder().maxTokens(16000)
            .addUserMessageOfBetaContentBlockParams(images(photos) + BetaContentBlockParam.ofText(prompt))
            .outputConfig(jsonFormat(LISTING_SCHEMA, BetaOutputConfig.Effort.LOW))
        val d = firstJson(create(c, b))
        d.put("ebay_title", d.optString("ebay_title").take(80))
        return d
    }

    /** カテゴリの Item Specifics 定義に合わせて値を埋める。選択式は許可値だけ残す。 */
    fun fillAspects(c: JSONObject, product: JSONObject, notes: String, aspects: List<Aspect>): Pair<JSONArray, JSONArray> {
        val lines = aspects.joinToString("\n") { a ->
            val tag = if (a.required) "REQUIRED" else a.usage.ifEmpty { "OPTIONAL" }
            var line = "- ${a.name} [$tag${if (a.multi) ", multiple values ok" else ""}]"
            if (a.values.isNotEmpty()) {
                val kind = if (a.mode == "SELECTION_ONLY") "choose only from" else "suggested values"
                line += " $kind: " + a.values.take(80).joinToString(" | ") + if (a.values.size > 80) " | ..." else ""
            }
            line
        }
        val prompt = "Fill eBay item specifics for this product. Use only facts supported by the product data or research notes.\n" +
            "Include every REQUIRED aspect (best supported guess if needed; use \"Unbranded\" for Brand only when there is no brand, " +
            "and \"Does Not Apply\" only when the aspect genuinely does not apply). Include RECOMMENDED/OPTIONAL aspects only when known. " +
            "Use the exact aspect names given. For 'choose only from' aspects, copy a listed value exactly.\n\n" +
            "<aspects>\n$lines\n</aspects>\n\n<product>\n$product\n</product>\n\n<research_notes>\n$notes\n</research_notes>"
        val b = MessageCreateParams.builder().maxTokens(8000).addUserMessage(prompt)
            .outputConfig(jsonFormat(ASPECTS_SCHEMA, BetaOutputConfig.Effort.LOW))
        val got = firstJson(create(c, b)).optJSONArray("aspects") ?: JSONArray()
        val defs = aspects.associateBy { it.name.lowercase() }
        val out = JSONArray()
        val have = HashSet<String>()
        for (i in 0 until got.length()) {
            val g = got.optJSONObject(i) ?: continue
            val a = defs[g.optString("name").lowercase()] ?: continue
            val arr = g.optJSONArray("values") ?: JSONArray()
            var vals = (0 until arr.length()).map { arr.optString(it).trim() }.filter { it.isNotEmpty() }
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

    companion object {
        const val MODEL = "claude-opus-5-5"
        const val FALLBACK_BETA = "server-side-fallback-2026-07-01"

        /** org.json → JsonValue.from が受け取れる素の Map / List。 */
        fun plain(v: Any?): Any? = when (v) {
            is JSONObject -> v.keys().asSequence().associateWith { plain(v.get(it)) }
            is JSONArray -> (0 until v.length()).map { plain(v.get(it)) }
            JSONObject.NULL -> null
            else -> v
        }

        val RESEARCH_SYSTEM = """
You are a product researcher for a Japanese seller who exports second-hand and new goods to eBay.
Given product photos, identify the exact product and research it on the web.

- Read every clue in the photos: logos, model numbers, JAN/EAN/UPC barcodes, serial plates, packaging text (often Japanese).
- Use web search to confirm the exact model/variant and collect specs: brand, official product name (Japanese and English),
  model/part number, JAN/UPC, release year, color, size, material, compatibility, what is included, country of manufacture.
  Prefer manufacturer pages, then major retailers (Amazon, Rakuten, Yodobashi, etc.).
- Also note whether it is a Japan-only / Japanese-version item (a selling point on eBay), and what buyers search for in English.
- If you cannot pin down the exact variant, say which candidates remain and why.
- Describe the visible condition of the item in the photos (scratches, wear, missing parts, box condition).

Finish with a concise research summary: confirmed facts (with source), uncertain points, and visible condition.""".trimStart()

        val LISTING_INSTRUCTIONS = """
Turn the research notes below into an eBay listing draft for marketplace {mp}. Use the photos to judge condition.

Field rules:
- search_query: 3-8 English keywords a buyer would type to find this exact item on eBay (brand + model + key variant). No condition words.
- ebay_title: English, at most 80 characters, keyword-rich: brand, product name, model number, key specs, "Japan" when it is a Japanese version.
  No emoji, no ALL CAPS words except model numbers and acronyms, no "L@@K"/"wow".
- description_html: English. Simple HTML (<h3>, <p>, <ul><li>, <br>) with sections: overview, specifications, condition
  (honest, based on the photos), what's included, shipping from Japan. No external links, no scripts, no images.
- item_specifics: eBay-style names (Brand, Model, MPN, Type, Color, Series, Country/Region of Manufacture, ...). Only facts you are confident in.
- condition: the eBay condition enum that best matches the photos; condition_notes: one or two English sentences.
- jan_code / model_number: empty string when unknown.
- notes_ja: 日本語で、出品者向けのメモ（確度が低い点、確認すべき点、付属品の確認など）。
- If the item could not be identified, set identified=false, confidence=low and still fill a best-effort draft.
{hint}
<research_notes>
{notes}
</research_notes>""".trimStart()

        private fun obj(props: JSONObject, required: List<String>) = JSONObject().put("type", "object")
            .put("properties", props).put("required", JSONArray(required)).put("additionalProperties", false)

        private fun str() = JSONObject().put("type", "string")

        val LISTING_SCHEMA: JSONObject = run {
            val p = JSONObject()
            p.put("identified", JSONObject().put("type", "boolean"))
            p.put("confidence", str().put("enum", JSONArray(listOf("high", "medium", "low"))))
            for (k in listOf("product_name_ja", "product_name_en", "brand", "model_number", "jan_code",
                "search_query", "ebay_title", "description_html")) p.put(k, str())
            p.put("item_specifics", JSONObject().put("type", "array")
                .put("items", obj(JSONObject().put("name", str()).put("value", str()), listOf("name", "value"))))
            p.put("condition", str().put("enum", JSONArray(Conf.CONDITION_KEYS)))
            p.put("condition_notes", str())
            p.put("notes_ja", str())
            obj(p, listOf("identified", "confidence", "product_name_ja", "product_name_en", "brand",
                "model_number", "jan_code", "search_query", "ebay_title", "description_html",
                "item_specifics", "condition", "condition_notes", "notes_ja"))
        }

        val ASPECTS_SCHEMA: JSONObject = obj(JSONObject().put("aspects", JSONObject().put("type", "array")
            .put("items", obj(JSONObject().put("name", str())
                .put("values", JSONObject().put("type", "array").put("items", str())), listOf("name", "values")))),
            listOf("aspects"))
    }
}
