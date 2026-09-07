package com.tekkansumo.cdprice

import android.content.Context

/** 学習した検索 URL と利用者の指定を残しておく。 */
object Prefs {

    private const val FILE = "cdprice"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** 前回商品が取れた URL テンプレート。 */
    fun learned(ctx: Context, siteId: String): String? =
        sp(ctx).getString("learned_$siteId", null)

    fun learn(ctx: Context, siteId: String, template: String) {
        sp(ctx).edit().putString("learned_$siteId", template).apply()
    }

    /** 診断画面で指定された URL テンプレート。学習より優先する。 */
    fun override(ctx: Context, siteId: String): String? =
        sp(ctx).getString("override_$siteId", null)?.ifBlank { null }

    fun setOverride(ctx: Context, siteId: String, template: String?) {
        val e = sp(ctx).edit()
        if (template.isNullOrBlank()) e.remove("override_$siteId") else e.putString("override_$siteId", template.trim())
        e.apply()
    }

    fun forget(ctx: Context) {
        sp(ctx).edit().apply { Sites.ALL.forEach { remove("learned_${it.id}") } }.apply()
    }
}
