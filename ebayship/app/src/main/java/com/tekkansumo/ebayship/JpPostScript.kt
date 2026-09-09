package com.tekkansumo.ebayship

import android.content.Context

/**
 * 国際郵便マイページに流し込む JavaScript の入り口。
 *
 * 本体は assets/jp_fill.js。PC 版（ebayship_web.py）も同じファイルを読むので、
 * 入力欄の当て方を直すときはあちらを 1 か所直せば両方に効く。
 */
object JpPostScript {

    /** 項目名 -> 画面での呼び名。学習ダイアログの選択肢にもなる。 */
    val FIELD_LABELS: List<Pair<String, String>> = listOf(
        "toName" to "お届け先 氏名",
        "toPostal" to "お届け先 郵便番号",
        "toAddress" to "お届け先 住所",
        "toCity" to "お届け先 都市",
        "toState" to "お届け先 州・県",
        "toCountry" to "お届け先 国",
        "toPhone" to "お届け先 電話番号",
        "toEmail" to "お届け先 メール",
        "fromName" to "ご依頼主 氏名",
        "fromPostal" to "ご依頼主 郵便番号",
        "fromAddress" to "ご依頼主 住所",
        "fromPhone" to "ご依頼主 電話番号",
        "content" to "内容品の品名",
        "quantity" to "内容品の個数",
        "weight" to "内容品の重量",
        "value" to "内容品の価格",
        "hsCode" to "HS コード",
        "origin" to "原産国",
        "loginId" to "ログイン ID",
        "loginPw" to "パスワード"
    )

    private var cached: String? = null

    /** assets から読む。何度も呼ばれるので一度読んだら持っておく。 */
    fun js(ctx: Context): String {
        cached?.let { return it }
        val text = ctx.assets.open("jp_fill.js").use { input ->
            input.readBytes().toString(Charsets.UTF_8)
        }
        cached = text
        return text
    }
}
