package com.tekkansumo.bookoffchecker

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 商品ID一覧とメール設定の保存。Python 版の bookoff_ids.json 相当。 */
object Store {

    private const val PREF = "bookoff"
    private const val K_IDS = "ids"
    private const val K_MAIL = "mail"

    private val DEFAULT_IDS = listOf("0016309421", "0001189556")

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun ids(ctx: Context): List<String> {
        val raw = prefs(ctx).getString(K_IDS, null) ?: return DEFAULT_IDS
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).map { a.getString(it) }
        } catch (e: Exception) {
            DEFAULT_IDS
        }
    }

    /** 数字以外を落とし 8 桁以上を 10 桁ゼロ埋め、重複除去。Python の save_ids と同じ。 */
    fun normalize(raw: List<String>): List<String> {
        val seen = LinkedHashSet<String>()
        for (s in raw) {
            val digits = s.filter { it.isDigit() }
            if (digits.length >= 8) seen.add(digits.padStart(10, '0'))
        }
        return seen.toList()
    }

    fun saveIds(ctx: Context, raw: List<String>): List<String> {
        val out = normalize(raw)
        prefs(ctx).edit().putString(K_IDS, JSONArray(out).toString()).apply()
        return out
    }

    fun mailConfig(ctx: Context): JSONObject {
        val raw = prefs(ctx).getString(K_MAIL, null)
        val o = try {
            if (raw != null) JSONObject(raw) else JSONObject()
        } catch (e: Exception) {
            JSONObject()
        }
        if (!o.has("from")) o.put("from", "")
        if (!o.has("password")) o.put("password", "")
        if (!o.has("to")) o.put("to", "")
        if (!o.has("host")) o.put("host", "smtp.gmail.com")
        if (!o.has("port")) o.put("port", 587)
        return o
    }

    /** パスワードは "****" のとき既存値を保持する（画面はマスク値を送ってくる）。 */
    fun saveMailConfig(ctx: Context, patch: JSONObject) {
        val cur = mailConfig(ctx)
        val pw = patch.optString("password", "")
        if (pw.isNotEmpty() && pw != "****") cur.put("password", pw)
        for (k in listOf("from", "to", "host", "port")) {
            if (patch.has(k)) cur.put(k, patch.get(k))
        }
        prefs(ctx).edit().putString(K_MAIL, cur.toString()).apply()
    }
}
