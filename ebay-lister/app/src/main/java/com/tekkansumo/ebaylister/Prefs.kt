package com.tekkansumo.ebaylister

import android.content.Context
import com.tekkansumo.ebaylister.core.EbayConfig
import com.tekkansumo.ebaylister.core.TokenStore
import org.json.JSONObject

/** 設定とトークンを端末内（アプリ専用領域）に保存する。 */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** UI とやり取りする設定キー */
    private val keys = listOf(
        "claudeKey", "ebayClientId", "ebayClientSecret", "ebayRuName", "marketplace",
        "fulfillmentPolicyId", "paymentPolicyId", "returnPolicyId", "locationKey",
    )

    var sandbox: Boolean
        get() = sp.getBoolean("sandbox", false)
        set(v) = sp.edit().putBoolean("sandbox", v).apply()

    fun get(key: String): String = sp.getString(key, "") ?: ""

    fun toJson(): JSONObject = JSONObject().apply {
        keys.forEach { put(it, get(it)) }
        put("sandbox", sandbox)
        if (get("marketplace").isBlank()) put("marketplace", "EBAY_US")
    }

    fun save(json: JSONObject) {
        val e = sp.edit()
        keys.forEach { k -> if (json.has(k)) e.putString(k, json.optString(k).trim()) }
        if (json.has("sandbox")) e.putBoolean("sandbox", json.optBoolean("sandbox"))
        e.apply()
    }

    fun ebayConfig() = EbayConfig(
        clientId = get("ebayClientId"),
        clientSecret = get("ebayClientSecret"),
        ruName = get("ebayRuName"),
        sandbox = sandbox,
        marketplaceId = get("marketplace").ifBlank { "EBAY_US" },
    )

    /** 本番とサンドボックスでトークンを分けて持つ */
    val tokens: TokenStore = object : TokenStore {
        private fun k(name: String) = (if (sandbox) "sb_" else "pr_") + name
        override var refreshToken: String?
            get() = sp.getString(k("refresh"), null)
            set(v) = sp.edit().putString(k("refresh"), v).apply()
        override var accessToken: String?
            get() = sp.getString(k("access"), null)
            set(v) = sp.edit().putString(k("access"), v).apply()
        override var accessTokenExpiresAt: Long
            get() = sp.getLong(k("accessExp"), 0)
            set(v) = sp.edit().putLong(k("accessExp"), v).apply()
    }
}
