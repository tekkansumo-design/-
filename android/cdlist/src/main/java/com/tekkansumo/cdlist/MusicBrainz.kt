package com.tekkansumo.cdlist

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 作品一覧の取り寄せ先。鍵は要らないが、素性の分かる User-Agent と
 * 「1 秒に 1 回まで」という約束がある。 https://musicbrainz.org/doc/MusicBrainz_API
 *
 * 画面（artist_cd_web.py の UI_JS）がそのまま読める形の JSON を返す。
 * Flask 版の /api/artists・/api/discography と同じ形。
 */
object MusicBrainz {

    private const val BASE = "https://musicbrainz.org/ws/2"
    private const val GAP_MS = 1100L

    private val lock = ReentrantLock()
    private var lastAt = 0L

    private val KIND = mapOf("Album" to "album", "Single" to "single", "EP" to "ep")
    private val SUB_JA = mapOf(
        "Compilation" to "編集盤", "Live" to "ライブ", "Remix" to "リミックス",
        "Soundtrack" to "サントラ", "Demo" to "デモ", "DJ-mix" to "DJミックス",
        "Mixtape/Street" to "ミックステープ", "Interview" to "インタビュー",
        "Audiobook" to "朗読", "Spokenword" to "語り", "Audio drama" to "ドラマ",
        "Field recording" to "フィールド録音"
    )

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** 約束の 1 秒 1 回を守る。503（混んでいる）は少し置いて数回だけ試す。 */
    private fun paced(url: String): Res {
        var attempt = 0
        while (true) {
            lock.withLock {
                val wait = lastAt + GAP_MS - System.currentTimeMillis()
                if (wait > 0) {
                    try {
                        TimeUnit.MILLISECONDS.sleep(wait)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                }
                lastAt = System.currentTimeMillis()
            }
            val res = Http.get(url, Http.APP_UA, "application/json")
            if (res.code != 503 || attempt >= 3) return res
            attempt++
            try {
                TimeUnit.SECONDS.sleep((2 + attempt * 2).toLong())
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return res
            }
        }
    }

    private fun fail(msg: String) = JSONObject().put("ok", false).put("msg", msg)

    // ── アーティストを探す ──────────────────────────────
    fun artists(word: String, limit: Int = 8): JSONObject {
        val res = paced("$BASE/artist?query=${enc(word)}&limit=$limit&fmt=json")
        if (res.code != 200 || res.body.isBlank()) {
            return fail(res.error ?: "MusicBrainz HTTP ${res.code}")
        }
        return try {
            JSONObject().put("ok", true).put("artists", parseArtists(res.body))
        } catch (e: Exception) {
            fail("応答を読めませんでした: ${e.javaClass.simpleName}")
        }
    }

    /** 応答の読み取り。通信と切り離してあるので単体で確かめられる。 */
    fun parseArtists(json: String): JSONArray {
        val arr = JSONObject(json).optJSONArray("artists") ?: JSONArray()
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id", "")
            if (id.isEmpty()) continue
            val name = o.optString("name", "")
            out.put(JSONObject().apply {
                put("id", id)
                put("name", name)
                put("alias", japaneseAlias(o, name))
                put("area", o.optJSONObject("area")?.optString("name", "").orEmpty()
                    .ifEmpty { o.optString("country", "") })
                put("type", o.optString("type", ""))
                put("note", o.optString("disambiguation", ""))
                put("score", o.optInt("score", 0))
            })
        }
        return out
    }

    /** 日本語表記の別名。BOOKOFF の検索はこちらのほうが当たる。 */
    private fun japaneseAlias(o: JSONObject, name: String): String {
        val al = o.optJSONArray("aliases") ?: return ""
        for (j in 0 until al.length()) {
            val v = al.optJSONObject(j)?.optString("name").orEmpty()
            if (v.isEmpty() || v == name) continue
            if (v.any { it in '぀'..'ヿ' || it in '一'..'鿿' }) return v
        }
        return ""
    }

    // ── 作品一覧 ────────────────────────────────────────
    /**
     * アルバム・シングル・EP をまとめて取る（100 件ずつ）。
     * 種別で絞るのは画面側なので、ここでは全部持ってくる。
     */
    fun discography(mbid: String, owned: JSONObject): JSONObject {
        val items = JSONArray()
        var offset = 0
        var total = -1
        while (offset < 600) {
            val res = paced(
                "$BASE/release-group?artist=${enc(mbid)}&limit=100&offset=$offset&fmt=json"
            )
            if (res.code != 200 || res.body.isBlank()) {
                if (items.length() > 0) break
                return fail(res.error ?: "MusicBrainz HTTP ${res.code}")
            }
            val page = try {
                parsePage(res.body)
            } catch (e: Exception) {
                return fail("応答を読めませんでした: ${e.javaClass.simpleName}")
            }
            if (total < 0) total = page.second
            for (i in 0 until page.first.length()) items.put(page.first.get(i))
            offset += page.first.length()
            if (page.first.length() == 0 || offset >= total) break
        }
        val sorted = sortNewestFirst(items)
        for (i in 0 until sorted.length()) {
            val o = sorted.getJSONObject(i)
            o.put("owned", owned.has(o.optString("id")))
        }
        return JSONObject().put("ok", true).put("items", sorted)
    }

    /** 1 ページ分の読み取り。戻り値は (このページの作品, 全体の件数)。 */
    fun parsePage(json: String): Pair<JSONArray, Int> {
        val root = JSONObject(json)
        val arr = root.optJSONArray("release-groups") ?: JSONArray()
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val title = o.optString("title", "")
            if (title.isEmpty()) continue
            val primary = o.optString("primary-type", "")
            val secs = o.optJSONArray("secondary-types")
            val sub = StringBuilder()
            if (secs != null) {
                for (j in 0 until secs.length()) {
                    val v = secs.optString(j, "")
                    if (v.isEmpty()) continue
                    if (sub.isNotEmpty()) sub.append("／")
                    sub.append(SUB_JA[v] ?: v)
                }
            }
            out.put(JSONObject().apply {
                put("id", o.optString("id", ""))
                put("title", title)
                put("date", o.optString("first-release-date", ""))
                put("kind", KIND[primary] ?: "other")
                put("primary", primary)
                put("sub", sub.toString())
                put("note", o.optString("disambiguation", ""))
            })
        }
        return Pair(out, root.optInt("release-group-count", out.length()))
    }

    /** 新しい順。日付の無いものは末尾へ。 */
    fun sortNewestFirst(arr: JSONArray): JSONArray {
        val list = ArrayList<JSONObject>()
        for (i in 0 until arr.length()) list.add(arr.getJSONObject(i))
        list.sortWith(compareByDescending { it.optString("date", "").ifEmpty { "0000" } })
        val out = JSONArray()
        for (o in list) out.put(o)
        return out
    }
}
