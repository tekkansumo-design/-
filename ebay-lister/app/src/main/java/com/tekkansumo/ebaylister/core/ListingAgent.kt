package com.tekkansumo.ebaylister.core

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.core.jsonMapper
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.models.beta.messages.BetaBase64ImageSource
import com.anthropic.models.beta.messages.BetaContentBlockParam
import com.anthropic.models.beta.messages.BetaFallbacksParam
import com.anthropic.models.beta.messages.BetaImageBlockParam
import com.anthropic.models.beta.messages.BetaMessage
import com.anthropic.models.beta.messages.BetaMessageParam
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.BetaTextBlockParam
import com.anthropic.models.beta.messages.BetaTool
import com.anthropic.models.beta.messages.BetaToolResultBlockParam
import com.anthropic.models.beta.messages.BetaWebFetchTool20260209
import com.anthropic.models.beta.messages.BetaWebSearchTool20260209
import com.anthropic.models.beta.messages.MessageCreateParams
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.util.Base64

/**
 * JAN コードと写真から商品を特定し、eBay のカテゴリと Item Specifics を埋めた下書きを作る。
 *
 * Claude にウェブ検索（サーバー側ツール）と、このアプリが実行する eBay ツール
 * （カテゴリ候補・アスペクト一覧・相場検索）を渡し、最後に submit_listing で下書きを提出させる。
 */
class ListingAgent(
    apiKey: String,
    private val ebay: EbayApi,
    private val marketplaceId: String,
    private val currency: String,
    private val progress: (String) -> Unit,
) {
    private val client: AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey(apiKey)
        .timeout(Duration.ofMinutes(5))
        .build()

    class Input(
        val jan: String,
        val notes: String,
        val condition: String,
        /** 長辺 1600px 程度に縮めた JPEG */
        val photos: List<ByteArray>,
    )

    fun run(input: Input): JSONObject {
        val messages = mutableListOf<BetaMessageParam>()
        messages += userMessage(input)

        var reminded = false
        repeat(MAX_TURNS) {
            progress("Claude が考えています…")
            val response = create(messages)
            messages += response.toParam()

            val stop = response.stopReason().orElse(null)
            if (stop == BetaStopReason.REFUSAL) {
                val why = response.stopDetails().flatMap { it.explanation() }.orElse("")
                throw IllegalStateException("Claude がこの依頼を断りました。$why")
            }
            reportServerTools(response)

            if (stop == BetaStopReason.PAUSE_TURN) return@repeat // サーバー側ツールの続きをそのまま再送

            val toolUses = response.content().mapNotNull { it.toolUse().orElse(null) }
            if (toolUses.isEmpty()) {
                if (stop == BetaStopReason.MAX_TOKENS || reminded) {
                    throw IllegalStateException("下書きを作れませんでした（${stop ?: "不明"}）。")
                }
                reminded = true
                messages += textMessage("Please finish by calling submit_listing with your best draft.")
                return@repeat
            }

            val results = mutableListOf<BetaContentBlockParam>()
            var submitted: JSONObject? = null
            for (tu in toolUses) {
                val args = JSONObject(jsonMapper().writeValueAsString(tu._input()))
                val (text, isError) = try {
                    when (tu.name()) {
                        "submit_listing" -> {
                            submitted = args
                            "Draft received." to false
                        }
                        else -> runEbayTool(tu.name(), args) to false
                    }
                } catch (e: Exception) {
                    "Error: ${e.message}" to true
                }
                results += BetaContentBlockParam.ofToolResult(
                    BetaToolResultBlockParam.builder()
                        .toolUseId(tu.id())
                        .content(text)
                        .isError(isError)
                        .build()
                )
            }
            submitted?.let { return finalize(it) }
            messages += BetaMessageParam.ofUserOfContentBlockParams(results)
        }
        throw IllegalStateException("やり取りが長くなりすぎたため中断しました。メモに商品名を書いて再試行してください。")
    }

    private fun create(messages: List<BetaMessageParam>): BetaMessage {
        val params = buildParams(messages)
        return try {
            client.beta().messages().create(params)
        } catch (e: AnthropicServiceException) {
            throw IllegalStateException("Claude API エラー (${e.statusCode()}): ${e.message}", e)
        }
    }

    internal fun buildParams(messages: List<BetaMessageParam>): MessageCreateParams =
        MessageCreateParams.builder()
            .model(MODEL)
            .maxTokens(16000L)
            .system(systemPrompt())
            .outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.MEDIUM).build())
            // 安全分類器が断ったときはサーバー側で別モデルに切り替えて続行させる
            .addBeta("server-side-fallback-2026-07-01")
            .fallbacks(BetaFallbacksParam.ofDefault())
            .addTool(BetaWebSearchTool20260209.builder().maxUses(8L).build())
            .addTool(BetaWebFetchTool20260209.builder().maxUses(5L).build())
            .apply { tools().forEach { addTool(it) } }
            .messages(messages)
            .build()

    internal fun firstMessage(input: Input) = userMessage(input)

    private fun reportServerTools(response: BetaMessage) {
        for (block in response.content()) {
            block.serverToolUse().ifPresent { stu ->
                val input = runCatching { JSONObject(jsonMapper().writeValueAsString(stu._input())) }
                    .getOrNull()
                val q = input?.optString("query").orEmpty().ifBlank { input?.optString("url").orEmpty() }
                if (q.isNotBlank()) progress("ウェブで調べています: $q")
            }
        }
    }

    private fun runEbayTool(name: String, args: JSONObject): String = when (name) {
        "ebay_category_suggestions" -> {
            val q = args.getString("query")
            progress("eBay のカテゴリを探しています: $q")
            ebay.categorySuggestions(q).toString()
        }
        "ebay_item_aspects" -> {
            val id = args.getString("category_id")
            progress("Item Specifics の項目を取得しています (カテゴリ $id)")
            val aspects = ebay.itemAspects(id)
            // 推奨度の高い順に並べ、候補値は多すぎると文脈を食うので絞る
            val sorted = aspects.sortedBy { USAGE_ORDER[it.usage] ?: 3 }
            JSONArray(sorted.map { it.toJson(maxValues = 40) }).toString()
        }
        "ebay_active_listings" -> {
            progress("eBay の相場を調べています")
            ebay.searchActiveListings(
                args.optString("gtin").ifBlank { null },
                args.optString("query").ifBlank { null },
            ).toString()
        }
        else -> throw IllegalArgumentException("unknown tool $name")
    }

    /** 下書きに、UI で編集するためのアスペクト定義を添える。 */
    private fun finalize(draft: JSONObject): JSONObject {
        val catId = draft.optString("category_id")
        val defs = runCatching { ebay.itemAspects(catId) }.getOrDefault(emptyList())
        draft.put("aspect_defs", JSONArray(defs.map { it.toJson(maxValues = 300) }))
        return draft
    }

    private fun userMessage(input: Input): BetaMessageParam {
        val blocks = mutableListOf<BetaContentBlockParam>()
        input.photos.forEach { jpeg ->
            blocks += BetaContentBlockParam.ofImage(
                BetaImageBlockParam.builder()
                    .source(
                        BetaBase64ImageSource.builder()
                            .mediaType(BetaBase64ImageSource.MediaType.IMAGE_JPEG)
                            .data(Base64.getEncoder().encodeToString(jpeg))
                            .build()
                    )
                    .build()
            )
        }
        val text = buildString {
            appendLine("Create an eBay listing draft for this item.")
            appendLine("Marketplace: $marketplaceId (currency $currency)")
            appendLine("JAN/EAN/UPC barcode: ${input.jan.ifBlank { "(none – identify from the photos)" }}")
            appendLine("Item condition chosen by the seller: ${input.condition}")
            appendLine("Photos attached: ${input.photos.size}")
            if (input.notes.isNotBlank()) {
                appendLine("Seller's notes (may be Japanese; treat as facts about this specific item):")
                appendLine(input.notes)
            }
        }
        blocks += BetaContentBlockParam.ofText(BetaTextBlockParam.builder().text(text).build())
        return BetaMessageParam.ofUserOfContentBlockParams(blocks)
    }

    private fun textMessage(text: String): BetaMessageParam = BetaMessageParam.ofUser(text)

    private fun systemPrompt() = """
You help a seller in Japan list items on eBay ($marketplaceId). Each request gives a barcode (JAN = EAN-13) and/or photos of one item. Your job is a listing draft whose facts are right, because wrong item specifics cause returns and bad feedback.

How to work:
1. Identify the exact product. Search the web for the barcode number itself first (Japanese retail and maker sites such as Amazon.co.jp, Rakuten, Yahoo Shopping, Kakaku.com and the manufacturer's page are usually the best sources), then confirm with the photos. Read the product page when a search snippet is not enough. If the barcode and the photos disagree, trust what the photos show and say so in summary_ja.
2. Call ebay_category_suggestions with a short English product phrase and pick the most specific matching leaf category.
3. Call ebay_item_aspects for that category and fill in the aspects. Use values you verified from a source or can see in the photos. For SELECTION_ONLY aspects use one of the listed values exactly. For FREE_TEXT aspects, prefer a listed value when one fits. Fill every required aspect; for a required aspect that genuinely does not apply, use a value such as "Does Not Apply" or "Unbranded" only when that is accurate. Skip optional aspects you could not verify instead of guessing.
4. Call ebay_active_listings (by gtin when you have it, otherwise a short query) to see current prices, then suggest a competitive price in $currency for an item shipped from Japan.
5. Call submit_listing once with the complete draft.

Writing the listing (English, for international buyers):
- title: at most 80 characters, brand + product name + model/key specs; no emojis, no ALL CAPS words except brand names and model numbers, no "L@@K" style filler.
- description_html: simple HTML (<p>, <ul>, <li>, <b>). Describe the product, what is included, and the condition as the seller described it. Mention that it ships from Japan. Do not invent accessories or condition details.
- condition_description: a short English description of this item's condition based on the seller's notes and photos (empty string for new items).
- summary_ja / price_note_ja are for the seller and must be in Japanese. In summary_ja state what you identified, which sources you used, and anything uncertain the seller should check.
Use empty strings for brand, mpn or gtin you could not determine.
""".trim()

    private fun tools(): List<BetaTool> = listOf(
        tool(
            "ebay_category_suggestions",
            "Search eBay's category tree for the marketplace. Returns up to 10 categories with id and full path. Use a short English phrase describing the product (e.g. 'Nintendo Switch game', 'Seiko automatic wristwatch').",
            props = mapOf("query" to str("Short English product phrase")),
        ),
        tool(
            "ebay_item_aspects",
            "Get the item specifics (aspects) for an eBay leaf category: name, whether required, mode (FREE_TEXT or SELECTION_ONLY), cardinality (SINGLE or MULTI), usage, and allowed/suggested values.",
            props = mapOf("category_id" to str("eBay category id from ebay_category_suggestions")),
        ),
        tool(
            "ebay_active_listings",
            "Search current eBay listings on this marketplace for price research. Give gtin when known, otherwise a short English query. Use an empty string for the one you do not use.",
            props = mapOf(
                "gtin" to str("EAN/UPC/JAN digits, or empty string"),
                "query" to str("Search keywords, or empty string"),
            ),
        ),
        tool(
            "submit_listing",
            "Submit the final listing draft. Call exactly once, after you have chosen the category and filled the aspects.",
            props = mapOf(
                "product_name_ja" to str("Product name in Japanese as sold in Japan"),
                "summary_ja" to str("Japanese note for the seller: what was identified, sources, uncertain points to check"),
                "title" to str("English eBay title, max 80 characters"),
                "description_html" to str("English HTML description"),
                "category_id" to str("Chosen eBay leaf category id"),
                "category_name" to str("Full path of the chosen category"),
                "aspects" to mapOf(
                    "type" to "array",
                    "items" to obj(
                        mapOf(
                            "name" to str("Aspect name exactly as returned by ebay_item_aspects"),
                            "values" to mapOf("type" to "array", "items" to mapOf("type" to "string")),
                        )
                    ),
                ),
                "brand" to str("Brand, or empty string"),
                "mpn" to str("Manufacturer part number / model number, or empty string"),
                "gtin" to str("EAN/UPC digits, or empty string"),
                "condition_description" to str("English condition description, or empty string for new items"),
                "suggested_price" to mapOf("type" to "number", "description" to "Suggested price in $currency"),
                "price_note_ja" to str("Japanese explanation of the suggested price"),
                "sources" to mapOf(
                    "type" to "array", "items" to mapOf("type" to "string"),
                    "description" to "URLs used to verify the product facts",
                ),
            ),
        ),
    )

    private fun str(desc: String) = mapOf("type" to "string", "description" to desc)

    private fun obj(props: Map<String, Any>) = mapOf(
        "type" to "object",
        "properties" to props,
        "required" to props.keys.toList(),
        "additionalProperties" to false,
    )

    private fun tool(name: String, desc: String, props: Map<String, Any>): BetaTool {
        val propBuilder = BetaTool.InputSchema.Properties.builder()
        props.forEach { (k, v) -> propBuilder.putAdditionalProperty(k, JsonValue.from(v)) }
        return BetaTool.builder()
            .name(name)
            .description(desc)
            .strict(true)
            .inputSchema(
                BetaTool.InputSchema.builder()
                    .properties(propBuilder.build())
                    .required(props.keys.toList())
                    .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                    .build()
            )
            .build()
    }

    companion object {
        const val MODEL = "claude-opus-5-5"
        private const val MAX_TURNS = 16
        private val USAGE_ORDER = mapOf("REQUIRED" to 0, "RECOMMENDED" to 1, "OPTIONAL" to 2)
    }
}
