package com.tekkansumo.ebayship

/**
 * eBay は国を 2 文字コードで返すが、国際郵便マイページのあて先国は日本語名の
 * プルダウンなので、よく出る国だけ日本語名を持っておく。
 * ここに無い国はコードのまま渡して、プルダウン側の文字合わせに任せる。
 */
object Countries {

    private val JA = mapOf(
        "US" to "アメリカ合衆国",
        "CA" to "カナダ",
        "GB" to "英国",
        "AU" to "オーストラリア",
        "NZ" to "ニュージーランド",
        "DE" to "ドイツ",
        "FR" to "フランス",
        "IT" to "イタリア",
        "ES" to "スペイン",
        "NL" to "オランダ",
        "BE" to "ベルギー",
        "CH" to "スイス",
        "AT" to "オーストリア",
        "SE" to "スウェーデン",
        "NO" to "ノルウェー",
        "DK" to "デンマーク",
        "FI" to "フィンランド",
        "IE" to "アイルランド",
        "PT" to "ポルトガル",
        "PL" to "ポーランド",
        "CZ" to "チェコ",
        "GR" to "ギリシャ",
        "HU" to "ハンガリー",
        "RO" to "ルーマニア",
        "SG" to "シンガポール",
        "HK" to "香港",
        "TW" to "台湾",
        "KR" to "韓国",
        "CN" to "中国",
        "TH" to "タイ",
        "MY" to "マレーシア",
        "PH" to "フィリピン",
        "ID" to "インドネシア",
        "VN" to "ベトナム",
        "IN" to "インド",
        "AE" to "アラブ首長国連邦",
        "IL" to "イスラエル",
        "MX" to "メキシコ",
        "BR" to "ブラジル",
        "CL" to "チリ",
        "AR" to "アルゼンチン",
        "ZA" to "南アフリカ共和国",
        "JP" to "日本"
    )

    fun ja(code: String): String = JA[code.uppercase()] ?: code

    /**
     * プルダウン用の候補。日本語名とコードの両方を渡し、
     * 画面側でどちらかに当たったほうを選ばせる。
     */
    fun candidates(code: String): String {
        val c = code.uppercase()
        val name = JA[c] ?: return c
        return "$name||$c"
    }
}
