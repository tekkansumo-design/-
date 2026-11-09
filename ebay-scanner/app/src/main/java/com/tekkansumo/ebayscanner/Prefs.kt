package com.tekkansumo.ebayscanner

import android.content.Context
import android.content.SharedPreferences

/** 設定の保存場所。 */
class Prefs(ctx: Context) {
    private val p: SharedPreferences = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var clientId: String
        get() = p.getString("client_id", "")!!.trim()
        set(v) = p.edit().putString("client_id", v.trim()).apply()
    var clientSecret: String
        get() = p.getString("client_secret", "")!!.trim()
        set(v) = p.edit().putString("client_secret", v.trim()).apply()

    var thresholdJpy: Int
        get() = p.getInt("threshold", 3000)
        set(v) = p.edit().putInt("threshold", v).apply()
    var feePercent: Float
        get() = p.getFloat("fee_percent", 17f)
        set(v) = p.edit().putFloat("fee_percent", v).apply()
    var shippingJpy: Int
        get() = p.getInt("shipping", 2500)
        set(v) = p.edit().putInt("shipping", v).apply()

    /** 自動取得しない場合に使う為替。 */
    var manualFx: Float
        get() = p.getFloat("manual_fx", 150f)
        set(v) = p.edit().putFloat("manual_fx", v).apply()
    var autoFx: Boolean
        get() = p.getBoolean("auto_fx", true)
        set(v) = p.edit().putBoolean("auto_fx", v).apply()
    var cachedFx: Float
        get() = p.getFloat("cached_fx", 0f)
        set(v) = p.edit().putFloat("cached_fx", v).apply()
    var cachedFxAt: Long
        get() = p.getLong("cached_fx_at", 0L)
        set(v) = p.edit().putLong("cached_fx_at", v).apply()

    /** バーコードも型番も無いとき、写真そのもので eBay を検索する。 */
    var imageSearch: Boolean
        get() = p.getBoolean("image_search", true)
        set(v) = p.edit().putBoolean("image_search", v).apply()
    /** 日本から発送している出品を優先して相場を見る（ライバルと同じ条件の相場）。 */
    var preferJapanSellers: Boolean
        get() = p.getBoolean("prefer_jp", true)
        set(v) = p.edit().putBoolean("prefer_jp", v).apply()
    var sound: Boolean
        get() = p.getBoolean("sound", true)
        set(v) = p.edit().putBoolean("sound", v).apply()

    val hasCredentials get() = clientId.isNotEmpty() && clientSecret.isNotEmpty()

    fun fx(): Double {
        if (autoFx && cachedFx > 0f) return cachedFx.toDouble()
        return manualFx.toDouble()
    }

    fun profitConfig() = ProfitConfig(
        feeRate = feePercent / 100.0,
        shippingJpy = shippingJpy,
        fxJpyPerUsd = fx(),
        thresholdJpy = thresholdJpy,
    )
}
