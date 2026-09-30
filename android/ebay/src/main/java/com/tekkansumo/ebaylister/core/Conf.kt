package com.tekkansumo.ebaylister.core

import org.json.JSONArray
import org.json.JSONObject

/** 画面にそのまま見せてよいエラー。Python 版の AppError 相当。 */
open class AppError(msg: String) : Exception(msg)

/** 設定の保存先。アプリでは SharedPreferences、テストではメモリ。 */
interface ConfStore {
    fun load(): String?
    fun save(json: String)
}

data class Marketplace(val currency: String, val language: String, val host: String)

/** 設定の既定値・検証・秘密の伏せ字。ebay_lister.py の DEFAULT_CONF まわりと同じ規則。 */
object Conf {
    val MARKETPLACES = linkedMapOf(
        "EBAY_US" to Marketplace("USD", "en-US", "www.ebay.com"),
        "EBAY_GB" to Marketplace("GBP", "en-GB", "www.ebay.co.uk"),
        "EBAY_AU" to Marketplace("AUD", "en-AU", "www.ebay.com.au"),
        "EBAY_CA" to Marketplace("CAD", "en-CA", "www.ebay.ca"),
        "EBAY_DE" to Marketplace("EUR", "de-DE", "www.ebay.de"),
    )

    val CONDITIONS = listOf(
        "NEW" to "新品",
        "NEW_OTHER" to "新品（箱なし・開封済み）",
        "NEW_WITH_DEFECTS" to "新品（傷あり）",
        "CERTIFIED_REFURBISHED" to "メーカー整備済み",
        "SELLER_REFURBISHED" to "セラー整備済み",
        "LIKE_NEW" to "未使用に近い",
        "USED_EXCELLENT" to "中古 - 非常に良い",
        "USED_VERY_GOOD" to "中古 - 良い",
        "USED_GOOD" to "中古 - 可",
        "USED_ACCEPTABLE" to "中古 - 難あり",
        "FOR_PARTS_OR_NOT_WORKING" to "ジャンク",
    )
    val CONDITION_KEYS = CONDITIONS.map { it.first }

    private val STRING_KEYS = listOf(
        "anthropic_api_key", "ebay_env", "client_id", "client_secret", "ru_name",
        "refresh_token", "refresh_token_expires", "marketplace_id",
        "fulfillment_policy_id", "payment_policy_id", "return_policy_id", "merchant_location_key",
    )
    val SECRETS = listOf("anthropic_api_key", "client_secret", "refresh_token")

    fun defaults(): JSONObject = JSONObject().apply {
        for (k in STRING_KEYS) put(k, "")
        put("ebay_env", "production")
        put("marketplace_id", "EBAY_US")
        put("usd_jpy", 150.0)
        put("scrape_sold", true)
        put("auto_draft", true)
    }

    fun load(store: ConfStore): JSONObject {
        val d = defaults()
        val raw = store.load() ?: return d
        val saved = try { JSONObject(raw) } catch (e: Exception) { return d }
        for (k in d.keys().asSequence().toList()) if (saved.has(k)) d.put(k, saved.get(k))
        return d
    }

    fun public(c: JSONObject): JSONObject {
        val out = JSONObject()
        for (k in c.keys()) if (k !in SECRETS) out.put(k, c.get(k))
        for (k in SECRETS) out.put("has_$k", c.optString(k).isNotEmpty())
        out.put("has_anthropic_env", false)
        return out
    }

    /** 画面からの保存。空欄の秘密は「変更しない」、トークンは画面から書かせない。 */
    fun applyPatch(c: JSONObject, patch: JSONObject): JSONObject {
        val d = defaults()
        for (k in d.keys().asSequence().toList()) {
            if (!patch.has(k) || k == "refresh_token" || k == "refresh_token_expires") continue
            val v = patch.get(k)
            if (k in SECRETS && (v == JSONObject.NULL || v.toString().isEmpty())) continue
            when (d.get(k)) {
                is Boolean -> c.put(k, v == true || v.toString() == "true")
                is Double -> c.put(k, v.toString().toDoubleOrNull()
                    ?: throw AppError("$k は数値で入力してください"))
                else -> c.put(k, if (v == JSONObject.NULL) "" else v.toString().trim())
            }
        }
        if (c.optString("marketplace_id") !in MARKETPLACES) throw AppError("未対応のマーケットプレイスです")
        if (c.optString("ebay_env") !in listOf("production", "sandbox"))
            throw AppError("ebay_env は production か sandbox です")
        return c
    }

    fun meta(): JSONObject = JSONObject().apply {
        put("conditions", JSONArray(CONDITIONS.map { JSONArray(listOf(it.first, it.second)) }))
        put("marketplaces", JSONArray(MARKETPLACES.keys.toList()))
    }

    fun market(c: JSONObject): Marketplace = MARKETPLACES[c.optString("marketplace_id")] ?: MARKETPLACES["EBAY_US"]!!
}
