package com.tekkansumo.ebayship

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Properties
import javax.mail.Folder
import javax.mail.Part
import javax.mail.Session
import javax.mail.internet.MimeMultipart

/**
 * 「国際郵便マイページサービス 二次元コードの送信・印刷用番号の通知」メールを
 * IMAP で読みに行き、▼二次元コード表示 のリンクとお問い合わせ番号を取り出す。
 *
 * 件名は日本語なのでサーバ側の検索に頼らず、直近のメールを何通か取ってきて
 * こちらで絞る。文字コードの扱いがサーバごとに違っても影響を受けない。
 */
object MailFetcher {

    class MailException(message: String) : Exception(message)

    private const val SCAN = 40

    private val RE_URL = Regex("""https?://[A-Za-z0-9\-._~:/?#\[\]@!${'$'}&'()*+,;=%]+""")
    private val RE_TRACK = Regex("""\b[A-Z]{2}\d{9}JP\b""")
    private val RE_PRINT = Regex("""印刷用番号[^0-9A-Za-z]{0,8}([0-9A-Za-z\-]{6,})""")

    /**
     * 直近のメールから二次元コードの通知だけ拾う。新しい順。
     * since より前に届いたものは無視する（0 なら全部）。
     */
    fun fetch(ctx: Context, since: Long): List<JSONObject> {
        val s = Db.settings(ctx)
        val host = s.optString("mailHost")
        val port = s.optInt("mailPort", 993)
        val user = s.optString("mailUser")
        val pass = s.optString("mailPassword")
        val folderName = s.optString("mailFolder", "INBOX").ifEmpty { "INBOX" }
        val keyword = s.optString("mailSubject", "二次元コード").ifEmpty { "二次元コード" }
        if (host.isEmpty() || user.isEmpty() || pass.isEmpty()) {
            throw MailException("メール設定（IMAP）が未入力です")
        }

        val props = Properties().apply {
            put("mail.store.protocol", "imaps")
            put("mail.imaps.host", host)
            put("mail.imaps.port", port.toString())
            put("mail.imaps.ssl.enable", "true")
            put("mail.imaps.connectiontimeout", "30000")
            put("mail.imaps.timeout", "60000")
        }

        val session = Session.getInstance(props)
        val store = session.getStore("imaps")
        val out = ArrayList<JSONObject>()
        try {
            store.connect(host, port, user, pass)
            val folder = store.getFolder(folderName)
            if (!folder.exists()) throw MailException("フォルダ $folderName がありません")
            folder.open(Folder.READ_ONLY)
            val count = folder.messageCount
            if (count > 0) {
                val from = maxOf(1, count - SCAN + 1)
                for (msg in folder.getMessages(from, count)) {
                    val subject = try {
                        msg.subject ?: ""
                    } catch (e: Exception) {
                        ""
                    }
                    if (!subject.contains(keyword)) continue
                    val at = (msg.receivedDate ?: msg.sentDate)?.time ?: 0L
                    if (since > 0 && at > 0 && at < since) continue
                    val body = try {
                        textOf(msg)
                    } catch (e: Exception) {
                        ""
                    }
                    out.add(parseMail(subject, at, body))
                }
            }
            folder.close(false)
        } catch (e: MailException) {
            throw e
        } catch (e: Exception) {
            throw MailException(e.message ?: e.javaClass.simpleName)
        } finally {
            try {
                store.close()
            } catch (e: Exception) { /* 閉じ損ねても実害はない */ }
        }
        out.sortByDescending { it.optLong("receivedAt") }
        return out
    }

    fun fetchJson(ctx: Context, since: Long): JSONArray = JSONArray(fetch(ctx, since))

    /** 本文から必要なものを抜く。 */
    fun parseMail(subject: String, receivedAt: Long, bodyRaw: String): JSONObject {
        val body = bodyRaw.replace("\r\n", "\n")
        val urls = RE_URL.findAll(body).map { trimUrl(it.value) }.toList()

        // 「▼二次元コード表示」の後ろに出てくる最初の URL がそれ。
        var qr = ""
        val marker = Regex("""[▼▽■・]?\s*二次元コード(の)?表示""").find(body)
        if (marker != null) {
            val after = body.substring(marker.range.last + 1)
            qr = RE_URL.find(after)?.value?.let { trimUrl(it) } ?: ""
        }
        if (qr.isEmpty()) {
            qr = urls.firstOrNull { it.contains("post.japanpost.jp") && it.contains("?") }
                ?: urls.firstOrNull { it.contains("post.japanpost.jp") }
                        ?: urls.firstOrNull() ?: ""
        }

        return JSONObject().apply {
            put("subject", subject)
            put("receivedAt", receivedAt)
            put("qrUrl", qr)
            put("trackingNo", RE_TRACK.find(body)?.value ?: "")
            put("printNo", RE_PRINT.find(body)?.groupValues?.getOrNull(1) ?: "")
            put("snippet", body.replace("\n", " ").trim().take(160))
            put("urls", JSONArray(urls.distinct()))
        }
    }

    /** 行末の句読点や閉じ括弧まで URL に取り込まないようにする。 */
    private fun trimUrl(u: String): String = u.trimEnd('.', ',', ')', '>', '」', '、', '。')

    /** マルチパートでも HTML でも、とにかく文字にする。 */
    private fun textOf(part: Part): String {
        val type = try {
            part.contentType ?: ""
        } catch (e: Exception) {
            ""
        }
        val content = part.content
        if (content is String) {
            return if (type.lowercase().contains("html")) stripHtml(content) else content
        }
        if (content is MimeMultipart) {
            val plain = StringBuilder()
            val html = StringBuilder()
            for (i in 0 until content.count) {
                val bp = content.getBodyPart(i)
                val t = (bp.contentType ?: "").lowercase()
                val sub = try {
                    textOf(bp)
                } catch (e: Exception) {
                    ""
                }
                if (t.contains("html")) html.append(sub).append("\n") else plain.append(sub).append("\n")
            }
            return if (plain.isNotBlank()) plain.toString() else html.toString()
        }
        return content?.toString() ?: ""
    }

    private fun stripHtml(html: String): String = html
        .replace(Regex("""(?is)<(script|style)[^>]*>.*?</\1>"""), " ")
        .replace(Regex("""(?i)<br\s*/?>"""), "\n")
        .replace(Regex("""(?i)</p>"""), "\n")
        // href の中身は本文に出てこないことがあるので拾っておく
        .replace(Regex("""(?i)<a[^>]+href=["']([^"']+)["'][^>]*>""")) { " " + it.groupValues[1] + " " }
        .replace(Regex("""<[^>]+>"""), " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&nbsp;", " ")
}
