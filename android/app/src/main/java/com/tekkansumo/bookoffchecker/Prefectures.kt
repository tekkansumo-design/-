package com.tekkansumo.bookoffchecker

object Prefectures {
    val ALL = listOf(
        "北海道", "青森", "岩手", "宮城", "秋田", "山形", "福島",
        "茨城", "栃木", "群馬", "埼玉", "千葉", "東京", "神奈川",
        "新潟", "富山", "石川", "福井", "山梨", "長野",
        "岐阜", "静岡", "愛知", "三重",
        "滋賀", "京都", "大阪", "兵庫", "奈良", "和歌山",
        "鳥取", "島根", "岡山", "広島", "山口",
        "徳島", "香川", "愛媛", "高知",
        "福岡", "佐賀", "長崎", "熊本", "大分", "宮崎", "鹿児島", "沖縄"
    )

    /** 取得済みの店舗名に含まれる都道府県を、ALL の並び順で返す。 */
    fun found(results: List<ItemResult>): List<String> {
        val hit = HashSet<String>()
        for (r in results) for (s in r.shops) for (p in ALL) {
            if (s.name.contains(p)) hit.add(p)
        }
        return ALL.filter { it in hit }
    }
}
