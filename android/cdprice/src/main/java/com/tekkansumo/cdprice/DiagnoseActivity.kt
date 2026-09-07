package com.tekkansumo.cdprice

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File

/**
 * 取れなかったときの原因切り分け。
 *
 * サイトは検索 URL の形も HTML も予告なく変わるので、
 *   ・実際に叩いた URL と応答（状態・長さ・金額の有無・取れた件数）を見せる
 *   ・その場で URL の形を差し替えて試し、通ったら保存できる
 *   ・取れた HTML をそのまま共有して中身を確認できる
 * ようにしてある。アプリを作り直さなくても追随できる。
 */
class DiagnoseActivity : AppCompatActivity() {

    private lateinit var report: TextView
    private lateinit var keyword: EditText
    private val boxes = LinkedHashMap<String, EditText>()

    /** 直前に取得した HTML。共有用。 */
    private var lastHtml: String = ""
    private var lastLabel: String = "page"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(24))
            setBackgroundColor(Color.parseColor("#0f1418"))
        }

        root.addView(head("検索するキーワード"))
        keyword = EditText(this).apply {
            setText(Finder.keyword.ifBlank { "" })
            hint = "アーティスト名"
            inputType = InputType.TYPE_CLASS_TEXT
            setTextColor(Color.parseColor("#e9eef2"))
            setHintTextColor(Color.parseColor("#6f8393"))
            textSize = 14f
        }
        root.addView(keyword)

        root.addView(
            note(
                "各サイトの検索 URL の形を試せます。{q} の位置にキーワードが入ります。\n" +
                        "「試す」で結果を確認し、取れたら「保存」でこの形を使い続けます。"
            )
        )

        for (site in Sites.ALL) {
            root.addView(head("${site.label}${if (site.needsJs) "（JavaScript 描画）" else ""}"))

            val box = EditText(this).apply {
                setText(Finder.templatesFor(this@DiagnoseActivity, site).firstOrNull() ?: "")
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                setTextColor(Color.parseColor("#e9eef2"))
                textSize = 12f
            }
            boxes[site.id] = box
            root.addView(box)

            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(button("試す") { probe(site) })
            row.addView(button("保存") {
                Prefs.setOverride(this, site.id, box.text.toString())
                toast("${site.label} の URL を保存しました")
            })
            row.addView(button("既定") {
                Prefs.setOverride(this, site.id, null)
                box.setText(site.templates.first())
                toast("${site.label} を既定に戻しました")
            })
            root.addView(row)
        }

        val tools = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        tools.addView(button("HTMLを共有") { shareHtml() })
        tools.addView(button("学習リセット") {
            Prefs.forget(this)
            toast("覚えた URL を消しました")
        })
        tools.addView(button("クリア") {
            report.text = ""
        })
        root.addView(tools)

        report = TextView(this).apply {
            setTextColor(Color.parseColor("#cfe0ea"))
            textSize = 11.5f
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(10), 0, 0)
            setTextIsSelectable(true)
        }
        root.addView(report)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0f1418"))
            addView(root)
        })
    }

    // ── 実際に叩いてみる ────────────────────────────────
    private fun probe(site: Site) {
        val word = keyword.text.toString().trim()
        if (word.isEmpty()) {
            toast("キーワードを入れてください")
            return
        }
        val tpl = boxes[site.id]?.text?.toString()?.trim().orEmpty()
        if (!tpl.contains("{q}")) {
            toast("URL に {q} が要ります")
            return
        }
        val url = fillTemplate(tpl, word)
        log("── ${site.label} ──")
        log(url)

        Thread {
            val res = if (site.needsJs) JsFetcher.fetch(this, url) else Http.get(url)
            val html = res.body
            val hasYen = html.contains('¥') || html.contains('￥') || html.contains('円')
            var crash: String? = null
            val hits = if (html.isEmpty()) emptyList() else try {
                Extract.extract(site, res.url, html, 10)
            } catch (e: Exception) {
                crash = "解析で例外: ${e.javaClass.simpleName} ${e.message}"
                emptyList()
            }

            runOnUiThread {
                lastHtml = html
                lastLabel = site.id
                log("状態 ${res.code} / 長さ ${html.length} / 金額表記 ${if (hasYen) "あり" else "なし"}")
                res.error?.let { log("エラー: $it") }
                crash?.let { log(it) }
                log("取れた件数 ${hits.size}")
                for (h in hits.take(5)) {
                    log("  ¥${h.price}  ${h.title.take(38)}")
                    log("     ${h.url.take(90)}")
                }
                if (hits.isEmpty() && html.isNotEmpty()) {
                    log("※ 0 件。JavaScript 描画のページか、URL の形が違う可能性があります。")
                    if (!site.needsJs && !hasYen) log("※ 金額が 1 つも無いので、描画前の骨組みだけが返っています。")
                    log("先頭 400 字:")
                    log(html.take(400).replace(Regex("\\s+"), " "))
                }
                log("")
            }
        }.start()
    }

    private fun shareHtml() {
        if (lastHtml.isEmpty()) {
            toast("先に「試す」を実行してください")
            return
        }
        val dir = File(cacheDir, "exports").apply { mkdirs() }
        val f = File(dir, "$lastLabel.html")
        f.writeText(lastHtml, Charsets.UTF_8)
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/html"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "HTML を送る"))
    }

    // ── 部品 ────────────────────────────────────────────
    private fun log(s: String) {
        report.append(s + "\n")
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun head(s: String) = TextView(this).apply {
        text = s
        setTextColor(Color.parseColor("#4fc3a1"))
        textSize = 13f
        setTypeface(null, Typeface.BOLD)
        setPadding(0, dp(14), 0, dp(2))
    }

    private fun note(s: String) = TextView(this).apply {
        text = s
        setTextColor(Color.parseColor("#8fa3b0"))
        textSize = 11.5f
        setPadding(0, dp(8), 0, 0)
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 12f
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
    }
}
