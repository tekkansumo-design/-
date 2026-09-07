package com.tekkansumo.cdprice

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/** アーティストの候補。同名や別表記があるので選び直せるようにする。 */
data class ArtistRef(
    val id: String,
    val name: String,
    val disambiguation: String,
    val country: String,
    val begin: String,
    val aliases: List<String>
) {
    /** 一覧に出す一行。「宇多田ヒカル ／ JP ／ 1998-」のような形。 */
    val line: String
        get() = buildString {
            append(name)
            val sub = listOf(disambiguation, country, begin.take(4)).filter { it.isNotBlank() }
            if (sub.isNotEmpty()) append("（").append(sub.joinToString(" / ")).append("）")
        }

    /** 日本語表記の別名。店の検索はこちらのほうが当たる。 */
    val japanese: String?
        get() = aliases.firstOrNull { a -> a.any { it in '぀'..'ヿ' || it in '一'..'鿿' } }
}

/** アルバム 1 枚。MusicBrainz の release-group にあたる。 */
data class Album(
    val id: String,
    val title: String,
    val date: String,
    val type: String,
    val secondary: List<String>
) {
    val year: String get() = date.take(4)

    /** ベスト・ライブなどの区別。無印はオリジナルアルバム。 */
    val kind: String
        get() = when {
            secondary.contains("Compilation") -> "ベスト"
            secondary.contains("Live") -> "ライブ"
            secondary.contains("Remix") -> "リミックス"
            secondary.contains("Soundtrack") -> "サントラ"
            type.equals("EP", true) -> "EP"
            else -> ""
        }
}

/**
 * アーティストのアルバム一覧を MusicBrainz から取る。
 *
 * 店の検索結果だけだと「何枚出ているうちの何枚が見つかったのか」が分からない。
 * 先に発売作の一覧を持っておくと、抜けている盤を名指しで探し直せる。
 *
 * 鍵は要らないが、素性の分かる User-Agent と 1 秒に 1 回までという約束がある。
 * https://musicbrainz.org/doc/MusicBrainz_API
 */
object MusicBrainz {

    private const val BASE = "https://musicbrainz.org/ws/2"
    private const val GAP_MS = 1100L

    private val gate = Object()
    private var lastAt = 0L

    /** 約束の 1 秒 1 回を守る。 */
    private fun paced(url: String): Res {
        synchronized(gate) {
            val wait = lastAt + GAP_MS - System.currentTimeMillis()
            if (wait > 0) {
                try {
                    Thread.sleep(wait)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
            lastAt = System.currentTimeMillis()
        }
        return Http.get(url, Http.APP_UA, "application/json")
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun JSONArray.strings(): List<String> =
        (0 until length()).mapNotNull { optString(it, "").ifBlank { null } }

    // ── アーティストを探す ──────────────────────────────
    fun searchArtists(word: String, limit: Int = 8): List<ArtistRef> {
        val res = paced("$BASE/artist/?query=${enc(word)}&limit=$limit&fmt=json")
        if (res.body.isBlank()) throw RuntimeException(res.error ?: "HTTP ${res.code}")
        return parseArtists(res.body)
    }

    /** 応答の読み取り。通信と切り離してあるので単体で確かめられる。 */
    fun parseArtists(json: String): List<ArtistRef> {
        val arr = JSONObject(json).optJSONArray("artists") ?: return emptyList()
        val out = ArrayList<ArtistRef>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val aliases = ArrayList<String>()
            o.optJSONArray("aliases")?.let { al ->
                for (j in 0 until al.length()) {
                    al.optJSONObject(j)?.optString("name")?.takeIf { it.isNotBlank() }?.let { aliases.add(it) }
                }
            }
            out.add(
                ArtistRef(
                    id = o.optString("id"),
                    name = o.optString("name"),
                    disambiguation = o.optString("disambiguation", ""),
                    country = o.optString("country", ""),
                    begin = o.optJSONObject("life-span")?.optString("begin", "") ?: "",
                    aliases = aliases
                )
            )
        }
        return out.filter { it.id.isNotBlank() }
    }

    // ── アルバム一覧 ────────────────────────────────────
    /** オリジナル・EP・ベストをまとめて、発売の古い順に返す。 */
    fun albums(artistId: String, max: Int = 200): List<Album> {
        val out = ArrayList<Album>()
        var offset = 0
        while (offset < max) {
            val url = "$BASE/release-group?artist=${enc(artistId)}" +
                    "&type=album%7Cep&limit=100&offset=$offset&fmt=json"
            val res = paced(url)
            if (res.body.isBlank()) {
                if (out.isEmpty()) throw RuntimeException(res.error ?: "HTTP ${res.code}")
                break
            }
            val (page, total) = parseAlbums(res.body)
            out.addAll(page)
            offset += 100
            if (page.isEmpty() || offset >= total) break
        }
        return tidy(out)
    }

    /** 1 ページ分の読み取り。戻り値は (このページの分, 全体の件数)。 */
    fun parseAlbums(json: String): Pair<List<Album>, Int> {
        val root = JSONObject(json)
        val arr = root.optJSONArray("release-groups") ?: return Pair(emptyList(), 0)
        val out = ArrayList<Album>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val title = o.optString("title", "")
            if (title.isBlank()) continue
            out.add(
                Album(
                    id = o.optString("id"),
                    title = title,
                    date = o.optString("first-release-date", ""),
                    type = o.optString("primary-type", ""),
                    secondary = o.optJSONArray("secondary-types")?.strings() ?: emptyList()
                )
            )
        }
        return Pair(out, root.optInt("release-group-count", out.size))
    }

    /**
     * 発売の古い順に並べ、同じ題名の重複を落とす。
     * 同じ盤が再発などで別の release-group になっていることがある。
     */
    fun tidy(list: List<Album>): List<Album> {
        val byTitle = LinkedHashMap<String, Album>()
        for (a in list.sortedWith(compareBy({ it.date.ifBlank { "9999" } }, { it.title }))) {
            val k = a.title.lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")
            if (k.isNotBlank() && !byTitle.containsKey(k)) byTitle[k] = a
        }
        return byTitle.values.toList()
    }
}
