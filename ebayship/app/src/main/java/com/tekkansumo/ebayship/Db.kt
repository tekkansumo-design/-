package com.tekkansumo.ebayship

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

/**
 * 設定と注文の保存先。
 *
 * 秘密（eBay のトークン、郵便局のパスワード、メールのアプリパスワード）は
 * EncryptedSharedPreferences に入れる。端末が古い等で暗号化領域が開けないときは
 * 通常の SharedPreferences に落とす。落ちてもアプリが起動しないよりはましなので、
 * 例外は握りつぶして平文にフォールバックする。
 */
object Db {

    private const val PREF_PLAIN = "ebayship"
    private const val PREF_SECRET = "ebayship_secret"

    private const val K_SETTINGS = "settings"
    private const val K_ORDERS = "orders"
    private const val K_PROFILE = "jppost_profile"

    /** 画面に返すときパスワード類はこの文字列に置き換える。保存時これなら据え置き。 */
    const val MASK = "****"

    private val SECRET_KEYS = setOf(
        "ebayClientSecret", "ebayRefreshToken", "ebayAccessToken", "ebayAccessExpiresAt",
        "jpPassword", "mailPassword"
    )

    private var plainRef: SharedPreferences? = null
    private var secretRef: SharedPreferences? = null

    private fun plain(ctx: Context): SharedPreferences =
        plainRef ?: ctx.applicationContext
            .getSharedPreferences(PREF_PLAIN, Context.MODE_PRIVATE).also { plainRef = it }

    private fun secret(ctx: Context): SharedPreferences {
        secretRef?.let { return it }
        val app = ctx.applicationContext
        val sp = try {
            val key = MasterKey.Builder(app)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                app, PREF_SECRET, key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            app.getSharedPreferences(PREF_SECRET + "_plain", Context.MODE_PRIVATE)
        }
        secretRef = sp
        return sp
    }

    // ---------------------------------------------------------------- 設定

    private fun defaults(): JSONObject = JSONObject().apply {
        put("ebayEnv", "production")          // production / sandbox
        put("ebayClientId", "")
        put("ebayClientSecret", "")
        put("ebayRuName", "")
        put("ebayRefreshToken", "")
        put("ebayAccessToken", "")
        put("ebayAccessExpiresAt", "0")
        put("carrierCode", "JapanPost")       // eBay 側の配送業者コード

        put("jpLoginId", "")
        put("jpPassword", "")
        put("jpStartUrl", "https://www.int-mypage.post.japanpost.jp/smart/MSC1000")
        // 入力のあと「次へ」まで自動で押すか。既定は押さない
        put("jpAutoAdvance", "")

        put("mailHost", "imap.gmail.com")
        put("mailPort", 993)
        put("mailUser", "")
        put("mailPassword", "")
        put("mailFolder", "INBOX")
        put("mailSubject", "二次元コード")

        // 送り状の依頼主（毎回同じなので設定に持つ）
        put("fromName", "")
        put("fromPostal", "")
        put("fromAddress", "")
        put("fromPhone", "")

        // 内容品の既定値
        put("defHsCode", "")
        put("defOrigin", "JP")
        put("defWeight", "")
    }

    fun settings(ctx: Context): JSONObject {
        val base = defaults()
        val raw = plain(ctx).getString(K_SETTINGS, null)
        if (raw != null) {
            try {
                val o = JSONObject(raw)
                for (k in o.keys()) base.put(k, o.get(k))
            } catch (e: Exception) { /* 壊れていたら既定値のまま */ }
        }
        val s = secret(ctx)
        for (k in SECRET_KEYS) {
            if (s.contains(k)) base.put(k, s.getString(k, "") ?: "")
        }
        return base
    }

    fun get(ctx: Context, key: String): String = settings(ctx).optString(key, "")

    fun save(ctx: Context, patch: JSONObject) {
        val cur = settings(ctx)
        for (k in patch.keys()) {
            val v = patch.get(k)
            // マスクのまま返ってきた項目は既存値を保つ
            if (v is String && v == MASK) continue
            cur.put(k, v)
        }
        val open = JSONObject()
        val se = secret(ctx).edit()
        for (k in cur.keys()) {
            if (SECRET_KEYS.contains(k)) se.putString(k, cur.optString(k, ""))
            else open.put(k, cur.get(k))
        }
        se.apply()
        plain(ctx).edit().putString(K_SETTINGS, open.toString()).apply()
    }

    /**
     * まとめて貼り付けた文字を設定として取り込む。
     *
     * スマホで App ID のような長い文字列を打つのは骨が折れるので、
     * JSON でも「App ID = xxxx」のような行の並びでも受け取れるようにする。
     * 戻り値は取り込んだ項目名。
     */
    fun importText(ctx: Context, text: String): List<String> {
        val patch = JSONObject()
        val trimmed = text.trim()

        if (trimmed.startsWith("{")) {
            try {
                val o = JSONObject(trimmed)
                for (k in o.keys()) {
                    val key = alias(k) ?: continue
                    patch.put(key, o.optString(k))
                }
            } catch (e: Exception) { /* JSON でなければ行として読む */ }
        }

        if (patch.length() == 0) {
            for (raw in trimmed.split('\n')) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val at = line.indexOfFirst { it == '=' || it == ':' || it == '\t' }
                if (at <= 0) continue
                val key = alias(line.substring(0, at)) ?: continue
                val value = line.substring(at + 1).trim().trim('"', '\'', ',')
                if (value.isNotEmpty()) patch.put(key, value)
            }
        }

        if (patch.length() == 0) return emptyList()
        // 環境は SBX / PRD どちらの書き方でも受ける
        val env = patch.optString("ebayEnv").lowercase()
        if (env.isNotEmpty()) {
            val sandbox = env.contains("sand") || env.contains("sbx") ||
                    env.contains("サンド") || env.contains("テスト")
            patch.put("ebayEnv", if (sandbox) "sandbox" else "production")
        }
        save(ctx, patch)
        return patch.keys().asSequence().toList()
    }

    /** 貼り付けた見出しを設定の項目名に読み替える。 */
    private fun alias(rawKey: String): String? {
        val k = rawKey.trim().trim('"', '\'').lowercase()
            .replace(" ", "").replace("_", "").replace("-", "")
        return when (k) {
            "ebayenv", "env", "environment", "環境" -> "ebayEnv"
            "ebayclientid", "appid", "clientid", "applicationid" -> "ebayClientId"
            "ebayclientsecret", "certid", "clientsecret" -> "ebayClientSecret"
            "ebayruname", "runame", "redirecturi", "redirecturl" -> "ebayRuName"
            "carriercode", "carrier" -> "carrierCode"
            "jploginid", "jpid", "jplogin" -> "jpLoginId"
            "jppassword", "jppw" -> "jpPassword"
            "jpstarturl", "jpurl" -> "jpStartUrl"
            "jpautoadvance", "jpauto" -> "jpAutoAdvance"
            "mailhost", "imaphost" -> "mailHost"
            "mailport", "imapport" -> "mailPort"
            "mailuser", "imapuser", "mailaddress" -> "mailUser"
            "mailpassword", "imappassword", "apppassword" -> "mailPassword"
            "mailfolder" -> "mailFolder"
            "mailsubject" -> "mailSubject"
            "fromname" -> "fromName"
            "frompostal", "fromzip" -> "fromPostal"
            "fromaddress" -> "fromAddress"
            "fromphone", "fromtel" -> "fromPhone"
            "defhscode", "hscode" -> "defHsCode"
            "deforigin", "origin" -> "defOrigin"
            "defweight", "weight" -> "defWeight"
            // Dev ID はこのアプリでは使わないので黙って捨てる
            else -> null
        }
    }

    /** 画面に渡す用。パスワード類は入っていれば MASK にする。 */
    fun settingsForUi(ctx: Context): JSONObject {
        val o = settings(ctx)
        for (k in listOf("ebayClientSecret", "jpPassword", "mailPassword")) {
            if (o.optString(k).isNotEmpty()) o.put(k, MASK)
        }
        // トークンそのものは画面に出さない。有無だけ伝える
        o.put("ebayLinked", o.optString("ebayRefreshToken").isNotEmpty())
        o.remove("ebayRefreshToken")
        o.remove("ebayAccessToken")
        o.remove("ebayAccessExpiresAt")
        return o
    }

    // ---------------------------------------------------------------- 注文

    /** orderId -> 注文 JSON。 */
    fun orders(ctx: Context): JSONObject {
        val raw = plain(ctx).getString(K_ORDERS, null) ?: return JSONObject()
        return try {
            JSONObject(raw)
        } catch (e: Exception) {
            JSONObject()
        }
    }

    fun order(ctx: Context, orderId: String): JSONObject? =
        orders(ctx).optJSONObject(orderId)

    fun putOrder(ctx: Context, o: JSONObject) {
        val all = orders(ctx)
        all.put(o.getString("orderId"), o)
        plain(ctx).edit().putString(K_ORDERS, all.toString()).apply()
    }

    /** 既存の注文に一部だけ書き込む。 */
    fun patchOrder(ctx: Context, orderId: String, patch: JSONObject): JSONObject? {
        val all = orders(ctx)
        val o = all.optJSONObject(orderId) ?: return null
        for (k in patch.keys()) o.put(k, patch.get(k))
        all.put(orderId, o)
        plain(ctx).edit().putString(K_ORDERS, all.toString()).apply()
        return o
    }

    /**
     * eBay から取り直した一覧を保存する。
     * 入力欄の内容や二次元コードのリンクなど、こちらで足した情報は消さずに残す。
     */
    fun mergeOrders(ctx: Context, fetched: List<JSONObject>): List<JSONObject> {
        val all = orders(ctx)
        val keep = listOf(
            "form", "state", "submittedAt", "qrUrl", "trackingNo", "shippedAt", "note"
        )
        for (f in fetched) {
            val id = f.getString("orderId")
            val old = all.optJSONObject(id)
            if (old != null) for (k in keep) if (old.has(k)) f.put(k, old.get(k))
            if (!f.has("state")) f.put("state", "new")
            all.put(id, f)
        }
        plain(ctx).edit().putString(K_ORDERS, all.toString()).apply()
        return list(ctx)
    }

    /** 注文日の新しい順。 */
    fun list(ctx: Context): List<JSONObject> {
        val all = orders(ctx)
        val out = ArrayList<JSONObject>()
        for (k in all.keys()) all.optJSONObject(k)?.let { out.add(it) }
        out.sortByDescending { it.optString("creationDate") }
        return out
    }

    fun listJson(ctx: Context): JSONArray = JSONArray(list(ctx))

    /** 発送済みにしたものを一覧から消す。 */
    fun removeShipped(ctx: Context): Int {
        val all = orders(ctx)
        val gone = ArrayList<String>()
        for (k in all.keys()) {
            if (all.optJSONObject(k)?.optString("state") == "shipped") gone.add(k)
        }
        for (k in gone) all.remove(k)
        plain(ctx).edit().putString(K_ORDERS, all.toString()).apply()
        return gone.size
    }

    /** 二次元コードのメールを注文に割り当て済みか調べるための一覧。 */
    fun usedQrUrls(ctx: Context): Set<String> {
        val out = HashSet<String>()
        for (o in list(ctx)) {
            val u = o.optString("qrUrl")
            if (u.isNotEmpty()) out.add(u)
        }
        return out
    }

    // ------------------------------------------------- マイページの入力欄の覚え書き

    /**
     * 国際郵便マイページの入力欄をどこに書くか、画面ごとに覚えたもの。
     * { "<画面キー>": { "<項目名>": "<CSS セレクタ>" } }
     */
    fun jpProfile(ctx: Context): JSONObject {
        val raw = plain(ctx).getString(K_PROFILE, null) ?: return JSONObject()
        return try {
            JSONObject(raw)
        } catch (e: Exception) {
            JSONObject()
        }
    }

    fun saveJpProfile(ctx: Context, pageKey: String, field: String, selector: String) {
        val p = jpProfile(ctx)
        val page = p.optJSONObject(pageKey) ?: JSONObject().also { p.put(pageKey, it) }
        page.put(field, selector)
        plain(ctx).edit().putString(K_PROFILE, p.toString()).apply()
    }

    fun clearJpProfile(ctx: Context) {
        plain(ctx).edit().remove(K_PROFILE).apply()
    }
}
