package com.tekkansumo.bookoffchecker

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties
import javax.mail.Authenticator
import javax.mail.Message
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage

/** レポート HTML の組み立てと SMTP 送信。Python 版の build_mail_html / api_mail_send 相当。 */
object Mailer {

    private fun esc(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

    /** 都道府県で絞った HTML と、ヒットした商品数を返す。 */
    fun buildHtml(results: List<ItemResult>, prefs: List<String>): Pair<String, Int> {
        val rows = StringBuilder()
        var total = 0
        for (r in results) {
            val hits = r.shops.filter { s -> prefs.isEmpty() || prefs.any { s.name.contains(it) } }
            if (hits.isEmpty()) continue
            total++
            val shopHtml = hits.joinToString("<br>") {
                "<a href=\"${esc(it.url)}\" style=\"color:#e0a458;text-decoration:none\">" +
                        "${esc(it.name)}</a>"
            }
            rows.append("<tr><td style=\"padding:10px 12px;border-bottom:1px solid #2a2620\">")
                .append("<div style=\"color:#f0e6da;font-size:14px;font-weight:600\">")
                .append(esc(r.name)).append("</div>")
                .append("<div style=\"margin-top:5px;font-size:12px;line-height:1.7\">")
                .append(shopHtml).append("</div></td></tr>")
        }

        val now = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.JAPAN).format(Date())
        val html = "<html><body style=\"margin:0;padding:16px;background:#16110d;" +
                "font-family:sans-serif\">" +
                "<h2 style=\"color:#e0a458;font-size:16px;margin:0 0 4px\">" +
                "BOOKOFF 在庫レポート</h2>" +
                "<p style=\"color:#a08d78;margin:0 0 14px;font-size:12px\">$now / ${total}件</p>" +
                "<table width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" " +
                "style=\"background:#17150f;border-radius:8px;border:1px solid #2a2620\">" +
                rows + "</table></body></html>"
        return Pair(html, total)
    }

    /** 送信。失敗したら例外メッセージを返す（成功時 null）。 */
    fun send(conf: JSONObject, html: String, count: Int): String? {
        val from = conf.optString("from")
        val pw = conf.optString("password")
        val to = conf.optString("to")
        if (from.isEmpty() || pw.isEmpty() || to.isEmpty()) return "メール設定が未入力です"

        val host = conf.optString("host", "smtp.gmail.com")
        val port = conf.optInt("port", 587)

        return try {
            val props = Properties().apply {
                put("mail.smtp.host", host)
                put("mail.smtp.port", port.toString())
                put("mail.smtp.auth", "true")
                put("mail.smtp.starttls.enable", "true")
                put("mail.smtp.connectiontimeout", "30000")
                put("mail.smtp.timeout", "30000")
            }
            val session = Session.getInstance(props, object : Authenticator() {
                override fun getPasswordAuthentication() = PasswordAuthentication(from, pw)
            })
            val stamp = SimpleDateFormat("MM/dd HH:mm", Locale.JAPAN).format(Date())
            val msg = MimeMessage(session).apply {
                setFrom(InternetAddress(from))
                setRecipients(Message.RecipientType.TO, InternetAddress.parse(to))
                setSubject("BOOKOFF 在庫レポート $stamp (${count}件)", "UTF-8")
                setContent(html, "text/html; charset=UTF-8")
            }
            Transport.send(msg)
            null
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        }
    }
}
