package com.tekkansumo.cdlist

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * 在庫が取れなくなったときに、何が起きているかをそのまま見せる画面。
 * どの検索 URL の形が生きているか、商品リンクを拾えているか、生の HTML まで。
 */
class DiagnoseActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_KEYWORD = "keyword"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val text = TextView(this).apply {
            setTextColor(Color.parseColor("#f0e6da"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.MONOSPACE
            setPadding(24, 24, 24, 48)
            setTextIsSelectable(true)
            text = "調べています…"
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#16110d"))
            addView(
                text,
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        setContentView(scroll)

        val keyword = intent.getStringExtra(EXTRA_KEYWORD).orEmpty()
            .ifBlank { "宇多田ヒカル First Love" }

        Thread {
            val out = try {
                Bookoff.diagnose(keyword)
            } catch (e: Exception) {
                "${e.javaClass.simpleName}: ${e.message}"
            }
            runOnUiThread { text.text = out }
        }.apply { isDaemon = true }.start()
    }
}
